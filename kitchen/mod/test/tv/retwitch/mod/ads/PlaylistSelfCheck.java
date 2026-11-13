package tv.retwitch.mod.ads;

/**
 * JVM self-check for the pure playlist logic (no Android calls on these paths).
 * Run from kitchen/:
 *   javac -cp /opt/android-sdk/platforms/android-36/android.jar:libs/okhttp-4.12.0.jar:libs/okio-jvm-3.6.0.jar:libs/kotlin-stdlib-2.0.0.jar:libs/bugsnag-android-core-6.11.0.jar -d work/selfcheck mod/src/tv/retwitch/mod/ads/*.java mod/src/tv/retwitch/mod/*.java mod/src/tv/retwitch/mod/emotes/*.java mod/test/tv/retwitch/mod/ads/PlaylistSelfCheck.java
 *   java -ea -cp work/selfcheck:/opt/android-sdk/platforms/android-36/android.jar:libs/okhttp-4.12.0.jar:libs/okio-jvm-3.6.0.jar:libs/kotlin-stdlib-2.0.0.jar tv.retwitch.mod.ads.PlaylistSelfCheck
 */
public final class PlaylistSelfCheck {

    public static void main(String[] args) {
        String master = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=1920x1080,CODECS=\"hvc1.1.2.L123,mp4a.40.2\",VIDEO=\"chunked\"\nhttps://a/hevc1080.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,CODECS=\"avc1.64002A,mp4a.40.2\",VIDEO=\"chunked\"\nhttps://a/avc1080.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720,CODECS=\"avc1.4D401F,mp4a.40.2\",VIDEO=\"720p60\"\nhttps://a/avc720.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=600000,RESOLUTION=640x360,CODECS=\"avc1.4D401E,mp4a.40.2\",VIDEO=\"360p30\"\nhttps://a/avc360.m3u8\n";

        assert "https://a/avc1080.m3u8".equals(SsaiSwapEngine.pickVariant(master, "1920x1080", "avc1.64002A,mp4a.40.2"));
        assert "https://a/hevc1080.m3u8".equals(SsaiSwapEngine.pickVariant(master, "1920x1080", "hvc1.1.2.L123,mp4a.40.2"));
        assert "https://a/avc720.m3u8".equals(SsaiSwapEngine.pickVariant(master, "1280x720", "avc1.4D401F"));
        // autoplay-style ladder (360p only) still yields a variant
        String autoplay = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=600000,RESOLUTION=640x360,CODECS=\"avc1.4D401E\"\nhttps://a/ap360.m3u8\n";
        assert "https://a/ap360.m3u8".equals(SsaiSwapEngine.pickVariant(autoplay, "1920x1080", "avc1.64002A"));
        assert "1920x1080".equals(SsaiSwapEngine.attribute(
                "#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1920x1080,CODECS=\"avc1\"", "RESOLUTION"));
        assert "hevc".equals(SsaiSwapEngine.codecFamily("hev1.1.6.L93,mp4a.40.2"));

        // A clean live playlist after a source switch carries a twitch-trigger DATERANGE: not an ad.
        String afterSwitch = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:4214\n"
                + "#EXT-X-DATERANGE:ID=\"source-1\",CLASS=\"twitch-stream-source\",START-DATE=\"2026-10-31T18:46:17.305Z\","
                + "END-ON-NEXT=YES,X-TV-TWITCH-STREAM-SOURCE=\"live\"\n"
                + "#EXT-X-DATERANGE:ID=\"trigger-1\",CLASS=\"twitch-trigger\",START-DATE=\"2026-10-31T18:46:17.305Z\","
                + "END-ON-NEXT=YES,X-TV-TWITCH-TRIGGER-URL=\"https://p/trigger/x\"\n"
                + "#EXTINF:2.000,live\nhttps://s/1.ts\n#EXT-X-TWITCH-PREFETCH:https://s/2.ts\n";
        assert !SsaiAdBlock.hasAdTags(afterSwitch);
        assert !SsaiAdBlock.isAdBreak(afterSwitch);
        assert SsaiAdBlock.unknownAdMarkers(afterSwitch).isEmpty();
        // Ad-looking markers that match nothing known are reported (Twitch changed its markers).
        java.util.Set<String> unknown = SsaiAdBlock.unknownAdMarkers("#EXTM3U\n"
                + "#EXT-X-DATERANGE:ID=\"x\",CLASS=\"twitch-sponsor-break\"\n#EXT-X-SCTE35:CUE=abc\n" + AD);
        assert unknown.size() == 2 && unknown.contains("CLASS=\"twitch-sponsor-break\"")
                && unknown.contains("#EXT-X-SCTE35") : unknown;
        assert "1280x720".equals(SsaiSwapEngine.attribute(
                SsaiSwapEngine.variantInfo(master, "https://a/avc720.m3u8"), "RESOLUTION"));
        assert "".equals(SsaiSwapEngine.variantInfo(master, "https://a/none.m3u8"));

        // Low-latency master URL: usher's own URL rides verbatim in our path.
        assert "https://usher.ttvnw.net/api/channel/hls/x.m3u8?a=1&b=%22c%22".equals(VaftServer.masterTargetInPath(
                "/s3c/master/https://usher.ttvnw.net/api/channel/hls/x.m3u8?a=1&b=%22c%22", "s3c"));
        assert "https://usher.ttvnw.net/x".equals(VaftServer.masterTargetInPath("/s3c/master/https:/usher.ttvnw.net/x", "s3c"));
        assert VaftServer.masterTargetInPath("/bad/master/https://usher.ttvnw.net/x", "s3c") == null;
        assert VaftServer.masterTargetInPath("/s3c/master?u=https%3A%2F%2Fa", "s3c") == null;

        String media = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:10\n"
                + "#EXT-X-DATERANGE:ID=\"stitched-ad-1\",CLASS=\"twitch-stitched-ad\",X-TV-TWITCH-AD-URL=\"https://ads.example/x\"\n"
                + "#EXTINF:2.000,Amazon|123\nhttps://ad/1.ts\n"
                + "#EXTINF:2.000,live\nhttps://live/2.ts\n"
                + "#EXT-X-TWITCH-PREFETCH:https://ad/3.ts\n";
        assert SsaiAdBlock.hasAdTags(media);
        String stripped = SsaiAdBlock.stripAdSegments(media);
        assert !stripped.contains("https://ad/1.ts") : stripped;
        assert stripped.contains("https://live/2.ts") : stripped;
        assert !stripped.contains("PREFETCH") : stripped;
        assert !stripped.contains("stitched-ad") : stripped;
        assert !SsaiAdBlock.hasAdTags("#EXTM3U\n#EXTINF:2.000,live\nhttps://live/2.ts\n");

        checkSequencer();
        checkAdMarkers();
        checkInitSegment();

        checkSubVods();
        System.out.println("PlaylistSelfCheck OK");
    }

    private static final String AD = "#EXT-X-DATERANGE:ID=\"stitched-ad-1\",CLASS=\"twitch-stitched-ad\"\n";

    /** Playlist with MEDIA-SEQUENCE {@code first}; 'a' = ad segment, 'l' = live, per position. */
    private static String window(long first, String kinds) {
        StringBuilder b = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:" + first + "\n");
        if (kinds.indexOf('a') >= 0) {
            b.append(AD);
        }
        for (int i = 0; i < kinds.length(); i++) {
            boolean live = kinds.charAt(i) == 'l';
            b.append("#EXTINF:2.000,").append(live ? "live" : "Amazon|1").append('\n')
                    .append("https://s/").append(first + i).append(".ts\n");
        }
        return b.toString();
    }

    private static void checkSequencer() {
        MediaSequencer seq = new MediaSequencer();
        // Clean: numbering untouched.
        String out = seq.rewrite("v", "primary", window(100, "lll"));
        assert out.contains("#EXT-X-MEDIA-SEQUENCE:100\n") && !out.contains("DISCONTINUITY") : out;
        // Break starts: trailing ads stripped, numbers unchanged.
        out = seq.rewrite("v", "primary", window(101, "llaa"));
        assert out.contains("#EXT-X-MEDIA-SEQUENCE:101\n") && out.contains("/102.ts") && !out.contains("/103.ts") : out;
        // All ads: nothing to serve (caller holds the last playlist).
        assert seq.rewrite("v", "primary", window(103, "aaaa")) == null;
        assert seq.last("v").contains("/102.ts");
        // Break ends with leading ads: live 108/109 continue at 103 behind a discontinuity.
        out = seq.rewrite("v", "primary", window(106, "aall"));
        assert out.contains("#EXT-X-MEDIA-SEQUENCE:103\n#EXT-X-DISCONTINUITY\n") : out;
        assert out.indexOf("/108.ts") < out.indexOf("/109.ts") && !out.contains("/106.ts") : out;
        // Next poll: same segments keep their numbers.
        out = seq.rewrite("v", "primary", window(107, "alll"));
        assert out.contains("#EXT-X-MEDIA-SEQUENCE:103\n#EXT-X-DISCONTINUITY\n") && out.contains("/110.ts") : out;
        // The discontinuity leaves the window: DISCONTINUITY-SEQUENCE counts it.
        out = seq.rewrite("v", "primary", window(109, "lll"));
        assert out.contains("#EXT-X-MEDIA-SEQUENCE:104\n#EXT-X-DISCONTINUITY-SEQUENCE:1\n") : out;
        assert !out.contains("#EXT-X-DISCONTINUITY\n") : out;
        // Switch to a backup with its own numbering: still monotonic, with a jump marker.
        out = seq.rewrite("v", "backup b", window(5000, "ll"));
        assert out.contains("#EXT-X-MEDIA-SEQUENCE:107\n") && out.contains("#EXT-X-DISCONTINUITY\n") : out;

        checkSourceSwitchByTime();
        checkPrefetchReserved();
        checkHeldBackEscape();
        checkLiveSequence();
        checkVariantsShareNumbers();
    }

    /** Which segments are ads, and which playlists are a break (Overhypedcat's AdMarkers). */
    private static void checkAdMarkers() {
        // A client-side ad cue alone over live segments is no break: prefetch stays, the cue goes.
        String maf = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:1\n"
                + "#EXT-X-DATERANGE:ID=\"maf-1\",CLASS=\"twitch-maf-ad\",START-DATE=\"2026-10-13T14:08:23.736Z\"\n"
                + "#EXTINF:2.000,live\nhttps://s/1.ts\n#EXT-X-TWITCH-PREFETCH:https://s/2.ts\n";
        assert SsaiAdBlock.hasAdTags(maf) && !SsaiAdBlock.isAdBreak(maf);
        String out = new MediaSequencer().rewrite("v", "primary", maf);
        assert out.contains("#EXT-X-TWITCH-PREFETCH:https://s/2.ts") && !out.contains("maf-ad") : out;
        // ...but over a segment not titled "live" it is.
        assert SsaiAdBlock.isAdBreak(maf.replace("#EXTINF:2.000,live", "#EXTINF:2.000,Something|1"));

        // An ad segment URL is an ad without any marker.
        String adsquared = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:2.000,live\nhttps://s/adsquared/1.ts\n"
                + "#EXTINF:2.000,live\nhttps://s/2.ts\n";
        assert SsaiAdBlock.isAdBreak(adsquared);
        out = new MediaSequencer().rewrite("v", "primary", adsquared);
        assert !out.contains("adsquared") && out.contains("https://s/2.ts") : out;

        // Placeholders: a hiccup without a stitched ad stays, during a stitched ad they go.
        String hiccup = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:2.000,live\nhttps://s/_404/1.ts\n"
                + "#EXTINF:2.000,live\nhttps://s/2.ts\n";
        assert !SsaiAdBlock.isAdBreak(hiccup);
        assert new MediaSequencer().rewrite("v", "primary", hiccup).contains("/_404/1.ts");
        String stitchedPlaceholder = hiccup.replace("#EXT-X-MEDIA-SEQUENCE:1\n", "#EXT-X-MEDIA-SEQUENCE:1\n" + AD);
        out = new MediaSequencer().rewrite("v", "primary", stitchedPlaceholder);
        assert !out.contains("_404") && out.contains("https://s/2.ts") : out;
        assert !SsaiAdBlock.stripAdSegments(stitchedPlaceholder).contains("_404");
    }

    /** fMP4: the init segment (EXT-X-MAP) stays before the first segment served, even when the one it came with is cut. */
    private static void checkInitSegment() {
        String map = "#EXT-X-MAP:URI=\"https://s/init.mp4\"";
        String fmp4 = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:11\n" + map + "\n" + AD
                + "#EXTINF:2.000,Amazon|1\nhttps://ad/11.mp4\n#EXTINF:2.000,live\nhttps://s/12.mp4\n";
        String out = new MediaSequencer().rewrite("v", "primary", fmp4);
        assert out.contains(map + "\n#EXTINF:2.000,live\nhttps://s/12.mp4") && !out.contains("ad/11") : out;
        assert out.indexOf("#EXT-X-MAP") == out.lastIndexOf("#EXT-X-MAP") : out;
    }

    /** Broadcast segments {@code from..to}, numbered from {@code base}, with a clock {@code lag} segments behind. */
    private static String lagged(long base, int from, int to, int lag) {
        StringBuilder b = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:" + (base + from) + "\n");
        for (int i = from; i <= to; i++) {
            b.append("#EXT-X-PROGRAM-DATE-TIME:").append(java.time.Instant.ofEpochMilli(1_700_000_000_000L + (i - lag) * 2000L))
                    .append("\n#EXTINF:2.000,live\nhttps://s/").append(base + i).append(".ts\n");
        }
        return b.toString();
    }

    /**
     * A source whose clock lags holds its segments back as "already played";
     * after a few polls the time check goes and its live edge plays.
     */
    private static void checkHeldBackEscape() {
        MediaSequencer seq = new MediaSequencer();
        seq.rewrite("v", "primary", timed(100, 0, 9)); // broadcast 0..9 served as 100..109
        // Backup with its clock 20 s (10 segments) behind: every segment looks played.
        for (int poll = 0; poll < 3; poll++) {
            assert seq.rewrite("v", "backup b", lagged(5000, 6 + poll, 10 + poll, 10)) == null : poll;
        }
        // Fourth poll: the time check is dropped, but only the newest segments go out (not the whole window).
        String out = seq.rewrite("v", "backup b", lagged(5000, 9, 13, 10));
        assert out.contains("#EXT-X-MEDIA-SEQUENCE:110\n#EXT-X-DISCONTINUITY\n") : out;
        assert out.contains("/5011.ts") && out.contains("/5013.ts") && !out.contains("/5010.ts") : out;
        // It continues from there as usual.
        out = seq.rewrite("v", "backup b", lagged(5000, 10, 14, 10));
        assert out.contains("#EXT-X-MEDIA-SEQUENCE:110\n") && out.contains("/5014.ts") && !out.contains("/5010.ts") : out;
    }

    /** One viewer session's live window: own MEDIA-SEQUENCE, the broadcast's LIVE-SEQUENCE, own segment URIs. */
    private static String session(String host, long mediaBase, int from, int to, int lag) {
        StringBuilder b = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:" + (mediaBase + from)
                + "\n#EXT-X-TWITCH-LIVE-SEQUENCE:" + (15170 + from) + "\n");
        for (int i = from; i <= to; i++) {
            b.append("#EXT-X-PROGRAM-DATE-TIME:").append(java.time.Instant.ofEpochMilli(1_700_000_000_000L + (i - lag) * 2000L))
                    .append("\n#EXTINF:2.000,live\nhttps://").append(host).append('/').append(i).append(".ts\n");
        }
        return b.toString();
    }

    /** Sessions of one broadcast line up by LIVE-SEQUENCE, whatever their own numbers and clocks. */
    private static void checkLiveSequence() {
        MediaSequencer seq = new MediaSequencer();
        String out = seq.rewrite("v", "primary", session("p", 15, 0, 4, 0)); // broadcast 0..4 as 15..19
        assert out.contains("#EXT-X-MEDIA-SEQUENCE:15\n") && !out.contains("DISCONTINUITY") : out;
        // Another session: own numbering, clock 20 s behind. Broadcast 2..4 keep 17..19, 5 and 6 follow.
        out = seq.rewrite("v", "backup b", session("b", 9000, 2, 6, 10));
        assert out.contains("#EXT-X-MEDIA-SEQUENCE:17\n") && out.contains("https://b/6.ts") : out;
        int switchAt = out.indexOf("#EXT-X-DISCONTINUITY\n");
        assert switchAt > out.indexOf("https://b/4.ts") && switchAt < out.indexOf("https://b/5.ts") : out;
        // Back to the first session: broadcast 7 continues at 22, the switch marked again.
        out = seq.rewrite("v", "primary", session("p", 15, 4, 8, 0));
        assert out.contains("#EXT-X-MEDIA-SEQUENCE:19\n") && out.contains("https://p/8.ts") : out;
        int back = out.lastIndexOf("#EXT-X-DISCONTINUITY\n");
        assert back > out.indexOf("https://p/6.ts") && back < out.indexOf("https://p/7.ts") : out;

        // Prefetch hints served before the switch (perhaps an ad): the switch is marked on the first
        // number past them, and no hint goes out before that (a hint can't carry a discontinuity).
        String hints = "#EXT-X-TWITCH-PREFETCH:https://s/h1.ts\n#EXT-X-TWITCH-PREFETCH:https://s/h2.ts\n";
        seq = new MediaSequencer();
        seq.rewrite("v", "primary", session("p", 15, 0, 4, 0) + hints); // 15..19, the player may hold 20, 21
        out = seq.rewrite("v", "backup b", session("b", 9000, 2, 5, 10) + hints); // b/5 lines up at 20: held
        assert out.contains("https://b/5.ts") && !out.contains("DISCONTINUITY") && !out.contains("PREFETCH") : out;
        out = seq.rewrite("v", "backup b", session("b", 9000, 3, 7, 10) + hints);
        switchAt = out.indexOf("#EXT-X-DISCONTINUITY\n");
        assert switchAt > out.indexOf("https://b/6.ts") && switchAt < out.indexOf("https://b/7.ts") : out;
        assert out.contains("PREFETCH") : out;
    }

    /** Sub-only VODs (SubVods): the CDN path from the storyboards URL, variant URLs, master, media fix. */
    private static void checkSubVods() {
        String[] cdn = SubVods.cdn("https://d1m7jfoe9zdc1j.cloudfront.net/abc123_chan_456_789/storyboards/2000-info.json");
        assert cdn != null && cdn[0].equals("d1m7jfoe9zdc1j.cloudfront.net") && cdn[1].equals("abc123_chan_456_789");
        assert SubVods.cdn("https://x.net/storyboards/a.json") == null;
        assert SubVods.cdn("") == null && SubVods.cdn("not a url") == null;

        assert "https://h/p/720p60/index-dvr.m3u8".equals(
                SubVods.variantUrl("h", "p", "chan", "9", "ARCHIVE", "2024-01-01T00:00:00Z", "720p60"));
        assert "https://h/p/chunked/highlight-9.m3u8".equals(
                SubVods.variantUrl("h", "p", "chan", "9", "HIGHLIGHT", "2024-01-01T00:00:00Z", "chunked"));
        assert "https://h/chan/9/p/160p30/index-dvr.m3u8".equals(
                SubVods.variantUrl("h", "p", "chan", "9", "UPLOAD", "2023-02-02T23:59:59Z", "160p30"));
        assert "https://h/p/160p30/index-dvr.m3u8".equals(
                SubVods.variantUrl("h", "p", "chan", "9", "UPLOAD", "2023-02-03T00:00:00Z", "160p30"));

        java.util.List<String[]> variants = new java.util.ArrayList<>();
        variants.add(new String[]{"chunked", "https://h/p/chunked/index-dvr.m3u8", "hev1.1.6.L93.B0"});
        variants.add(new String[]{"720p60", "https://h/p/720p60/index-dvr.m3u8", "avc1.4D001E"});
        String master = SubVods.masterPlaylist(variants, "s");
        assert master.startsWith("#EXTM3U\n#EXT-X-TWITCH-INFO:") : master;
        // Source above a 720p60 top transcode: 1080p60, with a resolution (else IVS height 0, skipped by pickQuality).
        assert master.contains("#EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID=\"chunked\",NAME=\"1080p60\",AUTOSELECT=YES,DEFAULT=YES\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=8534030,CODECS=\"hev1.1.6.L93.B0,mp4a.40.2\",RESOLUTION=1920x1080,"
                + "VIDEO=\"chunked\",FRAME-RATE=60\n"
                + "https://h/p/chunked/index-dvr.m3u8\n") : master;
        assert master.contains("BANDWIDTH=8533930,CODECS=\"avc1.4D001E,mp4a.40.2\",RESOLUTION=1280x720,VIDEO=\"720p60\"")
                : master;
        java.util.List<String[]> ladder = new java.util.ArrayList<>();
        ladder.add(new String[]{"chunked", "u", "c"});
        ladder.add(new String[]{"1080p60", "u", "c"});
        assert SubVods.source(ladder)[1].equals("2560x1440");
        ladder.remove(1);
        assert SubVods.source(ladder)[0].equals("1080p60"); // only the source on the CDN
        // What rewriteMaster picks up: one https line per variant, codecs and resolution on its STREAM-INF.
        assert "https://h/p/720p60/index-dvr.m3u8".equals(SsaiSwapEngine.pickVariant(master, "1280x720", "avc1.4D001E"));

        String media = "#EXTM3U\n#EXT-X-MAP:URI=\"init-0.mp4\"\n#EXTINF:10.000,\n0.mp4\n#EXTINF:10.000,\n1-unmuted.mp4\n"
                + "#EXTINF:10.000,\nhttps://o/2.mp4\n#EXT-X-ENDLIST";
        String fixed = SubVods.fixMedia("https://h/p/chunked/index-dvr.m3u8", media);
        assert fixed.equals("#EXTM3U\n#EXT-X-MAP:URI=\"https://h/p/chunked/init-0.mp4\"\n#EXTINF:10.000,\n"
                + "https://h/p/chunked/0.mp4\n#EXTINF:10.000,\nhttps://h/p/chunked/1-muted.mp4\n"
                + "#EXTINF:10.000,\nhttps://o/2.mp4\n#EXT-X-ENDLIST") : fixed;
    }

    /**
     * Low latency plays prefetch hints as the next numbers: a jump must number
     * past them, or its discontinuity is skipped with an already-held segment.
     */
    private static void checkPrefetchReserved() {
        MediaSequencer seq = new MediaSequencer();
        String hints = "#EXT-X-TWITCH-PREFETCH:https://s/p1.ts\n#EXT-X-TWITCH-PREFETCH:https://s/p2.ts\n";
        String out = seq.rewrite("v", "primary", window(100, "lll") + hints); // player may hold 103, 104
        assert out.contains("/p2.ts") : out;
        out = seq.rewrite("v", "backup b", window(5000, "ll"));
        assert out.contains("#EXT-X-MEDIA-SEQUENCE:105\n#EXT-X-DISCONTINUITY\n") : out;

        // Break starts after hints were served (104 was an ad, already played): the
        // first live segment after the break is numbered past it.
        seq = new MediaSequencer();
        seq.rewrite("v", "primary", window(100, "lll") + hints);
        out = seq.rewrite("v", "primary", window(101, "llla"));
        assert out.contains("/103.ts") && !out.contains("PREFETCH") : out;
        out = seq.rewrite("v", "primary", window(108, "all"));
        assert out.contains("#EXT-X-MEDIA-SEQUENCE:105\n#EXT-X-DISCONTINUITY\n") && out.contains("/109.ts") : out;
        // Hints that turn into listed segments keep their numbers (no jump).
        seq = new MediaSequencer();
        seq.rewrite("v", "primary", window(100, "lll") + hints);
        out = seq.rewrite("v", "primary", window(101, "llll"));
        assert out.contains("#EXT-X-MEDIA-SEQUENCE:101\n") && !out.contains("DISCONTINUITY") : out;
    }

    /** {@link #window} as served by quality {@code variant} (its own segment hosts). */
    private static String quality(String variant, long first, String kinds) {
        return window(first, kinds).replace("https://s/", "https://" + variant + "/");
    }

    /**
     * A quality switch keeps the numbers. From a tester's log: 720p was polled at 22889, then 480p for
     * five minutes; on its own numbering 720p came back at 23062 as 22904 and the player stood still
     * for the 158 segments it took to catch up (nothing wrong with the network or the stream).
     */
    private static void checkVariantsShareNumbers() {
        MediaSequencer seq = new MediaSequencer();
        seq.rewrite("720", "primary", quality("720", 22889, "lll"));
        String active = null;
        for (long first = 22889; first <= 23062; first++) {
            active = seq.rewrite("480", "primary", quality("480", first, "lll"));
        }
        assert active.contains("#EXT-X-MEDIA-SEQUENCE:23062\n") : active;
        String woken = seq.rewrite("720", "primary", quality("720", 23062, "lll"));
        assert woken.contains("#EXT-X-MEDIA-SEQUENCE:23062\n") && !woken.contains("DISCONTINUITY") : woken;
        assert woken.contains("https://720/23064.ts") && !woken.contains("https://480/") : woken;
        // The other variant's next poll: a segment first seen on 720p keeps its number, no jump.
        woken = seq.rewrite("720", "primary", quality("720", 23063, "lll"));
        active = seq.rewrite("480", "primary", quality("480", 23063, "lll"));
        assert woken.contains("#EXT-X-MEDIA-SEQUENCE:23063\n") && active.contains("#EXT-X-MEDIA-SEQUENCE:23063\n") : active;
        assert !active.contains("DISCONTINUITY") : active;

        // With the live numbering too, and a variant seen for the first time.
        seq = new MediaSequencer();
        seq.rewrite("480", "primary", session("480", 15, 0, 4, 0));
        String first = seq.rewrite("audio", "primary", session("audio", 15, 2, 6, 0));
        assert first.contains("#EXT-X-MEDIA-SEQUENCE:17\n") && !first.contains("DISCONTINUITY") : first;

        // Cut ads shift the numbers: a variant away during the cut comes back on the shifted ones.
        seq = new MediaSequencer();
        seq.rewrite("720", "primary", quality("720", 100, "l"));
        seq.rewrite("480", "primary", quality("480", 100, "lll"));
        seq.rewrite("480", "primary", quality("480", 101, "llaa"));
        assert seq.rewrite("480", "primary", quality("480", 103, "aaaa")) == null;
        // Nothing to serve: each variant holds its own playlist, not the other's segments.
        assert seq.last("480").contains("https://480/102.ts") && !seq.last("480").contains("https://720/");
        assert seq.last("720").contains("https://720/100.ts") && !seq.last("720").contains("https://480/");
        active = seq.rewrite("480", "primary", quality("480", 106, "aall"));
        assert active.contains("#EXT-X-MEDIA-SEQUENCE:103\n#EXT-X-DISCONTINUITY\n") : active;
        woken = seq.rewrite("720", "primary", quality("720", 108, "ll"));
        assert woken.contains("#EXT-X-MEDIA-SEQUENCE:103\n#EXT-X-DISCONTINUITY\n") : woken;
        assert woken.contains("https://720/108.ts") && !woken.contains("https://480/") : woken;
        // The cut leaves the window on one variant: the count is the stream's.
        seq.rewrite("480", "primary", quality("480", 109, "lll"));
        woken = seq.rewrite("720", "primary", quality("720", 109, "lll"));
        assert woken.contains("#EXT-X-MEDIA-SEQUENCE:104\n#EXT-X-DISCONTINUITY-SEQUENCE:1\n") : woken;

        // A backup serves every variant alike; one that sees the break's end late can't replay it.
        seq = new MediaSequencer();
        seq.rewrite("480", "primary", timed(100, 0, 4));
        seq.rewrite("480", "backup b", timed(5000, 1, 5)); // only 5 is new: 105
        woken = seq.rewrite("720", "backup b", timed(5000, 2, 6));
        assert woken.contains("#EXT-X-MEDIA-SEQUENCE:105\n#EXT-X-DISCONTINUITY\n") && woken.contains("/5006.ts") : woken;
        woken = seq.rewrite("720", "primary", timed(100, 3, 7));
        assert woken.contains("#EXT-X-MEDIA-SEQUENCE:107\n") && woken.contains("#EXT-X-DISCONTINUITY\n") : woken;
        assert woken.contains("/107.ts") && !woken.contains("/104.ts") : woken;
    }

    /** Live window of segments {@code from..to} of one broadcast, numbered from {@code base}, timed by broadcast index. */
    private static String timed(long base, int from, int to) {
        StringBuilder b = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:" + (base + from) + "\n");
        for (int i = from; i <= to; i++) {
            b.append("#EXT-X-PROGRAM-DATE-TIME:").append(java.time.Instant.ofEpochMilli(1_700_000_000_000L + i * 2000L))
                    .append("\n#EXTINF:2.000,live\nhttps://s/").append(base + i).append(".ts\n");
        }
        return b.toString();
    }

    /** A source switch continues after what was played instead of replaying the new source's window. */
    private static void checkSourceSwitchByTime() {
        MediaSequencer seq = new MediaSequencer();
        String out = seq.rewrite("v", "primary", timed(100, 0, 4)); // served up to broadcast segment 4
        assert out.contains("#EXT-X-MEDIA-SEQUENCE:100\n") : out;
        // Backup (own numbers) whose window overlaps 1..4 and adds 5: only 5 goes out, as 105.
        out = seq.rewrite("v", "backup b", timed(5000, 1, 5));
        assert out.contains("#EXT-X-MEDIA-SEQUENCE:105\n#EXT-X-DISCONTINUITY\n") && out.contains("/5005.ts") : out;
        assert !out.contains("/5004.ts") : out;
        out = seq.rewrite("v", "backup b", timed(5000, 2, 6));
        assert out.contains("#EXT-X-MEDIA-SEQUENCE:105\n") && out.contains("/5006.ts") && !out.contains("/5003.ts") : out;
        // A backup still behind what was played: nothing new (the caller holds the last playlist).
        assert seq.rewrite("v", "backup c", timed(9000, 2, 5)) == null;
        // Back to primary: resumes at broadcast segment 7.
        out = seq.rewrite("v", "primary", timed(100, 3, 7));
        assert out.contains("#EXT-X-MEDIA-SEQUENCE:107\n") && out.contains("#EXT-X-DISCONTINUITY\n") : out;
        assert out.contains("/107.ts") && !out.contains("/106.ts") && !out.contains("/104.ts") : out;
    }
}
