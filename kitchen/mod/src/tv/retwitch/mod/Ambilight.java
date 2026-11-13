package tv.retwitch.mod;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

/**
 * Ambilight for the fullscreen player (RTE's ambilight, adapted to a phone):
 * the pillar/letter-box bars around the video are filled with its blurred edge
 * colours. A tiny frame (32x18) is sampled from the video surface ~8 times a
 * second ({@link PixelCopy} for a SurfaceView, {@code getBitmap} for a
 * TextureView); the glow view sits right above the surface and paints only
 * outside the picture (video aspect fitted into the surface), so the player
 * controls stay on top.
 * Landscape only.
 */
public final class Ambilight {

    private static final String TAG = "RTAmbilight";
    private static final long FRAME_MS = 120L;
    private static final int SAMPLE_W = 32;
    private static final int SAMPLE_H = 18;
    /** Darker sample columns/rows are treated as letter-box bars, not picture. */
    private static final int BLACK_LUMA = 12;
    private static boolean installed;
    /** Whether a glow view may exist somewhere (so a disabled pass must look to remove it). */
    private static boolean glowAttached;

    private Ambilight() {
    }

    /** Called once from the injected {@code TwitchApplication.onCreate}. */
    public static void install(Application application) {
        try {
            if (installed || application == null) {
                return;
            }
            installed = true;
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                public void onActivityCreated(Activity a, Bundle b) { attach(a); }
                public void onActivityStarted(Activity a) { }
                public void onActivityResumed(Activity a) { }
                public void onActivityPaused(Activity a) { }
                public void onActivityStopped(Activity a) { }
                public void onActivitySaveInstanceState(Activity a, Bundle b) { }
                public void onActivityDestroyed(Activity a) { }
            });
        } catch (Throwable t) {
            Log.w(TAG, "install failed", t);
            CrashReporter.report(TAG, "install failed", t);
        }
    }

    private static void attach(final Activity activity) {
        if (activity.getWindow() == null || !activity.getClass().getName().startsWith("tv.twitch")) {
            return;
        }
        final View decor = activity.getWindow().getDecorView();
        decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                long t0 = UiStallLog.start();
                try {
                    update(activity, decor);
                } catch (Throwable t) {
                    Log.w(TAG, "update failed", t);
                    CrashReporter.report(TAG, "update failed", t);
                }
                UiStallLog.end("Ambilight", t0);
            }
        });
    }

    /** Keeps exactly one glow view behind the current video surface while enabled. */
    private static void update(Activity activity, View decor) {
        boolean wanted = Toggles.on(Toggles.AMBILIGHT) && isActive(activity)
                && activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (!wanted && !glowAttached) {
            return; // nothing to add or remove: skip the tree walks
        }
        View surface = wanted ? largestSurface(decor) : null;
        GlowView existing = findGlow(decor);
        if (surface == null || !(surface.getParent() instanceof ViewGroup)) {
            if (existing != null) {
                existing.detach();
            }
            glowAttached = wanted;
            return;
        }
        ViewGroup parent = glowHost(surface);
        if (existing != null && existing.getParent() == parent && existing.surface == surface) {
            return;
        }
        if (existing != null) {
            existing.detach();
        }
        GlowView glow = new GlowView(activity, surface);
        glowAttached = true;
        // Right above the surface: the surface may fill the whole player with
        // the picture letter-boxed inside it, so a view behind it would be
        // hidden. The glow only paints outside the picture, and the player's
        // controls (later siblings / other containers) stay above it.
        parent.addView(glow, parent.indexOfChild(childOf(parent, surface)) + 1, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        glow.start();
    }

    private static final String PREFS_NAME = "retwitch_ambilight";
    private static final String KEY_ACTIVE = "active";

    /** The player button's on/off state (on by default once the feature is enabled). */
    private static boolean isActive(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVE, true);
    }

    /** The player-strip button (see {@link ReloadHook}): toggles the glow. */
    private static void syncVisibility(View button) {
        boolean landscape = button.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        button.setVisibility(landscape ? View.VISIBLE : View.GONE);
    }

    static View createButton(final Context context) {
        // Landscape only: in portrait there are no bars around the video to light up.
        final android.widget.ImageView button = new android.widget.ImageView(context) {
            @Override
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                syncVisibility(this);
            }

            @Override
            protected void onConfigurationChanged(Configuration newConfig) {
                super.onConfigurationChanged(newConfig);
                syncVisibility(this);
            }
        };
        syncVisibility(button);
        final int px = Math.round(24 * context.getResources().getDisplayMetrics().density);
        button.setImageDrawable(new android.graphics.drawable.Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                boolean on = isActive(context);
                p.setColor(on ? 0xFFBF94FF : 0xFFFFFFFF);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(px / 12f);
                float r = px * 0.3f;
                canvas.drawRoundRect(b.exactCenterX() - r, b.exactCenterY() - r * 0.7f,
                        b.exactCenterX() + r, b.exactCenterY() + r * 0.7f, r / 4f, r / 4f, p);
                p.setStyle(Paint.Style.FILL);
                p.setAlpha(on ? 110 : 60);
                canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r * 1.6f, p);
            }

            @Override public int getIntrinsicWidth() { return px; }
            @Override public int getIntrinsicHeight() { return px; }
            @Override public void setAlpha(int alpha) { }
            @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        });
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        button.setContentDescription("Ambilight");
        button.setClickable(true);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    boolean active = !isActive(context);
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                            .putBoolean(KEY_ACTIVE, active).apply();
                    v.invalidate();
                    v.getRootView().requestLayout(); // next layout pass adds/removes the glow
                } catch (Throwable t) {
                    Log.w(TAG, "toggle failed", t);
                    CrashReporter.report(TAG, "toggle failed", t);
                }
            }
        });
        int size = Math.round(48 * context.getResources().getDisplayMetrics().density);
        button.setLayoutParams(new android.widget.LinearLayout.LayoutParams(size, size));
        return button;
    }

    private static final class GlowView extends View {
        final View surface;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Bitmap sample = Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888);
        private final Paint paint = new Paint();
        private final int[] glowPos = new int[2];
        private final int[] surfacePos = new int[2];
        private int left = Color.BLACK;
        private int right = Color.BLACK;
        private int top = Color.BLACK;
        private int bottom = Color.BLACK;
        private boolean running;

        GlowView(Context context, View surface) {
            super(context);
            this.surface = surface;
            setTag(TAG);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void start() {
            running = true;
            handler.post(tick);
        }

        void detach() {
            running = false;
            handler.removeCallbacks(tick);
            if (getParent() instanceof ViewGroup) {
                ((ViewGroup) getParent()).removeView(this);
            }
        }

        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!running || !surface.isShown() || surface.getWidth() == 0) {
                    if (running) {
                        handler.postDelayed(this, FRAME_MS * 4);
                    }
                    return;
                }
                try {
                    if (surface instanceof TextureView) {
                        ((TextureView) surface).getBitmap(sample);
                        onSample();
                    } else if (surface instanceof SurfaceView
                            && ((SurfaceView) surface).getHolder().getSurface().isValid()) {
                        PixelCopy.request((SurfaceView) surface, sample, new PixelCopy.OnPixelCopyFinishedListener() {
                            @Override
                            public void onPixelCopyFinished(int result) {
                                if (result == PixelCopy.SUCCESS) {
                                    onSample();
                                }
                            }
                        }, handler);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "sample failed", t);
                }
                handler.postDelayed(this, FRAME_MS);
            }
        };

        private void onSample() {
            // Picture bounds inside the sample: skip letter-box bars baked into
            // the frame (a TextureView sample can include them).
            int x0 = 0;
            int x1 = SAMPLE_W - 1;
            int y0 = 0;
            int y1 = SAMPLE_H - 1;
            while (x0 < x1 && luma(average(x0, 0, x0 + 1, SAMPLE_H)) < BLACK_LUMA) x0++;
            while (x1 > x0 && luma(average(x1, 0, x1 + 1, SAMPLE_H)) < BLACK_LUMA) x1--;
            while (y0 < y1 && luma(average(0, y0, SAMPLE_W, y0 + 1)) < BLACK_LUMA) y0++;
            while (y1 > y0 && luma(average(0, y1, SAMPLE_W, y1 + 1)) < BLACK_LUMA) y1--;
            left = lifted(average(x0, y0, x0 + 1, y1 + 1));
            right = lifted(average(x1, y0, x1 + 1, y1 + 1));
            if (surface.getScaleX() < 0) { // VideoMirror: the sample is the unmirrored frame
                int t = left;
                left = right;
                right = t;
            }
            top = lifted(average(x0, y0, x1 + 1, y0 + 1));
            bottom = lifted(average(x0, y1, x1 + 1, y1 + 1));
            invalidate();
        }

        /** Raw average colour of a sample block. */
        private int average(int x0, int y0, int x1, int y1) {
            long r = 0;
            long g = 0;
            long b = 0;
            int n = 0;
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    int c = sample.getPixel(x, y);
                    r += Color.red(c);
                    g += Color.green(c);
                    b += Color.blue(c);
                    n++;
                }
            }
            return n == 0 ? Color.BLACK : Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
        }

        private static int luma(int c) {
            return (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10;
        }

        /** RTE's brightness(1.3) contrast(0.85) lift, so dim scenes still glow. */
        private static int lifted(int c) {
            return Color.rgb(lift(Color.red(c)), lift(Color.green(c)), lift(Color.blue(c)));
        }

        private static int lift(long channel) {
            float v = Math.min(1f, channel / 255f * 1.3f);
            v = (v - 0.5f) * 0.85f + 0.5f;
            return Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // The picture: the video aspect fitted inside the surface.
            float sw = surface.getWidth();
            float sh = surface.getHeight();
            float aspect = PlayerHook.videoAspect();
            float pw = Math.min(sw, sh * aspect);
            float ph = Math.min(sh, sw / aspect);
            getLocationInWindow(glowPos);
            surface.getLocationInWindow(surfacePos);
            if (surface.getScaleX() < 0) {
                // VideoMirror's scaleX -1 is applied to the location: x 0 lands at 2 * pivotX, so the
                // "left bar" covered the whole picture.
                surfacePos[0] -= Math.round(2 * surface.getPivotX());
            }
            int l = Math.round(surfacePos[0] - glowPos[0] + (sw - pw) / 2f);
            int t = Math.round(surfacePos[1] - glowPos[1] + (sh - ph) / 2f);
            int r = Math.round(l + pw);
            int b = Math.round(t + ph);
            int w = getWidth();
            int h = getHeight();
            if (l > 0) {
                fill(canvas, 0, 0, l, h, l, 0, 0, 0, left);
            }
            if (r < w) {
                fill(canvas, r, 0, w, h, r, 0, w, 0, right);
            }
            if (t > 0) {
                fill(canvas, l, 0, r, t, 0, t, 0, 0, top);
            }
            if (b < h) {
                fill(canvas, l, b, r, h, 0, b, 0, h, bottom);
            }
        }

        /** Rect filled with a gradient from the edge colour at (x0,y0) to dark at (x1,y1). */
        private void fill(Canvas canvas, int l, int t, int r, int b,
                float x0, float y0, float x1, float y1, int color) {
            paint.setShader(new LinearGradient(x0, y0, x1, y1, color, Color.BLACK, Shader.TileMode.CLAMP));
            canvas.drawRect(l, t, r, b, paint);
        }
    }

    /**
     * The video surface with the largest on-screen area: feed pages and
     * preloaded players keep same-sized surfaces off screen, so raw size
     * would pick an arbitrary (often paused or black) player.
     */
    static View largestSurface(View root) {
        if (root == null || root.getVisibility() != View.VISIBLE) {
            return null;
        }
        View best = (root instanceof SurfaceView || root instanceof TextureView) && visibleArea(root) > 0 ? root : null;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = largestSurface(group.getChildAt(i));
                if (found != null && (best == null || visibleArea(found) > visibleArea(best))) {
                    best = found;
                }
            }
        }
        return best;
    }

    private static int visibleArea(View view) {
        Rect r = new Rect();
        return view.isShown() && view.getGlobalVisibleRect(r) ? r.width() * r.height() : 0;
    }

    /**
     * Where the glow goes: the surface's parent, unless the surface is
     * aspect-fitted (the picture fills it) inside a same-sized container --
     * then there's no room for bars there, so climb FrameLayouts to the first
     * larger ancestor (the bars live in it). A surface with letter-boxing
     * inside it never climbs, so the glow can't end up over unrelated UI.
     */
    private static ViewGroup glowHost(View surface) {
        ViewGroup host = (ViewGroup) surface.getParent();
        float aspect = PlayerHook.videoAspect();
        boolean fitted = surface.getHeight() > 0
                && Math.abs(surface.getWidth() / (float) surface.getHeight() - aspect) < aspect * 0.03f;
        while (fitted && host.getWidth() <= surface.getWidth() + 1 && host.getHeight() <= surface.getHeight() + 1
                && host.getId() != android.R.id.content && host.getParent() instanceof FrameLayout) {
            host = (ViewGroup) host.getParent();
        }
        return host;
    }

    /** The direct child of {@code host} that contains {@code view}. */
    private static View childOf(ViewGroup host, View view) {
        while (view.getParent() != host) {
            view = (View) view.getParent();
        }
        return view;
    }

    private static GlowView findGlow(View root) {
        if (root instanceof GlowView) {
            return (GlowView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                GlowView found = findGlow(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
