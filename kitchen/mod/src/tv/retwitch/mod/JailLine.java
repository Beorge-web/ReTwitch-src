package tv.retwitch.mod;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Synthetic IRC lines for {@link JailChat}: a jail message as the PRIVMSG Twitch's chat SDK
 * would get from the server, and the frame it rides in. Pure (no Android), checked by
 * {@code JailLineSelfCheck}. Every tag is optional for Twitch's parser (31.9.0 {@code pgz.S}
 * / {@code pgz.R}), so only the ones that drive the look are set.
 */
final class JailLine {

    static final int MAX_TEXT = 500;

    private JailLine() {
    }

    /** The PRIVMSG line, or null when the channel or text is unusable. */
    static String privmsg(String channel, String roomId, String userId, String login, String displayName,
                          String color, String id, String text, long sentAtMs) {
        channel = channel == null ? "" : channel.toLowerCase(Locale.ROOT);
        if (!channel.matches("[a-z0-9_]+")) {
            return null;
        }
        // CR/LF would split the line, \u0001 would make it a CTCP ACTION.
        text = text == null ? "" : text.replaceAll("[\\r\\n\\u0001]", " ").trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT);
        }
        String nick = loginOf(login);
        if (nick.isEmpty()) {
            nick = loginOf(displayName);
        }
        if (nick.isEmpty()) {
            nick = "rte";
        }
        String name = displayName == null || displayName.trim().isEmpty() ? nick : displayName.trim();
        String msgId = id == null ? "" : id.replaceAll("[^0-9A-Za-z-]", "");
        if (msgId.isEmpty()) {
            msgId = UUID.randomUUID().toString();
        }
        StringBuilder sb = new StringBuilder("@badges=;color=");
        if (color != null && color.matches("#[0-9A-Fa-f]{6}")) {
            sb.append(color);
        }
        sb.append(";display-name=").append(tag(name)).append(";emotes=;id=").append(msgId);
        if (roomId != null && roomId.matches("[0-9]+")) {
            sb.append(";room-id=").append(roomId);
        }
        sb.append(";tmi-sent-ts=").append(sentAtMs);
        if (userId != null && userId.matches("[0-9]+")) {
            sb.append(";user-id=").append(userId);
        }
        sb.append(" :").append(nick).append('!').append(nick).append('@').append(nick)
                .append(".tmi.twitch.tv PRIVMSG #").append(channel).append(" :").append(text);
        return sb.toString();
    }

    /** {@code frame} with {@code lines} appended, one per line, without blank lines (Twitch reports those). */
    static String merge(String frame, List<String> lines) {
        StringBuilder sb = new StringBuilder(frame.trim());
        for (String line : lines) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    /** IRCv3 tag value escaping. */
    static String tag(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case ';': sb.append("\\:"); break;
                case ' ': sb.append("\\s"); break;
                case '\r': sb.append("\\r"); break;
                case '\n': sb.append("\\n"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String loginOf(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
    }
}
