package tv.retwitch.mod.emotes;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.Call;
import okhttp3.Dispatcher;
import okhttp3.EventListener;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import tv.retwitch.mod.AppContext;

/**
 * Our own vendored copy of OkHttp for all emote API/CDN traffic (see
 * kitchen/mod_builder.py's {@code VENDOR_JAR_NAMES} for the rationale --
 * Twitch's own build almost certainly bundles an OkHttp of its own, but
 * R8 full-mode renames every one of its classes release to release, so
 * there is no stable name to bind against; a plain, unmodified copy of
 * the library under its real package name sidesteps that entirely, with
 * no duplicate-class risk since Twitch's copy never keeps those names).
 *
 * A single shared client/connection-pool backs both the JSON fetches here
 * and {@link EmoteImageCache}'s image fetches -- important because a
 * channel switch can trigger dozens of concurrent image downloads that
 * all benefit from sharing connections/keep-alive to the same few CDN or
 * proxy hosts. It also carries an on-disk {@link Cache}, so a previously
 * seen emote image survives an app restart without hitting the network
 * again -- see {@link ForceCacheableInterceptor}.
 */
final class EmoteHttp {

    private static final String TAG = "RTEmotes";
    private static final long DISK_CACHE_BYTES = 64L * 1024 * 1024;

    private static volatile OkHttpClient client;

    private EmoteHttp() {
    }

    /** Shared client so image fetches (EmoteImageCache) and JSON fetches (this class) pool the same connections. */
    static OkHttpClient sharedClient() {
        OkHttpClient existing = client;
        if (existing != null) {
            return existing;
        }
        synchronized (EmoteHttp.class) {
            if (client == null) {
                client = buildClient();
            }
            return client;
        }
    }

    /**
     * Current size (bytes) of the on-disk HTTP response cache, or {@code -1}
     * if it isn't available (no {@link android.content.Context} yet, or the
     * cache journal can't be read). Builds the shared client on demand so the
     * figure is reported even before any emote traffic has happened this run
     * -- the on-disk cache survives restarts, so it can be non-empty on a
     * cold start. For the cache-stats UI only.
     */
    static long diskCacheBytes() {
        try {
            Cache cache = sharedClient().cache();
            return cache != null ? cache.size() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Evicts everything from the on-disk HTTP response cache (emote image
     * bytes). Used by the manual cache-reset actions so a forced reload
     * genuinely re-downloads images from the origin rather than replaying
     * the cached bytes. Best-effort.
     */
    static void clearDiskCache() {
        try {
            Cache cache = sharedClient().cache();
            if (cache != null) {
                cache.evictAll();
                Log.i(TAG, "disk HTTP cache evicted (manual reset)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "disk cache clear failed: " + t);
        }
    }

    private static OkHttpClient buildClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .dispatcher(newDispatcher())
                .retryOnConnectionFailure(true)
                .addNetworkInterceptor(new ForceCacheableInterceptor())
                .eventListener(new DiagnosticEventListener());

        Context context = AppContext.get();
        if (context != null) {
            File cacheDir = new File(context.getCacheDir(), "retwitch_emote_http");
            builder.cache(new Cache(cacheDir, DISK_CACHE_BYTES));
        } else {
            Log.w(TAG, "no Application context yet; image responses won't be disk-cached this run");
        }
        return builder.build();
    }

    private static Dispatcher newDispatcher() {
        Dispatcher dispatcher = new Dispatcher();
        // Default OkHttp caps are 5/host, 64 total -- too low once a busy
        // channel needs a burst of emote images through a single proxy host.
        dispatcher.setMaxRequests(48);
        dispatcher.setMaxRequestsPerHost(16);
        return dispatcher;
    }

    /** Non-2xx answer; a 404 on a user/channel endpoint means "no account there", anything else is a failure. */
    static final class HttpError extends IOException {
        final int code;

        HttpError(int code, String url) {
            super("HTTP " + code + " for " + url);
            this.code = code;
        }
    }

    static String getJson(String url) throws IOException {
        String finalUrl = EmoteProxy.wrap(appendCacheBuster(url));
        long start = System.currentTimeMillis();
        Request request = new Request.Builder().url(finalUrl).header("Accept", "application/json").build();
        try {
            Response response = sharedClient().newCall(request).execute();
            try {
                ResponseBody body = response.body();
                String text = body != null ? body.string() : "";
                long elapsed = System.currentTimeMillis() - start;
                if (!response.isSuccessful()) {
                    throw new HttpError(response.code(), finalUrl);
                }
                Log.d(TAG, "GET " + finalUrl + " -> " + response.code() + " (" + elapsed + "ms)");
                return text;
            } finally {
                response.close();
            }
        } catch (IOException e) {
            // 404 is a real answer ("no such user"); anything else may be the proxy host.
            if (!(e instanceof HttpError) || ((HttpError) e).code != 404) {
                EmoteProxy.reportFailure(finalUrl);
            }
            throw e;
        }
    }

    /**
     * Appends a unique cache-buster query param to emote-list JSON URLs
     * (this method is only used by {@link #getJson}, never by image
     * fetches). An emote *set* changes over time -- a channel adds or
     * removes emotes -- so every actual (TTL-gated) refetch must reflect
     * the current list rather than a stale copy served by OkHttp's disk
     * cache, a CDN edge, or the ReYohoho proxy. A per-fetch timestamp
     * makes the URL unique, which busts all three at once. Image URLs are
     * content-addressed and deliberately left untouched (see
     * {@link EmoteImageCache}), so image caching still works.
     */
    private static String appendCacheBuster(String url) {
        if (url == null) {
            return null;
        }
        String separator = url.indexOf('?') >= 0 ? "&" : "?";
        return url + separator + "_ts=" + System.currentTimeMillis();
    }

    /**
     * Emote CDNs don't reliably send cache headers generous enough for a
     * "seen once, never fetch again" cache (some send short max-ages,
     * some send none at all) -- but emote image URLs are effectively
     * content-addressed (a given 7TV/BTTV/FFZ emote id+format never
     * changes what bytes it points to), so it's safe to force a long
     * cache lifetime on anything that looks like an image response
     * regardless of what the origin actually sent. Only touches image/*
     * responses -- the emote-list JSON endpoints deliberately keep
     * whatever (much shorter-lived) caching OkHttp would apply on its own,
     * since that data does change over time.
     */
    private static final class ForceCacheableInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Response response = chain.proceed(chain.request());
            String contentType = response.header("Content-Type");
            if (contentType == null || !contentType.startsWith("image/")) {
                return response;
            }
            return response.newBuilder()
                    .removeHeader("Pragma")
                    .header("Cache-Control", "public, max-age=2592000") // 30 days
                    .build();
        }
    }

    /** Logs failures on the shared client -- both JSON and image fetches go through here. */
    private static final class DiagnosticEventListener extends EventListener {
        @Override
        public void callFailed(Call call, IOException ioe) {
            Log.w(TAG, "okhttp callFailed: " + call.request().url() + " -> " + ioe);
        }
    }
}
