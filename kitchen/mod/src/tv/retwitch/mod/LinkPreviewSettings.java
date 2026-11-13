package tv.retwitch.mod;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Chat link-preview settings, mirroring the RTE browser extension's
 * "Link Preview" panel: a mode, max preview size, blur-until-tapped and
 * tap-to-enlarge. Off by default.
 */
public final class LinkPreviewSettings {

    public static final int MODE_OFF = 0;
    /** Hide media links completely (no text, no preview). */
    public static final int MODE_HIDE_ALL = 1;
    /** Show the preview instead of the link text. */
    public static final int MODE_PREVIEW_ONLY = 2;
    /** Show both the link text and the preview. */
    public static final int MODE_BOTH = 3;

    private static final String PREFS_NAME = "retwitch_link_preview";
    private static final String KEY_MODE = "mode";
    private static final String KEY_MAX_HEIGHT = "max_height_dp";
    private static final String KEY_MAX_WIDTH = "max_width_dp";
    private static final String KEY_BLUR = "blur";
    private static final String KEY_ZOOM = "zoom";

    public static final int DEFAULT_MAX_HEIGHT_DP = 150;
    public static final int DEFAULT_MAX_WIDTH_DP = 250;

    private LinkPreviewSettings() {
    }

    public static int getMode() {
        return getInt(KEY_MODE, MODE_OFF);
    }

    public static int getMaxHeightDp() {
        return getInt(KEY_MAX_HEIGHT, DEFAULT_MAX_HEIGHT_DP);
    }

    public static int getMaxWidthDp() {
        return getInt(KEY_MAX_WIDTH, DEFAULT_MAX_WIDTH_DP);
    }

    public static boolean isBlurEnabled() {
        return getInt(KEY_BLUR, 1) != 0;
    }

    public static boolean isZoomEnabled() {
        return getInt(KEY_ZOOM, 0) != 0;
    }

    static void save(Context context, int mode, int maxHeightDp, int maxWidthDp, boolean blur, boolean zoom) {
        prefs(context).edit()
                .putInt(KEY_MODE, mode)
                .putInt(KEY_MAX_HEIGHT, clamp(maxHeightDp))
                .putInt(KEY_MAX_WIDTH, clamp(maxWidthDp))
                .putInt(KEY_BLUR, blur ? 1 : 0)
                .putInt(KEY_ZOOM, zoom ? 1 : 0)
                .apply();
    }

    private static int clamp(int dp) {
        return Math.max(40, Math.min(600, dp));
    }

    private static int getInt(String key, int fallback) {
        try {
            Context context = AppContext.get();
            return context != null ? prefs(context).getInt(key, fallback) : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
