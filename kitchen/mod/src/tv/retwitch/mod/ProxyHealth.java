package tv.retwitch.mod;

import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;

import tv.retwitch.mod.ads.SsaiSwapEngine;

/**
 * Real end-to-end health check for the proxy picker, replacing the old
 * TCP-handshake "ping" (see the deleted {@code PingUtils}). A plain TCP
 * connect to :443 turned out to be misleading in both directions:
 * <ul>
 *   <li><b>False "alive":</b> the PerfProd hosts all sit behind the same
 *       Cloudflare edge, so the TCP handshake succeeds in ~35 ms even when
 *       the region's origin is dead (request then hangs) or the region
 *       replies {@code 403 Forbidden} to every playlist request.</li>
 *   <li><b>False "dead":</b> the ReYohoho hosts answer {@code 404} on
 *       {@code /} yet serve playlists perfectly through their passthrough
 *       route, so probing the bare host looks like a failure.</li>
 * </ul>
 * The only reliable signal is actually requesting a playlist in each
 * backend's own URL shape and confirming an {@code #EXTM3U} manifest comes
 * back. We probe a fixed, always-live reference channel (see
 * {@code SettingsHook.HEALTH_CHECK_CHANNEL}) so the check works even when the
 * user isn't currently watching anything.
 *
 * <p>Uses {@link HttpURLConnection} (not our vendored OkHttp, which lives in
 * a different package) to stay self-contained, and reads only the first few
 * bytes of the response before disconnecting -- enough to see the HLS magic
 * without downloading the whole master playlist.
 */
final class ProxyHealth {

    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final String HLS_MAGIC = "#EXTM3U";
    private static final int HEAD_BYTES = 16;

    private ProxyHealth() {
    }

    /**
     * Outcome of a single probe.
     *
     * @see #ok
     * @see #httpCode
     */
    static final class Result {
        /** {@code true} iff we got HTTP 200 with an {@code #EXTM3U} body. */
        final boolean ok;
        /** HTTP status we saw, or {@code -1} if no response arrived at all (connect/read timeout, DNS, TLS...). */
        final int httpCode;

        private Result(boolean ok, int httpCode) {
            this.ok = ok;
            this.httpCode = httpCode;
        }

        static Result ok() {
            return new Result(true, 200);
        }

        static Result http(int code) {
            return new Result(false, code);
        }

        static Result unreachable() {
            return new Result(false, -1);
        }
    }

    /**
     * GETs {@code probeUri} and classifies the result. Never throws.
     *
     * @param probeUri the fully-built proxy request for the reference
     *                 channel, or {@code null} (e.g. a custom backend with no
     *                 URL saved yet)
     * @param proxy    the HTTP proxy to send it through ({@link ProxyBackend#httpProxy()}): then
     *                 {@code probeUri} is usher itself and gets an anonymous token first, as
     *                 playback carries Twitch's own
     * @return the {@link Result}, or {@code null} when {@code probeUri} is
     *         {@code null} (nothing to check)
     */
    static Result check(Uri probeUri, Proxy proxy) {
        if (probeUri == null) {
            return null;
        }
        HttpURLConnection conn = null;
        try {
            Uri target = proxy != null ? SsaiSwapEngine.withAnonymousToken(probeUri) : probeUri;
            if (target == null) {
                return Result.unreachable();
            }
            URL url = new URL(target.toString());
            conn = (HttpURLConnection) (proxy != null ? url.openConnection(proxy) : url.openConnection());
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/vnd.apple.mpegurl, */*");

            int code = conn.getResponseCode();
            if (code != 200) {
                return Result.http(code);
            }
            String head;
            try (InputStream in = conn.getInputStream()) {
                head = readHead(in);
            }
            return head.startsWith(HLS_MAGIC) ? Result.ok() : Result.http(200);
        } catch (Throwable t) {
            // Connect/read timeout (Cloudflare accepts the TCP handshake but
            // the origin never answers), DNS, TLS, malformed URL, etc.
            return Result.unreachable();
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String readHead(InputStream in) throws IOException {
        byte[] buf = new byte[HEAD_BYTES];
        int total = 0;
        int n;
        while (total < HEAD_BYTES && (n = in.read(buf, total, HEAD_BYTES - total)) != -1) {
            total += n;
        }
        return new String(buf, 0, total, "UTF-8");
    }
}
