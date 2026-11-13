package tv.retwitch.mod.emotes;

import tv.retwitch.mod.DeletedMessagesHook;

/** JVM self-check for chat row logic: highlight terms, nick paint colours/geometry, deleted-line header. Build/run like ads/PlaylistSelfCheck. */
public final class ChatLineSelfCheck {

    public static void main(String[] args) {
        MentionHighlighter.Terms terms = new MentionHighlighter.Terms(" Kappa , @SomeOne,, привет ");
        assert terms.inBody("lol kappa!");
        assert terms.inBody("ПРИВЕТ всем");
        assert !terms.inBody("kappahd");
        assert !terms.inBody("xkappa");
        assert terms.byNick("12:00 someone: hi");
        assert !terms.byNick("other: @someone: hi");
        assert new MentionHighlighter.Terms("").isEmpty();
        assert new MentionHighlighter.Terms("a.b").inBody("x a.b y") && !new MentionHighlighter.Terms("a.b").inBody("axb");

        assert NickPaint.rgbaToArgb(0xFF0000FFL) == 0xFFFF0000;
        assert NickPaint.rgbaToArgb(-16776961L) == 0xFFFF0000; // same, as a signed int32
        assert NickPaint.rgbaToArgb(0x00FF0080L) == 0x8000FF00;
        float[] right = NickPaint.linearEnds(90, 100, 20);
        assert Math.abs(right[0]) < 0.01 && Math.abs(right[2] - 100) < 0.01 && Math.abs(right[1] - 10) < 0.01;
        float[] down = NickPaint.linearEnds(180, 100, 20);
        assert Math.abs(down[1]) < 0.01 && Math.abs(down[3] - 20) < 0.01;

        assert "hello".equals(DeletedMessagesHook.stripHeader("name: hello", "name: ").toString());
        assert "hello".equals(DeletedMessagesHook.stripHeader("⁧name: hello⁩", "name: ").toString());
        assert "hello".equals(DeletedMessagesHook.stripHeader("hello", "name: ").toString());
        assert "hello".equals(DeletedMessagesHook.stripHeader("hello", "").toString());
        assert ChatCosmetics.reformatName("Имя (login_1)", tv.retwitch.mod.ChatSettings.NAME_LOGIN).equals("login_1");
        assert ChatCosmetics.reformatName("Имя (login_1)", tv.retwitch.mod.ChatSettings.NAME_DISPLAY).equals("Имя");
        assert ChatCosmetics.reformatName("Имя (login_1)", tv.retwitch.mod.ChatSettings.NAME_BOTH) == null;
        assert ChatCosmetics.reformatName("plain", tv.retwitch.mod.ChatSettings.NAME_LOGIN) == null;
        assert ChatCosmetics.reformatName("名字 (a b)", tv.retwitch.mod.ChatSettings.NAME_LOGIN) == null;
        System.out.println("ChatLineSelfCheck OK");
    }
}
