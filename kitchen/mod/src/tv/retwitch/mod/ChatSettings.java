package tv.retwitch.mod;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists chat-related toggles the same way {@link ProxySettings} /
 * {@link PlayerSettings} do -- a private {@link SharedPreferences} file read
 * via {@link AppContext}, so it works from hooks that only ever get plain
 * data (no {@link Context}).
 *
 * <p>Currently holds the "show deleted messages" toggle. Twitch only wires up
 * the click-to-reveal treatment for deleted chat messages when the message
 * list is configured with {@code hasModAccess == true}; ReTwitch forces that
 * flag on (see {@code DeletedMessagesPatch} / {@link DeletedMessagesHook}) so
 * everyone can reveal deleted messages behind a tap. Default on.
 */
public final class ChatSettings {

    private static final String PREFS_NAME = "retwitch_chat_settings";
    private static final String KEY_SHOW_DELETED = "show_deleted_messages";
    private static final String KEY_AUTO_CLAIM = "auto_claim_points";
    private static final String KEY_STICKY_SCROLL = "sticky_scroll";
    private static final String KEY_SMOOTH_MESSAGES = "smooth_messages";
    private static final String KEY_MENTION_HIGHLIGHT = "mention_highlight";
    private static final String KEY_MENTION_VIBRATE = "mention_vibrate";
    private static final String KEY_RTE_BADGES = "rte_badges";
    private static final String KEY_NICK_PAINTS = "nick_paints";

    private ChatSettings() {
    }

    /**
     * Whether ReYohoho (RTE) chat badges are shown next to usernames (see
     * {@link tv.retwitch.mod.emotes.ChatCosmetics} /
     * {@link tv.retwitch.mod.emotes.RteBadgeStore}). Default on.
     */
    public static boolean isRteBadgesEnabled() {
        return getBool(KEY_RTE_BADGES, true);
    }

    public static void setRteBadgesEnabled(Context context, boolean value) {
        setBool(context, KEY_RTE_BADGES, value);
    }

    /**
     * Whether RTE/7TV nickname paints (gradient/coloured usernames) are
     * applied in chat (see {@link tv.retwitch.mod.emotes.ChatCosmetics#paintName}). Default off: new feature.
     */
    public static boolean isNickPaintsEnabled() {
        return getBool(KEY_NICK_PAINTS, false);
    }

    public static void setNickPaintsEnabled(Context context, boolean value) {
        setBool(context, KEY_NICK_PAINTS, value);
    }

    /**
     * Whether chat messages that @mention the logged-in user get a highlighted
     * background (see {@link tv.retwitch.mod.emotes.MentionHighlighter}).
     * Default on.
     */
    public static boolean isMentionHighlightEnabled() {
        return getBool(KEY_MENTION_HIGHLIGHT, true);
    }

    public static void setMentionHighlightEnabled(Context context, boolean value) {
        setBool(context, KEY_MENTION_HIGHLIGHT, value);
    }

    /**
     * Whether a new chat message that @mentions the logged-in user buzzes the
     * device (see {@link tv.retwitch.mod.emotes.MentionHighlighter}). Default on.
     */
    public static boolean isMentionVibrateEnabled() {
        return getBool(KEY_MENTION_VIBRATE, true);
    }

    public static void setMentionVibrateEnabled(Context context, boolean value) {
        setBool(context, KEY_MENTION_VIBRATE, value);
    }

    /** Mention highlight colours (ARGB); the first is PurpleTV's {@code USER_MENTION_COLOR}. */
    public static final int[] MENTION_COLORS = {
            0x64FF0000, 0x4D9146FF, 0x64FF8C00, 0x50FFD700, 0x5032CD32, 0x501E90FF,
    };
    private static final String KEY_MENTION_COLOR = "mention_color";

    public static int getMentionColorIndex() {
        Context context = AppContext.get();
        int index = context == null ? 0 : prefs(context).getInt(KEY_MENTION_COLOR, 0);
        return index >= 0 && index < MENTION_COLORS.length ? index : 0;
    }

    public static int getMentionColor() {
        return MENTION_COLORS[getMentionColorIndex()];
    }

    public static void setMentionColorIndex(Context context, int index) {
        prefs(context).edit().putInt(KEY_MENTION_COLOR, index).apply();
    }

    private static final String KEY_WORDS_COLOR = "highlight_words_color";

    /** Colour of rows matched by the highlight words and nicks: an index of {@link #MENTION_COLORS}, -1 the mention one. */
    public static int getWordsColorIndex() {
        Context context = AppContext.get();
        int index = context == null ? -1 : prefs(context).getInt(KEY_WORDS_COLOR, -1);
        return index >= -1 && index < MENTION_COLORS.length ? index : -1;
    }

    public static void setWordsColorIndex(Context context, int index) {
        prefs(context).edit().putInt(KEY_WORDS_COLOR, index).apply();
    }

    public static int getWordsColor() {
        int index = getWordsColorIndex();
        return index < 0 ? getMentionColor() : MENTION_COLORS[index];
    }

    /** Mention vibration lengths, ms; 60 was the fixed one before. */
    public static final int[] VIBRATE_MS = {60, 30, 100, 200, 400};
    private static final String KEY_MENTION_VIBRATE_MS = "mention_vibrate_ms";
    private static final String KEY_MENTION_SOUND = "mention_sound";
    private static final String KEY_MENTION_SOUND_VOLUME = "mention_sound_volume";
    private static final String KEY_MENTION_SOUND_URI = "mention_sound_uri";

    /** The picked sound, or empty for the system notification sound. */
    public static String getMentionSoundUri() {
        Context context = AppContext.get();
        return context == null ? "" : prefs(context).getString(KEY_MENTION_SOUND_URI, "");
    }

    public static void setMentionSoundUri(Context context, String uri) {
        prefs(context).edit().putString(KEY_MENTION_SOUND_URI, uri).apply();
    }

    /** A notification sound on a mention, with the same backlog and rate rules as the vibration. Default off. */
    public static boolean isMentionSoundEnabled() {
        return getBool(KEY_MENTION_SOUND, false);
    }

    public static void setMentionSoundEnabled(Context context, boolean value) {
        setBool(context, KEY_MENTION_SOUND, value);
    }

    /** Percent of the notification volume (Android 9+; older ones play at the notification volume). */
    public static int getMentionSoundVolume() {
        Context context = AppContext.get();
        return context == null ? 50 : prefs(context).getInt(KEY_MENTION_SOUND_VOLUME, 50);
    }

    public static void setMentionSoundVolume(Context context, int percent) {
        prefs(context).edit().putInt(KEY_MENTION_SOUND_VOLUME, percent).apply();
    }

    public static int getMentionVibrateMs() {
        Context context = AppContext.get();
        return context == null ? 60 : prefs(context).getInt(KEY_MENTION_VIBRATE_MS, 60);
    }

    public static void setMentionVibrateMs(Context context, int ms) {
        prefs(context).edit().putInt(KEY_MENTION_VIBRATE_MS, ms).apply();
    }

    private static final String KEY_HIGHLIGHT_WORDS = "highlight_words";

    /** Extra highlight terms, comma separated: words in a message, or "@nick" for a sender. */
    public static String getHighlightWords() {
        Context context = AppContext.get();
        return context == null ? "" : prefs(context).getString(KEY_HIGHLIGHT_WORDS, "");
    }

    public static void setHighlightWords(Context context, String value) {
        prefs(context).edit().putString(KEY_HIGHLIGHT_WORDS, value == null ? "" : value.trim()).apply();
    }

    /** Deleted messages: Twitch's placeholder only. */
    public static final int DELETED_TWITCH = 0;
    /** Placeholder, the text revealed by a tap (what a moderator gets). */
    public static final int DELETED_TAP = 1;
    /** The text right away: plain, struck through or grey. */
    public static final int DELETED_TEXT = 2;
    public static final int DELETED_STRIKE = 3;
    public static final int DELETED_GREY = 4;
    private static final String KEY_DELETED_MODE = "deleted_mode";

    /** One of the {@code DELETED_*} modes; the old on/off switch maps to tap / Twitch. */
    public static int getDeletedMode() {
        Context context = AppContext.get();
        if (context == null) {
            return DELETED_GREY;
        }
        SharedPreferences prefs = prefs(context);
        if (prefs.contains(KEY_DELETED_MODE)) {
            return prefs.getInt(KEY_DELETED_MODE, DELETED_GREY);
        }
        if (prefs.contains(KEY_SHOW_DELETED)) {
            return prefs.getBoolean(KEY_SHOW_DELETED, true) ? DELETED_TAP : DELETED_TWITCH;
        }
        // As RTE (deleted messages highlighted, text kept): a banned user's lines stay readable.
        return DELETED_GREY;
    }

    public static void setDeletedMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_DELETED_MODE, mode).apply();
    }

    /** Deleted message row tint ({@code DELETED_BACKGROUND}): a colour of {@link #MENTION_COLORS} at one of these %. */
    public static final int[] DELETED_ALPHAS = {10, 15, 20, 30, 40, 50};
    private static final String KEY_DELETED_BG_COLOR = "deleted_bg_color";
    private static final String KEY_DELETED_BG_ALPHA = "deleted_bg_alpha";

    public static int getDeletedBgColorIndex() {
        Context context = AppContext.get();
        int index = context == null ? 0 : prefs(context).getInt(KEY_DELETED_BG_COLOR, 0);
        return index >= 0 && index < MENTION_COLORS.length ? index : 0;
    }

    public static void setDeletedBgColorIndex(Context context, int index) {
        prefs(context).edit().putInt(KEY_DELETED_BG_COLOR, index).apply();
    }

    public static int getDeletedBgAlphaIndex() {
        Context context = AppContext.get();
        int index = context == null ? 2 : prefs(context).getInt(KEY_DELETED_BG_ALPHA, 2);
        return index >= 0 && index < DELETED_ALPHAS.length ? index : 2;
    }

    public static void setDeletedBgAlphaIndex(Context context, int index) {
        prefs(context).edit().putInt(KEY_DELETED_BG_ALPHA, index).apply();
    }

    /** ARGB of the deleted row tint. */
    public static int getDeletedBackground() {
        int alpha = Math.round(255 * DELETED_ALPHAS[getDeletedBgAlphaIndex()] / 100f);
        return alpha << 24 | MENTION_COLORS[getDeletedBgColorIndex()] & 0xFFFFFF;
    }

    /** Twitch keeps the deleted text (mod access forced) in every mode but its own. */
    public static boolean isShowDeletedMessagesEnabled() {
        return getDeletedMode() != DELETED_TWITCH;
    }

    /** Chat timestamp patterns; index 0 leaves Twitch's own (H:mm, only when its timestamps are on). */
    public static final String[] TIMESTAMP_PATTERNS = {
            "", "H:mm", "H:mm:ss", "HH:mm", "HH:mm:ss", "h:mm", "h:mm:ss", "hh:mm", "hh:mm:ss",
    };
    private static final String KEY_TIMESTAMP_FORMAT = "timestamp_format";

    /** Index into {@link #TIMESTAMP_PATTERNS}. */
    public static int getTimestampFormat() {
        Context context = AppContext.get();
        int index = context == null ? 0 : prefs(context).getInt(KEY_TIMESTAMP_FORMAT, 0);
        return index >= 0 && index < TIMESTAMP_PATTERNS.length ? index : 0;
    }

    public static void setTimestampFormat(Context context, int index) {
        prefs(context).edit().putInt(KEY_TIMESTAMP_FORMAT, index).apply();
    }

    public static boolean isAutoClaimEnabled() {
        return getBool(KEY_AUTO_CLAIM, false);
    }

    public static void setAutoClaimEnabled(Context context, boolean value) {
        setBool(context, KEY_AUTO_CLAIM, value);
    }

    /**
     * When on, manually scrolling up sticks: autoscroll-to-bottom only resumes
     * once you're back at the bottom (or tap the "more messages below" pill),
     * instead of Twitch yanking you down whenever the list settles. See
     * {@code AutoscrollPatch} / {@link LiveChatHook#effectiveScrolling}.
     * Default on.
     */
    public static boolean isStickyScrollEnabled() {
        return getBool(KEY_STICKY_SCROLL, true);
    }

    public static void setStickyScrollEnabled(Context context, boolean value) {
        setBool(context, KEY_STICKY_SCROLL, value);
    }

    /**
     * When on, keeps the chat RecyclerView's default item animator so new
     * messages animate in one-by-one (as older builds did) instead of popping
     * in as a batch. See {@code AnimatorPatch} / {@link
     * LiveChatHook#applyChatAnimator}. Default on.
     */
    public static boolean isSmoothMessagesEnabled() {
        return getBool(KEY_SMOOTH_MESSAGES, false);
    }

    public static void setSmoothMessagesEnabled(Context context, boolean value) {
        setBool(context, KEY_SMOOTH_MESSAGES, value);
    }

    /** Sender name of a non-ASCII display name: Twitch's "Display (login)", the display name, or the login. */
    public static final int NAME_BOTH = 0;
    public static final int NAME_DISPLAY = 1;
    public static final int NAME_LOGIN = 2;
    private static final String KEY_NAME_FORMAT = "name_format";

    public static int getNameFormat() {
        Context context = AppContext.get();
        return context == null ? NAME_BOTH : prefs(context).getInt(KEY_NAME_FORMAT, NAME_BOTH);
    }

    public static void setNameFormat(Context context, int format) {
        prefs(context).edit().putInt(KEY_NAME_FORMAT, format).apply();
    }

    /**
     * Chat message text size steps, % of Twitch's own, smallest first; 100 = untouched (the slider, ChatPinch). In 5 %
     * steps since feedback idea #67 (were 70, 85, 100, 115, 130, 150, 175: all still in the list).
     */
    public static final int[] FONT_SCALES = scales(70, 175, 5);

    private static int[] scales(int from, int to, int step) {
        int[] out = new int[(to - from) / step + 1];
        for (int i = 0; i < out.length; i++) {
            out[i] = from + i * step;
        }
        return out;
    }
    private static final String KEY_FONT_SCALE = "font_scale_percent";

    /** Chat message text size in % of Twitch's (applied per bound row, see {@code EmoteChatHook.onTextBound}). */
    public static int getFontScale() {
        Context context = AppContext.get();
        return context == null ? 100 : prefs(context).getInt(KEY_FONT_SCALE, 100);
    }

    public static void setFontScale(Context context, int percent) {
        prefs(context).edit().putInt(KEY_FONT_SCALE, percent).apply();
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
