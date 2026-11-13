package tv.retwitch.mod.ads;

/**
 * Text-level ad handling for Twitch HLS media playlists, used by
 * {@link VaftServer} (the local playlist proxy the player is pointed at).
 *
 * <p>Ad breaks are detected by the stitched-ad {@code EXT-X-DATERANGE} /
 * {@code X-TV-TWITCH-AD-*} markers ({@link #hasAdTags}); when no clean backup
 * stream is available ({@link SsaiSwapEngine}), {@link #stripAdSegments} drops
 * the ad segments in place. Port of the VAFT / TTV-AB "detect + strip" logic.
 */
public final class SsaiAdBlock {

    /**
     * Substrings that mark a media playlist as containing an SSAI ad break
     * (Twitch has shipped several marker spellings). Not "twitch-trigger":
     * that DATERANGE rides along clean live playlists after a source switch,
     * and a false positive is costly -- prefetch hints dropped (latency grows
     * for the rest of the session) and a backup search on every poll.
     */
    private static final String[] AD_SIGNIFIERS = {
            "stitched-ad",
            "twitch-stitched",
            "twitch-maf-ad",
            "X-TV-TWITCH-AD",
            // Thream's extra markers (Amazon-served segments, roll types).
            "amazon-ad",
            "\"preroll\"",
            "\"midroll\"",
    };

    /**
     * Markers of an ad stitched into the stream itself. The others above also
     * come as cues of client-side ads (twitch-maf-ad) or linger after a break
     * (ad quartiles): alone, over segments all titled "live", they are no break.
     */
    private static final String[] STITCHED_SIGNIFIERS = {"stitched-ad", "twitch-stitched", "EXT-X-CUE-OUT", "amazon-ad"};

    /** Segment URLs that are always an ad (Overhypedcat's AdMarkers). */
    private static final String AD_SEGMENT_URL = "/adsquared/";
    /**
     * Placeholders Twitch serves for missing or still transcoding segments: ads
     * during a stitched break, but also seen when a stream hiccups.
     */
    private static final String[] PLACEHOLDER_URLS = {"/_404/", "/processing"};

    /** DATERANGE classes that look ad related but are not ads (the trigger rides clean playlists, see above). */
    private static final java.util.Set<String> NON_AD_CLASSES = new java.util.HashSet<>(java.util.Arrays.asList(
            "twitch-session", "twitch-stream-source", "twitch-assignment", "twitch-ad-quartile", "twitch-trigger"));
    private static final java.util.Set<String> AD_WORDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "ad", "ads", "advert", "stitched", "maf", "break", "midroll", "preroll", "sponsor", "cue",
            "scte35", "splice", "commercial", "quartile", "trigger"));
    private static final java.util.regex.Pattern DATERANGE_CLASS = java.util.regex.Pattern.compile("CLASS=\"([^\"]+)\"");

    /** Where the overlay's clickable ad-tracking link is redirected to. */
    private static final String SAFE_URL = "https://twitch.tv";

    private SsaiAdBlock() {
    }

    /** @return {@code true} if the playlist text carries any SSAI ad marker. */
    public static boolean hasAdTags(String text) {
        if (text == null) {
            return false;
        }
        for (String s : AD_SIGNIFIERS) {
            if (text.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the playlist carries an ad break to replace or cut: a stitched
     * ad marker, an ad segment (URL, or "Amazon" title as in sabopura), or any ad
     * marker over a segment not titled "live". Ad markers alone over live segments are left to the marker
     * filter: switching streams for them costs quality and the prefetch hints.
     */
    public static boolean isAdBreak(String text) {
        if (text == null) {
            return false;
        }
        if (hasStitchedMarkers(text)) {
            return true;
        }
        boolean markers = hasAdTags(text);
        String[] lines = text.split("\n", -1);
        for (int i = 0; i + 1 < lines.length; i++) {
            if (lines[i].startsWith("#EXTINF")
                    && (lines[i + 1].contains(AD_SEGMENT_URL) || lines[i].contains(",Amazon")
                    || markers && !lines[i].contains(",live"))) {
                return true;
            }
        }
        return false;
    }

    /** True when the playlist announces a stitched ad (then placeholders count as ads). */
    static boolean hasStitchedMarkers(String text) {
        for (String s : STITCHED_SIGNIFIERS) {
            if (text.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param extinf   the segment's {@code #EXTINF} line
     * @param adBreak  {@link #isAdBreak} of the playlist
     * @param stitched {@link #hasStitchedMarkers} of the playlist
     * @param inCueOut inside an SCTE-35 CUE-OUT region
     */
    static boolean isAdSegment(String extinf, String uri, boolean adBreak, boolean stitched, boolean inCueOut) {
        if (uri.contains(AD_SEGMENT_URL)) {
            return true;
        }
        if (adBreak && (inCueOut || !extinf.contains(",live"))) {
            return true;
        }
        if (stitched) {
            for (String p : PLACEHOLDER_URLS) {
                if (uri.contains(p)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Tags and DATERANGE classes that look ad related but match no known marker:
     * Twitch has most likely changed how ads are marked (Overhypedcat's
     * suspiciousMarkers). Empty for an ordinary playlist.
     */
    static java.util.Set<String> unknownAdMarkers(String text) {
        java.util.Set<String> found = new java.util.LinkedHashSet<>();
        for (String line : text.split("\r?\n")) {
            if (!line.startsWith("#EXT") || line.startsWith("#EXT-X-CUE") || lineIsAdMarker(line)) {
                continue;
            }
            int colon = line.indexOf(':');
            String tag = colon >= 0 ? line.substring(0, colon) : line;
            if (hasAdWord(tag.substring(1))) {
                found.add(tag);
            }
            if (tag.equals("#EXT-X-DATERANGE")) {
                java.util.regex.Matcher m = DATERANGE_CLASS.matcher(line);
                if (m.find() && !NON_AD_CLASSES.contains(m.group(1)) && hasAdWord(m.group(1))) {
                    found.add("CLASS=\"" + m.group(1) + "\"");
                }
            }
        }
        return found;
    }

    private static boolean hasAdWord(String name) {
        for (String word : name.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+")) {
            if (AD_WORDS.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes ad segments and ad-marker tags from a media playlist, keeping the
     * live content segments and all structural tags. Port of VAFT's
     * {@code stripAdSegments} (strip-only subset).
     */
    public static String stripAdSegments(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        boolean inCueOut = false;
        boolean stitched = hasStitchedMarkers(text);

        for (int i = 0; i < lines.length; i++) {
            // strip() would drop the trailing '\r' the split kept on CRLF input.
            String raw = lines[i];
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;

            // SCTE-35 ad boundary tracking -- everything between CUE-OUT and
            // CUE-IN is ad, drop the markers themselves too.
            if (line.contains("EXT-X-CUE-OUT")) {
                inCueOut = true;
                continue;
            }
            if (line.contains("EXT-X-CUE-IN")) {
                inCueOut = false;
                continue;
            }

            // No low-latency prefetch during a break: the hints point at
            // upcoming (ad) segments before their #EXTINF even appears.
            if (line.startsWith("#EXT-X-TWITCH-PREFETCH:") || line.startsWith("#EXT-X-PRELOAD-HINT:")) {
                continue;
            }

            // Drop the ad-marker DATERANGE lines (stitched-ad / twitch-*-ad).
            if (line.startsWith("#EXT-X-DATERANGE") && lineIsAdMarker(line)) {
                continue;
            }

            // Segment: an #EXTINF followed by its URI on the next line. A live
            // content segment is tagged ",live"; anything else during a break
            // (or inside a cue-out region) is an ad segment -> drop both lines.
            if (line.startsWith("#EXTINF")) {
                String uri = i + 1 < lines.length ? lines[i + 1] : "";
                if (isAdSegment(line, uri, true, stitched, inCueOut)) {
                    // Skip this #EXTINF and the URI line that follows it.
                    if (i + 1 < lines.length) {
                        i++;
                    }
                    continue;
                }
                out.append(line).append('\n');
                continue;
            }

            // Rewrite the clickable ad-tracking URL that appears in the overlay
            // metadata so nothing phones the ad domain when the overlay is shown.
            if (line.contains("X-TV-TWITCH-AD") && line.contains("http")) {
                out.append(rewriteTrackingUrls(line)).append('\n');
                continue;
            }

            out.append(raw).append('\n');
        }

        // split(..., -1) yields a trailing "" for a text ending in '\n'; the
        // append loop already re-added a '\n' after the real last line, so trim
        // the one duplicate we introduced for that empty tail.
        int len = out.length();
        if (len >= 2 && out.charAt(len - 1) == '\n' && out.charAt(len - 2) == '\n'
                && text.endsWith("\n")) {
            out.setLength(len - 1);
        }
        return out.toString();
    }

    /** True for DATERANGE lines whose CLASS marks them as an ad. */
    static boolean lineIsAdMarker(String line) {
        for (String s : AD_SIGNIFIERS) {
            if (line.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /** Point any {@code http(s)} URL on the line at a harmless host. */
    static String rewriteTrackingUrls(String line) {
        // Cheap, allocation-light replace of the value after the first http.
        int idx = line.indexOf("http");
        if (idx < 0) {
            return line;
        }
        int end = idx;
        while (end < line.length()) {
            char c = line.charAt(end);
            if (c == '"' || c == ',' || c == ' ') {
                break;
            }
            end++;
        }
        return line.substring(0, idx) + SAFE_URL + line.substring(end);
    }
}
