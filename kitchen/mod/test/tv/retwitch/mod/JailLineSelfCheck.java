package tv.retwitch.mod;

import java.util.Arrays;
import java.util.Collections;

/**
 * JVM self-check for {@link JailLine}. Run from kitchen/:
 *   javac -d work/selfcheck mod/src/tv/retwitch/mod/JailLine.java mod/test/tv/retwitch/mod/JailLineSelfCheck.java
 *   java -ea -cp work/selfcheck tv.retwitch.mod.JailLineSelfCheck
 */
public final class JailLineSelfCheck {

    public static void main(String[] args) {
        String line = JailLine.privmsg("ReYohoho", "123", "52608844", "ReYohoho", "ReYohoho", "#FF4500",
                "a1b2-c3", "hello :) world", 1700000000000L);
        assert ("@badges=;color=#FF4500;display-name=ReYohoho;emotes=;id=a1b2-c3;room-id=123"
                + ";tmi-sent-ts=1700000000000;user-id=52608844"
                + " :reyohoho!reyohoho@reyohoho.tmi.twitch.tv PRIVMSG #reyohoho :hello :) world").equals(line) : line;

        // Line breaks and CTCP can't escape the line; tags are escaped; junk tags are dropped.
        line = JailLine.privmsg("chan", null, "x1", "", "Big Boss;x", "red", "<id>", "a\r\nb\u0001c", 1L);
        assert line.startsWith("@badges=;color=;display-name=Big\\sBoss\\:x;emotes=;id=id;tmi-sent-ts=1 :bigbossx!") : line;
        assert line.endsWith(" PRIVMSG #chan :a  b c") : line;
        assert !line.contains("user-id") && !line.contains("room-id") : line;
        assert line.indexOf('\n') < 0 && line.indexOf('\r') < 0;

        // No name at all, no id: still a valid line.
        line = JailLine.privmsg("chan", "", null, null, null, null, null, "hi", 1L);
        assert line.contains(":rte!rte@rte.tmi.twitch.tv PRIVMSG #chan :hi") : line;
        assert line.matches("@badges=;color=;display-name=rte;emotes=;id=[0-9a-f-]{36};tmi-sent-ts=1 .*") : line;

        assert JailLine.privmsg("bad chan", null, null, "a", "a", null, null, "hi", 1L) == null;
        assert JailLine.privmsg("chan", null, null, "a", "a", null, null, " \n ", 1L) == null;
        assert JailLine.privmsg("chan", null, null, "a", "a", null, null,
                new String(new char[600]).replace('\0', 'x'), 1L).endsWith(" :" + new String(new char[500]).replace('\0', 'x'));

        // Merge: real frame first, no blank lines even when the frame ends with CRLF.
        assert "A\r\nB\nX\nY".equals(JailLine.merge("A\r\nB\r\n", Arrays.asList("X", "Y")));
        assert "X".equals(JailLine.merge("  ", Collections.singletonList("X")));

        System.out.println("JailLineSelfCheck OK");
    }
}
