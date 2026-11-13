package tv.retwitch.mod.emotes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;

/** JVM self-check for the pure parts of {@link ChatDecor}. Build/run like ads/PlaylistSelfCheck. */
public final class ChatDecorSelfCheck {

    public static void main(String[] args) {
        assert names("@xqc hi").equals(Arrays.asList("xqc"));
        assert names("hi @Some_User, and @other!").equals(Arrays.asList("Some_User", "other"));
        assert names("@Вася: привет").equals(Arrays.asList("Вася"));
        // Not a mention: an e-mail, a bare @.
        assert names("mail me a@b.ru").isEmpty();
        assert names("@ alone").isEmpty();
        assert names("(@paren)").equals(Arrays.asList("paren"));

        assert !ChatDecor.isAction("xqc: ");
        assert !ChatDecor.isAction("xqc:");
        assert ChatDecor.isAction("xqc ");
        assert ChatDecor.isAction("xqc");
        System.out.println("ChatDecorSelfCheck OK");
    }

    private static List<String> names(String text) {
        List<String> out = new ArrayList<>();
        Matcher matcher = ChatDecor.MENTION.matcher(text);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return out;
    }
}
