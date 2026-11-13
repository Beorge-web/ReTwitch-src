package tv.retwitch.mod;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Chat blacklist: messages with a listed word or phrase, or from a listed
 * user, are dropped as chat events in {@code ChatFilters.filterEvent} (before
 * they reach the list, so no empty rows). Both lists are plain text, one entry
 * per line; empty lists switch the feature off. Words match whole words in any
 * case ("cat" hides "Cat!", not "category").
 */
public final class ChatBlacklist {

    static final String KEY_WORDS = "blacklist_words";
    static final String KEY_USERS = "blacklist_users";

    private static volatile String wordsSource = "";
    private static volatile Pattern words;
    private static volatile String usersSource = "";
    private static volatile Set<String> users = Collections.emptySet();

    private ChatBlacklist() {
    }

    /** The word pattern for the current list, or null when it is empty. */
    static Pattern words() {
        String raw = raw(KEY_WORDS);
        if (!raw.equals(wordsSource)) {
            words = compile(raw);
            wordsSource = raw;
        }
        return words;
    }

    /** Lower-cased logins of the current list. */
    static Set<String> users() {
        String raw = raw(KEY_USERS);
        if (!raw.equals(usersSource)) {
            users = parseUsers(raw);
            usersSource = raw;
        }
        return users;
    }

    /** Whether any sender name (login, display name) is on the list. */
    static boolean hidesSender(Collection<String> names, Set<String> list) {
        for (String name : names) {
            if (list.contains(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** One case-insensitive alternation of all entries, each bounded by non-word characters. */
    static Pattern compile(String lines) {
        StringBuilder alts = new StringBuilder();
        for (String line : lines.split("\n")) {
            String term = line.trim();
            if (!term.isEmpty()) {
                alts.append(alts.length() == 0 ? "" : "|").append(Pattern.quote(term));
            }
        }
        if (alts.length() == 0) {
            return null;
        }
        return Pattern.compile("(?<![\\p{L}\\p{N}_])(?:" + alts + ")(?![\\p{L}\\p{N}_])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    static Set<String> parseUsers(String lines) {
        Set<String> out = new HashSet<>();
        for (String line : lines.split("\n")) {
            String login = line.trim().toLowerCase(Locale.ROOT);
            if (login.startsWith("@")) {
                login = login.substring(1);
            }
            if (!login.isEmpty()) {
                out.add(login);
            }
        }
        return out;
    }

    static int count(String key) {
        int n = 0;
        for (String line : raw(key).split("\n")) {
            if (!line.trim().isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private static String raw(String key) {
        try {
            Context context = AppContext.get();
            return context != null ? Toggles.prefs(context).getString(key, "") : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** Settings: edits one list as text, one entry per line. */
    static void showEditor(final Context context, final String key, String title, String hint) {
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(4);
        input.setText(raw(key));
        TextView note = new TextView(context);
        note.setText(hint);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * context.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(note);
        box.addView(input);
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(Strings.save(), (d, which) ->
                        Toggles.prefs(context).edit().putString(key, input.getText().toString().trim()).apply())
                .setNegativeButton(Strings.cancel(), null)
                .show();
    }
}
