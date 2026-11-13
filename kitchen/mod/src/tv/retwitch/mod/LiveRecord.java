package tv.retwitch.mod;

import android.content.ContentValues;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.format.Formatter;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tv.retwitch.mod.ads.SsaiAdBlock;
import tv.retwitch.mod.ads.SsaiSwapEngine;

/**
 * Live stream recording ({@link Toggles#LIVE_RECORD}) and VOD downloads: one .ts per recording in Downloads/ReTwitch,
 * kept alive by {@link RecordService}, with its folder in {@link Records} (what was written, segment by segment). Sources:
 * <ul>
 *   <li>the stream's own archive, the VOD Twitch writes while the stream runs (GQL {@code stream.archiveVideo}):
 *       an EVENT playlist ~5 s behind the stream, with no ads and the source rendition even from RU, that keeps
 *       every segment from the start -- so a recording can begin at the start, pause and survive a dead network,
 *       catching up afterwards. Its segments go through {@link VodDownload#appendSegments} a batch at a time;</li>
 *   <li>a VOD: the same loop on a finished playlist;</li>
 *   <li>without an archive (VODs off, or {@link Toggles#LIVE_RECORD_ARCHIVE} off): the live variant playlist with
 *       an anonymous token (through the selected stream proxy, if any), polled every 2 s, ad segments cut
 *       ({@link SsaiAdBlock}). Its window is ~6 s, so whatever the recorder misses is gone.</li>
 * </ul>
 * The file is published right away (no {@code IS_PENDING}: Android deletes pending items after a week, and a killed
 * recording would leave one); a .ts cut anywhere still plays. A recording cut short (killed process, no network,
 * stopped) continues from its folder ({@link #resume}): the archive and a VOD lose nothing, a live stream only while
 * it is still the same stream, with the time in between lost. The chat goes along with {@link ChatTape}.
 */
final class LiveRecord {

    static final String TAG = "RTRecord";
    /** Auto-record's handled streams and 0.5.14's finished list (state, not settings: {@link SettingsBackup} leaves it out). */
    static final String STATE = "retwitch_record_state";
    /** Caught up with the archive: a new 10 s segment appears about this often. */
    private static final long ARCHIVE_POLL_MS = 10_000;
    private static final long LIVE_POLL_MS = 2_000;
    /** Archive segments per {@link VodDownload#appendSegments} call, so a stop or pause lands between batches too. */
    private static final int BATCH = 48;
    /** No network this long ends a recording. */
    private static final long GIVE_UP_MS = 30 * 60_000L;
    /** The stream went offline and nothing new came for this long: the recording is complete. */
    private static final long ENDED_MS = 3 * 60_000L;
    private static final long SAVE_MS = 15_000;
    private static final Pattern HLS_LOGIN = Pattern.compile("/hls/([A-Za-z0-9_]+)\\.m3u8");
    private static final Pattern HEIGHT = Pattern.compile("(\\d{3,4})p|x(\\d{3,4})");
    private static final Pattern TIME = Pattern.compile(
            "(\\d{4})-(\\d\\d)-(\\d\\d)T(\\d\\d):(\\d\\d):(\\d\\d)(?:\\.(\\d+))?(Z|[+-]\\d\\d:?\\d\\d)?");

    static final List<Rec> ACTIVE = new CopyOnWriteArrayList<>();

    private LiveRecord() {
    }

    /** One recording; its fields are read by the notification, the settings cards, the REC label and meta.json. */
    static final class Rec {
        final String id;
        /** The rendition label picked (as {@link VodDownload#variants} names it), matched again on a new master. */
        final String quality;
        final boolean fromStart;
        /** Wall clock to stop at, 0 = when the stream ends. */
        final long stopAt;
        final boolean auto;
        /** Continues a recording from its folder. */
        final boolean resumed;
        final Object gate = new Object();
        /** The channel; a VOD's owner once known. */
        volatile String login;
        /** {@link Records#KIND_ARCHIVE}, {@code KIND_LIVE} or {@code KIND_VOD}; null until a live one has its source. */
        volatile String kind;
        volatile String display;
        volatile String title;
        volatile String rendition;
        /** Archive or VOD: a playlist that keeps every segment, so pause and catching up make sense. */
        volatile boolean archive;
        volatile boolean paused;
        volatile boolean stopped;
        /** The source ended on its own (end of the playlist, stream offline, the timer). */
        volatile boolean ended;
        volatile long bytes;
        /** Seconds of video written. */
        volatile double seconds;
        /** Archive seconds not written yet. */
        volatile double behind;
        /** Seconds missing between segments (cut ads, time between a cut and its continuation). */
        volatile double lost;
        /** Why it is waiting instead of writing, null while it writes. */
        volatile String problem;
        volatile String file;
        volatile String uri;
        volatile String streamId;
        volatile String vodId;
        volatile String channelId;
        /** The media playlist being recorded. */
        volatile String variant;
        /** Playlist index of the first segment written (archive, VOD), -1 before. */
        volatile int first = -1;
        /** A VOD fragment (feedback #14): its start and end in the VOD, ms, -1 = the whole video. Snapped to segments. */
        volatile long clipFrom = -1;
        volatile long clipTo = -1;
        volatile int count;
        volatile long initBytes;
        /** Source position of the first segment and just past the last one (see {@link Records}). */
        volatile long firstPosition = -1;
        volatile long lastEnd;
        volatile long at;
        /** The chat goes along ({@link ChatTape}), and where it got to. */
        volatile boolean chat;
        volatile String chatCursor;
        volatile long chatLastT = -1;
        volatile int chatLines;
        volatile File dir;
        private long savedAt;

        Rec(String login, String quality, boolean fromStart, long stopAt, boolean auto) {
            this(System.currentTimeMillis() + "-" + login.toLowerCase(Locale.ROOT), login, quality, fromStart, stopAt,
                    auto, false);
        }

        private Rec(String id, String login, String quality, boolean fromStart, long stopAt, boolean auto,
                boolean resumed) {
            this.id = id;
            this.login = login.toLowerCase(Locale.ROOT);
            this.quality = quality == null ? "" : quality;
            this.fromStart = fromStart;
            this.stopAt = stopAt;
            this.auto = auto;
            this.resumed = resumed;
            this.display = login;
            this.at = System.currentTimeMillis();
        }

        boolean isVod() {
            return Records.KIND_VOD.equals(kind);
        }

        /** For {@link VodDownload#appendSegments}: waits while paused, ends the batch on stop. */
        void check() throws InterruptedException {
            synchronized (gate) {
                while (paused && !stopped) {
                    gate.wait();
                }
            }
            if (stopped) {
                throw new CancellationException();
            }
        }

        /** Sleeps unless stopped first. */
        void sleep(long ms) throws InterruptedException {
            synchronized (gate) {
                if (!stopped && ms > 0) {
                    gate.wait(ms);
                }
            }
        }

        private void wake() {
            synchronized (gate) {
                gate.notifyAll();
            }
        }
    }

    /** A VOD download of the rendition {@code label} ({@code variant} is its media playlist). */
    static Rec vod(String vodId, String variant, String label) {
        Rec rec = new Rec(System.currentTimeMillis() + "-v" + (vodId != null ? vodId : "0"), "", label, true, 0,
                false, false);
        rec.kind = Records.KIND_VOD;
        rec.archive = true;
        rec.vodId = vodId;
        rec.variant = variant;
        rec.rendition = label;
        rec.display = "VOD";
        return rec;
    }

    /** A cut recording, back from its folder. */
    static Rec fromMeta(Context context, JSONObject m) {
        Rec rec = new Rec(m.optString("id"), m.optString("login"), m.optString("quality"), true, 0,
                m.optBoolean("auto"), true);
        rec.kind = m.optString("kind");
        rec.archive = !Records.KIND_LIVE.equals(rec.kind);
        rec.display = m.optString("display", rec.login);
        rec.title = m.optString("title", null);
        rec.rendition = m.optString("rendition", null);
        rec.file = m.optString("name", null);
        rec.uri = m.optString("uri", null);
        rec.streamId = m.optString("streamId", null);
        rec.vodId = m.optString("vodId", null);
        rec.channelId = m.optString("channelId", null);
        rec.variant = m.optString("variant", null);
        rec.first = m.optInt("first", -1);
        rec.firstPosition = m.optLong("firstPosition", -1);
        rec.clipFrom = m.optLong("clipFrom", -1);
        rec.clipTo = m.optLong("clipTo", -1);
        rec.initBytes = m.optLong("initBytes");
        rec.lost = m.optDouble("lost", 0);
        rec.at = m.optLong("at", rec.at);
        rec.chat = m.optBoolean("chat");
        rec.chatCursor = m.optString("chatCursor", null);
        rec.chatLastT = m.optLong("chatLastT", -1);
        rec.chatLines = m.optInt("chatLines");
        rec.dir = Records.dir(context, rec.id);
        return rec;
    }

    static JSONObject meta(Rec rec, String state) throws JSONException {
        return new JSONObject().put("id", rec.id).put("kind", rec.kind).put("login", rec.login)
                .put("display", rec.display).put("title", rec.title).put("name", rec.file).put("uri", rec.uri)
                .put("channelId", rec.channelId).put("vodId", rec.vodId).put("streamId", rec.streamId)
                .put("quality", rec.quality).put("rendition", rec.rendition).put("variant", rec.variant)
                .put("first", rec.first).put("firstPosition", rec.firstPosition).put("initBytes", rec.initBytes)
                .put("clipFrom", rec.clipFrom).put("clipTo", rec.clipTo)
                .put("bytes", rec.bytes).put("seconds", (long) rec.seconds).put("lost", (long) rec.lost)
                .put("behind", (long) rec.behind).put("lastEnd", rec.lastEnd)
                .put("at", rec.at).put("updated", System.currentTimeMillis()).put("state", state)
                .put("auto", rec.auto).put("problem", rec.problem).put("chat", rec.chat)
                .put("chatCursor", rec.chatCursor).put("chatLastT", rec.chatLastT).put("chatLines", rec.chatLines);
    }

    private static void save(Rec rec, String state) {
        if (rec.dir == null) {
            return;
        }
        try {
            Records.write(rec.dir, meta(rec, state));
            rec.savedAt = System.currentTimeMillis();
        } catch (JSONException e) {
            Log.w(TAG, "meta failed", e);
        }
    }

    /** What the sheet offers for a channel: null when it is not live. */
    static final class Info {
        String display;
        String channelId;
        String title;
        String streamId;
        long createdAt;
        /** The archive VOD's id, null without one (or with the archive toggle off). */
        String vodId;
        List<String[]> options;
    }

    // ---- control ----

    /** The channel's running stream recording (not a VOD download). */
    static Rec find(String login) {
        if (login == null) {
            return null;
        }
        for (Rec rec : ACTIVE) {
            if (!rec.isVod() && rec.login.equalsIgnoreCase(login)) {
                return rec;
            }
        }
        return null;
    }

    /** A download of this VOD is running. */
    static boolean downloading(String vodId) {
        for (Rec rec : ACTIVE) {
            if (rec.isVod() && vodId != null && vodId.equals(rec.vodId)) {
                return true;
            }
        }
        return false;
    }

    static Rec byId(String id) {
        for (Rec rec : ACTIVE) {
            if (rec.id.equals(id)) {
                return rec;
            }
        }
        return null;
    }

    static void start(Context context, final Rec rec) {
        if (byId(rec.id) != null || !rec.isVod() && find(rec.login) != null) {
            return;
        }
        ACTIVE.add(rec);
        final Context app = context.getApplicationContext();
        RecordService.refresh(app);
        new Thread(() -> run(app, rec), "RTRecord-" + rec.id).start();
    }

    /** Continues a cut recording from where its folder says it got to. */
    static void resume(Context context, JSONObject meta) {
        start(context, fromMeta(context, meta));
    }

    /** Running in the folder but not in this process: the process died while recording. */
    static boolean interrupted(JSONObject meta) {
        return Records.RUNNING.equals(meta.optString("state")) && byId(meta.optString("id")) == null;
    }

    /** The recording of {@code streamId} a dead process left, for auto-record to continue; null if none. */
    static JSONObject cutFor(Context context, String streamId) {
        for (JSONObject meta : Records.all(context)) {
            if (streamId.equals(meta.optString("streamId")) && interrupted(meta)) {
                return meta;
            }
        }
        return null;
    }

    static void stop(Rec rec) {
        rec.stopped = true;
        rec.wake();
        RecordService.refresh(AppContext.get());
    }

    static void stopAll() {
        for (Rec rec : ACTIVE) {
            stop(rec);
        }
    }

    /** Pause only means something for the archive: it keeps every segment for later. */
    static void togglePause(Rec rec) {
        if (!rec.archive) {
            return;
        }
        rec.paused = !rec.paused;
        rec.wake();
        RecordService.refresh(AppContext.get());
    }

    /** The live channel the player shows: its usher URL (also inside our local proxy's), else the tracker's. */
    static String currentLogin() {
        String login = playerLogin();
        return login != null ? login : ChannelTracker.getCurrentChannelLogin();
    }

    /** The channel of the live usher master the player plays; null for a VOD, a clip or a proxy's own URL. */
    static String playerLogin() {
        try {
            Object player = PlayerHook.currentPlayer();
            Object path = player == null ? null : player.getClass().getMethod("getPath").invoke(player);
            if (path instanceof String) {
                Matcher m = HLS_LOGIN.matcher(Uri.decode((String) path));
                if (m.find()) {
                    return m.group(1).toLowerCase(Locale.ROOT);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "player path: " + t);
        }
        return null;
    }

    // ---- what the sheet shows ----

    /** The channel's stream and the renditions a recording would get. Network: not on the main thread. */
    static Info info(String login) throws Exception {
        JSONObject user = VodDownload.gql("query($s:String!){user(login:$s){id displayName stream{id title createdAt "
                + "archiveVideo{id}}}}", new JSONObject().put("s", login)).optJSONObject("user");
        JSONObject stream = user == null ? null : user.optJSONObject("stream");
        if (stream == null) {
            return null;
        }
        Info info = new Info();
        info.display = user.optString("displayName", login);
        info.channelId = user.optString("id", null);
        info.title = stream.optString("title");
        info.streamId = stream.optString("id");
        info.createdAt = parseTime(stream.optString("createdAt"));
        JSONObject archive = stream.optJSONObject("archiveVideo");
        if (archive != null && Toggles.on(Toggles.LIVE_RECORD_ARCHIVE)) {
            info.options = VodDownload.freshVodOptions(archive.optString("id"));
            if (info.options != null) {
                info.vodId = archive.optString("id");
            }
        }
        if (info.options == null) {
            info.options = liveOptions(login);
        }
        return info.options == null ? null : info;
    }

    /** "2026-11-08T12:00:01.500Z" (also without the fraction, or with an offset) -> epoch ms; 0 if unreadable. */
    static long parseTime(String iso) {
        Matcher m = iso == null ? null : TIME.matcher(iso.trim());
        if (m == null || !m.lookingAt()) {
            return 0;
        }
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        c.clear();
        c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
        long ms = c.getTimeInMillis();
        if (m.group(7) != null) {
            ms += Math.round(Double.parseDouble("0." + m.group(7)) * 1000);
        }
        String zone = m.group(8);
        if (zone != null && !"Z".equals(zone)) {
            String digits = zone.replace(":", "");
            int minutes = Integer.parseInt(digits.substring(1, 3)) * 60 + Integer.parseInt(digits.substring(3, 5));
            ms -= (zone.charAt(0) == '-' ? -1 : 1) * minutes * 60_000L;
        }
        return ms;
    }

    /** The live master with an anonymous token, through the selected stream proxy first; null if neither answers. */
    static List<String[]> liveOptions(String login) {
        Uri usher = Uri.parse("https://usher.ttvnw.net/api/channel/hls/" + login + ".m3u8").buildUpon()
                .appendQueryParameter("allow_source", "true")
                .appendQueryParameter("allow_audio_only", "true")
                .appendQueryParameter("fast_bread", "true")
                .appendQueryParameter("playlist_include_framerate", "true")
                .appendQueryParameter("player_backend", "mediaplayer")
                // h264 only: an HEVC/AV1 rendition is fMP4, one init per file, and players open .ts more often.
                .appendQueryParameter("supported_codecs", "h264")
                .appendQueryParameter("p", String.valueOf((int) (Math.random() * 999_999)))
                .build();
        Uri signed = SsaiSwapEngine.withAnonymousToken(usher);
        if (signed == null) {
            return null;
        }
        Uri proxied = ProxySettings.maybeGetProxyUri(signed, false);
        for (Uri uri : proxied != null ? new Uri[]{proxied, signed} : new Uri[]{signed}) {
            try {
                return VodDownload.variants(uri.toString(), VodDownload.fetch(VodDownload.HTTP, uri.toString()));
            } catch (IOException e) {
                Log.w(TAG, "live master failed: " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * The option labelled {@code quality}; else, with a height in it, the best one not taller; audio for audio;
     * else the first (Twitch lists the best first).
     */
    static String[] pick(List<String[]> options, String quality) {
        if (quality != null && !quality.isEmpty()) {
            for (String[] option : options) {
                if (option[0].equals(quality)) {
                    return option;
                }
            }
            int height = height(quality);
            for (String[] option : options) {
                int h = height(option[0]);
                if (height > 0 ? h > 0 && h <= height : h == 0) {
                    return option;
                }
            }
        }
        return options.get(0);
    }

    /** "720p60 · 1280x720" -> 720; 0 for audio. */
    static int height(String label) {
        Matcher m = HEIGHT.matcher(label);
        int best = 0;
        while (m.find()) {
            best = Math.max(best, Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2)));
        }
        return best;
    }

    // ---- the recording ----

    private static void run(Context app, Rec rec) {
        OutputStream out = null;
        Uri created = null;
        File parts = new File(app.getCacheDir(), "retwitch-record-" + rec.id);
        ChatTape chat = null;
        boolean failed = false;
        AtomicLong net = new AtomicLong();
        try {
            String url;
            String first;
            parts.mkdirs();
            if (rec.resumed) {
                Records.Index index = Records.index(rec.dir);
                rec.count = index.count;
                rec.bytes = rec.initBytes + index.totalBytes;
                rec.seconds = index.total;
                rec.lastEnd = index.end();
                String[] source = rec.archive ? archiveSource(rec) : liveSource(rec);
                if (source == null) {
                    return;
                }
                url = source[0];
                first = source[1];
                out = Records.openAt(app, Uri.parse(rec.uri), rec.bytes);
                Log.i(TAG, "continuing " + rec.file + " after " + rec.count + " segments");
            } else {
                if (rec.isVod()) {
                    describeVod(rec);
                } else {
                    Info info = info(rec.login);
                    if (info == null) {
                        rec.problem = Strings.pick("канал не в эфире", "the channel is offline");
                        return;
                    }
                    rec.display = info.display;
                    rec.title = info.title;
                    rec.channelId = info.channelId;
                    rec.streamId = info.streamId;
                    rec.vodId = info.vodId;
                    rec.archive = info.vodId != null;
                    rec.kind = rec.archive ? Records.KIND_ARCHIVE : Records.KIND_LIVE;
                    String[] variant = pick(info.options, rec.quality);
                    rec.rendition = variant[0];
                    rec.variant = variant[1];
                }
                url = rec.variant;
                first = VodDownload.fetch(VodDownload.HTTP, url);
                if (rec.clipTo >= 0) {
                    snapClip(rec, segments(url, first)); // the name says what the file holds
                    String range = clock(rec.clipFrom / 1000.0) + "-" + clock(rec.clipTo / 1000.0);
                    rec.title = rec.title == null || rec.title.isEmpty() ? range : rec.title + " " + range;
                }
                String init = initOf(url, first);
                rec.file = VodDownload.fileName(new String[]{rec.display, rec.title}) + (init != null ? ".mp4" : ".ts");
                created = createFile(app, rec.file);
                rec.uri = created.toString();
                out = Records.openAt(app, created, 0);
                rec.dir = Records.dir(app, rec.id);
                rec.dir.mkdirs();
                if (init != null) {
                    rec.initBytes = VodDownload.appendSegments(VodDownload.SEGMENTS, Collections.singletonList(init),
                            parts, out, net, rec::check, (n, written) -> { });
                    rec.bytes = rec.initBytes;
                }
                Log.i(TAG, "recording " + rec.display + " to " + rec.file + " (" + rec.kind + ")");
            }
            save(rec, Records.RUNNING);
            if (rec.chat || Toggles.on(Toggles.LIVE_RECORD_CHAT)) {
                rec.chat = true;
                chat = ChatTape.start(rec);
            }
            if (rec.archive) {
                archive(app, rec, url, first, out, parts, net);
            } else {
                live(rec, url, first, out, parts, net);
            }
        } catch (Throwable t) {
            // A stop ends a batch with CancellationException, from a segment thread wrapped in ExecutionException;
            // what was written stays.
            if (!rec.stopped) {
                failed = true;
                Log.w(TAG, "recording " + rec.id + " failed", t);
                CrashReporter.report(TAG, "recording failed", t);
                if (rec.problem == null) {
                    rec.problem = Strings.pick("ошибка", "failed");
                }
            }
        } finally {
            if (chat != null) {
                chat.finish(rec.ended && !failed && !rec.stopped);
            }
            try {
                if (out != null) {
                    out.close();
                }
                if (!rec.resumed && rec.count == 0) {
                    if (created != null) {
                        Records.delete(app, new JSONObject().put("id", rec.id).put("uri", created.toString()));
                    }
                } else {
                    save(rec, rec.ended ? Records.DONE : failed ? Records.FAILED : Records.STOPPED);
                }
            } catch (Throwable t) {
                Log.w(TAG, "closing " + rec.file + " failed", t);
            }
            File[] left = parts.listFiles();
            for (int i = 0; left != null && i < left.length; i++) {
                left[i].delete();
            }
            parts.delete();
            ACTIVE.remove(rec);
            if (!rec.isVod()) {
                AutoRecord.handled(app, rec.streamId); // a stream recorded by hand isn't auto-recorded again
            }
            RecordService.finished(app, rec);
            Log.i(TAG, "recording " + rec.id + " ended: " + rec.bytes + " bytes, " + (int) rec.seconds + " s");
        }
    }

    private static Uri createFile(Context app, String name) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ReTwitch");
            Uri item = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (item == null) {
                throw new IOException("MediaStore refused " + name);
            }
            return item;
        }
        File target = new File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name);
        if (!target.exists() && !target.createNewFile()) {
            throw new IOException("can't create " + target);
        }
        return Uri.fromFile(target);
    }

    /** A VOD's channel and title, for the file name and the chat's badges. */
    private static void describeVod(Rec rec) {
        if (rec.vodId == null) {
            return;
        }
        try {
            JSONObject video = VodDownload.gql("query($s:ID!){video(id:$s){title owner{id login displayName}}}",
                    rec.vodId).optJSONObject("video");
            JSONObject owner = video == null ? null : video.optJSONObject("owner");
            if (video != null) {
                rec.title = video.optString("title");
            }
            if (owner != null) {
                rec.display = owner.optString("displayName", rec.display);
                rec.login = owner.optString("login", "").toLowerCase(Locale.ROOT);
                rec.channelId = owner.optString("id", null);
            }
        } catch (Exception e) {
            Log.w(TAG, "VOD lookup failed: " + e);
        }
    }

    /** {URL, text} of the archive's or VOD's playlist to continue, the saved one or a fresh master's. */
    private static String[] archiveSource(Rec rec) throws Exception {
        try {
            return new String[]{rec.variant, VodDownload.fetch(VodDownload.HTTP, rec.variant)};
        } catch (IOException e) {
            Log.w(TAG, rec.id + ": saved playlist failed: " + e.getMessage());
            List<String[]> options = VodDownload.freshVodOptions(rec.vodId);
            if (options == null) {
                rec.problem = Strings.pick("нет сети или VOD удалено с Twitch", "no network, or the VOD is gone from Twitch");
                throw e;
            }
            rec.variant = pick(options, rec.quality)[1];
            return new String[]{rec.variant, VodDownload.fetch(VodDownload.HTTP, rec.variant)};
        }
    }

    /** {URL, text} of the live playlist to continue; null (and ended) when the stream is over. */
    private static String[] liveSource(Rec rec) throws Exception {
        JSONObject user = VodDownload.gql("query($s:String!){user(login:$s){stream{id}}}",
                new JSONObject().put("s", rec.login)).optJSONObject("user");
        JSONObject stream = user == null ? null : user.optJSONObject("stream");
        if (stream == null || rec.streamId != null && !rec.streamId.equals(stream.optString("id"))) {
            rec.ended = true;
            rec.problem = Strings.pick("эфир закончился", "the stream has ended");
            return null;
        }
        List<String[]> options = liveOptions(rec.login);
        if (options == null) {
            throw new IOException("no live master");
        }
        String url = pick(options, rec.quality)[1];
        return new String[]{url, VodDownload.fetch(VodDownload.HTTP, url)};
    }

    /** The archive from the first segment (or the newest one), until it ends, the stream goes offline or the timer. */
    private static void archive(Context app, Rec rec, String url, String text, OutputStream out, File parts,
            AtomicLong net) throws Exception {
        int next = rec.first >= 0 ? rec.first + rec.count : -1;
        long grew = System.currentTimeMillis();
        long checked = 0;
        long failing = 0;
        while (!rec.stopped) {
            rec.check();
            if (rec.auto && AutoRecord.wifiOnly() && metered(app)) {
                rec.problem = Strings.pick("ждёт Wi-Fi", "waiting for Wi-Fi");
                rec.sleep(15_000);
                continue;
            }
            if (text == null) {
                try {
                    text = VodDownload.fetch(VodDownload.HTTP, url);
                    failing = 0;
                } catch (IOException e) {
                    failing = failed(rec, failing, e);
                    rec.sleep(10_000);
                    continue;
                }
            }
            List<String[]> segments = segments(url, text);
            long[] positions = offsets(segments);
            boolean ended = text.contains("#EXT-X-ENDLIST");
            text = null;
            rec.problem = null;
            if (next < 0) {
                if (rec.clipTo >= 0) {
                    snapClip(rec, segments);
                    next = segmentAt(positions, rec.clipFrom);
                } else {
                    next = rec.fromStart ? 0 : Math.max(0, segments.size() - 1);
                }
                rec.first = next;
                save(rec, Records.RUNNING); // a resume counts from here
            }
            int limit = rec.clipTo >= 0 ? Math.min(segments.size(), segmentAfter(positions, rec.clipTo, rec.first))
                    : segments.size();
            if (next < limit) {
                int end = Math.min(limit, next + BATCH);
                append(rec, segments.subList(next, end), Arrays.copyOfRange(positions, next, end), out, parts, net);
                next = end;
                rec.behind = seconds(segments.subList(next, limit));
                grew = System.currentTimeMillis();
                continue; // catching up: no wait
            }
            rec.behind = 0;
            long now = System.currentTimeMillis();
            if (ended || timeUp(rec) || limit < segments.size()) {
                rec.ended = true;
                return;
            }
            if (now - grew > 60_000 && now - checked > 60_000) {
                checked = now;
                if (!isLive(rec) && now - grew > ENDED_MS) {
                    rec.ended = true;
                    return;
                }
            }
            rec.sleep(ARCHIVE_POLL_MS);
        }
    }

    /** The live window every 2 s; three failures in a row get a new master (token, session or the stream ended). */
    private static void live(Rec rec, String url, String text, OutputStream out, File parts, AtomicLong net)
            throws Exception {
        Set<String> seen = new LinkedHashSet<>();
        int failures = 0;
        long failing = 0;
        long offline = 0;
        while (!rec.stopped) {
            long started = System.currentTimeMillis();
            if (text == null) {
                try {
                    text = VodDownload.fetch(VodDownload.HTTP, url);
                    failures = 0;
                    failing = 0;
                } catch (IOException e) {
                    failing = failed(rec, failing, e);
                    if (++failures >= 3) {
                        List<String[]> options = liveOptions(rec.login);
                        if (options != null) {
                            url = pick(options, rec.quality)[1];
                            offline = 0;
                        } else if (!isLive(rec)) {
                            offline = offline == 0 ? started : offline;
                            if (started - offline > ENDED_MS) {
                                rec.ended = true;
                                return;
                            }
                        }
                    }
                }
            }
            if (text != null) {
                rec.problem = null;
                boolean ended = text.contains("#EXT-X-ENDLIST");
                if (SsaiAdBlock.isAdBreak(text)) {
                    text = SsaiAdBlock.stripAdSegments(text);
                }
                List<String[]> fresh = unseen(segments(url, text), seen);
                text = null;
                append(rec, fresh, liveTimes(fresh, rec.lastEnd, started), out, parts, net);
                if (ended) {
                    rec.ended = true;
                    return;
                }
            }
            if (timeUp(rec)) {
                rec.ended = true;
                return;
            }
            rec.sleep(LIVE_POLL_MS - (System.currentTimeMillis() - started));
        }
    }

    /** Notes a failed playlist fetch; returns when the failures began. Past {@link #GIVE_UP_MS} it gives up. */
    private static long failed(Rec rec, long since, IOException e) throws IOException {
        long now = System.currentTimeMillis();
        Log.w(TAG, rec.id + ": playlist failed: " + e.getMessage());
        rec.problem = Strings.pick("нет сети, повтор", "no network, retrying");
        if (since != 0 && now - since > GIVE_UP_MS) {
            throw e;
        }
        return since == 0 ? now : since;
    }

    private static boolean timeUp(Rec rec) {
        return rec.stopAt != 0 && System.currentTimeMillis() >= rec.stopAt;
    }

    /** Whether the stream the recording began on is still the channel's live one; true when unknown. */
    private static boolean isLive(Rec rec) {
        try {
            JSONObject user = VodDownload.gql("query($s:String!){user(login:$s){stream{id}}}",
                    new JSONObject().put("s", rec.login)).optJSONObject("user");
            JSONObject stream = user == null ? null : user.optJSONObject("stream");
            return stream != null && (rec.streamId == null || rec.streamId.equals(stream.optString("id")));
        } catch (Exception e) {
            return true;
        }
    }

    /** Writes {@code segments} (source positions alongside) to the file and each to the folder's segment list. */
    private static void append(final Rec rec, final List<String[]> segments, final long[] positions, OutputStream out,
            File parts, AtomicLong net) throws Exception {
        if (segments.isEmpty()) {
            return;
        }
        List<String> urls = new ArrayList<>(segments.size());
        for (String[] segment : segments) {
            urls.add(segment[0]);
        }
        final long[] before = {0};
        VodDownload.appendSegments(VodDownload.SEGMENTS, urls, parts, out, net, rec::check, (done, written) -> {
            int i = done - 1;
            double duration = parseSeconds(segments.get(i)[1]);
            long bytes = written - before[0];
            before[0] = written;
            long position = positions[i];
            if (rec.lastEnd > 0 && position - rec.lastEnd > Records.GAP_MS) {
                rec.lost += (position - rec.lastEnd) / 1000.0;
            }
            try {
                Records.appendSegment(rec.dir, duration, bytes, position);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            rec.bytes += bytes;
            rec.seconds += duration;
            rec.count++;
            rec.lastEnd = position + Math.round(duration * 1000);
            if (rec.firstPosition < 0) {
                rec.firstPosition = position;
            }
        });
        if (System.currentTimeMillis() - rec.savedAt > SAVE_MS) {
            save(rec, Records.RUNNING);
        }
    }

    static boolean metered(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm == null || cm.isActiveNetworkMetered();
        } catch (Throwable t) {
            return true;
        }
    }

    // ---- playlists (pure; LiveRecordSelfCheck) ----

    /** A media playlist's segments as {absolute URL, EXTINF seconds, PROGRAM-DATE-TIME or ""}, in order. */
    static List<String[]> segments(String base, String text) {
        List<String[]> out = new ArrayList<>();
        String duration = "0";
        String time = "";
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXTINF:")) {
                int comma = line.indexOf(',');
                duration = line.substring(8, comma < 0 ? line.length() : comma);
            } else if (line.startsWith("#EXT-X-PROGRAM-DATE-TIME:")) {
                time = line.substring(25);
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                out.add(new String[]{VodDownload.resolve(base, line), duration, time});
                duration = "0";
                time = "";
            }
        }
        return out;
    }

    /** The segment playing at {@code ms} (its start at or before it); 0 before the first. */
    static int segmentAt(long[] positions, long ms) {
        int at = 0;
        for (int i = 0; i < positions.length && positions[i] <= ms; i++) {
            at = i;
        }
        return at;
    }

    /** The first segment starting at or after {@code ms}, past {@code from}: the end of a fragment, exclusive. */
    static int segmentAfter(long[] positions, long ms, int from) {
        int i = from + 1;
        while (i < positions.length && positions[i] < ms) {
            i++;
        }
        return Math.min(i, positions.length);
    }

    /** A fragment snaps out to whole segments (again harmless): its chat and name follow what is written. */
    private static void snapClip(Rec rec, List<String[]> segments) {
        long[] positions = offsets(segments);
        int from = segmentAt(positions, rec.clipFrom);
        int stop = segmentAfter(positions, rec.clipTo, from);
        rec.clipFrom = positions.length == 0 ? 0 : positions[from];
        rec.clipTo = stop < positions.length ? positions[stop] : end(segments, positions);
    }

    /** Where the playlist ends, ms. */
    private static long end(List<String[]> segments, long[] positions) {
        return positions.length == 0 ? 0 : positions[positions.length - 1]
                + Math.round(parseSeconds(segments.get(segments.size() - 1)[1]) * 1000);
    }

    /** Each segment's start in a VOD playlist, in ms from its first one. */
    static long[] offsets(List<String[]> segments) {
        long[] out = new long[segments.size()];
        double t = 0;
        for (int i = 0; i < out.length; i++) {
            out[i] = Math.round(t * 1000);
            t += parseSeconds(segments.get(i)[1]);
        }
        return out;
    }

    /**
     * Live segments' wall clock from their PROGRAM-DATE-TIME; one without it follows the one before (the first,
     * the end of the last written, else {@code now}).
     */
    static long[] liveTimes(List<String[]> segments, long lastEnd, long now) {
        long[] out = new long[segments.size()];
        long next = lastEnd > 0 ? lastEnd : now;
        for (int i = 0; i < out.length; i++) {
            long time = parseTime(segments.get(i)[2]);
            out[i] = time > 0 ? time : next;
            next = out[i] + Math.round(parseSeconds(segments.get(i)[1]) * 1000);
        }
        return out;
    }

    /** The fMP4 init segment ({@code EXT-X-MAP}), absolute; null for .ts. */
    static String initOf(String base, String text) {
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            int at = line.indexOf("URI=\"");
            if (line.startsWith("#EXT-X-MAP:") && at >= 0) {
                return VodDownload.resolve(base, line.substring(at + 5, line.indexOf('"', at + 5)));
            }
        }
        return null;
    }

    /** Segments not written yet, remembered in {@code seen} (the last 200: a live window is a handful). */
    static List<String[]> unseen(List<String[]> segments, Set<String> seen) {
        List<String[]> fresh = new ArrayList<>();
        for (String[] segment : segments) {
            if (seen.add(segment[0])) {
                fresh.add(segment);
            }
        }
        while (seen.size() > 200) {
            seen.remove(seen.iterator().next());
        }
        return fresh;
    }

    static double seconds(List<String[]> segments) {
        double sum = 0;
        for (String[] segment : segments) {
            sum += parseSeconds(segment[1]);
        }
        return sum;
    }

    private static double parseSeconds(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- what the UI shows ----

    /** "1:02:03" / "02:03". */
    static String clock(double seconds) {
        long s = (long) seconds;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60);
    }

    /** "00:42:13 · 1,2 ГБ · 1080p60 · catching up, −6 min" (with the channel first when {@code named}). */
    static String line(Context context, Rec rec, boolean named) {
        StringBuilder b = new StringBuilder();
        if (named) {
            b.append(rec.display).append(" · ");
        }
        b.append(clock(rec.seconds)).append(" · ").append(Formatter.formatShortFileSize(context, rec.bytes));
        if (rec.rendition != null) {
            b.append(" · ").append(rec.rendition.split(" · ")[0]);
        }
        String state = state(rec);
        if (state != null) {
            b.append(" · ").append(state);
        }
        return b.toString();
    }

    private static String state(Rec rec) {
        if (rec.paused) {
            return Strings.pick("пауза", "paused");
        }
        if (rec.problem != null) {
            return rec.problem;
        }
        if (rec.isVod() && rec.behind > 0) {
            return percent(rec) + " %";
        }
        if (rec.archive && rec.behind >= 60) {
            return Strings.pickf("догоняет эфир, −%d мин", "catching up, −%d min", (int) (rec.behind / 60));
        }
        return null;
    }

    /** Archive: written of what there is; live: always full. */
    static int percent(Rec rec) {
        double total = rec.seconds + rec.behind;
        return !rec.archive || total <= 0 ? 100 : (int) (100 * rec.seconds / total);
    }

    // ---- the settings page's cards (one per running recording) ----

    static Rec at(int index) {
        List<Rec> now = new ArrayList<>(ACTIVE);
        return index < now.size() ? now.get(index) : null;
    }

    static String status(int index) {
        Rec rec = at(index);
        return rec == null ? null : (rec.isVod() ? rec.display + " · " + rec.title : rec.display) + "\n"
                + line(AppContext.get(), rec, false);
    }

    static int statusPercent(int index) {
        Rec rec = at(index);
        return rec == null ? 0 : percent(rec);
    }

    /** "Pause" / "Resume" for the archive, "" (no button) for live, null without a recording. */
    static String pauseLabel(int index) {
        Rec rec = at(index);
        return rec == null ? null : !rec.archive ? "" : rec.paused ? Strings.downloadResume() : Strings.downloadPause();
    }

    /** Opens a recording in the system's player; false when the file is gone or nothing plays it. */
    static boolean open(Context context, JSONObject meta) {
        try {
            Uri uri = Uri.parse(meta.getString("uri"));
            if ("file".equals(uri.getScheme())) {
                File file = new File(uri.getPath());
                if (!file.exists()) {
                    return false;
                }
                uri = ModUpdate.sharedUri(context, file);
            }
            String name = meta.optString("name");
            context.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW)
                    .setDataAndType(uri, name.endsWith(".mp4") ? "video/mp4" : "video/mp2t")
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "open failed: " + e);
            return false;
        }
    }
}
