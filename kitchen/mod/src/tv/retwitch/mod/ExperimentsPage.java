package tv.retwitch.mod;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Settings page with Twitch's feature flags ({@link ExperimentHook}): every flag of the Eppo
 * snapshot Twitch ships ({@code assets/eppo.json}) plus the native flags read this session. Editing
 * one stores an override; native flags and React Native both pick it up after a restart.
 */
final class ExperimentsPage {

    private static final String TAG = "RTExperiments";

    /** A flag of the snapshot: type letter (as stored) and its variations. */
    private static final class Entry {
        char type;
        final List<String> variations = new ArrayList<>();
    }

    private static TreeMap<String, Entry> snapshot;
    private static String filter = "";

    private ExperimentsPage() {
    }

    static void build(SettingsScreen s) {
        if (s.searching()) {
            return; // hundreds of flag keys would drown the settings search
        }
        final Context c = s.context;
        s.hint(Strings.experimentsHint());
        s.action(Strings.experimentsFilter(), filter.isEmpty() ? Strings.experimentsFilterNone() : filter,
                // The screen re-renders when the dialog closes and gives it back the focus.
                () -> input(c, Strings.experimentsFilter(), filter, InputType.TYPE_CLASS_TEXT,
                        value -> filter = value.trim()));
        final Map<String, String> overrides = ExperimentHook.overrides();
        // Only the flag overrides; the rest of the mod settings stay.
        s.action(Strings.experimentsReset(overrides.size()), Strings.experimentsResetHint(), () -> {
            if (overrides.isEmpty()) {
                return;
            }
            new AlertDialog.Builder(c)
                    .setTitle(Strings.experimentsReset(overrides.size()))
                    .setMessage(Strings.experimentsResetHint())
                    .setPositiveButton(android.R.string.ok, (d, which) -> {
                        ExperimentHook.clear(c);
                        s.restartNeeded();
                    })
                    .setNegativeButton(Strings.cancel(), null)
                    .show();
        });
        if (!overrides.isEmpty()) {
            s.section(Strings.experimentsChanged());
            for (String key : new TreeMap<>(overrides).keySet()) {
                row(s, key, overrides.get(key));
            }
        }
        TreeMap<String, Entry> all = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        all.putAll(snapshot(c));
        for (ExperimentHook.Flag f : ExperimentHook.SEEN.values()) {
            if (!all.containsKey(f.key) && f.type != 0) {
                Entry e = new Entry();
                e.type = f.type;
                all.put(f.key, e);
            }
        }
        String q = filter.toLowerCase(Locale.ROOT);
        s.section(Strings.experimentsAll(all.size()));
        for (String key : all.keySet()) {
            ExperimentHook.Flag f = ExperimentHook.SEEN.get(key);
            boolean match = q.isEmpty() || key.toLowerCase(Locale.ROOT).contains(q)
                    || f != null && f.name.toLowerCase(Locale.ROOT).contains(q);
            if (match && !overrides.containsKey(key)) {
                row(s, key, null);
            }
        }
    }

    private static void row(final SettingsScreen s, final String key, String stored) {
        ExperimentHook.Flag f = ExperimentHook.SEEN.get(key);
        Entry e = snapshot(s.context).get(key);
        StringBuilder summary = new StringBuilder();
        if (f != null) {
            summary.append(f.name).append(" · ").append(Strings.experimentsNow()).append(": ").append(shorten(String.valueOf(f.value)));
        }
        if (stored != null) {
            if (summary.length() > 0) {
                summary.append('\n');
            }
            summary.append(Strings.experimentsOverride()).append(": ").append(shorten(stored.substring(2)));
        }
        s.action(key, summary.toString(), () -> edit(s, key, f, e, stored));
    }

    /** Long JSON values (country lists, channel ids) would fill the screen. */
    private static String shorten(String value) {
        return value.length() > 80 ? value.substring(0, 80) + "..." : value;
    }

    private static void edit(final SettingsScreen s, final String key, ExperimentHook.Flag f, Entry e,
            String stored) {
        final Context c = s.context;
        final char type = e != null ? e.type : f != null ? f.type : 's';
        final List<String> options = new ArrayList<>();
        options.add(Strings.experimentsAsTwitch());
        if (type == 'b') {
            options.add("true");
            options.add("false");
        } else if (e != null) {
            options.addAll(e.variations);
        }
        options.add(Strings.experimentsCustom());
        new AlertDialog.Builder(c)
                .setTitle(key)
                .setItems(options.toArray(new String[0]), (d, which) -> {
                    if (which == 0) {
                        save(s, key, null);
                    } else if (which == options.size() - 1) {
                        int kind = type == 'i' ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                                : type == 'd' ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                                | InputType.TYPE_NUMBER_FLAG_SIGNED
                                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
                        input(c, key, stored != null ? stored.substring(2) : "", kind,
                                value -> save(s, key, value.isEmpty() ? null : type + ":" + value));
                    } else {
                        save(s, key, type + ":" + options.get(which));
                    }
                })
                .setNegativeButton(Strings.cancel(), null)
                .show();
    }

    private static void save(SettingsScreen s, String key, String stored) {
        ExperimentHook.set(s.context, key, stored);
        s.restartNeeded();
    }

    private interface TextSetter {
        void set(String value);
    }

    private static void input(Context c, String title, String value, int inputType, final TextSetter setter) {
        final EditText field = new EditText(c);
        field.setInputType(inputType);
        field.setText(value);
        field.setSelectAllOnFocus(true);
        new AlertDialog.Builder(c)
                .setTitle(title)
                .setView(field)
                .setPositiveButton(Strings.save(), (d, which) -> setter.set(field.getText().toString()))
                .setNegativeButton(Strings.cancel(), null)
                .show();
    }

    /** The Eppo snapshot Twitch ships, parsed once. Empty if it can't be read. */
    private static TreeMap<String, Entry> snapshot(Context c) {
        if (snapshot != null) {
            return snapshot;
        }
        TreeMap<String, Entry> flags = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try (InputStream in = c.getAssets().open("eppo.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            JSONObject all = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8))
                    .getJSONObject("flags");
            for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                String key = it.next();
                JSONObject flag = all.getJSONObject(key);
                Entry e = new Entry();
                switch (flag.optString("variationType")) {
                    case "BOOLEAN":
                        e.type = 'b';
                        break;
                    case "INTEGER":
                        e.type = 'i';
                        break;
                    case "NUMERIC":
                        e.type = 'd';
                        break;
                    case "JSON":
                        e.type = 'j';
                        break;
                    default:
                        e.type = 's';
                        break;
                }
                JSONObject variations = flag.optJSONObject("variations");
                if (variations != null && e.type == 's') {
                    for (Iterator<String> v = variations.keys(); v.hasNext(); ) {
                        JSONObject variation = variations.optJSONObject(v.next());
                        if (variation != null) {
                            e.variations.add(variation.optString("value"));
                        }
                    }
                }
                flags.put(key, e);
            }
        } catch (Exception ex) {
            Log.w(TAG, "eppo snapshot unreadable", ex);
        }
        snapshot = flags;
        return flags;
    }
}
