package tv.retwitch.mod.ads;

import android.app.Activity;
import android.app.Application;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Map;

import tv.retwitch.mod.AppContext;
import tv.retwitch.mod.CrashReporter;
import tv.retwitch.mod.Strings;
import tv.retwitch.mod.Toggles;

/**
 * A small label in the top left corner of the video while VAFT replaces or
 * cuts an ad break (toggle {@link Toggles#VAFT_BLOCKING_LABEL}, off by
 * default). Port of Overhypedcat's AdBreakIndicator: added to the window's
 * decor view and placed over the largest visible video surface, so it shows
 * without the player controls and follows the video into fullscreen.
 */
final class AdBreakLabel {

    private static final String TAG = "RTAdBreakLabel";
    /** Hidden if no playlist update arrives for this long, e.g. after leaving the stream. */
    private static final long STALE_MS = 15_000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable HIDE = new Runnable() {
        @Override
        public void run() {
            hideNow();
        }
    };
    private static WeakReference<Activity> resumed = new WeakReference<>(null);
    private static WeakReference<View> video = new WeakReference<>(null);
    private static boolean callbacksRegistered;
    private static TextView label;
    /** Variant playlist whose break the label shows: another player's clean poll leaves it alone. */
    private static volatile String owner;

    private AdBreakLabel() {
    }

    /** @param quality the backup's height ("360p"), or null when ad segments are cut instead */
    static void show(String variant, String quality) {
        if (!Toggles.on(Toggles.VAFT_BLOCKING_LABEL)) {
            return;
        }
        owner = variant;
        final String text = Strings.vaftBlockingLabel(quality);
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    showNow(text);
                } catch (Throwable t) {
                    Log.w(TAG, "show failed", t);
                    CrashReporter.report(TAG, "show failed", t);
                }
            }
        });
    }

    /** The break on {@code variant} is over. */
    static void hide(String variant) {
        if (owner != null && owner.equals(variant)) {
            owner = null;
            MAIN.post(HIDE);
        }
    }

    private static void showNow(String text) {
        registerCallbacks();
        Activity activity = resumed.get();
        if (activity == null || activity.getWindow() == null) {
            return;
        }
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        if (label == null || label.getContext() != activity) {
            hideNow();
            label = createLabel(activity);
            decor.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        }
        if (!text.contentEquals(label.getText())) {
            label.setText(text);
        }
        // Re-placed on every poll so it follows the video; the tree is searched only when the surface is gone.
        View surface = video.get();
        if (surface == null || !surface.isAttachedToWindow() || !surface.isShown()) {
            surface = findVideoSurface(decor);
            video = new WeakReference<>(surface);
        }
        place(activity, decor, label, surface);
        if (decor.getChildAt(decor.getChildCount() - 1) != label) {
            label.bringToFront();
        }
        MAIN.removeCallbacks(HIDE);
        MAIN.postDelayed(HIDE, STALE_MS);
    }

    private static void hideNow() {
        try {
            MAIN.removeCallbacks(HIDE);
            if (label != null && label.getParent() instanceof ViewGroup) {
                ((ViewGroup) label.getParent()).removeView(label);
            }
            label = null;
        } catch (Throwable t) {
            Log.w(TAG, "hide failed", t);
            CrashReporter.report(TAG, "hide failed", t);
        }
    }

    private static TextView createLabel(Activity activity) {
        TextView view = new TextView(activity);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        view.setTextColor(Color.WHITE);
        view.setSingleLine(true);
        int h = dp(activity, 8);
        int v = dp(activity, 3);
        view.setPadding(h, v, h, v);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB3000000);
        bg.setCornerRadius(dp(activity, 8));
        view.setBackground(bg);
        view.setOutlineProvider(null);
        view.setClickable(false);
        view.setFocusable(false);
        return view;
    }

    /** Top left corner of the video, or of the screen below the status bar when no video is found. */
    private static void place(Activity activity, ViewGroup decor, TextView label, View video) {
        int margin = dp(activity, 8);
        int left = 0;
        int top;
        WindowInsets insets = decor.getRootWindowInsets();
        if (video != null) {
            int[] at = new int[2];
            int[] base = new int[2];
            video.getLocationInWindow(at);
            decor.getLocationInWindow(base);
            left = at[0] - base[0];
            top = at[1] - base[1];
        } else {
            top = insets != null ? insets.getSystemWindowInsetTop() : 0;
        }
        // In fullscreen the video reaches the edge where the camera cutout can be.
        if (insets != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout != null) {
                left = Math.max(left, cutout.getSafeInsetLeft());
                top = Math.max(top, cutout.getSafeInsetTop());
            }
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) label.getLayoutParams();
        if (lp.leftMargin != left + margin || lp.topMargin != top + margin) {
            lp.setMargins(left + margin, top + margin, margin, margin);
            label.setLayoutParams(lp);
        }
    }

    /** The largest visible SurfaceView or TextureView: what the player draws the video on. */
    private static View findVideoSurface(View view) {
        if (!view.isShown()) {
            return null;
        }
        if (view instanceof SurfaceView || view instanceof TextureView) {
            return view.getWidth() > 0 && view.getHeight() > 0 ? view : null;
        }
        if (!(view instanceof ViewGroup)) {
            return null;
        }
        View largest = null;
        long largestArea = 0;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View surface = findVideoSurface(group.getChildAt(i));
            if (surface != null && (long) surface.getWidth() * surface.getHeight() > largestArea) {
                largestArea = (long) surface.getWidth() * surface.getHeight();
                largest = surface;
            }
        }
        return largest;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static void registerCallbacks() {
        if (callbacksRegistered) {
            return;
        }
        if (!(AppContext.get() instanceof Application)) {
            return;
        }
        callbacksRegistered = true;
        // The player screen is usually already showing when the first break arrives.
        resumed = new WeakReference<>(findResumedActivity());
        ((Application) AppContext.get()).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityResumed(Activity a) {
                resumed = new WeakReference<>(a);
            }

            public void onActivityPaused(Activity a) {
                if (resumed.get() == a) {
                    resumed = new WeakReference<>(null);
                }
                if (label != null && label.getContext() == a) {
                    hideNow();
                }
            }

            public void onActivityCreated(Activity a, Bundle b) { }
            public void onActivityStarted(Activity a) { }
            public void onActivityStopped(Activity a) { }
            public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            public void onActivityDestroyed(Activity a) { }
        });
    }

    /** The foreground activity, read from ActivityThread; null if Android blocks the lookup. */
    private static Activity findResumedActivity() {
        try {
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getMethod("currentActivityThread").invoke(null);
            Field activitiesField = threadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Map<?, ?> activities = (Map<?, ?>) activitiesField.get(thread);
            if (activities == null) {
                return null;
            }
            for (Object record : activities.values()) {
                Field paused = record.getClass().getDeclaredField("paused");
                paused.setAccessible(true);
                if (paused.getBoolean(record)) {
                    continue;
                }
                Field activity = record.getClass().getDeclaredField("activity");
                activity.setAccessible(true);
                return (Activity) activity.get(record);
            }
        } catch (Throwable ignored) {
            // the lifecycle callbacks take over from the next screen change
        }
        return null;
    }
}
