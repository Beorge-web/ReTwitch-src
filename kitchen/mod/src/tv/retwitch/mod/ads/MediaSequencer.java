package tv.retwitch.mod.ads;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Keeps a stream's media playlists consistent for the player while VAFT
 * edits them (Thream does the same): ad segments are dropped, sources switch
 * (primary / clean backup), yet the player must see
 * <ul>
 *   <li>one numbering across the variants of a master: the player carries its
 *       position over a quality switch by number, so a variant numbered on its
 *       own stalls it when it comes back after N seconds, N/2 segments behind
 *       (it waits for the numbers to catch up), and a variant's numbers are
 *       shifted by whatever was cut while another one played;</li>
 *   <li>a {@code MEDIA-SEQUENCE} that numbers the segments actually listed --
 *       stripping leading ad segments without moving it relabels live
 *       segments with the ads' numbers on every poll (repeats, stalls);</li>
 *   <li>numbers that only grow and stay fixed per segment, across sources;</li>
 *   <li>{@code #EXT-X-DISCONTINUITY} wherever the timeline jumps (an ad gap, a
 *       source switch), with a matching {@code DISCONTINUITY-SEQUENCE};</li>
 *   <li>on a jump, no segment whose time ({@code PROGRAM-DATE-TIME}) was
 *       already served: a new source's whole window numbered after the last
 *       served segment replayed it, ~30 s more latency per switch;</li>
 *   <li>on a jump, numbers past the prefetch hints already served: in low
 *       latency the player fetches and plays {@code #EXT-X-TWITCH-PREFETCH}
 *       as the next numbers, so a new source numbered from the last listed
 *       segment had its discontinuity skipped with the "known" segment -- the
 *       video froze on the timestamp jump while the audio went on.</li>
 * </ul>
 * A clean live playlist carries {@code #EXT-X-TWITCH-LIVE-SEQUENCE}: the
 * broadcast's own segment number, the same in every viewer session
 * (sabopura's Playlists). Segments are also keyed by it, so another session's
 * playlist lines up with what was served -- no time check, no gap -- whatever
 * its own {@code MEDIA-SEQUENCE}. During a break the per-source numbers are used.
 * Pure Java: covered by {@code PlaylistSelfCheck}.
 */
final class MediaSequencer {

    /** A segment is mapped under its source key and, when known, its live key. */
    private static final int MAPPED_SEGMENTS = 768;
    /**
     * Polls in a row where a source's new segments were all held back by the
     * time check. Then the check is dropped (Overhypedcat's MAX_EMPTY_POLLS): a
     * source whose clock lags must not stall playback until it catches up.
     */
    private static final int MAX_HELD_POLLS = 3;
    /** On that escape, only this many newest segments go out (a live edge, not the whole window). */
    private static final int LIVE_EDGE_SEGMENTS = 3;

    /** "source#sourceSequence" and "live#liveSequence" -> output sequence of segments already served. */
    private final Map<String, Long> outSeq = new LinkedHashMap<String, Long>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAPPED_SEGMENTS;
        }
    };
    private final TreeSet<Long> discontinuities = new TreeSet<>();
    private long discontinuityBase;
    private long nextSeq = -1; // -1: nothing served yet
    /** End time (PROGRAM-DATE-TIME + duration) of the latest segment served, or MIN_VALUE. */
    private long servedUntilMs = Long.MIN_VALUE;
    /** First number past every served segment and prefetch hint, or -1. */
    private long reservedSeq = -1;
    /** Estimated end time of the served prefetch hints, or MIN_VALUE. */
    private long reservedUntilMs = Long.MIN_VALUE;
    private int heldPolls;
    /** Source of the latest newly numbered segment. */
    private String lastSource;
    /**
     * After a switch that kept the broadcast's numbering: the first number the
     * player can't hold yet, where the switch gets its discontinuity; -1 when
     * none is pending. Until then no prefetch hint goes out (a hint can't carry it).
     */
    private long switchAt = -1;
    /** Per variant: a playlist's segments are one quality's, the numbers around them are the stream's. */
    private final Map<String, String> last = new HashMap<>();

    private static final class Segment {
        final long sourceSeq;
        final long liveSeq; // EXT-X-TWITCH-LIVE-SEQUENCE numbering, or -1
        final List<String> tags;
        final String extinf;
        final String uri;
        final String map; // EXT-X-MAP (fMP4 init segment) in effect, or null
        final boolean discontinuity;
        final long startMs; // PROGRAM-DATE-TIME, or -1
        final long durationMs;
        long seq;

        Segment(long sourceSeq, long liveSeq, List<String> tags, String extinf, String uri, String map,
                boolean discontinuity) {
            this.sourceSeq = sourceSeq;
            this.liveSeq = liveSeq;
            this.tags = tags;
            this.extinf = extinf;
            this.uri = uri;
            this.map = map;
            this.discontinuity = discontinuity;
            long start = -1;
            for (String tag : tags) {
                if (tag.startsWith("#EXT-X-PROGRAM-DATE-TIME:")) {
                    start = parseTime(tag.substring(tag.indexOf(':') + 1));
                }
            }
            this.startMs = start;
            this.durationMs = parseDurationMs(extinf);
        }
    }

    /**
     * @param variant the media playlist being polled
     * @param source which stream {@code text} came from ("primary", a backup id)
     * @return the playlist to serve, or null when nothing but ads is left
     *         (the caller then holds {@link #last})
     */
    synchronized String rewrite(String variant, String source, String text) {
        boolean adBreak = SsaiAdBlock.isAdBreak(text);
        boolean stitched = SsaiAdBlock.hasStitchedMarkers(text);
        List<String> header = new ArrayList<>();
        List<String> footer = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        List<Segment> kept = new ArrayList<>();
        boolean pendingDiscontinuity = false;
        boolean inCueOut = false;
        boolean seenSegment = false;
        long mediaSeq = 0;
        long liveSeq = -1;
        String map = null;
        int index = 0;

        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].endsWith("\r") ? lines[i].substring(0, lines[i].length() - 1) : lines[i];
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                mediaSeq = parseLong(line.substring(line.indexOf(':') + 1));
            } else if (line.startsWith("#EXT-X-TWITCH-LIVE-SEQUENCE:")) {
                // Trusted only without a break: where an ad window numbers from is unknown.
                liveSeq = adBreak ? -1 : parseLong(line.substring(line.indexOf(':') + 1));
                header.add(line);
            } else if (line.startsWith("#EXT-X-MAP")) {
                // Applies to every following segment: kept per segment, re-emitted before the first one.
                map = line;
            } else if (line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:")) {
                // re-emitted from our own count
            } else if (line.equals("#EXT-X-DISCONTINUITY")) {
                pendingDiscontinuity = true;
                seenSegment = true;
            } else if (line.contains("EXT-X-CUE-OUT")) {
                inCueOut = true;
            } else if (line.contains("EXT-X-CUE-IN")) {
                inCueOut = false;
            } else if (adBreak && (line.startsWith("#EXT-X-TWITCH-PREFETCH:")
                    || line.startsWith("#EXT-X-PRELOAD-HINT:"))) {
                // hints point at upcoming (ad) segments during a break
            } else if (line.startsWith("#EXT-X-DATERANGE") && SsaiAdBlock.lineIsAdMarker(line)) {
                // ad marker
            } else if (line.startsWith("#EXTINF")) {
                seenSegment = true;
                String uri = i + 1 < lines.length ? lines[++i].trim() : "";
                if (!SsaiAdBlock.isAdSegment(line, uri, adBreak, stitched, inCueOut)) {
                    kept.add(new Segment(mediaSeq + index, liveSeq < 0 ? -1 : liveSeq + index, pending, line,
                            uri, map, pendingDiscontinuity));
                    pendingDiscontinuity = false;
                }
                pending = new ArrayList<>();
                index++;
            } else if (!seenSegment && !isSegmentTag(line)) {
                header.add(line);
            } else {
                seenSegment = true;
                pending.add(line.contains("X-TV-TWITCH-AD") && line.contains("http")
                        ? SsaiAdBlock.rewriteTrackingUrls(line) : line);
            }
        }
        footer.addAll(pending); // tags after the last segment (prefetch hints)
        if (kept.isEmpty()) {
            return null;
        }

        boolean escape = heldPolls >= MAX_HELD_POLLS;
        int edge = kept.size() - LIVE_EDGE_SEGMENTS;
        boolean heldBack = false;
        boolean servedNew = false;
        List<Segment> numbered = new ArrayList<>(kept.size());
        for (int i = 0; i < kept.size(); i++) {
            Segment s = kept.get(i);
            String key = source + "#" + s.sourceSeq;
            String liveKey = s.liveSeq >= 0 ? "live#" + s.liveSeq : null;
            Long known = liveKey != null ? outSeq.get(liveKey) : null;
            if (known == null) {
                known = outSeq.get(key);
            }
            if (known != null) {
                s.seq = known;
            } else {
                Long prev = s.liveSeq >= 0 ? outSeq.get("live#" + (s.liveSeq - 1)) : null;
                if (prev == null) {
                    prev = outSeq.get(source + "#" + (s.sourceSeq - 1));
                }
                if (prev != null && prev + 1 >= nextSeq) {
                    s.seq = prev + 1;
                    if (lastSource != null && !lastSource.equals(source)) {
                        // Same broadcast timeline from another session: mark the switch, past
                        // the prefetch hints already served (they may have been an ad).
                        switchAt = Math.max(s.seq, reservedSeq);
                    }
                } else if (nextSeq < 0) {
                    s.seq = s.sourceSeq;
                } else if (s.startMs >= 0 && servedUntilMs != Long.MIN_VALUE
                        && s.startMs + s.durationMs / 2 <= Math.max(servedUntilMs, reservedUntilMs)
                        && !(escape && i >= edge)) {
                    // Another source's window reaches back over time already played: skip it
                    // (not mapped, so later polls skip it too).
                    heldBack = true;
                    continue;
                } else {
                    // Gap (stripped ads) or another source: continue after anything the player
                    // may hold (prefetched numbers too), mark the jump.
                    s.seq = Math.max(nextSeq, reservedSeq);
                    discontinuities.add(s.seq);
                    switchAt = -1;
                    VaftLog.log("sequencer: " + source + " jumps to " + s.seq + (escape ? " (time check dropped)" : ""));
                }
                if (s.discontinuity) {
                    discontinuities.add(s.seq);
                }
                if (switchAt >= 0 && s.seq >= switchAt) {
                    discontinuities.add(s.seq);
                    switchAt = -1;
                }
                lastSource = source;
                servedNew = true;
            }
            outSeq.put(key, s.seq);
            if (liveKey != null) {
                outSeq.put(liveKey, s.seq);
            }
            numbered.add(s);
            nextSeq = Math.max(nextSeq, s.seq + 1);
            if (s.startMs >= 0) {
                servedUntilMs = Math.max(servedUntilMs, s.startMs + s.durationMs);
            }
        }
        if (servedNew) {
            heldPolls = 0;
        } else if (heldBack && ++heldPolls == MAX_HELD_POLLS) {
            VaftLog.log("sequencer: " + source + " held back " + heldPolls + " polls in a row by time");
        }
        kept = numbered;
        if (kept.isEmpty()) {
            return null;
        }
        // A playlist numbers its segments consecutively: drop older ones before a gap.
        for (int i = kept.size() - 1; i > 0; i--) {
            if (kept.get(i).seq != kept.get(i - 1).seq + 1) {
                kept = kept.subList(i, kept.size());
                break;
            }
        }

        long first = kept.get(0).seq;
        while (!discontinuities.isEmpty() && discontinuities.first() < first) {
            discontinuities.pollFirst();
            discontinuityBase++;
        }

        StringBuilder out = new StringBuilder(text.length() + 64);
        for (String line : header) {
            out.append(line).append('\n');
        }
        out.append("#EXT-X-MEDIA-SEQUENCE:").append(first).append('\n');
        if (discontinuityBase > 0) {
            out.append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(discontinuityBase).append('\n');
        }
        String lastMap = null;
        for (Segment s : kept) {
            if (discontinuities.contains(s.seq)) {
                out.append("#EXT-X-DISCONTINUITY\n");
            }
            if (s.map != null && !s.map.equals(lastMap)) {
                // Before the first segment that uses it, and again when another source brings its own.
                lastMap = s.map;
                out.append(s.map).append('\n');
            }
            for (String tag : s.tags) {
                out.append(tag).append('\n');
            }
            out.append(s.extinf).append('\n').append(s.uri).append('\n');
        }
        int hints = 0;
        for (String line : footer) {
            if (switchAt >= 0 && (line.startsWith("#EXT-X-TWITCH-PREFETCH:") || line.startsWith("#EXT-X-PRELOAD-HINT:"))) {
                continue;
            }
            out.append(line).append('\n');
            if (line.startsWith("#EXT-X-TWITCH-PREFETCH:")) {
                hints++;
            }
        }
        Segment tail = kept.get(kept.size() - 1);
        reservedSeq = Math.max(reservedSeq, tail.seq + 1 + hints);
        if (tail.startMs >= 0) {
            reservedUntilMs = Math.max(reservedUntilMs, tail.startMs + tail.durationMs * (1 + hints));
        }
        String playlist = out.toString();
        last.put(variant, playlist);
        return playlist;
    }

    synchronized String last(String variant) {
        return last.get(variant);
    }

    /** Tags that belong to the following segment rather than the playlist header. */
    private static boolean isSegmentTag(String line) {
        return line.startsWith("#EXT-X-PROGRAM-DATE-TIME") || line.startsWith("#EXT-X-DATERANGE")
                || line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-BYTERANGE") || line.startsWith("#EXT-X-TWITCH-PREFETCH")
                || line.startsWith("#EXT-X-PRELOAD-HINT");
    }

    private static long parseTime(String iso) {
        try {
            return java.time.Instant.parse(iso.trim()).toEpochMilli(); // API 26 = minSdk
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** {@code #EXTINF:2.000,live} -> 2000. */
    private static long parseDurationMs(String extinf) {
        try {
            int comma = extinf.indexOf(',');
            return Math.round(Double.parseDouble(
                    extinf.substring(extinf.indexOf(':') + 1, comma < 0 ? extinf.length() : comma)) * 1000);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
