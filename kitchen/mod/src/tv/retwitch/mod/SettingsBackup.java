package tv.retwitch.mod;

import android.app.Activity;
import android.app.Fragment;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Backup of the mod's settings to a file the user picks (Storage Access
 * Framework), after ReVive's SettingsSync but one-shot, without syncing.
 *
 * <p>Covers every {@code retwitch_*} SharedPreferences file, so settings added
 * later are included without a list to keep up to date; launch counters are
 * left out. Format: {@code {"format":"retwitch-settings","version":1,"mod":..,
 * "prefs":{file:{key:[tag,value]}}}} with tags b/i/l/f/s/ss for the
 * SharedPreferences types. Import restores each file exactly (keys the backup
 * lacks go back to their defaults); files outside {@code retwitch_*}, unknown
 * tags, values not matching their tag and values whose type differs from the
 * one stored here are skipped, the last keeping the current value.
 *
 * <p>For comparing a tester's phone with ours the export also carries {@code "twitch"} (its version) and
 * {@code "experiments"}: every Twitch experiment flag the app read this session with its value (ExperimentHook;
 * the React Native channel page, for one, is {@code Android-Ultralight-Profile}). Import ignores both.
 *
 * <p>The picker result comes back through a headless framework Fragment added
 * to Twitch's activity: Activity hands a fragment's result to that fragment,
 * so Twitch's own onActivityResult needs no patch.
 */
public final class SettingsBackup {

    static final String FORMAT = "retwitch-settings";
    static final int VERSION = 1;
    private static final String PREFIX = "retwitch_";
    /** Device state, not settings. */
    private static final Set<String> EXCLUDED = new HashSet<>(Arrays.asList("retwitch_launch_promo", "retwitch_update",
            "retwitch_feedback", LiveRecord.STATE, StreamSwitch.STATE, QuickSetup.PREFS));
    /** A picked file larger than this is not a settings backup. */
    private static final int MAX_BYTES = 1 << 20;
    // 16-bit codes: FragmentActivity rejects larger ones.
    private static final int REQ_EXPORT = 0x5241;
    private static final int REQ_IMPORT = 0x5242;
    private static final String TAG = "RTBackup";
    private static final String FRAGMENT_TAG = "retwitch_settings_backup";

    /** Run on the main thread after a successful import (raises the restart bar). */
    private static volatile Runnable onImported;

    private SettingsBackup() {
    }

    static void exportTo(Context context) {
        String name = "retwitch-settings-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".json";
        pick(context, new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, name), REQ_EXPORT);
    }

    static void importFrom(Context context, Runnable done) {
        onImported = done;
        // */*: cloud providers often label .json files as octet-stream.
        pick(context, new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*"), REQ_IMPORT);
    }

    private static void pick(Context context, Intent intent, int code) {
        Activity activity = activity(context);
        if (activity == null) {
            toast(context, Strings.backupFailed());
            return;
        }
        try {
            PickerFragment fragment = new PickerFragment();
            activity.getFragmentManager().beginTransaction().add(fragment, FRAGMENT_TAG).commitNow();
            fragment.startActivityForResult(intent, code);
        } catch (ActivityNotFoundException e) {
            toast(context, Strings.backupNoPicker());
        } catch (Throwable t) {
            Log.w(TAG, "picker failed", t);
            CrashReporter.report(TAG, "picker failed", t);
            toast(context, Strings.backupFailed());
        }
    }

    /** Receives the picker result; public with a no-arg constructor so Android can restore it. */
    public static final class PickerFragment extends Fragment {

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            final Context app = getActivity() != null ? getActivity().getApplicationContext() : AppContext.get();
            try {
                getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
            } catch (Throwable ignored) {
                // a leftover headless fragment is harmless
            }
            final Runnable done = onImported;
            onImported = null;
            final Uri uri = data != null ? data.getData() : null;
            if (app == null || resultCode != Activity.RESULT_OK || uri == null
                    || (requestCode != REQ_EXPORT && requestCode != REQ_IMPORT)) {
                return;
            }
            final boolean export = requestCode == REQ_EXPORT;
            new Thread(() -> {
                String message;
                boolean imported = false;
                try {
                    if (export) {
                        write(app, uri, snapshot(app).toString(1));
                        message = Strings.backupSaved();
                    } else {
                        int count = restore(app, read(app, uri));
                        imported = count >= 0;
                        message = imported ? Strings.backupRestored(count) : Strings.backupNotOurs();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, export ? "export failed" : "import failed", t);
                    message = export ? Strings.backupFailed() : Strings.backupReadFailed();
                }
                final String text = message;
                final boolean restartNeeded = imported;
                new Handler(Looper.getMainLooper()).post(() -> {
                    toast(app, text);
                    if (restartNeeded && done != null) {
                        try {
                            done.run();
                        } catch (Throwable ignored) {
                            // the settings screen may be gone
                        }
                    }
                });
            }, "retwitch-backup").start();
        }
    }

    // ---- file contents ----

    static JSONObject snapshot(Context context) throws JSONException {
        JSONObject prefs = new JSONObject();
        for (String name : localFiles(context)) {
            JSONObject values = new JSONObject();
            for (Map.Entry<String, ?> entry : context.getSharedPreferences(name, Context.MODE_PRIVATE).getAll().entrySet()) {
                Object value = entry.getValue();
                String tag = tag(value);
                if (tag != null) {
                    values.put(entry.getKey(), new JSONArray().put(tag)
                            .put(value instanceof Set ? new JSONArray((Collection<?>) value) : value));
                }
            }
            prefs.put(name, values);
        }
        JSONObject experiments = new JSONObject();
        for (String key : new TreeSet<>(ExperimentHook.SEEN.keySet())) {
            Object value = ExperimentHook.SEEN.get(key).value;
            if (value != null) {
                String text = String.valueOf(value);
                experiments.put(key, text.length() > 200 ? text.substring(0, 200) + "..." : text);
            }
        }
        String twitch = null;
        try {
            twitch = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // informational only
        }
        return new JSONObject().put("format", FORMAT).put("version", VERSION)
                .put("mod", BuildInfo.VERSION_NAME).put("twitch", twitch).put("exported", System.currentTimeMillis())
                .put("experiments", experiments).put("prefs", prefs);
    }

    /** @return how many values were restored, or -1 when the text is not a ReTwitch settings backup. */
    static int restore(Context context, String text) throws JSONException {
        JSONObject root;
        try {
            root = new JSONObject(text);
        } catch (JSONException e) {
            return -1;
        }
        JSONObject prefs = root.optJSONObject("prefs");
        if (!FORMAT.equals(root.optString("format")) || prefs == null) {
            return -1;
        }
        Set<String> names = new TreeSet<>(localFiles(context));
        for (Iterator<String> it = prefs.keys(); it.hasNext(); ) {
            String name = it.next();
            if (fileAllowed(name)) {
                names.add(name);
            }
        }
        int restored = 0;
        for (String name : names) {
            JSONObject values = prefs.optJSONObject(name);
            if (values == null && prefs.has(name)) {
                continue; // malformed entry: leave this file alone
            }
            SharedPreferences sp = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            Map<String, ?> local = sp.getAll();
            // clear() runs before the puts of the same edit, whatever their order.
            SharedPreferences.Editor editor = sp.edit().clear();
            Set<String> written = new HashSet<>();
            if (values != null) {
                for (Iterator<String> it = values.keys(); it.hasNext(); ) {
                    String key = it.next();
                    JSONArray pair = values.optJSONArray(key);
                    Object value = pair == null ? null : decode(pair.optString(0), json(pair.opt(1)));
                    if (value != null && !conflicts(local.get(key), value)) {
                        put(editor, key, value);
                        written.add(key);
                        restored++;
                    }
                }
            }
            // Values the backup can't replace keep their current setting.
            for (Map.Entry<String, ?> entry : local.entrySet()) {
                if (!written.contains(entry.getKey()) && values != null && values.has(entry.getKey())) {
                    put(editor, entry.getKey(), entry.getValue());
                }
            }
            editor.commit(); // synchronous: the restart bar may exit the process right after
        }
        return restored;
    }

    // ---- pure helpers (see SettingsBackupSelfCheck) ----

    static boolean fileAllowed(String name) {
        return name != null && name.matches("retwitch_[a-z0-9_]+") && !EXCLUDED.contains(name);
    }

    /** The backup tag of a SharedPreferences value, or null for an unsupported type. */
    static String tag(Object value) {
        if (value instanceof Boolean) {
            return "b";
        }
        if (value instanceof Integer) {
            return "i";
        }
        if (value instanceof Long) {
            return "l";
        }
        if (value instanceof Float) {
            return "f";
        }
        if (value instanceof String) {
            return "s";
        }
        return value instanceof Set ? "ss" : null;
    }

    /**
     * The SharedPreferences value for {@code tag} from a parsed JSON value (a
     * string set arrives as a Collection), or null when the value doesn't fit.
     */
    static Object decode(String tag, Object raw) {
        if (tag == null) {
            return null;
        }
        switch (tag) {
            case "b":
                return raw instanceof Boolean ? raw : null;
            case "s":
                return raw instanceof String ? raw : null;
            case "i":
            case "l": {
                Long whole = whole(raw);
                if (whole == null) {
                    return null;
                }
                if (tag.equals("l")) {
                    return whole;
                }
                return whole >= Integer.MIN_VALUE && whole <= Integer.MAX_VALUE ? (Object) whole.intValue() : null;
            }
            case "f": {
                if (!(raw instanceof Number)) {
                    return null;
                }
                float f = ((Number) raw).floatValue();
                return Float.isNaN(f) || Float.isInfinite(f) ? null : (Object) f;
            }
            case "ss": {
                if (!(raw instanceof Collection)) {
                    return null;
                }
                Set<String> out = new HashSet<>();
                for (Object item : (Collection<?>) raw) {
                    if (!(item instanceof String)) {
                        return null;
                    }
                    out.add((String) item);
                }
                return out;
            }
            default:
                return null;
        }
    }

    /** Whether {@code value} would change the type of the {@code local} value stored under the same key. */
    static boolean conflicts(Object local, Object value) {
        if (local == null) {
            return false;
        }
        if (local instanceof Set) {
            return !(value instanceof Set);
        }
        return local.getClass() != value.getClass();
    }

    private static Long whole(Object raw) {
        if (!(raw instanceof Number)) {
            return null;
        }
        if (raw instanceof Double || raw instanceof Float) {
            double d = ((Number) raw).doubleValue();
            return d == Math.rint(d) && d >= -0x1p63 && d < 0x1p63 ? (Long) (long) d : null;
        }
        return ((Number) raw).longValue();
    }

    // ---- Android plumbing ----

    /** JSONArray -> List, so {@link #decode} works without org.json. */
    private static Object json(Object raw) {
        if (!(raw instanceof JSONArray)) {
            return raw;
        }
        JSONArray array = (JSONArray) raw;
        ArrayList<Object> list = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            list.add(array.opt(i));
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private static void put(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            editor.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            editor.putLong(key, (Long) value);
        } else if (value instanceof Float) {
            editor.putFloat(key, (Float) value);
        } else if (value instanceof String) {
            editor.putString(key, (String) value);
        } else if (value instanceof Set) {
            editor.putStringSet(key, (Set<String>) value);
        }
    }

    /**
     * Every mod setting back to its default. Kept: what is data rather than a setting --
     * favorite emotes, per-VOD chat offsets ({@code retwitch_vod_chat_offset}) and the
     * excluded state files. Applies after a restart (classes hold what they read).
     */
    static void resetAll(Context context) {
        for (String name : localFiles(context)) {
            if (name.equals("retwitch_vod_chat_offset")) {
                continue;
            }
            SharedPreferences prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            for (String key : prefs.getAll().keySet()) {
                if (!key.startsWith("favorite_emotes")) {
                    editor.remove(key);
                }
            }
            editor.commit();
        }
    }

    /** Our SharedPreferences files present on this device. */
    private static Set<String> localFiles(Context context) {
        Set<String> names = new TreeSet<>();
        String[] files = new File(context.getApplicationInfo().dataDir, "shared_prefs").list();
        if (files != null) {
            for (String file : files) {
                if (file.endsWith(".xml")) {
                    String name = file.substring(0, file.length() - 4);
                    if (fileAllowed(name)) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }

    private static String read(Context context, Uri uri) throws java.io.IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new java.io.IOException("no stream for " + uri);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_BYTES) {
                    return ""; // not a settings file: restore() rejects it
                }
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void write(Context context, Uri uri, String text) throws java.io.IOException {
        OutputStream out;
        try {
            out = context.getContentResolver().openOutputStream(uri, "wt");
        } catch (Throwable t) {
            out = null; // some providers don't support truncating
        }
        if (out == null) {
            out = context.getContentResolver().openOutputStream(uri, "w");
        }
        if (out == null) {
            throw new java.io.IOException("no stream for " + uri);
        }
        try (OutputStream stream = out) {
            stream.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }
}
