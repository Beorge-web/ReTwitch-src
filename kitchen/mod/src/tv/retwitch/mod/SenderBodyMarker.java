package tv.retwitch.mod;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Marks chat rows that belong to messages flagged in the chat event (by IRC
 * tags) when the row itself is only text. The flag is seen first, with the
 * sender model; the next chat row bound for that sender ("name: ...") is
 * remembered, and bound rows containing that text match. Used for FFZ giant
 * emotes.
 */
public final class SenderBodyMarker {

    private final Map<String, Boolean> pending = lru(64);
    private final Map<String, Boolean> bodies = lru(128);

    /** Flags the sender's next message (its login and display name, from the model's String fields). */
    public void markSender(Object sender) throws IllegalAccessException {
        synchronized (pending) {
            for (Field f : sender.getClass().getDeclaredFields()) {
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    Object name = f.get(sender);
                    if (name != null && !name.toString().isEmpty()) {
                        pending.put(name.toString().toLowerCase(Locale.ROOT), Boolean.TRUE);
                    }
                }
            }
        }
    }

    /** From the row bind ("badges name: message"): ties a flagged sender to this row's text. */
    public void onBody(CharSequence body) {
        String text = body.toString();
        String lower = text.toLowerCase(Locale.ROOT);
        synchronized (pending) {
            for (String name : pending.keySet()) {
                if (authoredBy(lower, name)) {
                    pending.remove(name);
                    synchronized (bodies) {
                        bodies.put(text, Boolean.TRUE);
                    }
                    return;
                }
            }
        }
    }

    /** Marks a row text directly, when the flag and the text are seen together. */
    public void markBody(CharSequence body) {
        synchronized (bodies) {
            bodies.put(body.toString(), Boolean.TRUE);
        }
    }

    /** "name:" as the row's author: a whole word, not "@name:" in a reply header. */
    public static boolean authoredBy(String lowerRow, String name) {
        String needle = name + ":";
        for (int at = lowerRow.indexOf(needle); at >= 0; at = lowerRow.indexOf(needle, at + 1)) {
            char prev = at > 0 ? lowerRow.charAt(at - 1) : ' ';
            if (!Character.isLetterOrDigit(prev) && prev != '_' && prev != '@') {
                return true;
            }
        }
        return false;
    }

    public boolean matches(CharSequence rowText) {
        if (rowText == null) {
            return false;
        }
        String haystack = rowText.toString();
        synchronized (bodies) {
            for (String body : bodies.keySet()) {
                if (haystack.contains(body)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static <K> Map<K, Boolean> lru(final int max) {
        return new LinkedHashMap<K, Boolean>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Boolean> eldest) {
                return size() > max;
            }
        };
    }
}
