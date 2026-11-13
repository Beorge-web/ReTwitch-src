package tv.retwitch.mod;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists the "which discovery-feed tab opens by default" toggle the same
 * way {@link PlayerSettings} / {@link ProxySettings} do -- a private {@link
 * SharedPreferences} file read via {@link AppContext}, so it works from a
 * hook that only ever gets a plain {@code int} (no {@link Context}).
 *
 * <p>Twitch's main discovery-feed pager lands on the "Live" tab by default;
 * with this on (the default), ReTwitch makes it land on the "Following" tab
 * instead (see {@link LandingTabHook}). Toggle in the ReTwitch settings menu.
 */
public final class LandingSettings {

    private static final String PREFS_NAME = "retwitch_landing_settings";
    private static final String KEY_FOLLOWING_DEFAULT = "following_default_tab";

    private LandingSettings() {
    }

    public static boolean isFollowingDefaultEnabled() {
        return getBool(KEY_FOLLOWING_DEFAULT, true);
    }

    public static void setFollowingDefaultEnabled(Context context, boolean value) {
        setBool(context, KEY_FOLLOWING_DEFAULT, value);
    }

    private static final String KEY_PREVIEW_SIZE = "following_preview_size";

    /** Following's live previews: 0 Twitch's, 1 medium, 2 full width (retwitch-rn.js). */
    public static int getFollowingPreviewSize() {
        Context context = AppContext.get();
        int size = context == null ? 0 : prefs(context).getInt(KEY_PREVIEW_SIZE, 0);
        return size >= 0 && size <= 2 ? size : 0;
    }

    public static void setFollowingPreviewSize(Context context, int size) {
        prefs(context).edit().putInt(KEY_PREVIEW_SIZE, size).apply();
    }

    private static final String KEY_CHANNEL_PAGE = "channel_page_mode";

    /** The channel page: 0 as Twitch decides (experiment Android-Ultralight-Profile), 1 native, 2 React Native. */
    public static int getChannelPageMode() {
        Context context = AppContext.get();
        int mode = context == null ? 0 : prefs(context).getInt(KEY_CHANNEL_PAGE, 0);
        return mode >= 0 && mode <= 2 ? mode : 0;
    }

    public static void setChannelPageMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_CHANNEL_PAGE, mode).apply();
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
