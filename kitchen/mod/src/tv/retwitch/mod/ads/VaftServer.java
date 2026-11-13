package tv.retwitch.mod.ads;

import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import tv.retwitch.mod.CrashReporter;

/**
 * Local HLS playlist proxy that runs VAFT on the device, the way Thream does.
 *
 * <p>Twitch's live player (IVS) fetches playlists natively, so there is no
 * single Java parse method to hook. Instead {@link tv.retwitch.mod.UsherHook}
 * points the player at {@code http://127.0.0.1:<port>/<secret>/master/<usher URL>}
 * (or {@code master?u=<encoded>} with the low-latency toggle off):
 * <ul>
 *   <li>{@code master}: fetches the real usher master playlist and rewrites
 *       every variant URL to {@code media?u=...} on this server (remembering
 *       each variant's {@code RESOLUTION}/{@code CODECS} and which master it came from);</li>
 *   <li>{@code media}: fetches the real media playlist; if it carries ad
 *       markers, serves a clean backup stream ({@link SsaiSwapEngine}) or, if
 *       none is available, the playlist with ad segments stripped
 *       ({@link SsaiAdBlock}). Segments themselves still go straight to the
 *       CDN -- only playlists pass through here.</li>
 *   <li>{@code vod}: a sub-only VOD's CDN media playlist, when usher refused
 *       the VOD's master and {@link SubVods} built one ({@link #wrapVod}).</li>
 * </ul>
 * Cleartext to {@code 127.0.0.1} is allowed by {@code LocalhostCleartextPatch}.
 *
 * <p>A stream proxy's master goes through here too, even with VAFT off (then
 * passed on as is): when the proxy fails (no answer, an error, no playlist),
 * Twitch's own usher master is served instead -- like kizu's proxy fallback,
 * without which a dead proxy meant a stream that never started. An HTTP proxy
 * (TTV LOL PRO) is the same route: the master is usher's, fetched through it.
 *
 * <p>Security: bound to loopback only, every path carries a random per-process
 * secret, and upstream URLs are limited to Twitch video hosts plus the hosts of
 * masters we wrapped ourselves (a selected stream proxy) and their variants, so
 * other apps on the device can't use it as an open proxy.
 */
public final class VaftServer {

    private static final String TAG = "RTVaft";
    private static final String HOST = "127.0.0.1";
    private static final String[] ALLOWED_HOST_SUFFIXES = {".ttvnw.net", ".live-video.net", ".twitch.tv"};
    private static final int MAX_VARIANTS = 32;

    private static ServerSocket server;
    private static String secret;

    /**
     * Non-Twitch hosts we may fetch: the stream proxy whose master we wrapped
     * and the hosts its master points variants at (proxies may serve those too).
     */
    private static final java.util.Set<String> allowedHosts =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** How a wrapped master is served, by its URL up to the query (IVS appends its own parameters). */
    private static final class Route {
        /** Twitch's usher master, when the master comes from a stream proxy. */
        final Uri fallback;
        /** Route the variants through VAFT; false: pass the master on as is. */
        final boolean vaft;
        /** A VOD's id: when usher refuses it (sub-only), the master is built from the CDN ({@link SubVods}). */
        final String subVodId;
        /** The HTTP proxy the master is fetched through (then {@link #fallback} is the same usher URL, direct), or null. */
        final java.net.Proxy proxy;

        Route(Uri fallback, boolean vaft, String subVodId, java.net.Proxy proxy) {
            this.fallback = fallback;
            this.vaft = vaft;
            this.subVodId = subVodId;
            this.proxy = proxy;
        }
    }

    private static final ConcurrentHashMap<String, Route> routes = new ConcurrentHashMap<>();
    /** Ad markers not recognised, logged once each. */
    private static final java.util.Set<String> reportedMarkers =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** Sequence bookkeeping per master, shared by its variants (see {@link MediaSequencer}). */
    private static final ConcurrentHashMap<String, MediaSequencer> sequencers = new ConcurrentHashMap<>();
    private static final AtomicLong masters = new AtomicLong();

    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "RTVaftWorker");
            t.setDaemon(true);
            return t;
        }
    });

    private VaftServer() {
    }

    /**
     * @param usher    the master to play: Twitch's usher or a stream proxy's
     * @param fallback Twitch's usher master when {@code usher} is a proxy's, else null
     * @param proxy    the HTTP proxy to fetch {@code usher} through (with a fallback), else null
     * @param vaft     filter the ads (else the master is passed on as is, for the fallback alone)
     * @return the master {@code Uri} routed through the local proxy, or
     *         {@code usher} unchanged if the server can't be started
     */
    public static Uri wrapMaster(Uri usher, Uri fallback, java.net.Proxy proxy, boolean vaft) {
        return wrap(usher, new Route(fallback, vaft, null, proxy));
    }

    /** A VOD's usher master through the local proxy, served from the CDN if usher refuses it (sub-only). */
    public static Uri wrapVod(Uri usher, String vodId) {
        return wrap(usher, new Route(null, false, vodId, null));
    }

    private static Uri wrap(Uri usher, Route route) {
        try {
            // We built this URL ourselves (Twitch or the selected stream proxy).
            if (usher.getHost() != null) {
                allowedHosts.add(usher.getHost());
            }
            if (routes.size() > MAX_VARIANTS) {
                routes.clear();
            }
            routes.put(routeKey(usher.toString()), route);
            String base = baseUrl();
            if (tv.retwitch.mod.Toggles.on(tv.retwitch.mod.Toggles.VAFT_LOW_LATENCY)) {
                // Unencoded, so the URL still contains "://usher.ttvnw.net": IVS
                // (TwitchLink::isUsherUrl) then plays it as a Twitch channel --
                // low latency with prefetch segments, its own usher parameters
                // appended to our query (which is usher's). Encoded, IVS falls
                // back to plain HLS, several seconds behind.
                return Uri.parse(base + "master/" + usher);
            }
            return Uri.parse(base + "master?u=" + Uri.encode(usher.toString()));
        } catch (Throwable t) {
            Log.w(TAG, "local server unavailable; playing direct", t);
            CrashReporter.report(TAG, "local server unavailable; playing direct", t);
            return usher;
        }
    }

    private static synchronized String baseUrl() throws IOException {
        if (server == null || server.isClosed()) {
            final ServerSocket socket = new ServerSocket(0, 16, InetAddress.getByName(HOST));
            byte[] random = new byte[16];
            new SecureRandom().nextBytes(random);
            StringBuilder hex = new StringBuilder();
            for (byte b : random) {
                hex.append(String.format("%02x", b));
            }
            secret = hex.toString();
            server = socket;
            Thread acceptor = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop(socket);
                }
            }, "RTVaftAccept");
            acceptor.setDaemon(true);
            acceptor.start();
            Log.i(TAG, "local playlist proxy on " + HOST + ":" + socket.getLocalPort());
        }
        return "http://" + HOST + ":" + server.getLocalPort() + "/" + secret + "/";
    }

    private static void acceptLoop(ServerSocket socket) {
        try {
            while (!socket.isClosed()) {
                final Socket client = socket.accept();
                WORKERS.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(client);
                    }
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "accept loop ended", t);
            CrashReporter.report(TAG, "accept loop ended", t);
        } finally {
            synchronized (VaftServer.class) {
                if (server == socket) {
                    server = null;
                }
            }
        }
    }

    private static void handle(Socket client) {
        boolean head = false;
        try {
            client.setSoTimeout(15_000);
            InputStream in = client.getInputStream();
            String requestLine = readLine(in);
            String header;
            do {
                header = readLine(in);
            } while (header != null && !header.isEmpty());

            String[] parts = requestLine != null ? requestLine.split(" ") : new String[0];
            head = parts.length > 0 && "HEAD".equals(parts[0]);
            if (parts.length < 2) {
                respond(client, 400, "text/plain", "bad request", head);
                return;
            }
            Uri request = Uri.parse("http://" + HOST + parts[1]);
            List<String> path = request.getPathSegments();
            String kind = path.size() == 2 && path.get(0).equals(secret) ? path.get(1) : null;
            String target = request.getQueryParameter("u");
            String inPath = masterTargetInPath(parts[1], secret);
            if (inPath != null) {
                kind = "master";
                target = inPath;
            }
            if (kind == null || !isAllowed(target)) {
                respond(client, 404, "text/plain", "not found", head);
                return;
            }

            Route route = "master".equals(kind) ? routes.get(routeKey(target)) : null;
            long started = System.currentTimeMillis();
            Upstream upstream = "master".equals(kind) ? fetchMaster(target, route) : fetch(target);
            String body = upstream.body;
            int code = upstream.code;
            if (code != 200) {
                VaftLog.log(kind + " " + VaftLog.describe(target) + ": HTTP " + code + " passed on");
                // Pass errors (offline channel, expired token...) through so the
                // player reacts exactly as it would to Twitch directly.
                respond(client, code, "application/vnd.apple.mpegurl", body, head);
                return;
            }

            String out;
            if ("master".equals(kind)) {
                out = route != null && !route.vaft ? body : rewriteMaster(body, "media");
                VaftLog.log("master " + VaftLog.describe(target) + " (" + (System.currentTimeMillis() - started) + " ms)");
            } else if ("media".equals(kind)) {
                out = processMedia(request.getQueryParameter("s"), target, body,
                        request.getQueryParameter("res"), request.getQueryParameter("c"));
                if (System.currentTimeMillis() - started > 2000) {
                    VaftLog.log("slow media poll: " + (System.currentTimeMillis() - started) + " ms");
                }
            } else if ("vod".equals(kind)) {
                out = SubVods.fixMedia(target, body);
            } else {
                respond(client, 404, "text/plain", "not found", head);
                return;
            }
            respond(client, 200, "application/vnd.apple.mpegurl", out, head);
        } catch (Throwable t) {
            Log.w(TAG, "request failed", t);
            if (!(t instanceof IOException)) { // the network (player gone, timeouts, upstream down) isn't a bug: 82% of all reports
                CrashReporter.report(TAG, "request failed", t);
            }
            try {
                respond(client, 502, "text/plain", "upstream error", head);
            } catch (Throwable ignored) {
                // client already gone
            }
        } finally {
            try {
                client.close();
            } catch (Throwable ignored) {
                // best-effort
            }
        }
    }

    private static String routeKey(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    private static final class Upstream {
        final int code;
        final String body;

        Upstream(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private static Upstream fetch(String url) throws IOException {
        return fetch(url, null);
    }

    private static Upstream fetch(String url, java.net.Proxy proxy) throws IOException {
        okhttp3.OkHttpClient client = proxy != null
                ? SsaiSwapEngine.client().newBuilder().proxy(proxy).build() : SsaiSwapEngine.client();
        Response upstream = client.newCall(new Request.Builder().url(url).build()).execute();
        try {
            ResponseBody rb = upstream.body();
            return new Upstream(upstream.code(), rb != null ? rb.string() : "");
        } finally {
            upstream.close();
        }
    }

    /**
     * The master, retried with an anonymous token when usher refuses the account's (403: the channel bans
     * the account from viewing, which Twitch applies to logged-in viewers only).
     */
    private static Upstream fetchMaster(String target, Route route) throws IOException {
        Upstream master = fetchMaster0(target, route);
        // Not VODs: the anonymous master is the last live channel's.
        if (master.code != 403 || target.contains("/vod/")
                || !tv.retwitch.mod.Toggles.on(tv.retwitch.mod.Toggles.BAN_BYPASS)) {
            return master;
        }
        String anonymous = SsaiSwapEngine.anonymousMaster();
        Upstream retried = anonymous != null ? fetch(anonymous) : null;
        VaftLog.log("master " + VaftLog.describe(target) + ": HTTP 403, anonymous "
                + (retried != null ? "HTTP " + retried.code : "token unavailable"));
        if (retried == null || retried.code != 200) {
            return master;
        }
        allowedHosts.add(Uri.parse(anonymous).getHost());
        return retried;
    }

    /** The master from {@code target}, or Twitch's own when the stream proxy fails. */
    private static Upstream fetchMaster0(String target, Route route) throws IOException {
        if (route != null && route.subVodId != null) {
            Upstream usher = fetch(target);
            String built = usher.code == 200 ? null : SubVods.master(route.subVodId);
            if (built == null) {
                return usher;
            }
            VaftLog.log("vod " + route.subVodId + ": usher HTTP " + usher.code + ", master from the CDN");
            return new Upstream(200, rewriteMaster(built, "vod"));
        }
        if (route == null || route.fallback == null) {
            return fetch(target);
        }
        long started = System.currentTimeMillis();
        String failure;
        try {
            Upstream proxied = fetch(target, route.proxy);
            if (proxied.code == 200 && proxied.body.trim().startsWith("#EXTM3U")) {
                VaftLog.log("master via proxy " + (route.proxy != null ? route.proxy.address() + " " : "")
                        + VaftLog.describe(target) + " (" + (System.currentTimeMillis() - started) + " ms)");
                return proxied;
            }
            failure = proxied.code == 200 ? "no playlist" : "HTTP " + proxied.code;
        } catch (IOException e) {
            failure = e.getClass().getSimpleName();
        }
        Log.w(TAG, "stream proxy failed (" + failure + "); playing from Twitch");
        VaftLog.log("proxy " + (route.proxy != null ? route.proxy.address() + " " : "")
                + VaftLog.describe(target) + " failed: " + failure + " after "
                + (System.currentTimeMillis() - started) + " ms; Twitch's master instead");
        notifyProxyFailed();
        return fetch(route.fallback.toString());
    }

    private static void notifyProxyFailed() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    android.content.Context context = tv.retwitch.mod.AppContext.get();
                    if (context != null) {
                        android.widget.Toast.makeText(context, tv.retwitch.mod.Strings.proxyFailedToast(),
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "proxy toast failed", t);
                    CrashReporter.report(TAG, "proxy toast failed", t);
                }
            }
        });
    }

    /** Upstream URL of wrapMaster's low-latency form {@code /<secret>/master/<url>}, else null. */
    static String masterTargetInPath(String requestTarget, String secret) {
        String prefix = "/" + secret + "/master/";
        if (!requestTarget.startsWith(prefix)) {
            return null;
        }
        String target = requestTarget.substring(prefix.length());
        // In case a client collapses the "//" inside our path.
        return target.startsWith("https:/") && !target.startsWith("https://")
                ? "https://" + target.substring("https:/".length()) : target;
    }

    private static boolean isAllowed(String url) {
        if (url == null || !url.startsWith("https://")) {
            return false;
        }
        String host = Uri.parse(url).getHost();
        if (host == null) {
            return false;
        }
        if (allowedHosts.contains(host)) {
            return true;
        }
        for (String suffix : ALLOWED_HOST_SUFFIXES) {
            if (host.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** Routes every variant of a master playlist through {@code kind} ({@code media} or {@code vod}) here. */
    static String rewriteMaster(String master, String kind) throws IOException {
        String base = baseUrl();
        long stream = masters.incrementAndGet();
        String[] lines = master.split("\n", -1);
        StringBuilder out = new StringBuilder(master.length() * 2);
        String resolution = null;
        String codecs = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                resolution = SsaiSwapEngine.attribute(line, "RESOLUTION");
                codecs = SsaiSwapEngine.attribute(line, "CODECS");
                out.append(raw).append('\n');
            } else if ((line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:")
                    || line.startsWith("#EXT-X-RENDITION-REPORT:")) && line.contains("URI=\"https://")) {
                // Alternate renditions are playlists too (Thream routes them as well).
                int start = line.indexOf("URI=\"") + 5;
                int end = line.indexOf('"', start);
                String renditionHost = Uri.parse(line.substring(start, end)).getHost();
                if (renditionHost != null) {
                    allowedHosts.add(renditionHost);
                }
                out.append(line, 0, start)
                        .append(base).append(kind).append("?u=").append(Uri.encode(line.substring(start, end)))
                        .append("&s=").append(stream).append(line.substring(end)).append('\n');
            } else if (!line.isEmpty() && !line.startsWith("#") && line.startsWith("https://")) {
                String variantHost = Uri.parse(line).getHost();
                if (variantHost != null) {
                    allowedHosts.add(variantHost);
                }
                out.append(base).append(kind).append("?u=").append(Uri.encode(line)).append("&s=").append(stream);
                if (resolution != null) {
                    out.append("&res=").append(Uri.encode(resolution));
                }
                if (codecs != null) {
                    out.append("&c=").append(Uri.encode(codecs));
                }
                out.append('\n');
                resolution = null;
                codecs = null;
            } else {
                out.append(raw).append('\n');
            }
        }
        out.setLength(Math.max(0, out.length() - 1));
        return out.toString();
    }

    /** The VAFT decision for one media-playlist poll. */
    static String processMedia(String stream, String variantUrl, String text, String resolution, String codecs) {
        if (sequencers.size() > MAX_VARIANTS) {
            sequencers.clear();
        }
        // Two variants of a new master can arrive at once.
        MediaSequencer sequencer = sequencers.computeIfAbsent(stream, s -> new MediaSequencer());
        String out;
        String decision;
        if (!SsaiAdBlock.isAdBreak(text)) {
            SsaiSwapEngine.onCleanPrimary();
            AdBreakLabel.hide(variantUrl);
            reportUnknownMarkers(text);
            out = sequencer.rewrite(variantUrl, "primary", text);
            decision = SsaiAdBlock.hasAdTags(text) ? "clean (ad markers only)" : "clean";
        } else {
            String backup = SsaiSwapEngine.fetchCleanBackup(text, resolution, codecs);
            if (backup != null) {
                Log.i(TAG, "ad break -> clean backup stream");
                out = sequencer.rewrite(variantUrl, "backup " + SsaiSwapEngine.activeBackupUrl(), backup);
                AdBreakLabel.show(variantUrl, SsaiSwapEngine.activeBackupQuality());
                decision = "ad -> backup " + SsaiSwapEngine.activeBackupQuality();
            } else {
                out = sequencer.rewrite(variantUrl, "primary", text);
                Log.i(TAG, out == null ? "ad break -> all segments stripped" : "ad break -> stripped ad segments");
                AdBreakLabel.show(variantUrl, null);
                decision = out == null ? "ad -> all stripped" : "ad -> stripped";
            }
        }
        if (VaftLog.isOn()) {
            VaftLog.log("media " + resolution + " " + decision + ": in " + summary(text) + " -> out "
                    + (out == null ? "held last" : summary(out)));
        }
        if (out == null) {
            // Nothing new to serve (all ads, or a source still behind what was
            // played): re-serve the last playlist so the player waits on known content.
            out = sequencer.last(variantUrl);
        }
        return out != null ? out : SsaiAdBlock.stripAdSegments(text);
    }

    /** Ad markers that match nothing known: Twitch may have changed them. Logged once each. */
    private static void reportUnknownMarkers(String text) {
        for (String marker : SsaiAdBlock.unknownAdMarkers(text)) {
            if (reportedMarkers.size() < 64 && reportedMarkers.add(marker)) {
                Log.w(TAG, "unrecognised ad marker in a playlist: " + marker);
                VaftLog.log("unrecognised ad marker: " + marker);
            }
        }
    }

    /** "seq 1234 +15, 2 disc, 2 hints" (first number, segments...) for the log. */
    private static String summary(String playlist) {
        long first = -1;
        int segments = 0;
        int discontinuities = 0;
        int hints = 0;
        for (String line : playlist.split("\n")) {
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                first = MediaSequencer.parseLong(line.substring(line.indexOf(':') + 1));
            } else if (line.startsWith("#EXTINF")) {
                segments++;
            } else if (line.startsWith("#EXT-X-DISCONTINUITY") && !line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")) {
                discontinuities++;
            } else if (line.startsWith("#EXT-X-TWITCH-PREFETCH:")) {
                hints++;
            }
        }
        return "seq " + first + " +" + segments + (discontinuities > 0 ? ", " + discontinuities + " disc" : "")
                + (hints > 0 ? ", " + hints + " hints" : "");
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buf.write(c);
            }
            if (buf.size() > 16 * 1024) {
                throw new IOException("request line too long");
            }
        }
        if (c == -1 && buf.size() == 0) {
            return null;
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void respond(Socket client, int code, String type, String body, boolean head)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "Error") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = client.getOutputStream();
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        if (!head) {
            out.write(bytes);
        }
        out.flush();
    }
}
