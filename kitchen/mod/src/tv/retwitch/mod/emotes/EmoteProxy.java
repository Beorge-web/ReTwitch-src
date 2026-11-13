package tv.retwitch.mod.emotes;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import tv.retwitch.mod.AppContext;

/**
 * ReYohoho's emote-traffic proxy. Unlike the usher proxies (which redirect
 * to a completely different backend), this one is a dumb tunnel: it just
 * expects the full original URL appended verbatim right after its own
 * host, e.g.:
 * <pre>
 *   https://starege.rte.net.ru/https://api.betterttv.net/3/cached/...
 *   https://starege.rte.net.ru/https://cdn.7tv.app/emote/.../1x.webp
 * </pre>
 * Applied uniformly to both API calls and CDN image fetches so the whole
 * emote pipeline can be tunneled with a single toggle (see
 * {@link EmoteSettings#isProxyEnabled()}).
 *
 * <p><b>Why four hosts, picked by a probe.</b> A single fixed host isn't
 * reliable: the tunnel is fronted by several different hosters, and on some
 * Russian ISPs a given host's IP gets throttled or blocked while the others
 * still work (this mirrors the frosty-reyohoho client's {@code
 * _staregeDomains} list and its HEAD-probe domain selection). So before the
 * first emote load of a session we race a {@code HEAD <base>/https://google.com}
 * request against every candidate in parallel and keep the one that answers
 * first (fastest reachable). If none answer we fall back to fetching
 * directly (no proxy) and surface a one-off toast so the user knows why
 * third-party emotes might be missing.
 */
public final class EmoteProxy {

    private static final String TAG = "RTEmotes";

    /**
     * Candidate tunnel hosts in priority order -- four different hosters so
     * at least one usually stays reachable when an ISP blocks a given IP.
     * Kept in sync with the frosty-reyohoho client's starege domains.
     */
    private static final String[] CANDIDATE_BASES = {
            "https://ext.rte.net.ru:8443",
            "https://starege.rte.net.ru",
            "https://starege3.rte.net.ru",
            "https://starege5.rte.net.ru",
            "https://starege4.rte.net.ru",
    };

    /**
     * Appended to a candidate host to test that it actually tunnels traffic:
     * {@code HEAD <base>/https://google.com}. A 2xx/3xx means the host both
     * answered and forwarded us to a reliably-reachable origin.
     */
    private static final String PROBE_TARGET = "/https://google.com";

    private static final long PROBE_TIMEOUT_MS = 3000L;

    /**
     * How long a probe result (working host, or "all down") is trusted before
     * the next fetch re-probes. Short enough that a host coming back / going
     * away mid-session is noticed within a few minutes, long enough that
     * back-to-back channel opens don't each pay the probe cost.
     */
    private static final long RESOLVE_TTL_MS = TimeUnit.MINUTES.toMillis(5);

    private static final Object LOCK = new Object();

    /**
     * The resolved working host <em>with</em> a trailing slash (e.g.
     * {@code "https://starege.rte.net.ru/"}), or {@code null} when nothing is
     * reachable -- in which case {@link #wrap} returns URLs unproxied.
     */
    private static volatile String workingBase;
    private static volatile long resolvedAt = -1L;

    /** A host a real fetch just failed through sits out this long, so the re-probe picks another one. */
    private static final long BENCH_MS = TimeUnit.MINUTES.toMillis(2);
    /** Candidate base (no trailing slash) -> time it may be picked again. */
    private static final Map<String, Long> benchedUntil = new ConcurrentHashMap<>();
    /** Bumped on every {@link #reportFailure} that dropped the host; lets callers tell whether a retry may help. */
    private static volatile int switches;

    /** Guards the "proxy down, going direct" toast so it fires once per outage, not per fetch. */
    private static volatile boolean unavailableNotified;

    private EmoteProxy() {
    }

    /**
     * Prefixes {@code url} with the currently-selected working proxy host,
     * or returns it untouched when proxying is off / no host is reachable.
     * Non-blocking (a plain volatile read) so it is safe on the render
     * thread -- {@link #ensureResolved()} does the actual (blocking) host
     * selection ahead of time on a background thread.
     */
    static String wrap(String url) {
        if (url == null || !EmoteSettings.isProxyEnabled()) {
            return url;
        }
        String base = workingBase;
        if (base != null) {
            // Already served by the proxy host (RTE badges): wrapping it twice is refused (403).
            // Our own API goes direct, as the roster and Twitch's loader of chat badges do.
            return url.startsWith(base) || url.startsWith("https://ext.rte.net.ru") ? url : base + url;
        }
        // Proxy enabled but no verified host (not resolved yet, or all down):
        // fetch directly rather than through a host we know isn't answering.
        return url;
    }

    /**
     * {@code url} through the working proxy host for code outside this package (Robotty's chat history, slow or
     * throttled directly from some networks), picking a host first: blocks, background threads only. Direct when
     * proxying is off or no host answers.
     */
    public static String proxied(String url) {
        ensureResolved();
        return wrap(url);
    }

    /** A {@link #proxied} fetch failed: its host sits out, as with {@link #reportFailure}. */
    public static void failed(String finalUrl) {
        reportFailure(finalUrl);
    }

    /** The 7TV EventAPI through the working proxy host, or null when proxying is off or no host answers. */
    static String sevenTvEventsUrl() {
        String base = EmoteSettings.isProxyEnabled() ? workingBase : null;
        return base == null ? null : base.replaceFirst("^https", "wss") + "7tv-proxy";
    }

    /**
     * Forgets any cached host so the next {@link #ensureResolved()} re-probes.
     * Called when the proxy setting is toggled or the emote cache is reset,
     * so the change takes effect on the next load rather than after the TTL.
     */
    static void reset() {
        synchronized (LOCK) {
            workingBase = null;
            resolvedAt = -1L;
            unavailableNotified = false;
            benchedUntil.clear();
        }
    }

    /**
     * Ensures a working proxy host has been selected, probing all candidates
     * in parallel (fastest 2xx/3xx wins) when the cached result is missing or
     * stale. Blocks up to ~{@link #PROBE_TIMEOUT_MS}, so it MUST be called
     * from a background thread -- it is invoked at the top of the emote
     * fetch paths ({@link EmoteCache}/{@link EmoteImageCache}), both of which
     * already run off the main thread. No-op when proxying is disabled.
     */
    static void ensureResolved() {
        if (!EmoteSettings.isProxyEnabled() || isFresh()) {
            return;
        }
        synchronized (LOCK) {
            if (isFresh()) {
                return;
            }
            String base = probeFastest();
            resolvedAt = System.currentTimeMillis();
            if (base != null) {
                workingBase = base.endsWith("/") ? base : base + "/";
                unavailableNotified = false;
                Log.i(TAG, "emote proxy: selected " + base);
            } else {
                workingBase = null;
                Log.w(TAG, "emote proxy: all " + CANDIDATE_BASES.length
                        + " hosts unreachable, falling back to direct");
                notifyUnavailable();
            }
        }
    }

    /**
     * A fetch through {@code finalUrl} failed (timeout, reset, non-404 error). If it went through the
     * current host, that host sits out {@link #BENCH_MS} and the next {@link #ensureResolved()} re-probes
     * the others: the probe is a one-off HEAD to google.com, the host can die or fail a provider later.
     */
    static void reportFailure(String finalUrl) {
        synchronized (LOCK) {
            String base = workingBase;
            if (base == null || finalUrl == null || !finalUrl.startsWith(base)) {
                return; // direct, or a host already replaced by a concurrent failure
            }
            String key = base.substring(0, base.length() - 1);
            benchedUntil.put(key, System.currentTimeMillis() + BENCH_MS);
            workingBase = null;
            resolvedAt = -1L;
            switches++;
            Log.w(TAG, "emote proxy: " + key + " failed a fetch, re-probing without it");
        }
    }

    static int switches() {
        return switches;
    }

    private static boolean isFresh() {
        return resolvedAt >= 0 && System.currentTimeMillis() - resolvedAt < RESOLVE_TTL_MS;
    }

    /**
     * Fires a HEAD probe at every candidate host concurrently and returns the
     * first one to answer with a 2xx/3xx, or {@code null} if none do within
     * the timeout window. "First to answer" -- not "first in the list" -- so
     * the fastest currently-reachable host is chosen.
     */
    private static String probeFastest() {
        OkHttpClient client = EmoteHttp.sharedClient().newBuilder()
                .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();

        ExecutorService pool = Executors.newFixedThreadPool(CANDIDATE_BASES.length, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "ReTwitch-EmoteProxyProbe");
                thread.setDaemon(true);
                return thread;
            }
        });
        try {
            ExecutorCompletionService<String> completion = new ExecutorCompletionService<>(pool);
            long now = System.currentTimeMillis();
            java.util.List<String> candidates = new java.util.ArrayList<>();
            for (String base : CANDIDATE_BASES) {
                Long until = benchedUntil.get(base);
                if (until == null || until <= now) {
                    candidates.add(base);
                }
            }
            if (candidates.isEmpty()) {
                // Every host failed a fetch lately: better any of them than none.
                candidates.addAll(java.util.Arrays.asList(CANDIDATE_BASES));
            }
            for (final String base : candidates) {
                completion.submit(new Callable<String>() {
                    @Override
                    public String call() {
                        return probeOne(client, base) ? base : null;
                    }
                });
            }

            long deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS + 1000L;
            for (int i = 0; i < candidates.size(); i++) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    Future<String> finished = completion.poll(remaining, TimeUnit.MILLISECONDS);
                    if (finished == null) {
                        break;
                    }
                    String result = finished.get();
                    if (result != null) {
                        return result;
                    }
                } catch (Exception e) {
                    // A single probe blew up -- keep waiting on the rest.
                    Log.d(TAG, "emote proxy probe error: " + e);
                }
            }
            return null;
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean probeOne(OkHttpClient client, String base) {
        Request request = new Request.Builder().url(base + PROBE_TARGET).head().build();
        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            boolean ok = code >= 200 && code < 400;
            Log.d(TAG, "emote proxy probe " + base + " -> " + code);
            return ok;
        } catch (Throwable t) {
            Log.d(TAG, "emote proxy probe " + base + " failed: " + t);
            return false;
        }
    }

    private static void notifyUnavailable() {
        if (unavailableNotified) {
            return;
        }
        unavailableNotified = true;
        final Context context = AppContext.get();
        if (context == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(context, tv.retwitch.mod.Strings.proxyUnavailableToast(),
                            Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                    // A failed toast must never take the app down.
                }
            }
        });
    }
}
