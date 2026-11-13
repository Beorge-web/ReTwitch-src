package tv.retwitch.mod;

import android.net.Uri;

import java.net.InetSocketAddress;
import java.net.Proxy;

/**
 * Third-party usher-playlist proxies. Selecting one swaps out Twitch's own
 * usher.ttvnw.net request for a request to the proxy's mirror of the same
 * live channel, which is how these community proxies strip/relocate ads
 * and/or unlock qualities Twitch's own edge would otherwise restrict.
 *
 * Providers differ in what they expect as input:
 * <ul>
 *   <li>{@link Kind#CHANNEL_PATH} (Luminous): build their own request from
 *       scratch, keyed by plain channel name --
 *       {@code https://host/playlist/<channel>?query...} with an ordinary
 *       query string. VOD uses the sibling {@code /vod/<videoId>?query...}
 *       endpoint.</li>
 *   <li>{@link Kind#TTV_LOL} (PerfProd / TTV-LOL-PRO): also keyed by plain
 *       channel name, but for <em>live</em> the whole {@code ?query} has to be
 *       percent-encoded <em>into the path</em> --
 *       {@code https://host/playlist/<channel>.m3u8%3Fallow_source%3Dtrue%26...}.
 *       These servers return HTTP 500 for a normal (unencoded) live query
 *       string; the encoded form is the "TTV.LOL API" convention (verified
 *       against {@code lb-*.cdn-perfprod.com}). VOD is simpler: a plain
 *       {@code /vod/<videoId>?query...} with an ordinary query string works.</li>
 *   <li>{@link Kind#URL_SUFFIX} (ReYohoho): expect Twitch's own,
 *       already-built usher URL appended verbatim right after their own
 *       domain -- {@code https://host/https://usher.ttvnw.net/...}. When the
 *       user is logged in, the OAuth token is also tacked on as the
 *       {@code auth} query parameter so the proxy can fetch the playlist on
 *       the user's behalf (see {@link #withUserAuth}).</li>
 *   <li>{@link Kind#HTTP_PROXY} (TTV LOL PRO 2.x): a plain HTTP proxy
 *       ({@code host:port}) that only accepts {@code CONNECT} to Twitch hosts.
 *       Twitch's own usher request goes through it unchanged, token and all
 *       (TLS end to end, the proxy sees only the host); see
 *       {@link #httpProxy()}. Live only, like the extension itself.</li>
 * </ul>
 */
public enum ProxyBackend {
    DISABLED(Kind.DISABLED, null, null),
    RTE_PROXY4(Kind.URL_SUFFIX, "ReYohoho (proxy4)", "https://proxy4.rte.net.ru/"),
    RTE_PROXY5(Kind.URL_SUFFIX, "ReYohoho (proxy5)", "https://proxy5.rte.net.ru/"),
    RTE_PROXY6(Kind.URL_SUFFIX, "ReYohoho (proxy6)", "https://proxy6.rte.net.ru/"),
    RTE_PROXY7(Kind.URL_SUFFIX, "ReYohoho (proxy7)", "https://proxy7.rte.net.ru/"),
    LUMINOUS_EU(Kind.CHANNEL_PATH, "Luminous (EU)", "https://eu.luminous.dev"),
    LUMINOUS_EU2(Kind.CHANNEL_PATH, "Luminous (EU2)", "https://eu2.luminous.dev"),
    LUMINOUS_AS(Kind.CHANNEL_PATH, "Luminous (AS)", "https://as.luminous.dev"),
    // The rest of lb-* (EU, EU2-5, NA, SA) died by 2026-11: 403 or no answer from every country tried.
    PERFPROD_AS(Kind.TTV_LOL, "PerfProd (AS)", "https://lb-as.cdn-perfprod.com"),
    TTV_LOL_PRO(Kind.HTTP_PROXY, "TTV LOL PRO", "chromium.api.cdn-perfprod.com:2023"),
    CUSTOM(Kind.CUSTOM, null, null);

    private enum Kind {
        DISABLED, CHANNEL_PATH, TTV_LOL, URL_SUFFIX, HTTP_PROXY, CUSTOM
    }

    /**
     * The {@code ?query} PerfProd/TTV-LOL-PRO servers expect, already
     * percent-encoded so it rides inside the URL path instead of as a real
     * query string (see {@link Kind#TTV_LOL}): {@code ?platform=web&
     * allow_source=true&allow_audio_only=true&fast_bread=true}. A plain,
     * unencoded query returns HTTP 500 from these hosts.
     */
    private static final String TTV_LOL_ENCODED_QUERY =
            "%3Fplatform%3Dweb%26allow_source%3Dtrue%26allow_audio_only%3Dtrue%26fast_bread%3Dtrue";

    /**
     * Fixed provider name (ReYohoho/Luminous/PerfProd hosts) that reads the
     * same in every language. {@code null} for {@link #DISABLED} and
     * {@link #CUSTOM}, whose labels are user-facing prose resolved per
     * device language via {@link #label()}.
     */
    private final String fixedLabel;
    private final Kind kind;
    private final String baseUrl;

    ProxyBackend(Kind kind, String fixedLabel, String baseUrl) {
        this.kind = kind;
        this.fixedLabel = fixedLabel;
        this.baseUrl = baseUrl;
    }

    /** Device-language-aware display label for this backend (see {@link Strings}). */
    public String label() {
        switch (kind) {
            case DISABLED:
                return Strings.proxyDisabled();
            case CUSTOM:
                return Strings.proxyCustom();
            default:
                return fixedLabel;
        }
    }

    public boolean needsCustomUrl() {
        return kind == Kind.CUSTOM;
    }

    public boolean isActive() {
        return kind != Kind.DISABLED;
    }

    /**
     * The HTTP proxy usher is fetched through ({@link Kind#HTTP_PROXY}), else null.
     * Unresolved: OkHttp/HttpURLConnection look the host up when connecting, off the main thread.
     */
    public Proxy httpProxy() {
        if (kind != Kind.HTTP_PROXY) {
            return null;
        }
        int colon = baseUrl.lastIndexOf(':');
        return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(
                baseUrl.substring(0, colon), Integer.parseInt(baseUrl.substring(colon + 1))));
    }

    /**
     * Every active backend but {@link Kind#HTTP_PROXY} supports VOD. Luminous/PerfProd expose a
     * dedicated {@code /vod/<videoId>} endpoint (verified live: it returns a
     * proper {@code #EXTM3U} VOD playlist -- the proxy fetches the VOD access
     * token itself, same as it does for live), and {@link Kind#URL_SUFFIX}
     * (ReYohoho) / {@link Kind#CUSTOM} simply relay whatever usher Uri they're
     * given, live or VOD alike. TTV LOL PRO leaves VODs direct.
     */
    public boolean supportsVod() {
        return isActive() && kind != Kind.HTTP_PROXY;
    }

    /** ReYohoho hosts tunnel arbitrary usher URLs (live + VOD) rather than being ad-proxies; used to group them separately in the picker. */
    public boolean isReYohoho() {
        return kind == Kind.URL_SUFFIX;
    }

    /**
     * Luminous / PerfProd build the master with their own anonymous token, so the playback session isn't the
     * viewer's and Drops don't count (measured on a device, 2026-11-08). The rest pass the account through.
     */
    public boolean countsDrops() {
        return kind != Kind.CHANNEL_PATH && kind != Kind.TTV_LOL;
    }

    /** Public anti-ad playlist proxies (Luminous / PerfProd / TTV LOL PRO), grouped together in the picker. */
    public boolean isPublicAdProxy() {
        return kind == Kind.CHANNEL_PATH || kind == Kind.TTV_LOL || kind == Kind.HTTP_PROXY;
    }

    /**
     * @param originalUri the Uri Twitch itself built for the live usher
     *                    playlist request -- used verbatim by
     *                    {@link Kind#URL_SUFFIX} / {@link Kind#HTTP_PROXY} providers
     * @param channel     plain channel/login name (live) or numeric video id
     *                    (VOD) extracted from {@code originalUri} -- used by
     *                    {@link Kind#CHANNEL_PATH} / {@link Kind#TTV_LOL}
     *                    providers to build their own request
     * @param isVod       whether this is a VOD request. Luminous/PerfProd
     *                    build a {@code /vod/<id>} request instead of
     *                    {@code /playlist/<channel>}; ReYohoho/custom ignore
     *                    it and just relay {@code originalUri}
     * @param customTemplate only used by {@link #CUSTOM}: a URL containing
     *                       a {@code {url}} and/or {@code {channel}}
     *                       placeholder, or (if neither is present) a base
     *                       URL that the full original request gets
     *                       appended to
     */
    Uri buildUri(Uri originalUri, String channel, boolean isVod, String customTemplate) {
        switch (kind) {
            case CHANNEL_PATH:
                if (channel == null || channel.isEmpty()) {
                    return null;
                }
                return Uri.parse(baseUrl + (isVod ? "/vod/" : "/playlist/") + Uri.encode(channel))
                        .buildUpon()
                        .appendQueryParameter("allow_source", "true")
                        .appendQueryParameter("allow_audio_only", "true")
                        .appendQueryParameter("allow_spectre", "false")
                        .appendQueryParameter("fast_bread", "true")
                        .appendQueryParameter("type", "any")
                        .appendQueryParameter("player", "twitchweb")
                        .build();
            case TTV_LOL:
                if (channel == null || channel.isEmpty()) {
                    return null;
                }
                if (isVod) {
                    // VOD accepts a plain /vod/<videoId> with an ordinary
                    // query string (verified live), unlike the live /playlist
                    // path which must be percent-encoded to avoid HTTP 500.
                    return Uri.parse(baseUrl + "/vod/" + Uri.encode(channel))
                            .buildUpon()
                            .appendQueryParameter("allow_source", "true")
                            .appendQueryParameter("allow_audio_only", "true")
                            .build();
                }
                // .../playlist/<channel>.m3u8 + a percent-encoded query baked
                // into the path (see TTV_LOL_ENCODED_QUERY). Built as a raw
                // string, not via buildUpon()/appendQueryParameter(), because
                // those would emit a real, unencoded query string -- exactly
                // what these servers reject with HTTP 500.
                return safeParse(baseUrl + "/playlist/" + Uri.encode(channel) + ".m3u8"
                        + TTV_LOL_ENCODED_QUERY);
            case URL_SUFFIX:
                return safeParse(withUserAuth(baseUrl + UriUtils.withAllowSourceTrue(originalUri).toString()));
            case HTTP_PROXY:
                // Usher itself; VaftServer fetches it through httpProxy().
                return UriUtils.withAllowSourceTrue(originalUri);
            case CUSTOM:
                return buildCustomUri(originalUri, channel, customTemplate);
            case DISABLED:
            default:
                return null;
        }
    }

    private static Uri buildCustomUri(Uri originalUri, String channel, String customTemplate) {
        if (customTemplate == null || customTemplate.trim().isEmpty()) {
            return null;
        }
        String template = customTemplate.trim();
        boolean hasUrlPlaceholder = template.contains("{url}");
        boolean hasChannelPlaceholder = template.contains("{channel}");

        if (!hasUrlPlaceholder && !hasChannelPlaceholder) {
            String forcedUrl = UriUtils.withAllowSourceTrue(originalUri).toString();
            return safeParse(template.endsWith("/") ? template + forcedUrl : template + "/" + forcedUrl);
        }

        String resolved = template;
        if (hasUrlPlaceholder) {
            resolved = resolved.replace("{url}", UriUtils.withAllowSourceTrue(originalUri).toString());
        }
        if (hasChannelPlaceholder) {
            if (channel == null || channel.isEmpty()) {
                return null;
            }
            resolved = resolved.replace("{channel}", channel);
        }
        return safeParse(resolved);
    }

    /**
     * Appends the logged-in user's Twitch OAuth token to a ReYohoho proxy
     * URL as the {@code auth} query parameter, matching the convention the
     * ReYohoho proxy expects ({@code .../usher...?...&auth=<token>}). This
     * lets the proxy make authenticated usher requests on the user's behalf
     * (subscriber/turbo qualities, ad handling, etc.). No-op when the user
     * isn't logged in, so anonymous playback keeps working unchanged.
     *
     * <p>Only ever called for {@link Kind#URL_SUFFIX} (the built-in ReYohoho
     * hosts), never for {@link #CUSTOM} -- we don't leak the token to an
     * arbitrary user-entered proxy.
     */
    private static String withUserAuth(String proxyUrl) {
        String token = TwitchAuth.getAuthToken();
        if (token == null || token.isEmpty()) {
            return proxyUrl;
        }
        String separator = proxyUrl.indexOf('?') >= 0 ? "&" : "?";
        return proxyUrl + separator + "auth=" + Uri.encode(token);
    }

    private static Uri safeParse(String url) {
        try {
            return Uri.parse(url);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
