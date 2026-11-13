package tv.retwitch.mod;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The mod's recordings and VOD downloads ({@link LiveRecord}) on disk, one folder each in
 * files/retwitch-records/&lt;id&gt;: meta.json (what it is, where the video is, how it ended), segments.tsv (a line per
 * segment written: seconds, bytes, source position) and chat.jsonl ({@link ChatTape}). The segment list lets a cut
 * recording continue -- the file is trimmed to its last whole segment -- and the player serve the file as HLS
 * ({@link RecordServer}). The video itself stays in Downloads/ReTwitch.
 * <p>
 * A source position is the segment's start in the VOD in ms (archive, VOD) or its {@code PROGRAM-DATE-TIME} (live);
 * chat lines carry the same clock (the comment's VOD offset, the IRC message's {@code tmi-sent-ts}), so the player maps
 * both onto the file's time the same way, gaps included.
 */
final class Records {

    private static final String TAG = "RTRecords";
    static final String KIND_ARCHIVE = "archive";
    static final String KIND_LIVE = "live";
    static final String KIND_VOD = "vod";
    /** A finished file from 0.5.14, before this folder existed: open and delete only. */
    static final String KIND_LEGACY = "legacy";
    static final String RUNNING = "running";
    static final String STOPPED = "stopped";
    static final String DONE = "done";
    static final String FAILED = "failed";
    static final String SEGMENTS = "segments.tsv";
    static final String CHAT = "chat.jsonl";
    private static final String META = "meta.json";
    /** Positions further apart than this between segments are a gap (cut ads, a dead process, a resumed stream). */
    static final long GAP_MS = 1500;

    private Records() {
    }

    static File root(Context context) {
        return new File(context.getFilesDir(), "retwitch-records");
    }

    static File dir(Context context, String id) {
        return new File(root(context), id);
    }

    static JSONObject read(File dir) {
        try {
            return new JSONObject(text(new File(dir, META)));
        } catch (Exception e) {
            return null;
        }
    }

    /** Replaces meta.json through a temporary file: a process killed mid-write keeps the old one. */
    static synchronized void write(File dir, JSONObject meta) {
        try {
            dir.mkdirs();
            File tmp = new File(dir, META + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(meta.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(new File(dir, META))) {
                throw new IOException("rename failed");
            }
        } catch (Exception e) {
            Log.w(TAG, "meta not saved in " + dir, e);
        }
    }

    /** Every recording, newest first; 0.5.14's list of finished files is moved in on the first call. */
    static List<JSONObject> all(Context context) {
        migrate(context);
        List<JSONObject> out = new ArrayList<>();
        File[] dirs = root(context).listFiles();
        for (int i = 0; dirs != null && i < dirs.length; i++) {
            JSONObject meta = read(dirs[i]);
            if (meta != null) {
                out.add(meta);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.optLong("at"), a.optLong("at")));
        return out;
    }

    private static void migrate(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(LiveRecord.STATE, Context.MODE_PRIVATE);
        String done = prefs.getString("done", null);
        if (done == null) {
            return;
        }
        try {
            JSONArray items = new JSONArray(done);
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String id = "legacy-" + item.optLong("at") + "-" + i;
                write(dir(context, id), item.put("id", id).put("kind", KIND_LEGACY).put("state", DONE));
            }
        } catch (Exception e) {
            Log.w(TAG, "0.5.14 list not moved", e);
        }
        prefs.edit().remove("done").apply();
    }

    /** One written segment, a line at a time: a line cut by a killed process is dropped by {@link #parseIndex}. */
    static void appendSegment(File dir, double seconds, long bytes, long position) throws IOException {
        try (OutputStream out = new FileOutputStream(new File(dir, SEGMENTS), true)) {
            out.write(String.format(Locale.ROOT, "%.3f\t%d\t%d\n", seconds, bytes, position)
                    .getBytes(StandardCharsets.UTF_8));
        }
    }

    static Index index(File dir) {
        try {
            return parseIndex(text(new File(dir, SEGMENTS)));
        } catch (IOException e) {
            return parseIndex("");
        }
    }

    /**
     * Opens the recording's file for writing at {@code length} (trimmed to it: a killed process may have left part
     * of a segment past the last indexed one). Works for MediaStore and file URIs alike.
     */
    static OutputStream openAt(Context context, Uri uri, long length) throws IOException {
        ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "rw");
        if (fd == null) {
            throw new IOException("can't open " + uri);
        }
        FileOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(fd);
        FileChannel channel = out.getChannel();
        if (channel.size() < length) {
            out.close();
            throw new IOException("the file is shorter than its segments: " + channel.size() + " < " + length);
        }
        channel.truncate(length);
        channel.position(length);
        return out;
    }

    /**
     * Deletes the video and the folder. False when Android refuses the video (an install before a reinstall made
     * it): the folder stays too, so the row does not lie.
     * shortcut: no system delete dialog for that case (MediaStore.createDeleteRequest needs an activity result),
     * the user deletes the file in Files; add it if testers hit this.
     */
    static boolean delete(Context context, JSONObject meta) {
        try {
            Uri uri = Uri.parse(meta.optString("uri"));
            if ("file".equals(uri.getScheme())) {
                File file = new File(uri.getPath());
                if (file.exists() && !file.delete()) {
                    return false;
                }
            } else if (!meta.optString("uri").isEmpty()) {
                context.getContentResolver().delete(uri, null, null); // 0 rows: already gone
            }
        } catch (SecurityException e) {
            Log.w(TAG, "video not deleted: " + e);
            return false;
        } catch (Exception e) {
            Log.w(TAG, "video delete failed: " + e);
        }
        File dir = dir(context, meta.optString("id"));
        File[] files = dir.listFiles();
        for (int i = 0; files != null && i < files.length; i++) {
            files[i].delete();
        }
        dir.delete();
        return true;
    }

    private static String text(File file) throws IOException {
        if (!file.exists()) {
            return "";
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = 0;
            for (int n; read < bytes.length && (n = in.read(bytes, read, bytes.length - read)) > 0; ) {
                read += n;
            }
            return new String(bytes, 0, read, StandardCharsets.UTF_8);
        }
    }

    // ---- pure (RecordsSelfCheck) ----

    /** The segments written, in file order. */
    static final class Index {
        final double[] seconds;
        final long[] bytes;
        final long[] position;
        final int count;
        /** Where each segment starts in the file: seconds and bytes (after the init segment). */
        final double[] start;
        final long[] offset;
        final double total;
        final long totalBytes;

        Index(List<double[]> rows) {
            count = rows.size();
            seconds = new double[count];
            bytes = new long[count];
            position = new long[count];
            start = new double[count];
            offset = new long[count];
            double t = 0;
            long b = 0;
            for (int i = 0; i < count; i++) {
                double[] row = rows.get(i);
                seconds[i] = row[0];
                bytes[i] = (long) row[1];
                position[i] = (long) row[2];
                start[i] = t;
                offset[i] = b;
                t += seconds[i];
                b += bytes[i];
            }
            total = t;
            totalBytes = b;
        }

        /** Source position just past the last segment, 0 without one. */
        long end() {
            return count == 0 ? 0 : position[count - 1] + Math.round(seconds[count - 1] * 1000);
        }

        /** Whether segment {@code i} doesn't follow the one before it. */
        boolean gapBefore(int i) {
            return i > 0 && Math.abs(position[i] - (position[i - 1] + Math.round(seconds[i - 1] * 1000))) > GAP_MS;
        }
    }

    /** segments.tsv, up to the first line that isn't whole. */
    static Index parseIndex(String text) {
        List<double[]> rows = new ArrayList<>();
        int at = 0;
        while (true) {
            int end = text.indexOf('\n', at);
            if (end < 0) {
                break;
            }
            String[] cells = text.substring(at, end).split("\t");
            at = end + 1;
            try {
                rows.add(new double[]{Double.parseDouble(cells[0]), Long.parseLong(cells[1]), Long.parseLong(cells[2])});
            } catch (RuntimeException e) {
                break;
            }
        }
        return new Index(rows);
    }

    /**
     * The file's time in seconds for a source position {@code t} (a chat line's clock); -1 before the first
     * segment. A position in a gap lands where the video picks up again.
     */
    static double fileSeconds(Index index, long t) {
        int lo = 0;
        int hi = index.count - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (index.position[mid] <= t) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (found < 0) {
            return -1;
        }
        double into = (t - index.position[found]) / 1000.0;
        return index.start[found] + Math.min(into, index.seconds[found]);
    }

    /** Chat lines the player keeps the clock of: past this many the rest is left out. */
    static final int MAX_CHAT_LINES = (1 << 21) - 1;

    /** The clock of a {@link ChatTape} line from its first bytes ({@code {"t":123,...}}); MIN_VALUE if it has none. */
    static long lineClock(byte[] head, int length) {
        String prefix = "{\"t\":";
        if (length <= prefix.length()) {
            return Long.MIN_VALUE;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (head[i] != prefix.charAt(i)) {
                return Long.MIN_VALUE;
            }
        }
        int i = prefix.length();
        boolean negative = head[i] == '-';
        if (negative) {
            i++;
        }
        long value = 0;
        int digits = 0;
        for (; i < length && head[i] >= '0' && head[i] <= '9'; i++, digits++) {
            value = value * 10 + (head[i] - '0');
        }
        return digits == 0 ? Long.MIN_VALUE : negative ? -value : value;
    }

    /**
     * The order of {@code n} times, earliest first, ties in their written order. Nearly sorted in practice (the
     * replay comes in order, IRC almost), so a sorted list costs one pass.
     */
    static int[] order(long[] times, int n) {
        int[] out = new int[n];
        boolean sorted = true;
        for (int i = 0; i < n; i++) {
            out[i] = i;
            sorted &= i == 0 || times[i - 1] <= times[i];
        }
        if (sorted) {
            return out;
        }
        long[] keys = new long[n];
        for (int i = 0; i < n; i++) {
            keys[i] = (Math.max(0, times[i]) << 21) | i; // n <= MAX_CHAT_LINES, times well under 2^42 ms
        }
        java.util.Arrays.sort(keys);
        for (int i = 0; i < n; i++) {
            out[i] = (int) (keys[i] & MAX_CHAT_LINES);
        }
        return out;
    }

    /** How many of the first {@code n} sorted {@code times} are at or before {@code t}. */
    static int upTo(long[] times, int n, long t) {
        int lo = 0;
        int hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= t) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * The recording as an HLS VOD playlist over the local server: segment {@code i} is {@code s/<i>} (bytes of the
     * file), fMP4 gets its init segment; a gap starts a discontinuity, its timestamps jump.
     */
    static String playlist(Index index, boolean fmp4) {
        double longest = 1;
        for (int i = 0; i < index.count; i++) {
            longest = Math.max(longest, index.seconds[i]);
        }
        StringBuilder b = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:").append(fmp4 ? 7 : 3)
                .append("\n#EXT-X-TARGETDURATION:").append((int) Math.ceil(longest))
                .append("\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n");
        if (fmp4) {
            b.append("#EXT-X-MAP:URI=\"init.mp4\"\n");
        }
        for (int i = 0; i < index.count; i++) {
            if (index.gapBefore(i)) {
                b.append("#EXT-X-DISCONTINUITY\n");
            }
            b.append(String.format(Locale.ROOT, "#EXTINF:%.3f,\ns/%d%s\n", index.seconds[i], i, fmp4 ? ".mp4" : ".ts"));
        }
        return b.append("#EXT-X-ENDLIST\n").toString();
    }
}
