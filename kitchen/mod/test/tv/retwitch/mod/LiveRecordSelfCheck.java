package tv.retwitch.mod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * JVM self-check for the pure parts of {@link LiveRecord} and {@link RecordSettings#login}. Run from kitchen/:
 *   javac -cp /opt/android-sdk/platforms/android-36/android.jar:libs/okhttp-4.12.0.jar:libs/okio-jvm-3.6.0.jar:libs/kotlin-stdlib-2.0.0.jar:$(ls libs/appmetrica-*.jar | tr '\n' ':') -d work/selfcheck mod/src/tv/retwitch/mod/ads/*.java mod/src/tv/retwitch/mod/*.java mod/src/tv/retwitch/mod/emotes/*.java mod/test/tv/retwitch/mod/LiveRecordSelfCheck.java
 *   java -ea -cp work/selfcheck:/opt/android-sdk/platforms/android-36/android.jar:libs/okhttp-4.12.0.jar:libs/okio-jvm-3.6.0.jar:libs/kotlin-stdlib-2.0.0.jar tv.retwitch.mod.LiveRecordSelfCheck
 */
public final class LiveRecordSelfCheck {

    public static void main(String[] args) {
        // An archive (EVENT) playlist: relative segments, EXTINF durations, no ENDLIST yet.
        String archive = "#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:10\n#EXT-X-PLAYLIST-TYPE:EVENT\n"
                + "#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:10.000,\n0.ts\n#EXTINF:10.000,\n1.ts\n#EXTINF:4.500,\n2-muted.ts\n";
        String base = "https://cdn.example/abc_123/chunked/index-dvr.m3u8";
        List<String[]> segments = LiveRecord.segments(base, archive);
        assert segments.size() == 3 : segments.size();
        assert "https://cdn.example/abc_123/chunked/0.ts".equals(segments.get(0)[0]) : segments.get(0)[0];
        assert "https://cdn.example/abc_123/chunked/2-muted.ts".equals(segments.get(2)[0]);
        assert LiveRecord.seconds(segments) == 24.5 : LiveRecord.seconds(segments);
        assert LiveRecord.seconds(segments.subList(2, 3)) == 4.5;
        assert LiveRecord.initOf(base, archive) == null;

        // A VOD fragment snaps out to whole segments: 0, 10 000, 20 000 ms starts.
        long[] starts = LiveRecord.offsets(segments);
        assert LiveRecord.segmentAt(starts, 0) == 0 && LiveRecord.segmentAt(starts, 9_999) == 0;
        assert LiveRecord.segmentAt(starts, 10_000) == 1 && LiveRecord.segmentAt(starts, 99_000) == 2;
        assert LiveRecord.segmentAfter(starts, 12_000, 1) == 2 : LiveRecord.segmentAfter(starts, 12_000, 1);
        assert LiveRecord.segmentAfter(starts, 20_000, 1) == 2;
        assert LiveRecord.segmentAfter(starts, 10_000, 1) == 2; // at least one segment
        assert LiveRecord.segmentAfter(starts, 99_000, 0) == 3;
        assert VodDownload.parseClock("1:02:03") == 3_723_000 && VodDownload.parseClock(" 62:03 ") == 3_723_000;
        assert VodDownload.parseClock("75") == 75_000 && VodDownload.parseClock("00:05") == 5_000;
        assert VodDownload.parseClock("") == -1 && VodDownload.parseClock("1:2:3:4") == -1 && VodDownload.parseClock("1,5") == -1;

        // fMP4: the init segment, resolved; CRLF lines.
        String fmp4 = "#EXTM3U\r\n#EXT-X-MAP:URI=\"init-0.mp4\"\r\n#EXTINF:2.002,live\r\nhttps://v.example/s1.mp4\r\n";
        assert "https://v.example/x/init-0.mp4".equals(LiveRecord.initOf("https://v.example/x/v.m3u8", fmp4));
        List<String[]> live = LiveRecord.segments("https://v.example/x/v.m3u8", fmp4);
        assert live.size() == 1 && "2.002".equals(live.get(0)[1]) : live.get(0)[1];

        // Live windows overlap: only new segments come out, prefetch hints are tags and never segments.
        Set<String> seen = new LinkedHashSet<>();
        String window1 = "#EXTM3U\n#EXTINF:2.000,live\nhttps://v/a.ts\n#EXTINF:2.000,live\nhttps://v/b.ts\n"
                + "#EXT-X-TWITCH-PREFETCH:https://v/c.ts\n";
        String window2 = "#EXTM3U\n#EXTINF:2.000,live\nhttps://v/b.ts\n#EXTINF:2.000,live\nhttps://v/c.ts\n";
        assert LiveRecord.unseen(LiveRecord.segments("https://v/p.m3u8", window1), seen).size() == 2;
        List<String[]> fresh = LiveRecord.unseen(LiveRecord.segments("https://v/p.m3u8", window2), seen);
        assert fresh.size() == 1 && "https://v/c.ts".equals(fresh.get(0)[0]) : fresh.size();
        assert LiveRecord.unseen(LiveRecord.segments("https://v/p.m3u8", window2), seen).isEmpty();
        // The memory stays bounded.
        for (int i = 0; i < 500; i++) {
            LiveRecord.unseen(LiveRecord.segments("https://v/p.m3u8", "#EXTINF:2,\nhttps://v/n" + i + ".ts\n"), seen);
        }
        assert seen.size() <= 200 : seen.size();

        // Quality: the same label, else the best not taller, else audio for audio, else the first.
        List<String[]> options = new ArrayList<>(Arrays.asList(
                new String[]{"Source · 1920x1080", "u/src"},
                new String[]{"720p60 · 1280x720", "u/720"},
                new String[]{"480p30 · 852x480", "u/480"},
                new String[]{"Audio only", "u/audio"}));
        assert "u/720".equals(LiveRecord.pick(options, "720p60 · 1280x720")[1]);
        assert "u/src".equals(LiveRecord.pick(options, "")[1]);
        assert "u/src".equals(LiveRecord.pick(options, "1080p60 · 1920x1080")[1]); // live label on an archive master
        assert "u/480".equals(LiveRecord.pick(options, "540p30 · 960x540")[1]);
        assert "u/audio".equals(LiveRecord.pick(options, "Только звук")[1]); // the label in the other language
        assert "u/src".equals(LiveRecord.pick(options, "4320p · 7680x4320")[1]);
        assert LiveRecord.height("1080p60 · 1920x1080") == 1080;
        assert LiveRecord.height("Source · 1920x1080") == 1080;
        assert LiveRecord.height("Audio only") == 0;

        // Auto-record's quality choice: a height picks the nearest not taller, audio_only the audio.
        assert "u/480".equals(LiveRecord.pick(options, "480p")[1]);
        assert "u/src".equals(LiveRecord.pick(options, "1080p")[1]); // the source is 1080
        assert "u/720".equals(LiveRecord.pick(options, "720p")[1]);
        assert "u/src".equals(LiveRecord.pick(options, "160p")[1]); // nothing that low: the first
        assert "u/audio".equals(LiveRecord.pick(options, "audio_only")[1]);
        assert AutoRecord.qualityIndex("") == 0 && AutoRecord.qualityIndex("Source (1080p60) · 1920x1080") == 0;
        assert AutoRecord.qualityIndex("720p60 · 1280x720") == 2 && AutoRecord.qualityIndex("936p60 · 1664x936") == 2;
        assert AutoRecord.qualityIndex("audio_only") == 6 && AutoRecord.qualityIndex("Только звук") == 6;
        assert AutoRecord.qualityIndex("144p · 256x144") == 5;
        for (int i = 0; i < AutoRecord.QUALITIES.length; i++) {
            assert AutoRecord.qualityIndex(AutoRecord.QUALITIES[i]) == i : AutoRecord.QUALITIES[i];
        }

        assert "00:05".equals(LiveRecord.clock(5.9));
        assert "42:13".equals(LiveRecord.clock(42 * 60 + 13));
        assert "1:02:03".equals(LiveRecord.clock(3723));

        assert "buster".equals(RecordSettings.login("Buster"));
        assert "kato_junichi0817".equals(RecordSettings.login("https://www.twitch.tv/kato_junichi0817/"));
        assert "lofigirl".equals(RecordSettings.login("twitch.tv/lofigirl"));
        assert RecordSettings.login("not a login") == null;
        assert RecordSettings.login("twitch.tv/lofigirl?x=1") == null;
        assert RecordSettings.login("") == null;

        System.out.println("LiveRecordSelfCheck OK");
    }
}
