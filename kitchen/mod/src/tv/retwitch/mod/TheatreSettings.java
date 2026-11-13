package tv.retwitch.mod;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists the landscape side-by-side ("chat next to player") divider
 * settings the same way {@link PlayerSettings} / {@link ChatSettings} do --
 * a private {@link SharedPreferences} file read via {@link AppContext}, so it
 * works from hooks/callbacks that only ever get plain data (no
 * {@link Context}).
 *
 * <p>Twitch's landscape theatre has three chat modes (no chat / overlay /
 * side-by-side). Only in the last one does the chat sit as its own column
 * beside the player, and Twitch sizes that column to a fixed percentage of
 * the display's largest edge (30% by default). {@link TheatreDividerHook}
 * adds a draggable handle on the player/chat boundary that live-resizes that
 * column; the chosen width (as a percentage of the largest screen edge) is
 * stored here so it survives leaving/re-entering a stream and app restarts.
 *
 * <p>{@link #KEY_ENABLED} gates the whole feature (the draggable handle);
 * default on.
 */
public final class TheatreSettings {

    private static final String PREFS_NAME = "retwitch_theatre_settings";
    private static final String KEY_ENABLED = "side_chat_divider_enabled";
    private static final String KEY_WIDTH_PERCENT = "side_chat_width_percent";
    private static final String KEY_CHAT_BG = "chat_bg_opacity";

    /** Chat background opacity steps, %: the settings slider and the quick panel on the divider. */
    public static final int[] CHAT_BG_STEPS = {0, 20, 40, 60, 80, 100};
    private static final int DEFAULT_CHAT_BG = 60;

    /**
     * Twitch's own default side-chat width (30% of the largest screen edge --
     * confirmed on-device: a 720px chat column on a 2400px-wide display). Used
     * as the fallback until the user drags the divider.
     */
    public static final int DEFAULT_WIDTH_PERCENT = 30;

    /**
     * Clamp range for the chat column width, as a percentage of the largest
     * screen edge. Keeps both the player and the chat usable no matter how far
     * the divider is dragged.
     */
    public static final int MIN_WIDTH_PERCENT = 15;
    public static final int MAX_WIDTH_PERCENT = 70;

    private TheatreSettings() {
    }

    public static boolean isSideChatDividerEnabled() {
        return getBool(KEY_ENABLED, true);
    }

    public static void setSideChatDividerEnabled(Context context, boolean value) {
        setBool(context, KEY_ENABLED, value);
    }

    /** The persisted chat-column width as a percentage of the largest screen edge, clamped to the usable range. */
    public static int getSideChatWidthPercent() {
        return clamp(getInt(KEY_WIDTH_PERCENT, DEFAULT_WIDTH_PERCENT));
    }

    public static void setSideChatWidthPercent(Context context, int value) {
        setInt(context, KEY_WIDTH_PERCENT, clamp(value));
    }

    /** Whether a width was dragged: it applies with the handle turned off too. */
    public static boolean hasSideChatWidth() {
        Context context = AppContext.get();
        return context != null && prefs(context).contains(KEY_WIDTH_PERCENT);
    }

    /** Forgets the dragged width, so the column falls back to {@link #DEFAULT_WIDTH_PERCENT}. */
    public static void resetSideChatWidth(Context context) {
        if (context == null) {
            return;
        }
        prefs(context).edit().remove(KEY_WIDTH_PERCENT).apply();
    }

    /** Opacity of the chat background over the video, %: the side column over the video and the overlay chat bubble. */
    public static int getChatBgOpacity() {
        return Math.max(0, Math.min(100, getInt(KEY_CHAT_BG, DEFAULT_CHAT_BG)));
    }

    public static void setChatBgOpacity(Context context, int percent) {
        setInt(context, KEY_CHAT_BG, Math.max(0, Math.min(100, percent)));
    }

    /** Whether the opacity was chosen: only then does the overlay chat bubble (Twitch's own look otherwise) follow it. */
    public static boolean hasChatBgOpacity() {
        Context context = AppContext.get();
        return context != null && prefs(context).contains(KEY_CHAT_BG);
    }

    public static int clamp(int percent) {
        if (percent < MIN_WIDTH_PERCENT) {
            return MIN_WIDTH_PERCENT;
        }
        if (percent > MAX_WIDTH_PERCENT) {
            return MAX_WIDTH_PERCENT;
        }
        return percent;
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

    private static int getInt(String key, int fallback) {
        Context context = AppContext.get();
        if (context == null) {
            return fallback;
        }
        return prefs(context).getInt(key, fallback);
    }

    private static void setInt(Context context, String key, int value) {
        prefs(context).edit().putInt(key, value).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
