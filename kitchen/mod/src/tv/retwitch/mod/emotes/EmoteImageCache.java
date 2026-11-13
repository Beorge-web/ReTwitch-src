package tv.retwitch.mod.emotes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Movie;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import tv.retwitch.mod.CrashReporter;
import tv.retwitch.mod.AppContext;

/**
 * Decoded-image cache for third-party emotes, keyed by their (unwrapped)
 * source URL. Fetching goes through {@link EmoteHttp}'s shared OkHttp
 * client (which also gives us an on-disk HTTP cache -- see there --
 * surviving app restarts) and decoding uses nothing but plain Android SDK
 * classes:
 * <ul>
 *   <li>API 28+: {@link ImageDecoder}, which decodes animated GIF *and*
 *       animated WEBP natively into an {@link Animatable}
 *       {@code AnimatedImageDrawable}, with decode-time downscaling via
 *       {@code setTargetSize};</li>
 *   <li>API 26/27 (Twitch's {@code minSdkVersion}, before
 *       {@code ImageDecoder} existed): the legacy {@link Movie} class for
 *       animated GIF (see {@link MovieDrawable}), falling back to plain
 *       {@link BitmapFactory} for anything Movie can't parse (a static
 *       WEBP/PNG/JPEG).</li>
 * </ul>
 * Deliberately not a vendored image library (Glide, Coil, ...): see
 * kitchen/kitchen/mod_builder.py for why that turned out to
 * reliably collide with whatever Twitch's own build bundles, in ways
 * ranging from a hard crash to a silent, permanent decode hang. Plain
 * platform classes can't have that problem -- there's only ever one
 * {@code android.graphics.ImageDecoder} in the whole process.
 * {@link SevenTvClient} still explicitly prefers the GIF file variant for
 * animated 7TV emotes -- not needed for the ImageDecoder path anymore,
 * but it's what keeps animation working on the API 26/27 {@link Movie}
 * fallback too, which only understands GIF.
 *
 * {@link #getIfReady} stays a synchronous, non-blocking map read safe to
 * call from the chat render path; a message whose emote image hasn't
 * finished downloading yet simply renders as plain text on that render
 * pass. Animated results come back wrapped in {@link AnimatedEmoteDrawable},
 * which is what actually makes them play: see its Javadoc and {@link
 * EmoteChatHook#onTextBound} for the second injection point that wires it
 * up to a real {@code TextView}.
 */
final class EmoteImageCache {

    private static final String TAG = "RTEmotes";
    // Decoded emote images are tiny (~22dp tall by default, EmoteSettings.getEmoteSizeDp), so a generous cap is cheap
    // and matters a lot on emote-heavy channels: a single big 7TV channel set
    // alone can be ~1000 codes, and a busy chat/VOD easily references several
    // hundred distinct ones at once.
    private static final int MAX_ENTRIES = 1024;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(6, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "ReTwitch-EmoteImg");
            thread.setDaemon(true);
            return thread;
        }
    });

    // Access-ordered LRU: on overflow it evicts only the single least-recently
    // *used* image, never the whole map. The old "clear() everything at the
    // cap" behaviour thrashed hard on big channels -- once past the cap, every
    // new download wiped all already-decoded emotes, so they fell back to plain
    // text and had to re-download, repeatedly, and many never stabilised. get()
    // counts as an access, so emotes actually on screen stay hot.
    private static final Map<String, Drawable> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, Drawable>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Drawable> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });
    private static final Map<String, Boolean> pending = new ConcurrentHashMap<>();
    /**
     * Link-preview images ({@link LinkPreview}) are ~100x bigger than an emote,
     * so they get their own small LRU instead of crowding the emote cache.
     */
    private static final int MAX_PREVIEW_ENTRIES = 24;
    /** Preview URLs that failed to load, so refresh passes don't refetch them forever. */
    private static final Set<String> failedPreviews =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Map<String, Drawable> previewCache = Collections.synchronizedMap(
            new LinkedHashMap<String, Drawable>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Drawable> eldest) {
                    return size() > MAX_PREVIEW_ENTRIES;
                }
            });

    private EmoteImageCache() {
    }

    /**
     * Drops every decoded image. Used by the manual "reset emote cache"
     * action so a forced reload re-downloads and re-decodes from scratch
     * (e.g. after an emote's artwork changed). In-flight decodes still
     * finish and repopulate; that's fine -- they're the fresh copies.
     */
    static void clear() {
        cache.clear();
        previewCache.clear();
        failedPreviews.clear();
        Log.i(TAG, "image cache cleared (manual reset)");
    }

    /** Non-blocking; kicks off a background fetch+decode if this URL isn't already cached/in flight. */
    static Drawable getIfReady(final String url) {
        return getIfReady(url, EmoteSettings.getEmoteSizeDp(), 0, true, false);
    }

    /**
     * Same as {@link #getIfReady(String)} for a link preview: decoded
     * {@code heightDp} tall, scaled down to fit {@code maxWidthDp}.
     */
    static Drawable getPreviewIfReady(String url, int heightDp, int maxWidthDp, boolean proxy) {
        return getIfReady(url, heightDp, maxWidthDp, proxy, true);
    }

    private static Drawable getIfReady(final String url, final int heightDp, final int maxWidthDp,
            final boolean proxy, final boolean preview) {
        if (url == null) {
            return null;
        }
        final Map<String, Drawable> target = preview ? previewCache : cache;
        Drawable cached = target.get(url);
        if (cached != null || (preview && failedPreviews.contains(url))) {
            return cached;
        }
        final String pendingKey = preview ? "preview:" + url : url;
        if (pending.putIfAbsent(pendingKey, Boolean.TRUE) == null) {
            EXECUTOR.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Drawable drawable = fetchAndDecode(url, heightDp, maxWidthDp, proxy);
                        if (drawable != null) {
                            // LRU map evicts its own eldest entry on overflow.
                            target.put(url, drawable);
                            // A message that rendered as plain text because
                            // this image wasn't ready yet can now show it --
                            // re-inject over the currently-bound chat rows.
                            EmoteChatHook.requestRefresh();
                        } else if (preview && failedPreviews.size() < 256) {
                            failedPreviews.add(url);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "fetch worker crashed for: " + url, t);
                        CrashReporter.report(TAG, "fetch worker crashed for: " + url, t);
                    } finally {
                        pending.remove(pendingKey);
                    }
                }
            });
        }
        return null;
    }

    /** Delivered a freshly decoded large drawable (or {@code null} on failure), always on the main thread. */
    interface LargeImageCallback {
        void onResult(Drawable drawable);
    }

    /**
     * Fetches + decodes {@code url} at a larger {@code targetHeightPx} (for the
     * tap-to-view emote card, {@link EmoteCardDialog}) off the main thread and
     * hands the result back on it. Independent of the small chat-render cache
     * so it never evicts on-screen chat emotes, and always fetched fresh so
     * the card can show a crisper image than the ~22dp chat glyph. Animated
     * results come back started.
     */
    static void loadLarge(final String url, final int targetHeightPx, final LargeImageCallback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                Drawable result = null;
                try {
                    result = fetchAndDecodeAt(url, targetHeightPx);
                } catch (Throwable t) {
                    Log.w(TAG, "large image load crashed for: " + url, t);
                    CrashReporter.report(TAG, "large image load crashed for: " + url, t);
                }
                final Drawable delivered = result;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            callback.onResult(delivered);
                        } catch (Throwable t) {
                            Log.w(TAG, "large image callback crashed", t);
                            CrashReporter.report(TAG, "large image callback crashed", t);
                        }
                    }
                });
            }
        });
    }

    private static Drawable fetchAndDecodeAt(String url, int targetHeightPx) {
        Context context = AppContext.get();
        if (context == null || url == null) {
            return null;
        }
        EmoteProxy.ensureResolved();
        String finalUrl = EmoteProxy.wrap(url);
        try {
            byte[] bytes = fetchBytes(finalUrl);
            int targetHeight = Math.max(1, targetHeightPx);
            Drawable decoded = Build.VERSION.SDK_INT >= 28
                    ? decodeWithImageDecoder(bytes, targetHeight)
                    : decodeLegacy(bytes);

            int naturalWidth = decoded.getIntrinsicWidth();
            int naturalHeight = decoded.getIntrinsicHeight();
            int targetWidth = naturalHeight > 0
                    ? Math.max(1, Math.round(naturalWidth * (targetHeight / (float) naturalHeight)))
                    : targetHeight;

            boolean animated = decoded instanceof Animatable;
            decoded.setBounds(0, 0, targetWidth, targetHeight);
            if (animated) {
                ((Animatable) decoded).start();
            }
            return decoded;
        } catch (Throwable t) {
            Log.w(TAG, "large image load failed: " + finalUrl, t);
            if (!(t instanceof IOException)) { // timeouts and refused hosts are the network, not a bug
                CrashReporter.report(TAG, "large image load failed: " + finalUrl, t);
            }
            return null;
        }
    }

    private static Drawable fetchAndDecode(String url, int heightDp, int maxWidthDp, boolean proxy) {
        Context context = AppContext.get();
        if (context == null) {
            Log.w(TAG, "no Application context yet, skipping: " + url);
            return null;
        }

        // Ensure a working proxy host is selected (cheap when already resolved
        // by the metadata fetch that preceded this image; this runs on the
        // image-decode background pool, never the main thread).
        String finalUrl = url;
        if (proxy) {
            EmoteProxy.ensureResolved();
            finalUrl = EmoteProxy.wrap(url);
        }
        long start = System.currentTimeMillis();
        try {
            byte[] bytes = fetchBytes(finalUrl);

            float density = context.getResources().getDisplayMetrics().density;
            int targetHeight = Math.max(1, Math.round(heightDp * density));

            Drawable decoded = Build.VERSION.SDK_INT >= 28
                    ? decodeWithImageDecoder(bytes, targetHeight)
                    : decodeLegacy(bytes);

            int naturalWidth = decoded.getIntrinsicWidth();
            int naturalHeight = decoded.getIntrinsicHeight();
            int targetWidth = naturalHeight > 0
                    ? Math.max(1, Math.round(naturalWidth * (targetHeight / (float) naturalHeight)))
                    : targetHeight;
            int maxWidth = Math.round(maxWidthDp * density);
            if (maxWidth > 0 && targetWidth > maxWidth) {
                targetHeight = Math.max(1, Math.round(targetHeight * (maxWidth / (float) targetWidth)));
                targetWidth = maxWidth;
            }

            boolean animated = decoded instanceof Animatable;
            // Only animated drawables need the invalidate-forwarding wrapper --
            // a static image only ever needs its one, first paint.
            Drawable drawable = animated ? new AnimatedEmoteDrawable(decoded) : decoded;
            drawable.setBounds(0, 0, targetWidth, targetHeight);
            if (animated) {
                ((Animatable) decoded).start();
            }

            long elapsed = System.currentTimeMillis() - start;
            Log.i(TAG, "image ready (" + targetWidth + "x" + targetHeight + (animated ? ", animated" : "")
                    + ", " + elapsed + "ms): " + finalUrl);
            return drawable;
        } catch (Throwable t) {
            Log.w(TAG, "image load failed (" + (System.currentTimeMillis() - start) + "ms): " + finalUrl, t);
            if (!(t instanceof IOException)) { // timeouts and refused hosts are the network, not a bug
                CrashReporter.report(TAG, "image load failed (" + (System.currentTimeMillis() - start) + "ms): " + finalUrl, t);
            }
            return null;
        }
    }

    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;
    /** Some image hosts in chat links (gifyu.com) reset requests from non-browser clients, proxied ones too. */
    private static final String BROWSER_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    private static byte[] fetchBytes(String finalUrl) throws IOException {
        Request request = new Request.Builder().url(finalUrl).header("User-Agent", BROWSER_USER_AGENT).build();
        Response response = EmoteHttp.sharedClient().newCall(request).execute();
        try {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " for " + finalUrl);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("empty response body for " + finalUrl);
            }
            // Link previews fetch arbitrary URLs posted in chat: never buffer a huge file.
            if (body.contentLength() > MAX_IMAGE_BYTES) {
                throw new IOException("image too large (" + body.contentLength() + " bytes): " + finalUrl);
            }
            okio.BufferedSource source = body.source();
            if (source.request(MAX_IMAGE_BYTES + 1)) {
                throw new IOException("image too large (> " + MAX_IMAGE_BYTES + " bytes): " + finalUrl);
            }
            return source.getBuffer().readByteArray();
        } finally {
            response.close();
        }
    }

    /**
     * Only ever called behind the {@code Build.VERSION.SDK_INT >= 28}
     * check in {@link #fetchAndDecode}: ART defers verification of a
     * method's body (including the anonymous {@code
     * OnHeaderDecodedListener} instantiated here) until it's actually
     * invoked, so referencing API-28 classes here is safe on older
     * devices as long as this method itself never runs there.
     */
    private static Drawable decodeWithImageDecoder(byte[] bytes, final int targetHeightPx) throws IOException {
        // Android 10/11 native crash (AOSP issue 139371066, fixed in API 31):
        // an ImageDecoder source backed by a byte[]/ByteBuffer is read through a
        // JNI stream (ByteArrayStream). Animated frames are decoded on the native
        // AnimatedImageThread, which isn't attached to the JVM, so its JNI read
        // aborts with "Failed to get JNIEnv for JavaVM" -- SIGABRT, whole app
        // dies. A File source uses an fd-backed stream that never touches JNI, so
        // it's safe on every version. We only pay the disk round-trip where the
        // bug exists; API 31+ keeps the cheap in-memory path.
        ImageDecoder.Source source;
        if (Build.VERSION.SDK_INT < 31) {
            File file = writeToDecodeCache(bytes);
            source = file != null
                    ? ImageDecoder.createSource(file)
                    : ImageDecoder.createSource(ByteBuffer.wrap(bytes));
        } else {
            source = ImageDecoder.createSource(ByteBuffer.wrap(bytes));
        }
        return ImageDecoder.decodeDrawable(source, new ImageDecoder.OnHeaderDecodedListener() {
            @Override
            public void onHeaderDecoded(ImageDecoder decoder, ImageDecoder.ImageInfo info, ImageDecoder.Source src) {
                // Hardware bitmaps aren't guaranteed drawable into every
                // Canvas a TextView/ImageSpan might end up using.
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                android.util.Size size = info.getSize();
                int naturalWidth = size.getWidth();
                int naturalHeight = size.getHeight();
                int targetWidth = naturalHeight > 0
                        ? Math.max(1, Math.round(naturalWidth * (targetHeightPx / (float) naturalHeight)))
                        : targetHeightPx;
                decoder.setTargetSize(Math.max(1, targetWidth), targetHeightPx);
            }
        });
    }

    /**
     * Persists {@code bytes} to a stable file in the app cache and returns it,
     * for use as an {@link ImageDecoder} {@code File} source on Android
     * versions with the byte-buffer decode bug (see
     * {@link #decodeWithImageDecoder}). Named by content hash so identical
     * images share one file, and deliberately <em>not</em> deleted afterwards:
     * an {@code AnimatedImageDrawable} keeps decoding frames straight from this
     * file's descriptor for its whole lifetime, and it doubles as a decode
     * cache across restarts. Returns {@code null} (caller falls back to the
     * in-memory source) if no context or anything goes wrong -- never throws.
     */
    private static File writeToDecodeCache(byte[] bytes) {
        try {
            Context context = AppContext.get();
            if (context == null) {
                return null;
            }
            File dir = new File(context.getCacheDir(), "rt-emote-decode");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File file = new File(dir, hashName(bytes));
            if (file.exists() && file.length() == bytes.length) {
                return file;
            }
            File tmp = File.createTempFile("dec", ".part", dir);
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(bytes);
                out.flush();
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (!tmp.renameTo(file)) {
                tmp.delete();
                return file.exists() ? file : null;
            }
            return file;
        } catch (Throwable t) {
            Log.w(TAG, "decode-cache write failed", t);
            return null;
        }
    }

    private static String hashName(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(java.util.Arrays.hashCode(bytes)) + "_" + bytes.length;
        }
    }

    private static Drawable decodeLegacy(byte[] bytes) throws IOException {
        Movie movie = Movie.decodeByteArray(bytes, 0, bytes.length);
        if (movie != null && movie.width() > 0 && movie.height() > 0) {
            return new MovieDrawable(movie);
        }

        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null) {
            throw new IOException("BitmapFactory/Movie could not decode image");
        }
        return new BitmapDrawable(null, bitmap);
    }
}
