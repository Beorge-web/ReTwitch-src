package tv.retwitch.mod;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;

/**
 * Per-VOD chat offset (PurpleTV's VodSyncHook): the chat replay asks
 * ChommentsFetcherImpl for the comments at the player's second once a second
 * ({@code VodPlayerPatch} hooks that call); {@link #shift} moves the second by
 * the offset saved for this VOD, so +N shows chat N seconds ahead. Set from a
 * VOD player-strip button (see {@link ReloadHook}), -30..+90 s like PurpleTV.
 */
public final class VodChatSync {

    private static final String TAG = "RTVodChatSync";
    private static final String PREFS_NAME = "retwitch_vod_chat_offset";
    static final int MIN = -30;
    static final int MAX = 90;

    private static volatile Field vodField;

    private VodChatSync() {
    }

    /**
     * Injected at the entry of the comments fetcher's fetch-for-second method;
     * {@code fetcher} keeps the VodModel of its {@code prepareForVod} in a field.
     * Never throws.
     */
    public static int shift(Object fetcher, int seconds) {
        try {
            String vodId = vodId(fetcher);
            int offset = vodId != null ? offset(vodId) : 0;
            return offset == 0 ? seconds : Math.max(0, seconds + offset);
        } catch (Throwable t) {
            return seconds;
        }
    }

    private static String vodId(Object fetcher) throws Exception {
        Field field = vodField;
        if (field == null || !field.getDeclaringClass().isInstance(fetcher)) {
            field = null;
            for (Field f : fetcher.getClass().getDeclaredFields()) {
                if (f.getType().getName().equals("tv.twitch.android.models.videos.VodModel")) {
                    f.setAccessible(true);
                    field = f;
                    break;
                }
            }
            if (field == null) {
                return null;
            }
            vodField = field;
        }
        Object vod = field.get(fetcher);
        Object id = vod != null ? vod.getClass().getMethod("getId").invoke(vod) : null;
        String text = id != null ? id.toString() : null;
        // Same form as ChannelTracker's (the button's key): "v123" -> "123".
        return text != null && text.startsWith("v") ? text.substring(1) : text;
    }

    static int offset(String vodId) {
        Context context = AppContext.get();
        return context == null ? 0 : clamp(prefs(context).getInt(vodId, 0));
    }

    private static void setOffset(Context context, String vodId, int seconds) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (seconds == 0) {
            editor.remove(vodId); // only VODs with an offset keep an entry
        } else {
            editor.putInt(vodId, clamp(seconds));
        }
        editor.apply();
    }

    static int clamp(int seconds) {
        return Math.max(MIN, Math.min(MAX, seconds));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** The VOD player-strip button. */
    static View createButton(Context context) {
        ImageView button = new ImageView(context);
        float density = context.getResources().getDisplayMetrics().density;
        button.setImageDrawable(new SyncIcon(Math.round(24 * density)));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(Strings.vodChatSyncTitle());
        button.setClickable(true);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    show(v.getContext());
                } catch (Throwable t) {
                    Log.w(TAG, "show failed", t);
                    CrashReporter.report(TAG, "show failed", t);
                }
            }
        });
        int size = Math.round(48 * density); // Android's minimum comfortable touch target
        button.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return button;
    }

    private static void show(final Context context) {
        final String vodId = ChannelTracker.getCurrentVodId();
        if (vodId == null) {
            Toast.makeText(context, Strings.vodChatSyncUnavailable(), Toast.LENGTH_SHORT).show();
            return;
        }
        float density = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(20 * density);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad / 2, pad, 0);
        final TextView value = new TextView(context);
        value.setGravity(Gravity.CENTER_HORIZONTAL);
        value.setTextSize(18);
        final SeekBar bar = new SeekBar(context);
        bar.setMax(MAX - MIN);
        int current = offset(vodId);
        bar.setProgress(current - MIN);
        value.setText(Strings.vodChatSyncValue(current));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int seconds = progress + MIN;
                value.setText(Strings.vodChatSyncValue(seconds));
                setOffset(context, vodId, seconds);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        TextView hint = new TextView(context);
        hint.setText(Strings.vodChatSyncHint());
        hint.setPadding(0, pad / 2, 0, 0);
        layout.addView(value);
        layout.addView(bar);
        layout.addView(hint);
        new AlertDialog.Builder(context)
                .setTitle(Strings.vodChatSyncTitle())
                .setView(layout)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(Strings.reset(), (d, w) -> setOffset(context, vodId, 0))
                .show();
    }

    /** Two opposite arrows: "move chat in time". */
    private static final class SyncIcon extends Drawable {
        private final int size;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SyncIcon(int size) {
            this.size = size;
            paint.setColor(0xFFFFFFFF);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(size / 11f);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float half = size * 0.35f;
            float gap = size * 0.16f;
            float head = size * 0.14f;
            // upper arrow pointing right, lower arrow pointing left
            canvas.drawLine(cx - half, cy - gap, cx + half, cy - gap, paint);
            canvas.drawLine(cx + half, cy - gap, cx + half - head, cy - gap - head, paint);
            canvas.drawLine(cx - half, cy + gap, cx + half, cy + gap, paint);
            canvas.drawLine(cx - half, cy + gap, cx - half + head, cy + gap + head, paint);
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
