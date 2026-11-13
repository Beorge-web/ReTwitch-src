package tv.retwitch.mod;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * JVM self-check for the recordings' pure parts: {@link Records} (segment list, chat clock on the file's time,
 * playlist, chat index), {@link ChatTape} lines, {@link LiveRecord} positions and {@link RecordSettings#resumable}.
 * Run from kitchen/ (org.json's real jar first at run time: android.jar only has stubs, and it must not be on the
 * compile classpath, where its unchecked JSONException breaks other files):
 *   javac -cp /opt/android-sdk/platforms/android-36/android.jar:libs/okhttp-4.12.0.jar:libs/okio-jvm-3.6.0.jar:libs/kotlin-stdlib-2.0.0.jar:$(ls libs/appmetrica-*.jar | tr '\n' ':') -d work/selfcheck mod/src/tv/retwitch/mod/ads/*.java mod/src/tv/retwitch/mod/*.java mod/src/tv/retwitch/mod/emotes/*.java mod/test/tv/retwitch/mod/RecordsSelfCheck.java
 *   java -ea -cp ~/.gradle/caches/modules-2/files-2.1/org.json/json/20180813/*&#47;json-20180813.jar:work/selfcheck:/opt/android-sdk/platforms/android-36/android.jar tv.retwitch.mod.RecordsSelfCheck
 */
public final class RecordsSelfCheck {

    public static void main(String[] args) throws Exception {
        // The segment list: a line cut by a killed process (no newline) is not a segment.
        Records.Index index = Records.parseIndex("10.000\t1000\t0\n10.000\t1200\t10000\n4.500\t300\t20000\n2.0\t9");
        assert index.count == 3 : index.count;
        assert index.total == 24.5 : index.total;
        assert index.totalBytes == 2500;
        assert index.offset[2] == 2200 && index.start[2] == 20.0;
        assert index.end() == 24_500;
        assert Records.parseIndex("").count == 0;
        assert Records.parseIndex("garbage\n1\t2\t3\n").count == 0; // stops at the first bad line

        // A chat clock on the file's time; a live recording with a 60 s gap (cut ads) after segment 1.
        Records.Index live = Records.parseIndex("2.000\t10\t1000000\n2.000\t10\t1002000\n2.000\t10\t1064000\n");
        assert Records.fileSeconds(live, 999_000) == -1; // before the recording
        assert Records.fileSeconds(live, 1_000_500) == 0.5;
        assert Records.fileSeconds(live, 1_003_000) == 3.0;
        assert Records.fileSeconds(live, 1_030_000) == 4.0; // in the gap: where the video picks up
        assert Records.fileSeconds(live, 1_065_000) == 5.0;
        assert Records.fileSeconds(live, 9_999_999) == 6.0; // past the end: the end
        assert !live.gapBefore(1) && live.gapBefore(2);

        String ts = Records.playlist(live, false);
        assert ts.startsWith("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n") : ts;
        assert ts.contains("#EXTINF:2.000,\ns/0.ts\n#EXTINF:2.000,\ns/1.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:2.000,\ns/2.ts\n");
        assert ts.endsWith("#EXT-X-ENDLIST\n");
        assert !ts.contains("EXT-X-MAP");
        String fmp4 = Records.playlist(index, true);
        assert fmp4.contains("#EXT-X-VERSION:7\n") && fmp4.contains("#EXT-X-MAP:URI=\"init.mp4\"\n")
                && fmp4.contains("s/2.mp4") && fmp4.contains("#EXT-X-TARGETDURATION:10\n") : fmp4;
        assert !fmp4.contains("DISCONTINUITY");

        // The chat index: the clock read from a line's first bytes, the order, the count up to a time.
        assert Records.lineClock(bytes("{\"t\":123456,\"u\":\"x\"}"), 20) == 123456;
        assert Records.lineClock(bytes("{\"t\":-5,"), 8) == -5;
        assert Records.lineClock(bytes("{\"u\":1}"), 7) == Long.MIN_VALUE;
        assert Records.lineClock(bytes("{\"t\":}"), 6) == Long.MIN_VALUE;
        long[] times = {5, 3, 3, 9, 1};
        assert Arrays.equals(Records.order(times, 5), new int[]{4, 1, 2, 0, 3}) : Arrays.toString(Records.order(times, 5));
        assert Arrays.equals(Records.order(new long[]{1, 2, 2, 7}, 4), new int[]{0, 1, 2, 3});
        long[] sorted = {1000, 2000, 2000, 5000};
        assert Records.upTo(sorted, 4, 999) == 0;
        assert Records.upTo(sorted, 4, 2000) == 3;
        assert Records.upTo(sorted, 4, 99_999) == 4;
        assert Records.upTo(sorted, 0, 99_999) == 0;

        // IRC: tags, emote ranges in code points (an emoji before the emote), /me, escapes; not PRIVMSG -> null.
        String irc = "@badge-info=subscriber/14;badges=subscriber/12,premium/1;color=#1E90FF;display-name=Мишка;"
                + "emotes=25:2-6;id=abc;room-id=1;tmi-sent-ts=1762600000123;user-id=42;flags= "
                + ":mishka!mishka@mishka.tmi.twitch.tv PRIVMSG #lofigirl :😀 Kappa hi";
        JSONObject line = new JSONObject(ChatTape.fromIrc(irc));
        assert line.getLong("t") == 1762600000123L;
        assert ChatTape.fromIrc(irc).startsWith("{\"t\":1762600000123,");
        assert "mishka".equals(line.getString("u")) && "Мишка".equals(line.getString("n"));
        assert "42".equals(line.getString("i")) && "#1E90FF".equals(line.getString("c"));
        assert "subscriber/12,premium/1".equals(line.getString("b"));
        JSONArray f = line.getJSONArray("f");
        assert f.length() == 3 : f;
        assert "😀 ".equals(f.getString(0));
        assert "Kappa".equals(f.getJSONArray(1).getString(0)) && "25".equals(f.getJSONArray(1).getString(1));
        assert " hi".equals(f.getString(2));
        assert !line.has("m");
        JSONObject me = new JSONObject(ChatTape.fromIrc("@display-name=A\\sB;tmi-sent-ts=5 :a!a@a PRIVMSG #c :\u0001ACTION waves\u0001"));
        assert me.getInt("m") == 1 && "waves".equals(me.getJSONArray("f").getString(0)) && "A B".equals(me.getString("n"));
        assert ChatTape.fromIrc("@id=1 :tmi.twitch.tv USERNOTICE #c :x") == null;
        assert ChatTape.fromIrc(":tmi.twitch.tv PING") == null;
        assert ChatTape.fragments("LUL", "1:0-2").length() == 1;
        assert ChatTape.fragments("ab", "1:5-9").getString(0).equals("ab"); // a range past the text stays text

        // A chat replay comment: the clock is the VOD offset in ms.
        JSONObject node = new JSONObject("{\"id\":\"x\",\"contentOffsetSeconds\":598,\"commenter\":{\"id\":\"7\","
                + "\"login\":\"zozero\",\"displayName\":\"Zozero\"},\"message\":{\"userColor\":null,\"userBadges\":"
                + "[{\"setID\":\"vip\",\"version\":\"1\"}],\"fragments\":[{\"text\":\"hi \",\"emote\":null},"
                + "{\"text\":\"OhMyDog\",\"emote\":{\"emoteID\":\"81103\"}}]}}");
        JSONObject comment = new JSONObject(ChatTape.fromComment(node));
        assert comment.getLong("t") == 598_000;
        assert "zozero".equals(comment.getString("u")) && "vip/1".equals(comment.getString("b"));
        assert !comment.has("c");
        assert "81103".equals(comment.getJSONArray("f").getJSONArray(1).getString(1));
        assert "{\"t\":7}".equals(ChatTape.line(7, new JSONObject()));

        // Live positions: PROGRAM-DATE-TIME, else following the one before.
        assert LiveRecord.parseTime("2026-11-08T12:00:01.500Z") == 1794139201500L : LiveRecord.parseTime("2026-11-08T12:00:01.500Z");
        assert LiveRecord.parseTime("2026-11-08T12:00:01Z") == 1794139201000L;
        assert LiveRecord.parseTime("2026-11-08T15:00:01.5+03:00") == 1794139201500L;
        assert LiveRecord.parseTime("") == 0 && LiveRecord.parseTime(null) == 0;
        List<String[]> segments = LiveRecord.segments("https://v/x.m3u8", "#EXTM3U\n"
                + "#EXT-X-PROGRAM-DATE-TIME:2026-11-08T12:00:01.500Z\n#EXTINF:2.000,live\na.ts\n#EXTINF:2.000,live\nb.ts\n");
        assert "2026-11-08T12:00:01.500Z".equals(segments.get(0)[2]) && segments.get(1)[2].isEmpty();
        long[] positions = LiveRecord.liveTimes(segments, 0, 1);
        assert positions[0] == 1794139201500L && positions[1] == 1794139203500L;
        assert LiveRecord.liveTimes(segments.subList(1, 2), 77_000, 1)[0] == 77_000;
        assert Arrays.equals(LiveRecord.offsets(LiveRecord.segments("https://v/v.m3u8",
                "#EXTINF:10,\n0.ts\n#EXTINF:4.5,\n1.ts\n#EXTINF:10,\n2.ts\n")), new long[]{0, 10_000, 14_500});

        // Which cut recordings can go on.
        assert RecordSettings.resumable(new JSONObject().put("kind", "archive").put("state", "stopped"));
        assert RecordSettings.resumable(new JSONObject().put("kind", "vod").put("state", "failed"));
        assert RecordSettings.resumable(new JSONObject().put("kind", "live").put("state", "running"));
        assert !RecordSettings.resumable(new JSONObject().put("kind", "live").put("state", "stopped"));
        assert !RecordSettings.resumable(new JSONObject().put("kind", "archive").put("state", "done"));
        assert !RecordSettings.resumable(new JSONObject().put("kind", "legacy").put("state", "done"));

        System.out.println("RecordsSelfCheck OK");
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
