package tv.retwitch.mod;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists the sleep/exit-timer settings the same way {@link PlayerSettings} /
 * {@link TheatreSettings} do -- a private {@link SharedPreferences} file read
 * via {@link AppContext}, so it works from hooks/callbacks that only ever get
 * plain data (no {@link Context}).
 *
 * <p>The feature (inspired by PurpleTV's timer) drops a small clock button into
 * the player controls overlay; tapping it opens a dialog to pick an
 * hours/minutes countdown after which the whole app is closed (see
 * {@link TimerHook} for the UI and {@link TimerController} for the countdown).
 *
 * <ul>
 * <li>{@link #KEY_SHOW_BUTTON} gates whether the overlay button is shown at all
 *     (default on; the timer is still reachable from the ReTwitch settings menu
 *     when it's off).</li>
 * <li>{@link #KEY_LAST_HOURS}/{@link #KEY_LAST_MINUTES} remember the last picked
 *     duration so the dialog re-opens pre-filled with it.</li>
 * </ul>
 */
public final class TimerSettings {

    private static final String PREFS_NAME = "retwitch_timer_settings";
    private static final String KEY_SHOW_BUTTON = "show_timer_button";
    /** When time is up: false closes the app (PurpleTV), true only pauses playback (ReVive). */
    private static final String KEY_PAUSE = "pause_instead_of_close";
    private static final String KEY_LAST_HOURS = "last_hours";
    private static final String KEY_LAST_MINUTES = "last_minutes";

    /** Default picker position: a 30-minute sleep timer. */
    public static final int DEFAULT_HOURS = 0;
    public static final int DEFAULT_MINUTES = 30;

    private TimerSettings() {
    }

    public static boolean isButtonEnabled() {
        return getBool(KEY_SHOW_BUTTON, true);
    }

    public static void setButtonEnabled(Context context, boolean value) {
        setBool(context, KEY_SHOW_BUTTON, value);
    }

    public static boolean isPauseMode() {
        return getBool(KEY_PAUSE, false);
    }

    public static void setPauseMode(Context context, boolean value) {
        setBool(context, KEY_PAUSE, value);
    }

    public static int getLastHours() {
        return clamp(getInt(KEY_LAST_HOURS, DEFAULT_HOURS), 0, 23);
    }

    public static int getLastMinutes() {
        return clamp(getInt(KEY_LAST_MINUTES, DEFAULT_MINUTES), 0, 59);
    }

    public static void setLastDuration(Context context, int hours, int minutes) {
        if (context == null) {
            return;
        }
        prefs(context).edit()
                .putInt(KEY_LAST_HOURS, clamp(hours, 0, 23))
                .putInt(KEY_LAST_MINUTES, clamp(minutes, 0, 59))
                .apply();
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
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

    private static int getInt(String key, int fallback) {
        Context context = AppContext.get();
        if (context == null) {
            return fallback;
        }
        return prefs(context).getInt(key, fallback);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
