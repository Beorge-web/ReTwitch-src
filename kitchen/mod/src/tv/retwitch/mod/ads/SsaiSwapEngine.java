package tv.retwitch.mod.ads;

import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import tv.retwitch.mod.CrashReporter;

/**
 * VAFT-style <b>backup-stream swap</b> for ad breaks.
 *
 * <p>The same channel requested with a different {@code playerType} often
 * comes back without the stitched ad, because Twitch decides ads per
 * player-type/session. This is a Java port of VAFT's backup search
 * (TwitchAdSolutions {@code vaft.user.js}: {@code getAccessToken} / the
 * backup-search loop / {@code getStreamUrlForResolution}).
 *
 * <h3>Flow (per ad-laden media-playlist poll, see {@link VaftServer})</h3>
 * <ol>
 *   <li>If a clean backup variant was already found during this break, just
 *       re-fetch it (one GET).</li>
 *   <li>Otherwise, for each candidate {@code playerType}: anonymous
 *       {@code PlaybackAccessToken} from GQL (web Client-ID, like VAFT and
 *       Thream -- the Android OAuth token doesn't match that Client-ID), usher
 *       master built from the token + the real usher query captured off
 *       Twitch's own request ({@link #rememberLiveUsher}), the variant closest
 *       to the primary's resolution and codec family, and its media playlist.
 *       The first one without ad markers wins.</li>
 * </ol>
 * The search runs on a background thread with a short foreground budget so a
 * slow search never stalls the player's playlist poll; a search that finishes
 * late is picked up by the next poll.
 */
public final class SsaiSwapEngine {

    private static final String TAG = "RTSsaiSwap";

    private static final String GQL_URL = "https://gql.twitch.tv/gql";
    /** Public Twitch web Client-ID (matches the persisted query hash below). */
    private static final String CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko";
    /** sha256 of the web {@code PlaybackAccessToken} persisted query (from VAFT). */
    private static final String PAT_HASH =
            "ed230aa1e33e07eebb8928504583da78a5173989fadfb1ac94be06a04f3cdbe9";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /**
     * Candidate player types, in order. {@code mobile_feed} first: sabopura
     * measured it ad-free, at full quality, in all of 254 sessions with a
     * stitched ad (2026-10-14; embed and popout almost never were). Then the
     * other Source-quality types; VAFT's 2026 field data shows they are
     * usually ad-laden too, so {@code autoplay} (360p, but nearly always
     * clean) is the last resort.
     */
    // Full-quality mobile_feed first, then the Android sessions that came clean in lurk's and VAFT's 2026 tests
    // (picture-by-picture, autoplay; both <=360p); popout/site/embed almost always carry the ads too.
    private static final String[] BACKUP_PLAYER_TYPES = {
            "mobile_feed", "picture-by-picture", "autoplay", "popout", "site", "embed",
    };

    /** How long the player's playlist request may wait for the search. */
    private static final long FOREGROUND_BUDGET_MS = 2500L;
    /** How long a fetched encodings (master) playlist stays reusable. */
    private static final long ENCODINGS_TTL_MS = 20_000L;

    private static volatile OkHttpClient httpClient;

    // Captured off Twitch's own live usher request (see rememberLiveUsher).
    private static volatile String channelLogin;
    private static volatile String usherQuery; // raw query string, no leading '?'

    private static volatile String deviceId;
    /** Last player type that yielded a clean backup -- tried first next time. */
    private static volatile String pinnedPlayerType;
    /** Clean backup media-playlist URL found for the current ad break. */
    private static volatile String activeBackupUrl;
    /** {@code RESOLUTION} of that backup variant, or null (audio only). */
    private static volatile String activeBackupResolution;
    private static Future<String> runningSearch;

    private static final ExecutorService SEARCH = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "RTVaftSearch");
            t.setDaemon(true);
            return t;
        }
    });

    private static final ConcurrentHashMap<String, CachedEncodings> encodingsCache =
            new ConcurrentHashMap<>();

    private SsaiSwapEngine() {
    }

    private static final class CachedEncodings {
        final String text;
        final long fetchedAt;

        CachedEncodings(String text, long fetchedAt) {
            this.text = text;
            this.fetchedAt = fetchedAt;
        }
    }

    /**
     * Remembers the channel + query string from Twitch's own live usher request
     * so a backup can be requested with the exact same parameters. Called from
     * {@link tv.retwitch.mod.UsherHook} for live streams only.
     */
    public static void rememberLiveUsher(Uri uri) {
        try {
            if (uri == null) {
                return;
            }
            String path = uri.getPath();
            if (path == null) {
                return;
            }
            // .../channel/hls/<login>.m3u8
            int hls = path.indexOf("/hls/");
            int m3u8 = path.indexOf(".m3u8");
            if (hls < 0 || m3u8 < 0 || m3u8 <= hls + 5) {
                return;
            }
            String login = path.substring(hls + 5, m3u8).toLowerCase();
            if (login.isEmpty()) {
                return;
            }
            if (!login.equals(channelLogin)) {
                encodingsCache.clear();
                activeBackupUrl = null;
            }
            channelLogin = login;
            usherQuery = uri.getEncodedQuery();
            Log.d(TAG, "captured live usher for channel=" + login);
        } catch (Throwable t) {
            Log.w(TAG, "rememberLiveUsher failed", t);
            CrashReporter.report(TAG, "rememberLiveUsher failed", t);
        }
    }

    /** The backup variant currently served, as the source id for {@link MediaSequencer}. */
    static String activeBackupUrl() {
        return activeBackupUrl;
    }

    /** Height of the backup served, as {@code 360p}, or null. */
    static String activeBackupQuality() {
        String res = activeBackupResolution;
        int x = res == null ? -1 : res.indexOf('x');
        return x < 0 ? null : res.substring(x + 1) + "p";
    }

    /** The primary playlist is clean again: the ad break is over. */
    static void onCleanPrimary() {
        activeBackupUrl = null;
    }

    /**
     * Attempts to obtain a clean, ad-free backup media playlist for the current
     * ad break.
     *
     * @param primaryText the current (ad-laden) primary media playlist
     * @param resolution  the primary variant's {@code RESOLUTION}, or null
     * @param codecs      the primary variant's {@code CODECS}, or null
     * @return clean backup media-playlist text, or {@code null} if none is
     *         available yet (caller should fall back to stripping)
     */
    static String fetchCleanBackup(String primaryText, final String resolution, final String codecs) {
        if (channelLogin == null || usherQuery == null) {
            return null;
        }
        try {
            String serverTime = getServerTime(primaryText);
            String active = activeBackupUrl;
            if (active != null) {
                String media = httpGet(active);
                if (media != null && !SsaiAdBlock.isAdBreak(media)) {
                    return replaceServerTime(media, serverTime);
                }
                activeBackupUrl = null;
            }

            Future<String> search;
            synchronized (SsaiSwapEngine.class) {
                if (runningSearch == null || runningSearch.isDone()) {
                    runningSearch = SEARCH.submit(new java.util.concurrent.Callable<String>() {
                        @Override
                        public String call() {
                            return searchCleanVariant(resolution, codecs);
                        }
                    });
                }
                search = runningSearch;
            }
            String url;
            try {
                url = search.get(FOREGROUND_BUDGET_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                Log.d(TAG, "backup search still running; stripping this poll");
                VaftLog.log("backup search still running after " + FOREGROUND_BUDGET_MS + " ms");
                return null;
            }
            if (url == null) {
                return null;
            }
            String media = httpGet(url);
            if (media == null || SsaiAdBlock.isAdBreak(media)) {
                VaftLog.log("backup " + VaftLog.describe(url) + (media == null ? " failed" : " has an ad now"));
                activeBackupUrl = null;
                return null;
            }
            return replaceServerTime(media, serverTime);
        } catch (Throwable t) {
            Log.w(TAG, "fetchCleanBackup failed", t);
            CrashReporter.report(TAG, "fetchCleanBackup failed", t);
            return null;
        }
    }

    /** Background search; returns (and pins) a clean variant URL, or null. */
    private static String searchCleanVariant(String resolution, String codecs) {
        String login = channelLogin;
        if (login == null) {
            return null;
        }
        String pin = pinnedPlayerType;
        if (pin != null) {
            String url = tryPlayerType(pin, login, resolution, codecs);
            if (url != null) {
                activeBackupUrl = url;
                return url;
            }
        }
        // The primary is already requested as this type (VaftPlayerType).
        String primaryType = tv.retwitch.mod.AdSettings.getInitialPlayerType();
        for (String playerType : BACKUP_PLAYER_TYPES) {
            if (playerType.equals(pin) || playerType.equals(primaryType)) {
                continue;
            }
            String url = tryPlayerType(playerType, login, resolution, codecs);
            if (url != null) {
                pinnedPlayerType = playerType;
                activeBackupUrl = url;
                return url;
            }
        }
        Log.i(TAG, "no ad-free backup across " + BACKUP_PLAYER_TYPES.length + " player types");
        VaftLog.log("search: no ad-free backup across " + BACKUP_PLAYER_TYPES.length + " player types");
        return null;
    }

    /** One player-type attempt: token -> usher -> variant -> clean check. */
    private static String tryPlayerType(String playerType, String login, String resolution, String codecs) {
        long started = System.currentTimeMillis();
        try {
            String encodings = getEncodings(playerType, login);
            if (encodings == null) {
                return null;
            }
            String variantUrl = pickVariant(encodings, resolution, codecs);
            if (variantUrl == null) {
                return null;
            }
            String media = httpGet(variantUrl);
            if (media == null || media.isEmpty()) {
                return null;
            }
            if (SsaiAdBlock.isAdBreak(media)) {
                Log.d(TAG, "backup(" + playerType + ") also ad-laden");
                VaftLog.log("search: " + playerType + " also has an ad (" + (System.currentTimeMillis() - started) + " ms)");
                return null;
            }
            activeBackupResolution = attribute(variantInfo(encodings, variantUrl), "RESOLUTION");
            Log.i(TAG, "clean backup via playerType=" + playerType);
            VaftLog.log("search: " + playerType + " clean, " + activeBackupResolution + " for wanted " + resolution
                    + " (" + (System.currentTimeMillis() - started) + " ms)");
            return variantUrl;
        } catch (Throwable t) {
            Log.d(TAG, "tryPlayerType(" + playerType + ") failed: " + t);
            VaftLog.log("search: " + playerType + " failed: " + t.getClass().getSimpleName());
            return null;
        }
    }

    /** Fetches (and briefly caches) the encodings master playlist for a player type. */
    private static String getEncodings(String playerType, String login) throws Exception {
        CachedEncodings cached = encodingsCache.get(playerType);
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.fetchedAt) < ENCODINGS_TTL_MS) {
            return cached.text;
        }
        JSONObject token = getAccessToken(login, playerType);
        if (token == null) {
            return null;
        }
        String value = token.optString("value", "");
        String signature = token.optString("signature", "");
        if (value.isEmpty() || signature.isEmpty()) {
            return null;
        }
        String encodings = httpGet(buildUsherUrl(login, value, signature));
        if (encodings == null || encodings.isEmpty()) {
            return null;
        }
        encodingsCache.put(playerType, new CachedEncodings(encodings, now));
        return encodings;
    }

    /**
     * Requests an anonymous {@code PlaybackAccessToken} for {@code login} as
     * {@code playerType} and returns the {@code streamPlaybackAccessToken}
     * object ({@code value} + {@code signature}), or {@code null}.
     */
    private static JSONObject getAccessToken(String login, String playerType) throws Exception {
        String platform = "autoplay".equals(playerType) || "mobile_feed".equals(playerType)
                || "picture-by-picture".equals(playerType) ? "android" : "web";
        JSONObject variables = new JSONObject()
                .put("isLive", true)
                .put("login", login)
                .put("isVod", false)
                .put("vodID", "")
                .put("playerType", playerType)
                .put("platform", platform);
        JSONObject persisted = new JSONObject()
                .put("version", 1)
                .put("sha256Hash", PAT_HASH);
        JSONObject body = new JSONObject()
                .put("operationName", "PlaybackAccessToken")
                .put("variables", variables)
                .put("extensions", new JSONObject().put("persistedQuery", persisted));

        Request request = new Request.Builder()
                .url(GQL_URL)
                .header("Client-ID", CLIENT_ID)
                .header("X-Device-Id", deviceId())
                .post(RequestBody.create(JSON, body.toString()))
                .build();
        String json = executeToString(request);
        if (json == null) {
            return null;
        }
        JSONObject root = new JSONObject(json);
        JSONObject data = root.optJSONObject("data");
        return data != null
                ? data.optJSONObject("streamPlaybackAccessToken")
                : root.optJSONObject("streamPlaybackAccessToken");
    }

    /** The current channel's usher master with an anonymous web token, or null. Network: not on the main thread. */
    static String anonymousMaster() {
        String login = channelLogin;
        if (login == null || usherQuery == null) {
            return null;
        }
        try {
            JSONObject token = getAccessToken(login, "site");
            if (token == null || token.optString("value").isEmpty()) {
                return null;
            }
            return buildUsherUrl(login, token.optString("value"), token.optString("signature"));
        } catch (Exception e) {
            Log.w(TAG, "anonymous token failed", e);
            return null;
        }
    }

    /**
     * {@code usher} (a live master without a token) with an anonymous web token for its channel, or null.
     * For the proxy health check: usher refuses a request without one. Network: not on the main thread.
     */
    public static Uri withAnonymousToken(Uri usher) {
        try {
            JSONObject token = getAccessToken(usher.getLastPathSegment().replace(".m3u8", ""), "site");
            if (token == null || token.optString("value").isEmpty()) {
                return null;
            }
            return usher.buildUpon().appendQueryParameter("token", token.optString("value"))
                    .appendQueryParameter("sig", token.optString("signature")).build();
        } catch (Exception e) {
            Log.w(TAG, "anonymous token failed", e);
            return null;
        }
    }

    /** Builds the usher master URL from the captured params, overriding token+sig. */
    private static String buildUsherUrl(String login, String token, String signature) {
        Uri base = Uri.parse("https://usher.ttvnw.net/api/channel/hls/" + login + ".m3u8"
                + (usherQuery != null && !usherQuery.isEmpty() ? "?" + usherQuery : ""));
        Uri.Builder b = base.buildUpon().clearQuery();
        for (String name : base.getQueryParameterNames()) {
            // Not the primary's play_session_id either (sabopura drops it too): a backup is a session of its own.
            if (!"token".equals(name) && !"sig".equals(name) && !"play_session_id".equals(name)) {
                b.appendQueryParameter(name, base.getQueryParameter(name));
            }
        }
        b.appendQueryParameter("token", token);
        b.appendQueryParameter("sig", signature);
        return b.build().toString();
    }

    /**
     * Picks the backup variant closest to the primary: same codec family
     * first (an HEVC/AV1 decoder can't take AVC segments mid-stream and vice
     * versa), then exact resolution, else the best one not taller than it,
     * else simply the best one.
     */
    static String pickVariant(String encodings, String resolution, String codecs) {
        String family = codecFamily(codecs);
        int targetHeight = height(resolution);
        String[] lines = encodings.split("\n", -1);
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        long bestBandwidth = -1L;
        for (int i = 0; i < lines.length - 1; i++) {
            String line = lines[i];
            if (!line.startsWith("#EXT-X-STREAM-INF")) {
                continue;
            }
            String url = lines[i + 1].trim();
            if (url.isEmpty() || url.startsWith("#")) {
                continue;
            }
            String res = attribute(line, "RESOLUTION");
            int h = height(res);
            int score = 0;
            if (family == null || family.equals(codecFamily(attribute(line, "CODECS")))) {
                score += 4;
            }
            if (resolution != null && resolution.equals(res)) {
                score += 2;
            } else if (targetHeight <= 0 || h <= targetHeight) {
                score += 1;
            }
            long bandwidth = parseLong(attribute(line, "BANDWIDTH"));
            if (score > bestScore || (score == bestScore && bandwidth > bestBandwidth)) {
                best = url;
                bestScore = score;
                bestBandwidth = bandwidth;
            }
        }
        return best;
    }

    /** The {@code #EXT-X-STREAM-INF} line of {@code variantUrl} in a master playlist, or "". */
    static String variantInfo(String encodings, String variantUrl) {
        String[] lines = encodings.split("\n", -1);
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().equals(variantUrl) && lines[i - 1].startsWith("#EXT-X-STREAM-INF")) {
                return lines[i - 1];
            }
        }
        return "";
    }

    /** {@code avc}, {@code hevc}, {@code av1} or null for a CODECS value. */
    static String codecFamily(String codecs) {
        if (codecs == null) {
            return null;
        }
        String c = codecs.toLowerCase();
        if (c.contains("hev1") || c.contains("hvc1")) {
            return "hevc";
        }
        if (c.contains("av01")) {
            return "av1";
        }
        if (c.contains("avc1")) {
            return "avc";
        }
        return null;
    }

    /** Value of {@code NAME=...} (quoted or not) on an attribute-list line, or null. */
    static String attribute(String line, String name) {
        int idx = line.indexOf(name + "=");
        while (idx > 0 && line.charAt(idx - 1) != ',' && line.charAt(idx - 1) != ':') {
            idx = line.indexOf(name + "=", idx + 1);
        }
        if (idx < 0) {
            return null;
        }
        int start = idx + name.length() + 1;
        if (start < line.length() && line.charAt(start) == '"') {
            int end = line.indexOf('"', start + 1);
            return end > start ? line.substring(start + 1, end) : null;
        }
        int end = line.indexOf(',', start);
        return line.substring(start, end < 0 ? line.length() : end).trim();
    }

    private static int height(String resolution) {
        if (resolution == null) {
            return -1;
        }
        int x = resolution.indexOf('x');
        return x < 0 ? -1 : (int) parseLong(resolution.substring(x + 1));
    }

    private static long parseLong(String s) {
        if (s == null) {
            return 0L;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Reads Twitch's {@code SERVER-TIME="..."} out of a playlist, or {@code null}. */
    static String getServerTime(String m3u8) {
        if (m3u8 == null) {
            return null;
        }
        int idx = m3u8.indexOf("SERVER-TIME=\"");
        if (idx < 0) {
            return null;
        }
        int start = idx + "SERVER-TIME=\"".length();
        int end = m3u8.indexOf('"', start);
        return end > start ? m3u8.substring(start, end) : null;
    }

    /** Splices {@code newServerTime} into a playlist's {@code SERVER-TIME} attribute. */
    static String replaceServerTime(String m3u8, String newServerTime) {
        if (newServerTime == null || m3u8 == null) {
            return m3u8;
        }
        int idx = m3u8.indexOf("SERVER-TIME=\"");
        if (idx < 0) {
            return m3u8;
        }
        int start = idx + "SERVER-TIME=\"".length();
        int end = m3u8.indexOf('"', start);
        if (end <= start) {
            return m3u8;
        }
        return m3u8.substring(0, start) + newServerTime + m3u8.substring(end);
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    static String httpGet(String url) throws Exception {
        return executeToString(new Request.Builder().url(url).build());
    }

    private static String executeToString(Request request) throws Exception {
        Response response = client().newCall(request).execute();
        try {
            ResponseBody rb = response.body();
            String text = rb != null ? rb.string() : "";
            if (!response.isSuccessful()) {
                Log.d(TAG, "HTTP " + response.code() + " for " + request.url().host());
                return null;
            }
            return text;
        } finally {
            response.close();
        }
    }

    static OkHttpClient client() {
        OkHttpClient existing = httpClient;
        if (existing != null) {
            return existing;
        }
        synchronized (SsaiSwapEngine.class) {
            if (httpClient == null) {
                httpClient = new OkHttpClient.Builder()
                        .connectTimeout(3, TimeUnit.SECONDS)
                        .readTimeout(4, TimeUnit.SECONDS)
                        .callTimeout(6, TimeUnit.SECONDS)
                        .retryOnConnectionFailure(true)
                        .build();
            }
            return httpClient;
        }
    }

    private static String deviceId() {
        String existing = deviceId;
        if (existing != null) {
            return existing;
        }
        StringBuilder sb = new StringBuilder(32);
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < 32; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        deviceId = sb.toString();
        return deviceId;
    }
}
