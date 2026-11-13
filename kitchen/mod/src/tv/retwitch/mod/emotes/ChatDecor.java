package tv.retwitch.mod.emotes;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tv.retwitch.mod.ChatSettings;
import tv.retwitch.mod.CrashReporter;
import tv.retwitch.mod.Toggles;

/**
 * RTE's coloured chat text, applied where a row's parts come together (the span group constructor,
 * sender and message side by side): a /me message takes the sender's colour, an {@code @name} takes
 * the colour and nick paint of that user. Users are learned from the rows built so far, as RTE
 * learns them from the chat, so someone who hasn't written yet stays plain.
 */
public final class ChatDecor {

    private static final String TAG = "RTChatDecor";
    /** A name after @, not the tail of an e-mail. Display names can be non-Latin. */
    static final Pattern MENTION = Pattern.compile("(?<![\\p{L}\\p{N}_])@([\\p{L}\\p{N}_]+)");
    /** Under every other span of the message: links and mentions keep their own colour. */
    private static final int LOWEST = 255 << Spanned.SPAN_PRIORITY_SHIFT;

    private static final class User {
        final int color;
        final String id;

        User(int color, String id) {
            this.color = color;
            this.id = id;
        }
    }

    private static final Map<String, User> USERS = new LinkedHashMap<String, User>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, User> eldest) {
            return size() > 4000;
        }
    };
    /** Sender model of the span group being built on this thread, from {@link ChatCosmetics#injectBadges}. */
    private static final ThreadLocal<Object> SENDER = new ThreadLocal<>();

    private ChatDecor() {
    }

    static void noteSender(Object sender) {
        SENDER.set(sender);
    }

    /** The sender part of a /me row has no ": " after the name. */
    static boolean isAction(String sender) {
        return !sender.trim().endsWith(":");
    }

    /**
     * Injected at the entry of the span group constructor with its sender, the part between them and
     * the message: returns the message to use. Never throws.
     */
    public static CharSequence onSpanGroup(CharSequence sender, CharSequence between, CharSequence message) {
        Object model = SENDER.get();
        SENDER.remove();
        try {
            boolean colorAction = Toggles.on(Toggles.COLORED_ME);
            boolean colorMentions = Toggles.on(Toggles.COLORED_MENTIONS);
            if (!colorAction && !colorMentions || !(sender instanceof Spanned) || message == null) {
                return message;
            }
            Spanned from = (Spanned) sender;
            ForegroundColorSpan[] colors = from.getSpans(0, from.length(), ForegroundColorSpan.class);
            int color = colors.length > 0 ? colors[0].getForegroundColor() : 0;
            if (colorMentions && colors.length > 0 && model != null) {
                learn(model, color);
            }
            SpannableStringBuilder out = null;
            if (colorAction && colors.length > 0 && isAction(from.toString())) {
                out = new SpannableStringBuilder(message);
                out.setSpan(new ForegroundColorSpan(color), 0, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE | LOWEST);
            }
            if (colorMentions) {
                Matcher matcher = MENTION.matcher(message);
                while (matcher.find()) {
                    User user = user(matcher.group(1));
                    if (user != null) {
                        if (out == null) {
                            out = new SpannableStringBuilder(message);
                        }
                        paint(out, matcher.start(), matcher.end(), user);
                    }
                }
            }
            return out == null ? message : new SpannableString(out);
        } catch (Throwable t) {
            Log.w(TAG, "onSpanGroup failed", t);
            CrashReporter.report(TAG, "onSpanGroup failed", t);
            return message;
        }
    }

    private static void paint(SpannableStringBuilder out, int start, int end, User user) {
        out.setSpan(new ForegroundColorSpan(user.color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        NickPaint nick = user.id != null && ChatSettings.isNickPaintsEnabled() ? RteBadgeStore.getPaint(user.id) : null;
        if (nick != null) {
            out.setSpan(new NickPaint.Span(nick), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** Remembers the sender under its login and display name, the model's String fields. */
    private static void learn(Object model, int color) throws IllegalAccessException {
        User user = new User(color, ChatCosmetics.extractUserId(model));
        synchronized (USERS) {
            for (Field f : model.getClass().getDeclaredFields()) {
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    Object name = f.get(model);
                    if (name != null) {
                        USERS.put(name.toString().toLowerCase(Locale.ROOT), user);
                    }
                }
            }
        }
    }

    private static User user(String name) {
        synchronized (USERS) {
            return USERS.get(name.toLowerCase(Locale.ROOT));
        }
    }
}
