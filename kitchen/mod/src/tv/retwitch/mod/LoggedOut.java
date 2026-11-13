package tv.retwitch.mod;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

/**
 * "Continue without an account" on Twitch's login screen: without it a signed-out user could never reach
 * the mod settings to turn {@code WATCH_LOGGED_OUT} on. The button turns it on and restarts into the app.
 * In the app the entry to the mod settings is in Twitch's settings, which need an account: a guest gets a
 * button of its own over the navigation bar.
 */
final class LoggedOut {

    private static final String TAG = "RTLoggedOut";
    private static final String LOGIN = "tv.twitch.android.login.LoginActivity";
    private static final String LANDING = "tv.twitch.android.feature.viewer.landing.ViewerLandingActivity";
    private static final int BUTTON_TAG_KEY = 0x7e700d64;
    private static final int SETTINGS_TAG_KEY = 0x7e700d65;

    private LoggedOut() {
    }

    /** Right above Twitch's login button on the start screen; the password form has no room for it. */
    private static void place(Activity activity, View content, Button button) {
        try {
            List<View> starts = UiHider.findAll(activity, content, "logged_out_layout");
            List<View> logins = starts.isEmpty() || !starts.get(0).isShown()
                    ? Collections.<View>emptyList() : UiHider.findAll(activity, starts.get(0), "login_button");
            if (logins.isEmpty() || logins.get(0).getHeight() == 0) {
                button.setVisibility(View.GONE);
                return;
            }
            int[] login = new int[2];
            int[] root = new int[2];
            logins.get(0).getLocationInWindow(login);
            content.getLocationInWindow(root);
            int margin = root[1] + content.getHeight() - login[1]
                    + Math.round(12 * activity.getResources().getDisplayMetrics().density);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) button.getLayoutParams();
            if (lp.bottomMargin != margin) {
                lp.bottomMargin = margin;
                button.setLayoutParams(lp);
            }
            button.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            Log.w(TAG, "place failed", t);
        }
    }

    static void install(Application application) {
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) { }
            public void onActivityStarted(Activity a) { }
            public void onActivityResumed(Activity a) {
                attach(a);
                attachSettings(a);
            }
            public void onActivityPaused(Activity a) { }
            public void onActivityStopped(Activity a) { }
            public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            public void onActivityDestroyed(Activity a) { }
        });
    }

    /** Over the navigation bar, away while the player is up: the mini-player sits in that corner. */
    private static void placeSettings(Activity activity, View content, View button) {
        try {
            View nav = activity.findViewById(UiHider.id(activity, "viewer_landing_bottom_navigation"));
            View player = activity.findViewById(UiHider.id(activity, "draggable_container"));
            if (nav == null || !nav.isShown() || (player != null && player.isShown())) {
                button.setVisibility(View.GONE);
                return;
            }
            int[] bar = new int[2];
            int[] root = new int[2];
            nav.getLocationInWindow(bar);
            content.getLocationInWindow(root);
            int margin = root[1] + content.getHeight() - bar[1]
                    + Math.round(5 * activity.getResources().getDisplayMetrics().density);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) button.getLayoutParams();
            if (lp.bottomMargin != margin) {
                lp.bottomMargin = margin;
                button.setLayoutParams(lp);
            }
            button.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            Log.w(TAG, "placeSettings failed", t);
        }
    }

    private static void attachSettings(final Activity activity) {
        try {
            if (!LANDING.equals(activity.getClass().getName()) || !TwitchAuth.getUserId().isEmpty()) {
                return;
            }
            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout) || content.getTag(SETTINGS_TAG_KEY) != null) {
                return;
            }
            content.setTag(SETTINGS_TAG_KEY, Boolean.TRUE);
            float density = activity.getResources().getDisplayMetrics().density;
            TextView button = new TextView(activity);
            button.setText("ReTwitch");
            button.setContentDescription(Strings.pick("Настройки мода", "Mod settings"));
            button.setTextColor(0xFFFFFFFF);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            button.setBackground(SettingsScreen.brandBackground(Math.round(16 * density)));
            // Low enough to stay under the feed's own buttons.
            button.setPadding(Math.round(12 * density), Math.round(3 * density), Math.round(12 * density),
                    Math.round(3 * density));
            button.setOnClickListener(v -> SettingsHook.showMainMenu(activity));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
            lp.setMarginEnd(Math.round(12 * density));
            ((FrameLayout) content).addView(button, lp);
            content.getViewTreeObserver().addOnGlobalLayoutListener(() -> placeSettings(activity, content, button));
        } catch (Throwable t) {
            Log.w(TAG, "attachSettings failed", t);
        }
    }

    private static void attach(final Activity activity) {
        try {
            if (!LOGIN.equals(activity.getClass().getName()) || Toggles.on(Toggles.WATCH_LOGGED_OUT)) {
                return;
            }
            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout) || content.getTag(BUTTON_TAG_KEY) != null) {
                return;
            }
            content.setTag(BUTTON_TAG_KEY, Boolean.TRUE);
            Button button = new Button(activity);
            button.setText(Strings.loggedOutButton());
            button.setAllCaps(false);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            button.setOnClickListener(v -> {
                // commit(): restartApp ends the process, an apply() would be lost with it.
                Toggles.prefs(activity).edit().putBoolean(Toggles.WATCH_LOGGED_OUT.key, true).commit();
                SettingsHook.restartApp(activity);
            });
            ((FrameLayout) content).addView(button, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
            content.getViewTreeObserver().addOnGlobalLayoutListener(() -> place(activity, content, button));
        } catch (Throwable t) {
            Log.w(TAG, "attach failed", t);
        }
    }
}
