package tv.retwitch.mod;

import android.util.Log;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Twitch emotes in VOD chat. The server gives an emote fragment's {@code from}/{@code to} in UTF-8 bytes of
 * the message, and the app cuts its Java string with them: after Cyrillic (two bytes a letter) or an emoji
 * the range lands past the word and the emote stays text. Twitch's bug, in the stock app too. On entry of the
 * chomment message model (31.9.0 {@code n67}, body = the joined fragment texts) the ranges become char ranges.
 */
public final class VodChatEmotes {

    private static final String TAG = "RTVodChat";
    private static volatile Field begin;
    private static volatile Field end;

    private VodChatEmotes() {
    }

    /** Injected on entry of the message model's constructor: fixes {@code EmoticonModel.begin/end} in place. Never throws. */
    public static void fix(String body, List<?> emotes) {
        try {
            if (body == null || emotes == null || emotes.isEmpty() || isAscii(body) || !Toggles.on(Toggles.VOD_CHAT_EMOTES)) {
                return;
            }
            for (Object emote : emotes) {
                if (begin == null) {
                    end = emote.getClass().getField("end");
                    begin = emote.getClass().getField("begin");
                }
                int[] range = charRange(body, begin.getInt(emote), end.getInt(emote));
                if (range != null) {
                    begin.setInt(emote, range[0]);
                    end.setInt(emote, range[1]);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "fix failed", t);
            CrashReporter.report(TAG, "fix failed", t);
        }
    }

    /**
     * The char range of {@code body} for the inclusive UTF-8 byte range [from, to], or null to keep the range:
     * it already sits on a word (char offsets, or no multi-byte text before it) or the bytes don't map.
     */
    static int[] charRange(String body, int from, int to) {
        if (onWord(body, from, to)) {
            return null;
        }
        int b = charAt(body, from, false);
        int e = charAt(body, to, true);
        return b >= 0 && e >= b ? new int[]{b, e} : null;
    }

    /** Index of the char holding byte {@code offset}; for {@code last}, a surrogate pair's second char. -1 past the end. */
    private static int charAt(String s, int offset, boolean last) {
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int width = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            int chars = Character.charCount(cp);
            if (offset < bytes + width) {
                return offset < bytes ? -1 : last ? i + chars - 1 : i;
            }
            bytes += width;
            i += chars;
        }
        return -1;
    }

    private static boolean onWord(String s, int b, int e) {
        return b >= 0 && e >= b && e < s.length()
                && (b == 0 || Character.isWhitespace(s.charAt(b - 1)))
                && (e == s.length() - 1 || Character.isWhitespace(s.charAt(e + 1)));
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x80) {
                return false;
            }
        }
        return true;
    }
}
