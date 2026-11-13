package tv.retwitch.mod;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

/**
 * VOD / clip download from the seekable player's button strip ({@link Toggles#DOWNLOAD_BUTTON}).
 * The source is what IVS plays ({@code Player.getPath()}: a clip's JSON, a VOD master's URL or
 * its text): a clip's MP4 goes to the system {@link DownloadManager} at the quality picked right
 * after the tap (all renditions from GQL); a VOD's rendition picked from its master is a recording
 * ({@link LiveRecord#vod}): its segments fetched {@link #THREADS} at a time into one .ts (.mp4 for
 * fMP4) in Downloads/ReTwitch under {@link RecordService}, with pause, stop and continuing later in
 * the recordings list. Muted parts: a refused {@code -unmuted} segment is retried as {@code -muted}.
 */
final class VodDownload {

    private static final String TAG = "RTDownload";
    private static Handler main;

    /** Lazy: a static Handler breaks the JVM self-check (android.jar stubs). */
    private static Handler main() {
        if (main == null) {
            main = new Handler(Looper.getMainLooper());
        }
        return main;
    }

    static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS).build();
    /** VOD segments fetched at once, as other VOD downloaders do; one request at a time crawled. */
    private static final int THREADS = 8;
    /** HTTP/1.1: a TCP connection per thread, not every segment multiplexed onto one HTTP/2 connection. */
    static final OkHttpClient SEGMENTS = HTTP.newBuilder()
            .protocols(java.util.Collections.singletonList(Protocol.HTTP_1_1))
            .connectionPool(new okhttp3.ConnectionPool(THREADS, 1, TimeUnit.MINUTES)).build();

    private VodDownload() {
    }

    /** What {@link #appendSegments} asks before each segment: wait while paused, throw {@link CancellationException} to stop. */
    interface Gate {
        void check() throws InterruptedException;
    }

    static View createButton(Context context) {
        ImageView button = new ImageView(context);
        int px = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24,
                context.getResources().getDisplayMetrics());
        button.setImageDrawable(new Icon(px));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(Strings.downloadTitle());
        button.setClickable(true);
        button.setOnClickListener(v -> {
            try {
                start(v.getContext());
            } catch (Throwable t) {
                Log.w(TAG, "download start failed", t);
                CrashReporter.report(TAG, "download start failed", t);
            }
        });
        int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48,
                context.getResources().getDisplayMetrics());
        button.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return button;
    }

    private static void start(final Context context) throws Exception {
        Object player = PlayerHook.currentPlayer();
        final String source = player == null ? null : (String) player.getClass().getMethod("getPath").invoke(player);
        if (source == null || source.isEmpty()) {
            Toast.makeText(context, Strings.downloadNothing(), Toast.LENGTH_SHORT).show();
            return;
        }
        final String vodId = ChannelTracker.getCurrentVodId();
        new Thread(() -> {
            try {
                List<String[]> options;
                String[] named;
                if (source.startsWith("{")) {
                    String slug = new org.json.JSONObject(new org.json.JSONObject(source)
                            .getJSONObject("token").getString("value")).optString("clip_slug");
                    List<String[]> all = new ArrayList<>();
                    named = clipInfo(slug, all);
                    // IVS only carries the rendition it plays; GQL has them all.
                    options = all.isEmpty() ? clipOptions(source) : all;
                } else if (source.startsWith("#EXTM3U") || source.contains(".m3u8")) {
                    String id = vodId != null ? vodId : vodIdOf(source);
                    // Our own master first: every rendition (Twitch's may be capped), via the stream proxy.
                    options = freshVodOptions(id);
                    if (options == null) {
                        // Twitch hands IVS the usher master's text (its manifest cache); variants are absolute.
                        options = source.startsWith("#EXTM3U") ? variants("https://usher.ttvnw.net/", source)
                                : variants(source, fetch(HTTP, source));
                    }
                    named = vodName(id);
                } else {
                    options = new ArrayList<>();
                    options.add(new String[]{"MP4", source});
                    named = null;
                }
                final String name = fileName(named);
                final List<String[]> list = options;
                main().post(() -> choose(context, list, name, vodId));
            } catch (Throwable t) {
                Log.w(TAG, "download options failed", t);
                main().post(() -> Toast.makeText(context, Strings.downloadFailed(), Toast.LENGTH_LONG).show());
            }
        }, "RTDownload").start();
    }

    /** Quality right after the tap; a VOD becomes a recording, a file goes to DownloadManager. */
    private static void choose(final Context context, final List<String[]> options, final String name,
            final String vodId) {
        String[] labels = new String[options.size()];
        boolean vod = false;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = options.get(i)[0];
            vod |= options.get(i)[1].contains(".m3u8");
        }
        new AlertDialog.Builder(context)
                .setTitle(vod ? Strings.downloadVodQuality() : Strings.downloadQuality())
                .setItems(labels, (d, which) -> {
                    String url = options.get(which)[1];
                    if (url.contains(".m3u8")) {
                        // Our progress notification needs the permission (DownloadManager's doesn't);
                        // without it the progress comes as toasts.
                        if (PushRegistration.needsNotificationPermission(context)) {
                            PushRegistration.requestNotificationPermission(context);
                        }
                        if (LiveRecord.downloading(vodId)) {
                            Toast.makeText(context, Strings.pick("Это видео уже скачивается",
                                    "This video is already downloading"), Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String label = options.get(which)[0];
                        askRange(context, (from, to) -> {
                            LiveRecord.Rec rec = LiveRecord.vod(vodId, url, label);
                            rec.clipFrom = from;
                            rec.clipTo = to;
                            LiveRecord.start(context, rec);
                            Toast.makeText(context, Strings.downloadStarted(), Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                            .setTitle(name + ".mp4")
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                                    "ReTwitch/" + name + ".mp4");
                    ((DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(request);
                    Toast.makeText(context, Strings.downloadStarted(), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * The whole VOD or a fragment of it (feedback #14), {@code (-1, -1)} for the whole. The fields start around where
     * the player is: 30 s before and after.
     */
    private static void askRange(Context context, BiConsumer<Long, Long> download) {
        long position = 0;
        long duration = 0;
        try {
            Object player = PlayerHook.currentPlayer();
            if (player != null) {
                position = (Long) player.getClass().getMethod("getPosition").invoke(player);
                duration = (Long) player.getClass().getMethod("getDuration").invoke(player);
            }
        } catch (Throwable t) {
            Log.w(TAG, "player position unknown", t);
        }
        long to = position + 30_000;
        EditText fromField = clockField(context, Math.max(0, position - 30_000));
        EditText toField = clockField(context, duration > 0 ? Math.min(duration, to) : to);
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(24 * density);
        row.setPadding(pad, Math.round(8 * density), pad, 0);
        TextView dash = new TextView(context);
        dash.setText("-");
        dash.setPadding(pad / 2, 0, pad / 2, 0);
        row.addView(fromField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(dash);
        row.addView(toField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        new AlertDialog.Builder(context)
                .setTitle(Strings.pick("Что скачать?", "What to download?"))
                .setMessage(Strings.pick("Фрагмент - кусок видео между двумя метками времени, сейчас вокруг текущего места. Точность - около 10 секунд",
                        "A fragment is the part of the video between two times, now around where you are. Accurate to about 10 seconds"))
                .setView(row)
                .setPositiveButton(Strings.pick("Фрагмент", "Fragment"), (d, w) -> {
                    long from = parseClock(fromField.getText().toString());
                    long until = parseClock(toField.getText().toString());
                    if (from < 0 || until <= from) {
                        Toast.makeText(context, Strings.pick("Конец фрагмента должен быть позже начала, например 1:02:30",
                                "The fragment must end after it starts, e.g. 1:02:30"), Toast.LENGTH_LONG).show();
                        return;
                    }
                    download.accept(from, until);
                })
                .setNeutralButton(Strings.pick("Всё видео", "Whole video"), (d, w) -> download.accept(-1L, -1L))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static EditText clockField(Context context, long ms) {
        EditText field = new EditText(context);
        field.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        field.setGravity(Gravity.CENTER);
        field.setSingleLine(true);
        field.setText(LiveRecord.clock(ms / 1000.0));
        return field;
    }

    /** "1:02:03", "62:03" or "75" (seconds) in ms; -1 if it isn't a time. */
    static long parseClock(String text) {
        String[] parts = text.trim().split(":");
        if (parts.length > 3) {
            return -1;
        }
        long seconds = 0;
        for (String part : parts) {
            if (!part.matches("\\d{1,6}")) {
                return -1;
            }
            seconds = seconds * 60 + Long.parseLong(part);
        }
        return seconds * 1000;
    }

    /** "Channel - Title (date time)", file-safe; "ReTwitch (date time)" without a name. */
    static String fileName(String[] channelAndTitle) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.ROOT).format(new Date());
        String base = channelAndTitle == null ? "ReTwitch"
                : (channelAndTitle[0] + " - " + channelAndTitle[1]);
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
        if (base.length() > 120) {
            base = base.substring(0, 120).trim();
        }
        return (base.isEmpty() ? "ReTwitch" : base) + " (" + stamp + ")";
    }

    /** A clip's IVS source JSON: one option per asset, tallest first. */
    static List<String[]> clipOptions(String json) throws org.json.JSONException {
        org.json.JSONObject source = new org.json.JSONObject(json);
        org.json.JSONObject token = source.getJSONObject("token");
        org.json.JSONArray assets = source.getJSONArray("assets");
        List<org.json.JSONObject> sorted = new ArrayList<>();
        for (int i = 0; i < assets.length(); i++) {
            sorted.add(assets.getJSONObject(i));
        }
        java.util.Collections.sort(sorted, (a, b) -> b.optInt("height") - a.optInt("height"));
        List<String[]> out = new ArrayList<>();
        for (org.json.JSONObject a : sorted) {
            String url = a.getString("sourceURL");
            String name = a.optString("name", a.optInt("height") + "p");
            out.add(new String[]{name, url + (url.contains("?") ? "&" : "?") + "sig="
                    + Uri.encode(token.getString("signature")) + "&token=" + Uri.encode(token.getString("value"))});
        }
        return out;
    }

    /**
     * The VOD's master as the web player gets it -- anonymous playback token over GQL, usher
     * with {@code allow_source} -- through the selected stream proxy if any; null on failure.
     */
    static List<String[]> freshVodOptions(String vodId) {
        if (vodId == null) {
            return null;
        }
        try {
            org.json.JSONObject token = gql("query($s:ID!){videoPlaybackAccessToken(id:$s,params:{platform:\"web\","
                    + "playerBackend:\"mediaplayer\",playerType:\"site\"}){value signature}}", vodId)
                    .getJSONObject("videoPlaybackAccessToken");
            Uri usher = Uri.parse("https://usher.ttvnw.net/vod/" + vodId + ".m3u8").buildUpon()
                    .appendQueryParameter("sig", token.getString("signature"))
                    .appendQueryParameter("token", token.getString("value"))
                    .appendQueryParameter("allow_source", "true")
                    .appendQueryParameter("allow_audio_only", "true")
                    .appendQueryParameter("player", "twitchweb")
                    .appendQueryParameter("platform", "web")
                    .build();
            Uri proxied = ProxySettings.maybeGetProxyUri(usher, true);
            String url = (proxied != null ? proxied : usher).toString();
            return variants(url, fetch(HTTP, url));
        } catch (Throwable t) {
            Log.w(TAG, "own VOD master failed: " + t);
            return null;
        }
    }

    /** VOD id from a usher URL, also when wrapped by our local proxy ({@code ...?u=<encoded>}). */
    static String vodIdOf(String url) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("vod(?:/|%2F)(\\d+)\\.m3u8").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /**
     * {channel, title} of a clip, and every rendition into {@code options} (signed with the
     * clip's playback token, as the web player does); null / nothing on failure.
     */
    private static String[] clipInfo(String slug, List<String[]> options) {
        if (slug == null || slug.isEmpty()) {
            return null;
        }
        try {
            org.json.JSONObject clip = gql("query($s:ID!){clip(slug:$s){title broadcaster{displayName} "
                    + "videoQualities{quality frameRate sourceURL} playbackAccessToken(params:{platform:\"web\","
                    + "playerBackend:\"mediaplayer\",playerType:\"site\"}){signature value}}}", slug)
                    .optJSONObject("clip");
            if (clip == null) {
                return null;
            }
            org.json.JSONObject token = clip.optJSONObject("playbackAccessToken");
            org.json.JSONArray qualities = clip.optJSONArray("videoQualities");
            for (int i = 0; token != null && qualities != null && i < qualities.length(); i++) {
                org.json.JSONObject q = qualities.getJSONObject(i);
                String url = q.getString("sourceURL");
                int fps = (int) Math.round(q.optDouble("frameRate", 0));
                options.add(new String[]{q.optString("quality") + "p" + (fps > 30 ? fps : ""),
                        url + (url.contains("?") ? "&" : "?") + "sig=" + Uri.encode(token.getString("signature"))
                                + "&token=" + Uri.encode(token.getString("value"))});
            }
            org.json.JSONObject owner = clip.optJSONObject("broadcaster");
            return new String[]{owner == null ? "" : owner.optString("displayName"), clip.optString("title")};
        } catch (Throwable t) {
            Log.w(TAG, "clip lookup failed: " + t);
            return null;
        }
    }

    private static String[] vodName(String id) {
        return gqlName("query($s:ID!){video(id:$s){title owner{displayName}}}", id, "video", "owner");
    }

    /** Anonymous GQL (web Client-ID) with one variable {@code s}; the {@code data} object. */
    static org.json.JSONObject gql(String query, String s) throws Exception {
        return gql(query, new org.json.JSONObject().put("s", s));
    }

    /** Anonymous GQL (web Client-ID) with any variables; the {@code data} object. */
    static org.json.JSONObject gql(String query, org.json.JSONObject variables) throws Exception {
        org.json.JSONObject body = new org.json.JSONObject().put("query", query).put("variables", variables);
        Request request = new Request.Builder().url("https://gql.twitch.tv/gql")
                .header("Client-ID", "kimne78kx3ncx6brgo4mv6wki5h1ko")
                .post(okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json"), body.toString()))
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            return new org.json.JSONObject(response.body().string()).getJSONObject("data");
        }
    }

    /** {channel, title} over anonymous GQL; null when unknown. */
    private static String[] gqlName(String query, String id, String field, String ownerField) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        try {
            org.json.JSONObject item = gql(query, id).optJSONObject(field);
            org.json.JSONObject owner = item == null ? null : item.optJSONObject(ownerField);
            return item == null ? null : new String[]{owner == null ? "" : owner.optString("displayName"),
                    item.optString("title")};
        } catch (Throwable t) {
            Log.w(TAG, "name lookup failed: " + t);
            return null;
        }
    }

    /**
     * The master playlist's renditions as {label, absolute URL}, in playlist order (Twitch lists
     * the best first): Twitch's own name ({@code VIDEO="720p60"}, "chunked" = source) with the
     * resolution; a media playlist is its one option.
     */
    static List<String[]> variants(String masterUrl, String master) {
        List<String[]> out = new ArrayList<>();
        String[] lines = master.split("\n");
        for (int i = 0; i + 1 < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF")) {
                continue;
            }
            String uri = lines[i + 1].trim();
            if (uri.isEmpty() || uri.startsWith("#")) {
                continue;
            }
            String video = attr(line, "VIDEO");
            String res = attr(line, "RESOLUTION");
            String label;
            if ("chunked".equals(video)) {
                label = Strings.downloadSource() + (res != null ? " (" + sourceName(res, attr(line, "FRAME-RATE")) + ") · " + res : "");
            } else if ("audio_only".equals(video) || res == null) {
                label = video != null && !"audio_only".equals(video) ? video : Strings.downloadAudioOnly();
            } else {
                label = (video != null ? video : res.substring(res.indexOf('x') + 1) + "p") + " · " + res;
            }
            out.add(new String[]{label, resolve(masterUrl, uri)});
        }
        if (out.isEmpty()) {
            out.add(new String[]{Strings.downloadSource(), masterUrl});
        }
        return out;
    }

    /** The source rendition named as Twitch names the others: "1080p60", "936p60", "1080p" (fps only from 50). */
    static String sourceName(String resolution, String frameRate) {
        String name = resolution.substring(resolution.indexOf('x') + 1) + "p";
        try {
            long fps = Math.round(Double.parseDouble(frameRate));
            return fps >= 50 ? name + fps : name;
        } catch (RuntimeException e) {
            return name; // no FRAME-RATE
        }
    }

    private static String attr(String line, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[:,]" + key + "=(\"([^\"]*)\"|[^,]*)").matcher(line);
        return m.find() ? (m.group(2) != null ? m.group(2) : m.group(1)) : null;
    }

    static String resolve(String base, String ref) {
        return java.net.URI.create(base).resolve(ref).toString();
    }

    static String fetch(OkHttpClient http, String url) throws IOException {
        try (Response response = http.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code() + " for playlist");
            }
            return response.body().string();
        }
    }

    /**
     * Fetches {@code urls} {@link #THREADS} at a time into part files in {@code dir} and appends
     * them to {@code out} in playlist order; {@code net} counts bytes off the network, and
     * {@code onWritten} gets (segments written, bytes written) after each one. Returns the bytes written.
     */
    static long appendSegments(OkHttpClient http, List<String> urls, File dir, OutputStream out,
            AtomicLong net, Gate gate, BiConsumer<Integer, Long> onWritten) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        java.util.ArrayDeque<Future<File>> queue = new java.util.ArrayDeque<>();
        long written = 0;
        try {
            for (int i = 0, next = 0; i < urls.size(); i++) {
                gate.check();
                // Twice the threads queued: every thread stays busy while a part is appended.
                for (; next < urls.size() && next < i + 2 * THREADS; next++) {
                    final String url = urls.get(next);
                    final File part = new File(dir, next + ".part");
                    queue.add(pool.submit(() -> fetchPart(http, url, part, net, gate)));
                }
                File part = queue.poll().get();
                try (InputStream in = new FileInputStream(part)) {
                    pipe(in, out, null);
                }
                written += part.length();
                part.delete();
                onWritten.accept(i + 1, written);
            }
            return written;
        } finally {
            pool.shutdownNow();
            File[] left = dir.listFiles();
            for (int i = 0; left != null && i < left.length; i++) {
                left[i].delete();
            }
        }
    }

    /** One segment into {@code part}, three tries: one dropped request shouldn't cost the whole VOD. */
    private static File fetchPart(OkHttpClient http, String url, File part, AtomicLong net, Gate gate) throws Exception {
        for (int attempt = 1; ; attempt++) {
            gate.check();
            try (OutputStream out = new FileOutputStream(part)) {
                copy(http, url, out, net);
                return part;
            } catch (IOException e) {
                if (attempt == 3) {
                    throw e;
                }
                Thread.sleep(1000L * attempt);
            }
        }
    }

    private static void copy(OkHttpClient http, String url, OutputStream out, AtomicLong net) throws IOException {
        Response response = http.newCall(new Request.Builder().url(url).build()).execute();
        if (!response.isSuccessful() && url.contains("-unmuted")) {
            response.close();
            response = http.newCall(new Request.Builder().url(url.replace("-unmuted", "-muted")).build()).execute();
        }
        try {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code() + " for a segment");
            }
            try (InputStream in = response.body().byteStream()) {
                pipe(in, out, net);
            }
        } finally {
            response.close();
        }
    }

    private static void pipe(InputStream in, OutputStream out, AtomicLong counted) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        for (int n; (n = in.read(buffer)) > 0; ) {
            out.write(buffer, 0, n);
            if (counted != null) {
                counted.addAndGet(n);
            }
        }
    }

    /** An arrow into a tray, white. */
    private static final class Icon extends Drawable {
        private final int size;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Icon(int size) {
            this.size = size;
            paint.setColor(0xFFFFFFFF);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(size / 11f);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float s = size / 24f;
            canvas.save();
            canvas.translate(b.exactCenterX() - size / 2f, b.exactCenterY() - size / 2f);
            Path p = new Path();
            p.moveTo(12 * s, 4 * s);
            p.lineTo(12 * s, 15 * s);
            p.moveTo(7 * s, 10 * s);
            p.lineTo(12 * s, 15 * s);
            p.lineTo(17 * s, 10 * s);
            p.moveTo(5 * s, 19 * s);
            p.lineTo(19 * s, 19 * s);
            canvas.drawPath(p, paint);
            canvas.restore();
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
