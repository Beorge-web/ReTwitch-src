package tv.retwitch.mod.emotes;

import java.util.HashMap;
import java.util.Map;

/**
 * BTTV / FFZ emote modifier tokens, mirroring the browser extension's
 * {@code EMOTE_MODIFIERS} table (reyohoho-twitch-extension
 * {@code modules/chat/index.js}).
 *
 * <ul>
 *   <li><b>Prefix</b> (BTTV, plain text, not emotes): {@code w! KEKW} --
 *       applies to the emote that follows.</li>
 *   <li><b>Suffix</b> (FFZ "modifier" emotes from the global set):
 *       {@code KEKW ffzX} -- applies to the emote that precedes it.</li>
 * </ul>
 * Modifier words are collapsed to zero width by {@link EmoteChatHook}; the
 * effect itself is drawn by {@link ModifiedEmoteDrawable}.
 */
final class EmoteModifiers {

    static final int WIDE = 1;
    static final int FLIP_X = 1 << 1;
    static final int FLIP_Y = 1 << 2;
    static final int ZERO_SPACE = 1 << 3;
    static final int CURSED = 1 << 4;
    static final int ROTATE_LEFT = 1 << 5;
    static final int ROTATE_RIGHT = 1 << 6;
    static final int PARTY = 1 << 7;
    static final int SHAKE = 1 << 8;

    private static final Map<String, Integer> PREFIX = new HashMap<>();
    private static final Map<String, Integer> SUFFIX = new HashMap<>();

    static {
        PREFIX.put("w!", WIDE);
        PREFIX.put("h!", FLIP_X);
        PREFIX.put("v!", FLIP_Y);
        PREFIX.put("z!", ZERO_SPACE);
        PREFIX.put("c!", CURSED);
        PREFIX.put("l!", ROTATE_LEFT);
        PREFIX.put("r!", ROTATE_RIGHT);
        PREFIX.put("p!", PARTY);
        PREFIX.put("s!", SHAKE);
        SUFFIX.put("ffzW", WIDE);
        SUFFIX.put("ffzX", FLIP_X);
        SUFFIX.put("ffzY", FLIP_Y);
        SUFFIX.put("ffzCursed", CURSED);
    }

    private EmoteModifiers() {
    }

    /** @return the modifier flag of a BTTV prefix token, or 0. */
    static int prefix(String word) {
        Integer flag = PREFIX.get(word);
        return flag != null ? flag : 0;
    }

    /** @return the modifier flag of an FFZ suffix token, or 0. */
    static int suffix(String word) {
        Integer flag = SUFFIX.get(word);
        return flag != null ? flag : 0;
    }

    /** Flags that change how the emote image itself is painted. */
    static int drawFlags(int flags) {
        return flags & ~ZERO_SPACE;
    }
}
