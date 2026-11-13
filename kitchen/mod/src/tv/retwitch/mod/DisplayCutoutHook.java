package tv.retwitch.mod;

import android.app.Activity;
import android.app.Application;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

/**
 * Lets the fullscreen player draw into the display cutout (camera notch)
 * area instead of being letterboxed away from it by a black bar.
 *
 * <p>Contrary to first assumptions, Twitch's <em>window</em> is not the
 * problem: it already sets {@code layoutInDisplayCutoutMode = ALWAYS} and
 * the decor view spans the full display (verified on-device: the top-level
 * {@code fullscreen_layout} measures the full 2400px in landscape). The
 * black bar comes one level down -- Twitch's own layout consumes the
 * {@link WindowInsets.Type#displayCutout()} inset and shifts the whole
 * player/chat container (`draggable_container`) inward by exactly the cutout
 * width (136px on a Pixel 7), leaving that strip black.
 *
 * <p>So the fix is to stop that inset from ever reaching Twitch's views:
 * we install an {@link View.OnApplyWindowInsetsListener} on the activity's
 * root content view ({@code android.R.id.content}) that zeroes only the
 * display-cutout insets before dispatching to children. System-bar insets
 * are left untouched, and nothing references Twitch's obfuscated classes,
 * so this survives R8 renames. We (re)apply on every Twitch activity's
 * create/resume so toggling the option takes effect as soon as the screen
 * returns to the foreground.
 *
 * <p>The window's cutout mode is the necessary precondition for that inset
 * fix to matter at all (a {@code DEFAULT}-mode window is letterboxed at the
 * decor level, so there would be nothing to un-inset). Twitch already uses
 * {@code ALWAYS}, so we only <em>raise</em> a {@code DEFAULT} window as a
 * safety net and never override or lower a mode Twitch deliberately set --
 * including when the option is off, where we simply drop our listener and
 * leave Twitch's own layout behavior intact.
 */
public final class DisplayCutoutHook {

    // WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_* (API 28+),
    // referenced by literal so the extension compiles against pre-P
    // android.jars; guarded at runtime by SDK_INT below.
    private static final int CUTOUT_MODE_DEFAULT = 0;
    private static final int CUTOUT_MODE_ALWAYS = 3;

    private static final int TAG_KEY = 0x7e700c07; // arbitrary, unique to us

    private static volatile boolean installed;

    private DisplayCutoutHook() {
    }

    /**
     * Called once from the injected {@code TwitchApplication.onCreate}.
     *
     * @param application the process Application (smali passes {@code p0})
     */
    public static void install(Application application) {
        // Needs the WindowInsets.Builder per-type API (R+); the whole
        // feature is a no-op below that.
        if (installed || application == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        installed = true;
        application.registerActivityLifecycleCallbacks(new Callbacks());
    }

    private static void applyTo(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        // Only touch Twitch's own activities; leave embedded third-party
        // screens (Google/Amazon sign-in, billing, ...) at their defaults.
        if (!activity.getClass().getName().startsWith("tv.twitch")) {
            return;
        }
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        View content = window.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        boolean enabled = PlayerSettings.isDrawUnderCutoutEnabled();
        Object marker = content.getTag(TAG_KEY);
        if (enabled) {
            // Safety net only: raise a DEFAULT-mode window so its decor view
            // can reach under the cutout. Never override/lower a mode Twitch
            // set on purpose (it already uses ALWAYS).
            WindowManager.LayoutParams params = window.getAttributes();
            if (params != null && params.layoutInDisplayCutoutMode == CUTOUT_MODE_DEFAULT) {
                params.layoutInDisplayCutoutMode = CUTOUT_MODE_ALWAYS;
                window.setAttributes(params);
            }
            if (marker == null) {
                content.setOnApplyWindowInsetsListener(new CutoutStrippingListener());
                content.setTag(TAG_KEY, Boolean.TRUE);
            }
        } else if (marker != null) {
            content.setOnApplyWindowInsetsListener(null);
            content.setTag(TAG_KEY, null);
        }
        content.requestApplyInsets();
    }

    /**
     * Rebuilds the incoming {@link WindowInsets} with the display-cutout
     * insets zeroed (and the {@link android.view.DisplayCutout} itself
     * dropped), then dispatches the result to the children. Everything below
     * the content view therefore lays out as if there were no cutout, while
     * status/navigation-bar insets are preserved.
     */
    private static final class CutoutStrippingListener implements View.OnApplyWindowInsetsListener {
        @Override
        public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
            WindowInsets stripped = new WindowInsets.Builder(insets)
                    .setInsets(WindowInsets.Type.displayCutout(), Insets.NONE)
                    .setDisplayCutout(null)
                    .build();
            return v.onApplyWindowInsets(stripped);
        }
    }

    private static final class Callbacks implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            applyTo(activity);
        }

        @Override
        public void onActivityResumed(Activity activity) {
            applyTo(activity);
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }
}
