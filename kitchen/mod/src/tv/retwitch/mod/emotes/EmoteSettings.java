package tv.retwitch.mod.emotes;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import tv.retwitch.mod.AppContext;
import tv.retwitch.mod.ChannelTracker;
import tv.retwitch.mod.Toggles;

/**
 * Per-provider on/off toggles plus the "proxy emote traffic through
 * ReYohoho" switch, persisted the same way {@code ProxySettings} persists
 * the usher proxy choice -- a private {@link SharedPreferences} file, read
 * via {@link AppContext} so it works from contexts that only ever hand us
 * plain data (chat tokens), not a {@link Context}.
 */
public final class EmoteSettings {

    private static final String PREFS_NAME = "retwitch_emote_settings";
    private static final String KEY_BTTV = "bttv_enabled";
    private static final String KEY_FFZ = "ffz_enabled";
    private static final String KEY_SEVENTV = "seventv_enabled";
    private static final String KEY_PROXY = "proxy_enabled";
    private static final String KEY_NOCOLON = "autocomplete_no_colon";
    private static final String KEY_EMOTE_CARD = "emote_card_enabled";
    private static final String KEY_MODIFIERS = "modifiers_enabled";

    private EmoteSettings() {
    }

    private static final String KEY_EMOTE_SIZE = "emote_size_dp";
    private static final int DEFAULT_EMOTE_SIZE_DP = 22;

    /** Chat height of 7TV/BTTV/FFZ emotes in dp (Twitch's own emotes keep theirs). */
    public static int getEmoteSizeDp() {
        Context context = AppContext.get();
        return context == null ? DEFAULT_EMOTE_SIZE_DP : prefs(context).getInt(KEY_EMOTE_SIZE, DEFAULT_EMOTE_SIZE_DP);
    }

    private static final String KEY_FAVORITES = "favorite_emotes";
    private static final int MAX_FAVORITES = 100;

    /**
     * Favourites, newest first (see {@link EmoteFavorites}): {@code "code"} for a 7TV/BTTV/FFZ
     * emote, {@code "code twitchId"} for a Twitch one. One list for all channels, or the
     * current channel's own list with {@link Toggles#EMOTE_FAVORITES_PER_CHANNEL}.
     */
    public static List<String> getFavorites() {
        Context context = AppContext.get();
        String raw = context == null ? "" : prefs(context).getString(favoritesKey(), "");
        List<String> out = new ArrayList<>();
        for (String entry : raw.split("\n")) {
            if (!entry.isEmpty()) {
                out.add(entry);
            }
        }
        return out;
    }

    /** The emote code of a {@link #getFavorites} entry. */
    static String favoriteCode(String entry) {
        int space = entry.indexOf(' ');
        return space < 0 ? entry : entry.substring(0, space);
    }

    /**
     * Adds {@code code} on top, or removes it if already there.
     *
     * @param twitchId the Twitch emote id, or null for a 7TV/BTTV/FFZ emote
     * @return whether it is a favourite now
     */
    static synchronized boolean toggleFavorite(String code, String twitchId) {
        Context context = AppContext.get();
        List<String> favorites = getFavorites();
        boolean removed = false;
        for (Iterator<String> it = favorites.iterator(); it.hasNext(); ) {
            if (code.equals(favoriteCode(it.next()))) {
                it.remove();
                removed = true;
            }
        }
        if (!removed) {
            favorites.add(0, twitchId == null ? code : code + " " + twitchId);
        }
        while (favorites.size() > MAX_FAVORITES) {
            favorites.remove(favorites.size() - 1);
        }
        if (context != null) {
            prefs(context).edit().putString(favoritesKey(), android.text.TextUtils.join("\n", favorites)).apply();
        }
        return !removed;
    }

    private static String favoritesKey() {
        String channelId = Toggles.on(Toggles.EMOTE_FAVORITES_PER_CHANNEL) ? ChannelTracker.getCurrentChannelId() : null;
        return channelId == null || channelId.isEmpty() ? KEY_FAVORITES : KEY_FAVORITES + ":" + channelId;
    }

    private static final String KEY_QUALITY = "emote_quality";
    private static final String KEY_BTTV_WEBP = "bttv_webp";
    private static final String KEY_HOMIES = "homies_enabled";
    private static final String KEY_PICKER_BACKSPACE = "picker_backspace";

    /** Picker backspace modes ({@link #getPickerBackspace}); the order of the settings choice. */
    public static final int BACKSPACE_TWITCH = 0;
    public static final int BACKSPACE_WORD = 1;
    public static final int BACKSPACE_LETTER = 2;

    /** Image size asked from the CDNs, 1..4 (1x..4x); 2 is what ReTwitch always used. */
    public static int getQuality() {
        Context context = AppContext.get();
        int q = context == null ? 2 : prefs(context).getInt(KEY_QUALITY, 2);
        return Math.max(1, Math.min(4, q));
    }

    /** Takes effect on the next fetch, so the emote cache is reset (URLs are built at parse time). */
    public static void setQuality(Context context, int quality) {
        prefs(context).edit().putInt(KEY_QUALITY, Math.max(1, Math.min(4, quality))).apply();
        EmoteCache.resetAll(ChannelTracker.getCurrentChannelId());
    }

    /** Twitch CDN size for {@link #getQuality}: 1.0 / 2.0 / 3.0 (there is no 4x). */
    static String twitchSize() {
        return Math.min(getQuality(), 3) + ".0";
    }

    /** BTTV images as WEBP instead of the original GIF/PNG. Default off. */
    public static boolean isBttvWebpEnabled() {
        return getBool(KEY_BTTV_WEBP, false);
    }

    public static void setBttvWebpEnabled(Context context, boolean value) {
        setBool(context, KEY_BTTV_WEBP, value);
        EmoteCache.resetAll(ChannelTracker.getCurrentChannelId());
    }

    /** Homies emotes (itzalex.github.io, 7TV-hosted) inside the 7TV sets. Default off. */
    public static boolean isHomiesEnabled() {
        return getBool(KEY_HOMIES, false);
    }

    public static void setHomiesEnabled(Context context, boolean value) {
        setBool(context, KEY_HOMIES, value);
        EmoteCache.resetAll(ChannelTracker.getCurrentChannelId());
    }

    /** What the emote picker's backspace button deletes; default Twitch's own behaviour. */
    public static int getPickerBackspace() {
        Context context = AppContext.get();
        return context == null ? BACKSPACE_TWITCH : prefs(context).getInt(KEY_PICKER_BACKSPACE, BACKSPACE_TWITCH);
    }

    public static void setPickerBackspace(Context context, int mode) {
        prefs(context).edit().putInt(KEY_PICKER_BACKSPACE, mode).apply();
    }

    /** Clamped to 12..64 dp; drops decoded images so new messages use the new size. */
    public static void setEmoteSizeDp(Context context, int dp) {
        prefs(context).edit().putInt(KEY_EMOTE_SIZE, Math.max(12, Math.min(64, dp))).apply();
        EmoteImageCache.clear();
    }

    /**
     * Whether the chat-input emote autocomplete dropdown pops up for plain
     * words too, not only after a leading {@code :} (see
     * {@code AutocompleteNoColonPatch} / {@link EmoteAutocompleteBridge}).
     * Default on.
     */
    public static boolean isAutocompleteNoColonEnabled() {
        return getBool(KEY_NOCOLON, true);
    }

    public static void setAutocompleteNoColonEnabled(Context context, boolean value) {
        setBool(context, KEY_NOCOLON, value);
    }

    /**
     * Whether tapping a third-party emote in a chat message opens an info
     * card for it (see {@link EmoteChatHook} click spans /
     * {@code EmoteCardDialog}). Default on.
     */
    public static boolean isEmoteCardEnabled() {
        return getBool(KEY_EMOTE_CARD, true);
    }

    public static void setEmoteCardEnabled(Context context, boolean value) {
        setBool(context, KEY_EMOTE_CARD, value);
    }

    /**
     * Whether BTTV prefix ({@code w! h! v! z! c! l! r! p! s!}) and FFZ suffix
     * ({@code ffzW ffzX ffzY ffzCursed}) emote modifiers are applied in chat
     * (see {@link EmoteModifiers}). Default on.
     */
    public static boolean isModifiersEnabled() {
        return getBool(KEY_MODIFIERS, true);
    }

    public static void setModifiersEnabled(Context context, boolean value) {
        setBool(context, KEY_MODIFIERS, value);
    }

    public static boolean isBttvEnabled() {
        return getBool(KEY_BTTV, true);
    }

    public static boolean isFfzEnabled() {
        return getBool(KEY_FFZ, true);
    }

    public static boolean isSevenTvEnabled() {
        return getBool(KEY_SEVENTV, true);
    }

    /**
     * Whether API + CDN requests get routed through the ReYohoho tunnel
     * ({@code https://<starege host>/<original url>}); the actual host is
     * picked at load time by {@link EmoteProxy}.
     *
     * <p>Defaults to {@code true}: the third-party emote origins
     * ({@code 7tv.io}, {@code api.betterttv.net}, their CDNs) are unreliable
     * or outright blocked/DPI-throttled from some networks, where direct
     * access stalls or returns a non-image/non-JSON interstitial instead of
     * the real asset. Routing everything through the ReYohoho tunnel by
     * default keeps the whole pipeline on the path that actually works for
     * those users. Users on networks with clean direct access can still turn
     * it off in the emote settings dialog.
     */
    public static boolean isProxyEnabled() {
        return getBool(KEY_PROXY, true);
    }

    public static boolean isAnyProviderEnabled() {
        return isBttvEnabled() || isFfzEnabled() || isSevenTvEnabled();
    }

    public static void setBttvEnabled(Context context, boolean value) {
        setBool(context, KEY_BTTV, value);
    }

    public static void setFfzEnabled(Context context, boolean value) {
        setBool(context, KEY_FFZ, value);
    }

    public static void setSevenTvEnabled(Context context, boolean value) {
        setBool(context, KEY_SEVENTV, value);
    }

    public static void setProxyEnabled(Context context, boolean value) {
        setBool(context, KEY_PROXY, value);
        // Drop any previously-selected proxy host so the change takes effect on
        // the next emote load (re-probe when enabling; clear stale host when
        // disabling) rather than lingering until the resolve TTL expires.
        EmoteProxy.reset();
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
