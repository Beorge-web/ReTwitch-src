package tv.retwitch.mod;

import java.nio.charset.StandardCharsets;

/**
 * JVM self-check for {@link VodChatEmotes#charRange}. Run from kitchen/:
 *   javac -nowarn -source 8 -target 8 -bootclasspath /opt/android-sdk/platforms/android-36/android.jar:/opt/android-sdk/build-tools/37.0.0/core-lambda-stubs.jar -cp "libs/*" -d work/selfcheck $(find mod/src -name '*.java') mod/test/tv/retwitch/mod/VodChatEmotesSelfCheck.java
 *   java -ea -cp work/selfcheck tv.retwitch.mod.VodChatEmotesSelfCheck
 */
public final class VodChatEmotesSelfCheck {

    public static void main(String[] args) {
        // Real server ranges (lasqa VOD, 2026-11-08): "from" is the UTF-8 byte offset.
        assertCode("@bibus_dikkus Приветик LuvLuvLUV", "LuvLuvLUV");
        assertCode("уйди опять 4Head", "4Head");
        assertCode("ЛАСКА. НАСРАЛ. ПОБЕДА WW Kappa хаха", "Kappa");
        // Emoji: 4 bytes, 2 chars.
        assertCode("😂 Kappa 😂 Kappa", "Kappa");
        // Two emotes after Cyrillic.
        String body = "Привет Kappa и LUL";
        int[] second = bytes(body, "LUL");
        int[] r = VodChatEmotes.charRange(body, second[0], second[1]);
        assert "LUL".equals(body.substring(r[0], r[1] + 1)) : body;

        // A range already on the word (char offsets, or ASCII before it) is kept.
        assert VodChatEmotes.charRange("Kappa привет", 0, 4) == null;
        assert VodChatEmotes.charRange("привет Kappa", 7, 11) == null;
        // Out of range bytes: kept, no crash.
        assert VodChatEmotes.charRange("привет", 40, 44) == null;
        System.out.println("VodChatEmotesSelfCheck OK");
    }

    private static void assertCode(String body, String code) {
        int[] b = bytes(body, code);
        int[] r = VodChatEmotes.charRange(body, b[0], b[1]);
        assert r != null && code.equals(body.substring(r[0], r[1] + 1)) : body + " -> " + (r == null ? null : r[0] + ".." + r[1]);
    }

    /** The server's range for the first {@code code}: UTF-8 bytes, inclusive end. */
    private static int[] bytes(String body, String code) {
        int at = body.indexOf(code);
        int from = body.substring(0, at).getBytes(StandardCharsets.UTF_8).length;
        return new int[]{from, from + code.getBytes(StandardCharsets.UTF_8).length - 1};
    }
}
