package tv.retwitch.mod;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * JVM self-check for the pure chat-event logic: blacklist matching, mod-log and IRC ban lines.
 * Run from kitchen/:
 *   javac -cp /opt/android-sdk/platforms/android-36/android.jar:libs/okhttp-4.12.0.jar:libs/okio-jvm-3.6.0.jar:libs/kotlin-stdlib-2.0.0.jar:libs/bugsnag-android-core-6.11.0.jar -d work/selfcheck mod/src/tv/retwitch/mod/ads/*.java mod/src/tv/retwitch/mod/*.java mod/src/tv/retwitch/mod/emotes/*.java mod/test/tv/retwitch/mod/ChatEventsSelfCheck.java
 *   java -ea -cp work/selfcheck:/opt/android-sdk/platforms/android-36/android.jar:libs/okhttp-4.12.0.jar:libs/okio-jvm-3.6.0.jar:libs/kotlin-stdlib-2.0.0.jar tv.retwitch.mod.ChatEventsSelfCheck
 */
public final class ChatEventsSelfCheck {

    public static void main(String[] args) {
        // Whole words, any case, phrases, Cyrillic.
        Pattern p = ChatBlacklist.compile("cat\n  \nBad Word\nспойлер\n");
        assert p.matcher("I love my CAT!").find();
        assert !p.matcher("category").find();
        assert !p.matcher("bobcat").find();
        assert p.matcher("that is a bad word here").find();
        assert !p.matcher("badword").find();
        assert p.matcher("Осторожно, СПОЙЛЕР: ...").find();
        assert !p.matcher("спойлеры").find();
        // Regex characters in an entry are literal.
        Pattern q = ChatBlacklist.compile("a.b\n(x)");
        assert q.matcher("say a.b now").find() && !q.matcher("say axb now").find();
        assert q.matcher("(x)").find();
        assert ChatBlacklist.compile("\n \n") == null;

        Set<String> users = ChatBlacklist.parseUsers("@SomeUser\n other \n\n");
        assert users.equals(new java.util.HashSet<>(Arrays.asList("someuser", "other")));
        assert ChatBlacklist.hidesSender(Arrays.asList("123", "someuser", "SomeUser"), users);
        assert !ChatBlacklist.hidesSender(Arrays.asList("123", "nobody"), users);

        Locale.setDefault(Locale.ENGLISH);
        assert "10 min".equals(ModLogs.duration("600"));
        assert "1 d".equals(ModLogs.duration("86400"));
        assert "90 s".equals(ModLogs.duration("90"));
        assert "x".equals(ModLogs.duration("x"));
        assert "⚖ mod timed out bob for 10 min, reason: spam"
                .equals(ModLogs.describe("timeout", "mod", "bob", Arrays.asList("bob", "600", "spam")));
        assert "⚖ mod banned bob".equals(ModLogs.describe("ban", "mod", "bob", Arrays.asList("bob", "")));
        assert "⚖ A moderator deleted a message from bob: \"hi\""
                .equals(ModLogs.describe("delete", "", "bob", Arrays.asList("bob", "hi", "id")));
        assert ModLogs.describe("mod", "mod", "bob", Collections.<String>emptyList()) == null;

        // IRC CLEARCHAT / CLEARMSG lines (tags as Twitch's parser leaves them, content = trailing param).
        assert "[timeout, bob, ⚖ bob was timed out for 10 min]".equals(Arrays.toString(ModLogs.describeIrc(
                "CLEARCHAT", tags("room-id", "1", "target-user-id", "42", "ban-duration", "600"), "bob")));
        assert "[ban, bob, ⚖ bob was permanently banned]".equals(Arrays.toString(ModLogs.describeIrc(
                "CLEARCHAT", tags("room-id", "1", "target-user-id", "42"), "bob")));
        assert ModLogs.describeIrc("CLEARCHAT", tags("room-id", "1"), null) == null;
        assert ModLogs.describeIrc("CLEARCHAT", tags("target-user-id", "0"), "bob") == null;
        assert ModLogs.describeIrc("CLEARCHAT", tags("target-user-id", "42"), "") == null;
        assert "[delete, Bob, ⚖ A message from Bob was deleted]".equals(Arrays.toString(ModLogs.describeIrc(
                "CLEARMSG", tags("login", "Bob", "target-msg-id", "abc"), "hi")));
        assert ModLogs.describeIrc("CLEARMSG", tags("target-msg-id", "abc"), "hi") == null;
        assert ModLogs.describeIrc("PRIVMSG", tags("login", "bob"), "hi") == null;
        Locale.setDefault(new Locale("ru"));
        assert "⚖ bob отстранён на 90 с".equals(ModLogs.describeIrc(
                "CLEARCHAT", tags("target-user-id", "42", "ban-duration", "90"), "bob")[2]);
        assert "⚖ Сообщение bob удалено".equals(ModLogs.describeIrc(
                "CLEARMSG", tags("login", "bob", "target-msg-id", "x"), "")[2]);
        Locale.setDefault(Locale.ENGLISH);

        // The same action on the same user from IRC and PubSub: one line within 5 s.
        assert ModLogs.claim("timeout", "Bob", 1000);
        assert !ModLogs.claim("timeout", "bob", 3000);
        assert ModLogs.claim("ban", "bob", 3000);
        assert ModLogs.claim("timeout", "bob", 7000);

        // Fields of the IRC model by type.
        Field[] f = ModLogs.ircFields(FakeIrc.class);
        assert f != null && "a".equals(f[0].getName()) && "c".equals(f[1].getName()) && "f".equals(f[2].getName());

        System.out.println("ChatEventsSelfCheck OK");
    }

    private static Map<String, String> tags(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** The shape of 31.8.0 {@code lim}: messageTags, prefix, command, channel, ctcpEvent, content. */
    @SuppressWarnings("unused")
    static final class FakeIrc {
        enum Command { PRIVMSG, CLEARCHAT, CLEARMSG }

        // Like 31.8.0 g8c: the CTCP enum also has a CLEARCHAT constant, after the command field.
        enum Ctcp { NONE, ACTION, CLEARCHAT }

        static String STATIC_NOISE;
        final Map<String, String> a = null;
        final String b = null;
        final Command c = null;
        final String d = null;
        final Ctcp e = null;
        final String f = null;
    }
}
