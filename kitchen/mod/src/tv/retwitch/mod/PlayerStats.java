package tv.retwitch.mod;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Locale;

import tv.retwitch.mod.ads.VaftLog;

/**
 * Player stats (ReVive's PlayerStats, PurpleTV's SHOW_STATS_BUTTON): a strip
 * button (see {@link ReloadHook}) that lays a box over the video's top left
 * corner with what the audible IVS player is doing -- quality (and whether
 * "auto" picked it), bitrate, fps, latency, buffer, network estimate, dropped
 * frames -- refreshed once a second. All public IVS SDK API, by reflection.
 * The same values go to {@link VaftLog} on buffering and quality changes.
 */
public final class PlayerStats {

    private static final String TAG = "RTStats";
    private static Handler main;

    /** Lazy: a static Handler breaks the JVM self-check (android.jar stubs). */
    private static Handler main() {
        if (main == null) {
            main = new Handler(Looper.getMainLooper());
        }
        return main;
    }

    private static WeakReference<TextView> overlay;

    /** For {@link VaftLog}, main thread only: the quality last written, the player in BUFFERING and since when. */
    private static String loggedQuality = "-";
    private static WeakReference<Object> buffering;
    private static long bufferingSince;
    private static boolean qualityCheck;

    private PlayerStats() {
    }

    /** The player-strip button. */
    static View createButton(Context context) {
        ImageView button = new ImageView(context);
        float density = context.getResources().getDisplayMetrics().density;
        button.setImageDrawable(new BarsIcon(Math.round(24 * density)));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(Strings.statsTitle());
        button.setClickable(true);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    toggle(v);
                } catch (Throwable t) {
                    Log.w(TAG, "toggle failed", t);
                    CrashReporter.report(TAG, "toggle failed", t);
                }
            }
        });
        int size = Math.round(48 * density); // Android's minimum comfortable touch target
        button.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return button;
    }

    private static TextView shown() {
        TextView box = overlay != null ? overlay.get() : null;
        return box != null && box.isAttachedToWindow() ? box : null;
    }

    private static void toggle(View button) {
        TextView box = shown();
        button.invalidate(); // the icon's colour follows the box
        if (box != null) {
            hide(box);
            return;
        }
        View content = button.getRootView().findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) {
            return;
        }
        Context ctx = button.getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        box = new TextView(ctx);
        box.setTextColor(0xFFFFFFFF);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        box.setTypeface(Typeface.MONOSPACE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC0E0A14);
        bg.setCornerRadius(10 * density);
        box.setBackground(bg);
        int pad = Math.round(8 * density);
        box.setPadding(pad + pad / 4, pad, pad + pad / 4, pad);
        box.setElevation(4 * density);
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hide((TextView) v);
            }
        });
        ((FrameLayout) content).addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        overlay = new WeakReference<>(box);
        main().removeCallbacks(TICK);
        TICK.run();
    }

    private static void hide(TextView box) {
        main().removeCallbacks(TICK);
        if (box.getParent() instanceof ViewGroup) {
            ((ViewGroup) box.getParent()).removeView(box);
        }
        overlay = null;
    }

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            TextView box = shown();
            if (box == null) {
                overlay = null;
                return;
            }
            try {
                if (!Toggles.on(Toggles.STATS_BUTTON)) {
                    hide(box);
                    return;
                }
                if (place(box) && box.getWindowVisibility() == View.VISIBLE) {
                    Object player = PlayerHook.currentPlayer();
                    box.setText(player != null ? describe(player) : Strings.statsNoPlayer());
                }
            } catch (Throwable t) {
                Log.w(TAG, "tick failed", t);
            }
            main().postDelayed(this, 1000L);
        }
    };

    /** Over the video's top left corner; hidden while the video isn't on screen. */
    private static boolean place(TextView box) {
        Context ctx = box.getContext();
        int id = ctx.getResources().getIdentifier("playback_view_container", "id", ctx.getPackageName());
        View video = id != 0 ? box.getRootView().findViewById(id) : null;
        if (video == null || !video.isShown()) {
            box.setVisibility(View.GONE);
            return false;
        }
        box.setVisibility(View.VISIBLE);
        int[] v = new int[2];
        int[] p = new int[2];
        video.getLocationInWindow(v);
        ((View) box.getParent()).getLocationInWindow(p);
        float margin = 8 * ctx.getResources().getDisplayMetrics().density;
        box.setX(v[0] - p[0] + margin);
        box.setY(v[1] - p[1] + margin);
        return true;
    }

    static Object call(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    static String mbps(long bps) {
        return bps <= 0 ? "-" : String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0);
    }

    private static String describe(Object player) {
        Object quality = call(player, "getQuality");
        Object stats = call(player, "getStatistics");
        String name = quality != null ? String.valueOf(call(quality, "getName")) : "-";
        long w = quality != null ? number(call(quality, "getWidth")) : 0;
        long h = quality != null ? number(call(quality, "getHeight")) : 0;
        boolean auto = Boolean.TRUE.equals(call(player, "isAutoQualityMode"));
        long bitrate = stats != null ? number(call(stats, "getVideoBitRate")) : 0;
        long fps = stats != null ? number(call(stats, "getFrameRate")) : 0;
        long dropped = stats != null ? number(call(stats, "getDroppedFrames")) : 0;
        long decoded = stats != null ? number(call(stats, "getDecodedFrames")) : 0;
        Object latency = call(player, "getLiveLatency");
        Object low = call(player, "isLiveLowLatency");
        long buffer = bufferMs(player);
        long network = number(call(player, "getBandwidthEstimate"));

        StringBuilder sb = new StringBuilder();
        sb.append(Strings.statsQuality()).append(name);
        if (w > 0 && h > 0) {
            sb.append(" (").append(w).append('x').append(h).append(')');
        }
        if (auto) {
            sb.append(", auto");
        }
        sb.append('\n').append(Strings.statsVideo()).append(mbps(bitrate)).append(", ").append(fps).append(" fps");
        if (latency instanceof Long && (Long) latency > 0) {
            sb.append('\n').append(Strings.statsLatency())
                    .append(String.format(Locale.US, "%.1f s", (Long) latency / 1000.0));
            if (low instanceof Boolean) {
                sb.append((Boolean) low ? ", low latency" : ", normal latency");
            }
        }
        sb.append('\n').append(Strings.statsBuffer()).append(String.format(Locale.US, "%.1f s", buffer / 1000.0));
        sb.append('\n').append(Strings.statsNetwork()).append(mbps(network));
        sb.append('\n').append(Strings.statsFrames(dropped, decoded));
        return sb.toString();
    }

    private static long bufferMs(Object player) {
        return Math.max(0, number(call(player, "getBufferedPosition")) - number(call(player, "getPosition")));
    }

    /**
     * From the audible player's state changes. VaftServer sees only playlists, segments go straight to the
     * CDN, so a stall leaves no other trace in the log. Only a flag check while the log is off.
     */
    static void logState(Object player, String state) {
        if (!VaftLog.isOn()) {
            return;
        }
        log(bufferingLine(player, state, SystemClock.elapsedRealtime()));
        log(qualityLine(player));
        if (isRunning(state) && !qualityCheck) {
            qualityCheck = true;
            main().postDelayed(QUALITY_CHECK, 1000L);
        }
    }

    /** ABR switches without a state change, and IVS's own quality listener would need a new hook. */
    private static final Runnable QUALITY_CHECK = new Runnable() {
        @Override
        public void run() {
            qualityCheck = false;
            try {
                Object player = PlayerHook.currentPlayer();
                if (player == null || !VaftLog.isOn()) {
                    return;
                }
                if (PlayerHook.isAudible(player)) {
                    log(qualityLine(player));
                }
                // A released player reads IDLE: its native getters check the handle.
                if (isRunning(String.valueOf(call(player, "getState")))) {
                    qualityCheck = true;
                    main().postDelayed(this, 1000L);
                }
            } catch (Throwable t) {
                Log.w(TAG, "quality check failed", t);
                CrashReporter.report(TAG, "quality check failed", t);
            }
        }
    };

    private static boolean isRunning(String state) {
        return "PLAYING".equals(state) || "BUFFERING".equals(state);
    }

    private static void log(String line) {
        if (line != null) {
            VaftLog.log(line);
        }
    }

    /** The line for a state change, or null. A player closed in BUFFERING gets no end: the next player's state isn't it. */
    static String bufferingLine(Object player, String state, long now) {
        if ("BUFFERING".equals(state)) {
            buffering = new WeakReference<>(player);
            bufferingSince = now;
            return "player buffering: " + snapshot(player);
        }
        Object stalled = buffering != null ? buffering.get() : null;
        buffering = null;
        return stalled != player ? null
                : "player buffering ended (" + state + ") after " + (now - bufferingSince) + " ms: " + snapshot(player);
    }

    /** The line when IVS plays another quality than the last one written, or null; before a load the name is empty. */
    static String qualityLine(Object player) {
        Object name = call(call(player, "getQuality"), "getName");
        if (name == null || "".equals(name) || name.equals(loggedQuality)) {
            return null;
        }
        String line = "player quality " + loggedQuality + " -> " + snapshot(player);
        loggedQuality = name.toString();
        return line;
    }

    static String snapshot(Object player) {
        Object quality = call(player, "getQuality");
        return call(quality, "getName") + " (" + number(call(quality, "getHeight")) + "p, "
                + mbps(number(call(quality, "getBitrate"))) + "), net " + network(player) + ", buffer " + buffer(player)
                + ", latency " + number(call(player, "getLiveLatency")) + " ms";
    }

    /** IVS starts at exactly 1 Mbps and stays there at a fixed quality: no estimate, as measured on a 150 Mbps link. */
    private static String network(Object player) {
        long estimate = number(call(player, "getBandwidthEstimate"));
        return estimate == 1000000L ? "?" : mbps(estimate);
    }

    /** A manual quality switch zeroes the position for a moment, while the buffered position stays: no buffer to tell. */
    private static String buffer(Object player) {
        return number(call(player, "getPosition")) > 0 ? bufferMs(player) + " ms" : "?";
    }

    /** Three rising bars; purple while the box is open. */
    private static final class BarsIcon extends Drawable {
        private final int size;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        BarsIcon(int size) {
            this.size = size;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            paint.setColor(shown() != null ? 0xFFBF94FF : 0xFFFFFFFF);
            float bar = size / 7f;
            float bottom = b.exactCenterY() + size * 0.35f;
            float x = b.exactCenterX() - bar * 2.5f;
            float[] heights = {0.3f, 0.7f, 0.5f};
            for (float hgt : heights) {
                canvas.drawRoundRect(x, bottom - size * hgt, x + bar, bottom, bar / 3f, bar / 3f, paint);
                x += bar * 2f;
            }
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
