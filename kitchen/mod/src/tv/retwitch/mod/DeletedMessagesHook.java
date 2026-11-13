package tv.retwitch.mod;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import tv.retwitch.mod.emotes.EmoteChatHook;

/**
 * Bridge invoked from smali injected at every write to Twitch's
 * {@code ChatMessageListConfig.hasModAccess} field (its constructor and the
 * {@code setHasModAccess} setter). See {@code DeletedMessagesPatch}.
 *
 * <p>Twitch only sets up the click-to-reveal treatment for deleted chat
 * messages when {@code hasModAccess} is true (a moderator). By OR-ing the
 * real value with our "show deleted messages" toggle, we make deleted
 * messages revealable for everyone without permanently overriding the flag:
 * when the toggle is off, the original (real) value passes through unchanged.
 *
 * <p>Deliberately independent of any Twitch-internal class -- the injected
 * smali only ever hands us the plain {@code boolean} the app was about to
 * store, so this stays stable across app updates.
 */
public final class DeletedMessagesHook {

    private static final String TAG = "RTdeleted";

    private DeletedMessagesHook() {
    }

    /**
     * @param original the mod-access value Twitch itself was about to store
     * @return {@code original || showDeletedMessagesEnabled}
     */
    public static boolean forceModAccess(boolean original) {
        try {
            return original || ChatSettings.isShowDeletedMessagesEnabled();
        } catch (Throwable t) {
            Log.w(TAG, "forceModAccess failed", t);
            CrashReporter.report(TAG, "forceModAccess failed", t);
            return original;
        }
    }

    /**
     * Injected at the entry of the deleted-message span's {@code onClick}.
     * Twitch 31.x creates the chat-row span without an EventDispatcher, so its
     * own reveal (dispatching the clicked event) never fires; swap the
     * "&lt;message deleted&gt;" placeholder for the original text right in the
     * tapped row instead. Fields are read by type (String id, SpannedString
     * body, boolean flag, EventDispatcher). Returns true when handled. Never throws.
     */
    public static boolean revealInPlace(Object span, View view) {
        try {
            if (ChatSettings.getDeletedMode() == ChatSettings.DELETED_TWITCH || !(view instanceof TextView)) {
                return false;
            }
            for (Field f : span.getClass().getDeclaredFields()) {
                if (f.getType().getName().endsWith(".EventDispatcher")) {
                    f.setAccessible(true);
                    if (f.get(span) != null) {
                        return false; // Twitch can reveal it itself
                    }
                }
            }
            TextView row = (TextView) view;
            CharSequence text = row.getText();
            if (!(text instanceof Spanned)) {
                return false;
            }
            CharSequence revealed = replacePlaceholder((Spanned) text, span, ChatSettings.DELETED_TEXT);
            if (revealed == null) {
                return false;
            }
            row.setText(revealed);
            // Emotes and the link preview: the revealed text skipped our injection pass.
            EmoteChatHook.refreshTextView(row);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "revealInPlace failed", t);
            CrashReporter.report(TAG, "revealInPlace failed", t);
            return false;
        }
    }

    /**
     * Wraps the message span handed to Twitch's span group (31.8.0 {@code wa6}
     * constructor): a message that arrived already deleted is "&lt;message
     * deleted&gt;" under the deleted-message span, which still holds the body.
     * In the text/strike/grey modes the body is shown right away. Never throws.
     */
    public static CharSequence styleDeleted(CharSequence message) {
        CharSequence out = styleDeleted0(message);
        if (BanDiag.active()) {
            BanDiag.log("styleDeleted in=\"" + message + "\" out=\"" + out + "\"");
        }
        return out;
    }

    private static CharSequence styleDeleted0(CharSequence message) {
        try {
            int mode = ChatSettings.getDeletedMode();
            if (mode < ChatSettings.DELETED_TEXT || !(message instanceof Spanned)) {
                return message;
            }
            Spanned spanned = (Spanned) message;
            for (ClickableSpan span : spanned.getSpans(0, spanned.length(), ClickableSpan.class)) {
                CharSequence styled = replacePlaceholder(spanned, span, mode);
                if (styled != null) {
                    return styled;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "styleDeleted failed", t);
            CrashReporter.report(TAG, "styleDeleted failed", t);
        }
        return message;
    }

    /**
     * The same for the line of a deleted message (31.8.0 {@code cqo.k}'s result), and the mark for
     * {@link #tintRow}: every deleted row passes here, Twitch's own placeholder has no span to tell it
     * by outside mod access. Never throws.
     */
    public static Spanned styleDeletedLine(Spanned line) {
        CharSequence styled = styleDeleted(line);
        Spanned out = styled instanceof Spanned ? (Spanned) styled : line;
        if (out == null || out.getSpans(0, out.length(), Mark.class).length > 0) {
            return out;
        }
        SpannableStringBuilder marked = new SpannableStringBuilder(out);
        marked.setSpan(new Mark(), 0, marked.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return new SpannedString(marked);
    }

    /**
     * Replaces the range under {@code span} (a deleted-message span: the only
     * SpannedString field is the original) with the original text, styled per
     * {@code mode}. The span holds the message body when the message arrived
     * deleted, and the whole line (header included) when it was deleted on
     * screen: then the header already in the text is cut off it.
     *
     * @return the new text, or null if {@code span} is not a deleted-message span
     */
    private static CharSequence replacePlaceholder(Spanned text, Object span, int mode)
            throws IllegalAccessException {
        Class<?> known = deletedSpanClass;
        if (known != null && span.getClass() != known) {
            return null;
        }
        SpannedString original = null;
        boolean dispatcher = false;
        for (Field f : span.getClass().getDeclaredFields()) {
            if (f.getType() == SpannedString.class) {
                f.setAccessible(true);
                original = (SpannedString) f.get(span);
            } else if (f.getType().getName().endsWith(".EventDispatcher")) {
                dispatcher = true;
            }
        }
        // Mention/link spans carry no (SpannedString, EventDispatcher) pair.
        if (!dispatcher) {
            return null;
        }
        deletedSpanClass = span.getClass();
        int start = text.getSpanStart(span);
        int end = text.getSpanEnd(span);
        if (original == null || original.length() == 0 || start < 0 || end < start) {
            return null;
        }
        CharSequence body = stripHeader(original, text.subSequence(0, start).toString());
        SpannableStringBuilder out = new SpannableStringBuilder(text);
        out.removeSpan(span);
        out.replace(start, end, body);
        int bodyEnd = start + body.length();
        if (mode == ChatSettings.DELETED_STRIKE) {
            out.setSpan(new StrikethroughSpan(), start, bodyEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (mode == ChatSettings.DELETED_GREY) {
            out.setSpan(new ForegroundColorSpan(GREY), start, bodyEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return new SpannedString(out);
    }

    /**
     * Injected at the entry of a notice-style chat row's "mark deleted" ({@code
     * retwitchDeletedNoticesPatch}: generic user notice, sub goal notice): Twitch blanks those rows,
     * so a banned user's channel points message vanished from chat. Outside Twitch's own mode the
     * text stays, styled as deleted (the tap mode has no reveal on these rows: grey). Returns true
     * to skip Twitch's blanking. Never throws.
     */
    public static boolean keepDeletedNotice(Object row) {
        try {
            int mode = ChatSettings.getDeletedMode();
            if (row == null || mode == ChatSettings.DELETED_TWITCH) {
                return false;
            }
            boolean kept = false;
            for (Field f : row.getClass().getDeclaredFields()) {
                if (f.getType() != Spanned.class || java.lang.reflect.Modifier.isFinal(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                Object text = f.get(row);
                if (text instanceof Spanned) {
                    SpannableStringBuilder look = new SpannableStringBuilder((Spanned) text);
                    Object span = mode == ChatSettings.DELETED_STRIKE ? new StrikethroughSpan()
                            : mode == ChatSettings.DELETED_TEXT ? null : new ForegroundColorSpan(GREY);
                    if (span != null) {
                        look.setSpan(span, 0, look.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    look.setSpan(new Mark(), 0, look.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    f.set(row, new SpannedString(look));
                    kept = true;
                }
            }
            if (BanDiag.active()) {
                BanDiag.log("notice row deleted: " + row.getClass().getSimpleName() + " kept=" + kept);
            }
            return kept;
        } catch (Throwable t) {
            Log.w(TAG, "keepDeletedNotice failed", t);
            CrashReporter.report(TAG, "keepDeletedNotice failed", t);
            return false;
        }
    }

    /** Learned from the first deleted-message span, so other clickable spans skip reflection. */
    private static volatile Class<?> deletedSpanClass;

    /** Text we show for a deleted message: the row is tinted at bind ({@link #tintRow}). */
    private static final class Mark {
    }

    /** A tint we put over a row's own background. */
    private static final class Tint extends LayerDrawable {
        Tint(Drawable[] layers) {
            super(layers);
        }
    }

    /** Tag on a tinted row: the background it had before, or FALSE for none. */
    private static final int TINT_TAG_KEY = 0x7e700d70;

    /**
     * After the chat adapter binds a row ({@code DELETED_BACKGROUND}): a deleted message's whole row, name
     * included, gets the chosen tint, as on the web. Twitch resets a message row's background in its own bind,
     * other rows get theirs back here. Never throws.
     */
    public static void tintRow(View row) {
        try {
            Object saved = row.getTag(TINT_TAG_KEY);
            Drawable current = row.getBackground();
            if (saved != null && !(current instanceof Tint)) {
                saved = null; // Twitch set its own since
                row.setTag(TINT_TAG_KEY, null);
            }
            if (Toggles.on(Toggles.DELETED_BACKGROUND) && isDeleted(row)) {
                if (saved == null) {
                    saved = current != null ? current : Boolean.FALSE;
                    row.setTag(TINT_TAG_KEY, saved);
                }
                Drawable color = new ColorDrawable(ChatSettings.getDeletedBackground());
                row.setBackground(new Tint(saved instanceof Drawable
                        ? new Drawable[]{(Drawable) saved, color} : new Drawable[]{color}));
            } else if (saved != null) {
                row.setBackground(saved instanceof Drawable ? (Drawable) saved : null);
                row.setTag(TINT_TAG_KEY, null);
            }
        } catch (Throwable t) {
            Log.w(TAG, "tintRow failed", t);
        }
    }

    /**
     * Row models seen deleted, by identity: a revealed message rebinds as a plain row, past the mark, and
     * revealing changes the model's hashCode (a data class, {@code vwp.j}). Main thread only.
     */
    private static final Map<Integer, Boolean> DELETED_ITEMS = new LinkedHashMap<Integer, Boolean>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest) {
            return size() > 2000;
        }
    };

    private static boolean isDeleted(View row) {
        Object item = ChatRows.boundItem();
        int id = System.identityHashCode(item);
        if (showsDeleted(row)) {
            if (item != null) {
                DELETED_ITEMS.put(id, Boolean.TRUE);
            }
            return true;
        }
        return item != null && DELETED_ITEMS.containsKey(id);
    }

    private static boolean showsDeleted(View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            return text instanceof Spanned && ((Spanned) text).getSpans(0, text.length(), Mark.class).length > 0;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (showsDeleted(group.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** ReVive's grey for deleted text. */
    private static final int GREY = 0xFF6E6E78;

    private static final char RLI = '\u2067';
    private static final char PDI = '\u2069';

    /**
     * {@code original} minus {@code header} when it is the whole line that
     * starts with it (an RTL line is wrapped in RLI..PDI, the header isn't).
     */
    public static CharSequence stripHeader(CharSequence original, String header) {
        if (header.isEmpty()) {
            return original;
        }
        String line = original.toString();
        int from = line.startsWith(header) ? 0 : line.length() > 0 && line.charAt(0) == RLI
                && line.startsWith(header, 1) ? 1 : -1;
        if (from < 0) {
            return original;
        }
        int to = line.length();
        if (from == 1 && line.charAt(to - 1) == PDI) {
            to--;
        }
        int bodyStart = from + header.length();
        return bodyStart < to ? original.subSequence(bodyStart, to) : original;
    }
}
