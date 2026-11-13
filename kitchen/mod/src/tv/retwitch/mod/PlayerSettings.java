package tv.retwitch.mod;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists player-related toggles the same way {@link ProxySettings} /
 * {@code EmoteSettings} do -- a private {@link SharedPreferences} file read
 * via {@link AppContext}, so it works from hooks that only ever get plain
 * data (no {@link Context}).
 *
 * <p>Currently holds a single option: whether the fullscreen player is
 * allowed to draw into the display cutout (camera notch) area. Twitch's
 * window already uses {@code LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS}, but its
 * own layout consumes the display-cutout inset and letterboxes the player in
 * landscape; enabling this strips that inset so the player reaches under a
 * side notch (see {@link DisplayCutoutHook}). Default on -- that's the whole
 * point of the feature -- with a toggle in the ReTwitch settings menu.
 */
public final class PlayerSettings {

    private static final String PREFS_NAME = "retwitch_player_settings";
    private static final String KEY_DRAW_UNDER_CUTOUT = "draw_under_cutout";

    private PlayerSettings() {
    }

    public static boolean isDrawUnderCutoutEnabled() {
        return getBool(KEY_DRAW_UNDER_CUTOUT, true);
    }

    public static void setDrawUnderCutoutEnabled(Context context, boolean value) {
        setBool(context, KEY_DRAW_UNDER_CUTOUT, value);
    }

    /** Default-quality choices: 0 = Twitch's auto, -1 = source (highest), else max height. */
    public static final int[] QUALITY_HEIGHTS = {0, -1, 1080, 720, 480, 360, 160};

    private static final String KEY_QUALITY_WIFI = "default_quality_wifi";
    private static final String KEY_QUALITY_CELLULAR = "default_quality_cellular";

    /** @return a {@link #QUALITY_HEIGHTS} value for the network kind; default auto. */
    public static int getDefaultQuality(boolean cellular) {
        try {
            Context context = AppContext.get();
            return context == null ? 0
                    : prefs(context).getInt(cellular ? KEY_QUALITY_CELLULAR : KEY_QUALITY_WIFI, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void setDefaultQuality(Context context, boolean cellular, int height) {
        prefs(context).edit().putInt(cellular ? KEY_QUALITY_CELLULAR : KEY_QUALITY_WIFI, height).apply();
    }

    /** StreamSwitch modes: back and forward through the opened channels, or round the followed ones live. */
    public static final int ARROWS_RECENT = 0;
    public static final int ARROWS_FOLLOWED = 1;
    private static final String KEY_STREAM_ARROWS_MODE = "stream_arrows_mode";

    public static int getStreamArrowsMode() {
        try {
            Context context = AppContext.get();
            return context == null ? ARROWS_RECENT : prefs(context).getInt(KEY_STREAM_ARROWS_MODE, ARROWS_RECENT);
        } catch (Throwable t) {
            return ARROWS_RECENT;
        }
    }

    public static void setStreamArrowsMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_STREAM_ARROWS_MODE, mode).apply();
    }

    /** How long a stream may buffer before AUTO_RELOAD restarts it, seconds (feedback #45). */
    public static final int[] STALL_SECONDS = {3, 5, 8, 10, 15, 20, 30};
    private static final String KEY_STALL_SECONDS = "stall_seconds";

    public static int getStallSeconds() {
        return getInt(KEY_STALL_SECONDS, 5);
    }

    public static void setStallSeconds(Context context, int seconds) {
        prefs(context).edit().putInt(KEY_STALL_SECONDS, seconds).apply();
    }

    /** VOD seek step choices in seconds; Twitch's own are 10 back, 30 forward. */
    public static final int[] SEEK_STEPS = {5, 10, 15, 20, 30, 45, 60, 90, 120};

    private static final String KEY_SEEK_BACK = "seek_back_seconds";
    private static final String KEY_SEEK_FORWARD = "seek_forward_seconds";

    public static int getSeekBackSeconds() {
        return getInt(KEY_SEEK_BACK, 10);
    }

    public static int getSeekForwardSeconds() {
        return getInt(KEY_SEEK_FORWARD, 30);
    }

    public static void setSeekSeconds(Context context, boolean forward, int seconds) {
        prefs(context).edit().putInt(forward ? KEY_SEEK_FORWARD : KEY_SEEK_BACK, seconds).apply();
    }

    private static int getInt(String key, int fallback) {
        Context context = AppContext.get();
        return context == null ? fallback : prefs(context).getInt(key, fallback);
    }

    private static boolean getBool(String key, boolean fallback) {
        Context context = AppContext.get();
        if (context == null) {
            return fallback;
        }
        return prefs(context).getBoolean(key, fallback);
    }

    private static void setBool(Context context, String key, boolean value) {
        prefs(context).edit().putBoolean(key, value).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
