package tv.retwitch.mod;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists the two independent ad-blocking toggles, same storage pattern as
 * {@link PlayerSettings} / {@link ProxySettings} -- a private
 * {@link SharedPreferences} file read via {@link AppContext}, so it can be
 * queried from hooks that only ever receive plain data (no {@link Context}).
 *
 * <p>Twitch serves two distinct kinds of ad, and each needs its own,
 * unrelated mechanism -- hence two separate switches rather than one:
 *
 * <ul>
 *   <li><b>Client-side (VAST) ads</b> ({@link #isBlockClientAdsEnabled()}):
 *       the ones the app itself requests and plays through its own ad
 *       presenters/state machines. Blocked "in place" by neutering that
 *       pipeline in bytecode (see {@code ClientAdsPatch}). This is the
 *       equivalent of ReVanced/De-Vanced "Block video/audio ads".</li>
 *   <li><b>Stream ads</b> ({@link #isBlockSsaiAdsEnabled()}): ads delivered
 *       inside the live HLS stream itself (stitched segments marked with
 *       {@code X-TV-TWITCH-AD-*}). Neutering presenters can't touch them, so
 *       the live playlists are routed through an on-device VAFT proxy
 *       ({@code tv.retwitch.mod.ads.VaftServer}) that swaps in a clean backup
 *       stream or strips the ad segments.</li>
 * </ul>
 *
 * <p>Both default to {@code true}: a user installing an ad-block-capable mod
 * wants ads gone out of the box; either can be turned off individually from
 * the ReTwitch settings menu.
 */
public final class AdSettings {

    private static final String PREFS_NAME = "retwitch_ad_settings";
    private static final String KEY_BLOCK_CLIENT_ADS = "block_client_ads";
    private static final String KEY_BLOCK_SSAI_ADS = "block_ssai_ads";
    // v2: the default changed from "popout" to Twitch's own; old saved values are dropped.
    private static final String KEY_INITIAL_PLAYER_TYPE = "initial_player_type_v2";

    /** Player types offered for the live access token (VAFT's ForceAccessTokenPlayerType). */
    public static final String[] PLAYER_TYPES = {"", "popout", "embed", "site", "autoplay"};

    private AdSettings() {
    }

    /** @return whether client-side (VAST) video/audio ads should be blocked. Default {@code true}. */
    public static boolean isBlockClientAdsEnabled() {
        return getBool(KEY_BLOCK_CLIENT_ADS, true);
    }

    public static void setBlockClientAdsEnabled(Context context, boolean value) {
        setBool(context, KEY_BLOCK_CLIENT_ADS, value);
    }

    /** @return whether in-stream ads should be filtered by the VAFT proxy. Default {@code true}. */
    public static boolean isBlockSsaiAdsEnabled() {
        return getBool(KEY_BLOCK_SSAI_ADS, true);
    }

    public static void setBlockSsaiAdsEnabled(Context context, boolean value) {
        setBool(context, KEY_BLOCK_SSAI_ADS, value);
    }

    /**
     * @return the player type live access tokens are requested as while the
     *         VAFT toggle is on, or "" for Twitch's own. Default "": the stream
     *         stays untouched outside ad breaks, where VAFT swaps in a backup.
     */
    /** Display ads / Following headliner (DisplayAdsPatch); off by default. Never throws. */
    public static boolean isDisplayAdsBlocked() {
        return Toggles.on(Toggles.BLOCK_DISPLAY_ADS);
    }

    /**
     * URL of a React Native fetch (DisplayAdsPatch, `NetworkingModule.sendRequestInternalReal`):
     * the RN home builds Ad Edge requests itself (Following headliner, feed and stream display
     * ads). Blocked ones get port 0, which OkHttp rejects inside the module's try, so JS sees a
     * network error; http keeps the blob URI handler (non-http schemes) away. The favorites list
     * from retwitch-rn.js comes in the same way. Never throws.
     */
    public static String rnRequestUrl(String url) {
        if (url != null && url.startsWith(FavoriteChannels.RN_URL)) {
            return FavoriteChannels.fromRn(url);
        }
        return url != null && url.startsWith("https://edge.ads.twitch.tv/") && isDisplayAdsBlocked()
                ? "http://127.0.0.1:0/" : url;
    }

    /** Tracker SDK start-ups (TrackersPatch); off by default. Never throws. */
    public static boolean isTrackersBlocked() {
        return Toggles.on(Toggles.BLOCK_TRACKERS);
    }

    public static String getInitialPlayerType() {
        try {
            Context context = AppContext.get();
            return context != null ? prefs(context).getString(KEY_INITIAL_PLAYER_TYPE, "") : "";
        } catch (Throwable t) {
            return "";
        }
    }

    public static void setInitialPlayerType(Context context, String value) {
        prefs(context).edit().putString(KEY_INITIAL_PLAYER_TYPE, value).apply();
    }

    private static boolean getBool(String key, boolean fallback) {
        try {
            Context context = AppContext.get();
            if (context == null) {
                return fallback;
            }
            return prefs(context).getBoolean(key, fallback);
        } catch (Throwable t) {
            // Called from injected smali on the player hot path -- never let a
            // preferences read escape into Twitch. Fall back to "blocking on".
            return fallback;
        }
    }

    private static void setBool(Context context, String key, boolean value) {
        prefs(context).edit().putBoolean(key, value).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
