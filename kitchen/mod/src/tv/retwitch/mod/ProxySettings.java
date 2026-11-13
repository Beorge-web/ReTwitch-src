package tv.retwitch.mod;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * Persists the user's usher-proxy choice and resolves it into a
 * replacement {@link Uri} for a given live usher.ttvnw.net playlist
 * request. Backed by a private {@link SharedPreferences} file so the
 * choice survives app restarts without touching any Twitch-owned storage.
 */
public final class ProxySettings {

    private static final String PREFS_NAME = "retwitch_proxy_settings";
    private static final String KEY_BACKEND = "backend";
    private static final String KEY_CUSTOM_URL = "custom_url";

    private ProxySettings() {
    }

    public static ProxyBackend getSelected() {
        Context context = AppContext.get();
        if (context == null) {
            return ProxyBackend.DISABLED;
        }
        String name = prefs(context).getString(KEY_BACKEND, ProxyBackend.DISABLED.name());
        try {
            return ProxyBackend.valueOf(name);
        } catch (IllegalArgumentException e) {
            return ProxyBackend.DISABLED;
        }
    }

    public static void setSelected(Context context, ProxyBackend backend) {
        prefs(context).edit().putString(KEY_BACKEND, backend.name()).apply();
    }

    public static String getCustomUrl() {
        Context context = AppContext.get();
        if (context == null) {
            return "";
        }
        return prefs(context).getString(KEY_CUSTOM_URL, "");
    }

    public static void setCustomUrl(Context context, String url) {
        prefs(context).edit().putString(KEY_CUSTOM_URL, url).apply();
    }

    /**
     * @param originalUsherUri the Uri Twitch itself built for the usher
     *                         playlist request (live or VOD)
     * @param isVod            whether this is a VOD request. All active
     *                         backends support VOD (Luminous/PerfProd via
     *                         their {@code /vod/<videoId>} endpoint), but the
     *                         flag selects the right path/query shape
     * @return a replacement Uri pointing at the selected proxy, or
     *         {@code null} if the proxy is disabled/unsupported/misconfigured
     *         (caller should keep using the original Twitch Uri in that case)
     */
    public static Uri maybeGetProxyUri(Uri originalUsherUri, boolean isVod) {
        if (originalUsherUri == null) {
            return null;
        }
        ProxyBackend backend = getSelected();
        if (!backend.isActive()) {
            return null;
        }
        if (isVod && !backend.supportsVod()) {
            return null;
        }
        // Some providers (Luminous/PerfProd) build their own request from just
        // the channel login (live) or numeric video id (VOD); others
        // (ReYohoho) only need the original Uri verbatim and don't care
        // whether this extraction succeeds.
        String slug = extractSlug(originalUsherUri);
        String customUrl = backend.needsCustomUrl() ? getCustomUrl() : null;
        return backend.buildUri(originalUsherUri, slug, isVod, customUrl);
    }

    /**
     * Pulls the channel login (live: {@code .../api/channel/hls/<channel>.m3u8})
     * or numeric video id (VOD: {@code .../vod/<videoId>.m3u8}) out of the
     * usher Uri's last path segment.
     */
    private static String extractSlug(Uri usherUri) {
        String last = usherUri.getLastPathSegment();
        if (last == null) {
            return null;
        }
        if (last.endsWith(".m3u8")) {
            return last.substring(0, last.length() - ".m3u8".length());
        }
        return last;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
