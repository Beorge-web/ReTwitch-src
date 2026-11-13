package tv.retwitch.mod;

import java.util.Map;

/** JVM self-check for {@link RecentMessages#parse}; build/run like LiveRecordSelfCheck, main class tv.retwitch.mod.RecentMessagesSelfCheck. */
public final class RecentMessagesSelfCheck {

    public static void main(String[] args) {
        Object[] m = RecentMessages.parse("@badges=;display-name=Some\\sName;id=abc;room-id=1;tmi-sent-ts=1700000000000"
                + " :some!some@some.tmi.twitch.tv PRIVMSG #chan :hello :) there");
        assert m != null;
        Map<?, ?> tags = (Map<?, ?>) m[0];
        assert "Some Name".equals(tags.get("display-name")) : tags.get("display-name");
        assert "abc".equals(tags.get("id")) && "hello :) there".equals(m[1]) : m[1];
        assert RecentMessages.parse("@login=x;room-id=1 :tmi.twitch.tv CLEARCHAT #chan :x") == null;
        assert RecentMessages.parse(":tmi.twitch.tv PRIVMSG #chan :no tags") == null;
        assert RecentMessages.parse("@id=1 PRIVMSG #chan :no prefix") != null;
        assert "a;b\\c".equals(RecentMessages.unescape("a\\:b\\\\c"));
        System.out.println("RecentMessagesSelfCheck OK");
    }
}
