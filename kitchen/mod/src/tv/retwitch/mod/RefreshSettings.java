package tv.retwitch.mod;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists the "reload player" feature settings the same way
 * {@link TimerSettings} / {@link PlayerSettings} do -- a private
 * {@link SharedPreferences} file read via {@link AppContext}, so it works
 * from hooks/callbacks that only ever get plain data (no {@link Context}).
 *
 * <p>The feature (ReTwitch's port of PurpleTV's "refresh stream") drops a
 * small circular-arrow button into the player controls overlay; tapping it
 * asks Twitch to re-fetch the manifest and replay the current live stream /
 * VOD (see {@link ReloadHook}). {@link #KEY_SHOW_BUTTON} gates whether that
 * overlay button is shown at all (default on).
 */
public final class RefreshSettings {

    private static final String PREFS_NAME = "retwitch_refresh_settings";
    private static final String KEY_SHOW_BUTTON = "show_reload_button";

    private RefreshSettings() {
    }

    public static boolean isButtonEnabled() {
        return getBool(KEY_SHOW_BUTTON, true);
    }

    public static void setButtonEnabled(Context context, boolean value) {
        setBool(context, KEY_SHOW_BUTTON, value);
    }

    private static boolean getBool(String key, boolean fallback) {
        Context context = AppContext.get();
        if (context == null) {
            return fallback;
        }
        return prefs(context).getBoolean(key, fallback);
    }

    private static void setBool(Context context, String key, boolean value) {
        if (context == null) {
            return;
        }
        prefs(context).edit().putBoolean(key, value).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
