package tv.retwitch.mod;

import android.net.Uri;

import tv.retwitch.mod.ads.SsaiSwapEngine;
import tv.retwitch.mod.ads.VaftServer;

/**
 * Entry point injected into Twitch's live-usher-URL builder lambda by the
 * ReTwitch "kitchen" toolchain (revanced-patcher fingerprint patch).
 *
 * Deliberately independent from any Twitch-internal classes: the patch only
 * ever hands us the already-built {@link Uri} for the usher.ttvnw.net
 * request, so this class only touches the public Android SDK. That keeps
 * this stable across app updates even though the class/method that builds
 * the Uri gets renamed by R8 on every release.
 */
public final class UsherHook {

    private UsherHook() {
    }

    /** ReYohoho's chat relay (the RTE / ReYohoho Twitch Proxy extensions' IRC WebSocket proxy). */
    static final String CHAT_PROXY_URL = "wss://ext.rte.net.ru:8443/tw-irc-proxy";

    /** Last IRC frame seen by the chat SDK (JailChat's frame hook), elapsedRealtime. */
    static volatile long lastChatFrame;
    private static volatile long lastProxyAttempt;
    private static volatile long proxyDownUntil;
    /** How long chat stays direct after the relay failed, before the next connect tries it again. */
    private static final long PROXY_RETRY_MS = 10 * 60_000L;

    /**
     * Injected where the chat SDK opens its IRC WebSocket (31.9.0 {@code pe2.q()}: the URL
     * handed to OkHttp's request builder; every (re)connect), else at its endpoint getter
     * ({@code p06.getUrl}, once): with {@link Toggles#CHAT_PROXY} the socket goes through
     * ReYohoho's relay, for regions where irc-ws.chat.twitch.tv is blocked. A relay connection
     * that brought no frame before Twitch reconnected counts as down: chat goes direct for
     * {@link #PROXY_RETRY_MS} and says so in the chat. Never throws.
     */
    public static String chatUrl(String url) {
        try {
            if (url == null || !url.startsWith("wss://irc-ws.chat.twitch.tv") || !Toggles.on(Toggles.CHAT_PROXY)) {
                return url;
            }
            long now = android.os.SystemClock.elapsedRealtime();
            if (now < proxyDownUntil) {
                return url;
            }
            long tried = lastProxyAttempt;
            if (tried > 0 && lastChatFrame < tried && now - tried < 120_000L) {
                proxyDownUntil = now + PROXY_RETRY_MS;
                lastProxyAttempt = 0;
                android.util.Log.w("RTChatProxy", "relay gave no frame, direct for 10 min");
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(() -> ChatNotices.post(Strings.chatProxyDown()), 3000L);
                return url;
            }
            lastProxyAttempt = now;
            return CHAT_PROXY_URL;
        } catch (Throwable t) {
            return url;
        }
    }

    /**
     * Called with the fully-built usher.ttvnw.net live playlist request
     * Uri. If the user picked a proxy server in ReTwitch's settings, the
     * channel name is extracted straight from this Uri's path (it is
     * already public data Twitch itself put there, so no dependence on
     * any obfuscated internals) and a request to that proxy is returned
     * instead -- the player downstream doesn't care which host it came
     * from, it just GETs whatever Uri it's handed. Otherwise falls back
     * to forcing {@code allow_source=true} on Twitch's own Uri, unlocking
     * the Source quality option.
     */
    public static Uri forceSourceQuality(Uri uri) {
        return apply(uri, false);
    }

    /**
     * Same idea as {@link #forceSourceQuality(Uri)}, for the VOD usher
     * request instead of the live one. Kept as a separate entry point
     * (rather than auto-detecting) because the two are injected into two
     * different, independently-fingerprinted methods, and because the VOD
     * vs. live distinction changes the proxy request shape -- see
     * {@link ProxyBackend#buildUri} / {@link ProxyBackend#supportsVod()}.
     */
    public static Uri forceVodSourceQuality(Uri uri) {
        return apply(uri, true);
    }

    /** VodModel.isRestricted(): false with sub-only VODs on, so the app plays them instead of asking to subscribe. */
    public static boolean vodRestricted(boolean restricted) {
        return restricted && !Toggles.on(Toggles.SUB_VODS);
    }

    private static Uri apply(Uri uri, boolean isVod) {
        if (uri == null) {
            return null;
        }

        Uri source = UriUtils.withAllowSourceTrue(uri);
        Uri proxied = ProxySettings.maybeGetProxyUri(uri, isVod);
        Uri target = proxied != null ? proxied : source;
        boolean vaft = !isVod && AdSettings.isBlockSsaiAdsEnabled();
        android.util.Log.i("RTUsher", (isVod ? "vod via " : "live via ") + target.getHost() + (vaft ? " + VAFT" : ""));
        // A stream proxy's master goes through the local server even without
        // VAFT: if the proxy fails or refuses (a channel blocked in its region),
        // Twitch's own master is played instead.
        Uri fallback = proxied != null ? source : null;
        java.net.Proxy via = fallback != null ? ProxySettings.getSelected().httpProxy() : null;
        if (isVod && Toggles.on(Toggles.SUB_VODS)) {
            // usher/vod/<id>.m3u8; refused (sub-only) -> master from the VOD CDN.
            String id = uri.getLastPathSegment();
            return VaftServer.wrapVod(target, id != null ? id.replace(".m3u8", "") : null);
        }
        if (vaft) {
            // VAFT on top of whatever serves the stream (Twitch or a stream
            // proxy -- proxies can carry ads too): remember the real Twitch
            // request (backups reuse its exact query) and route the playlists
            // through the local ad-filtering proxy. Both calls swallow errors.
            SsaiSwapEngine.rememberLiveUsher(source);
            return VaftServer.wrapMaster(target, fallback, via, true);
        }
        if (!isVod && Toggles.on(Toggles.BAN_BYPASS)) {
            // Through the local server so a refused master (channel ban on viewing) is retried anonymously.
            SsaiSwapEngine.rememberLiveUsher(source);
            return VaftServer.wrapMaster(target, fallback, via, false);
        }
        return fallback != null ? VaftServer.wrapMaster(target, fallback, via, false) : target;
    }
}
