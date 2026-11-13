package tv.retwitch.mod;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The "Language and translation" page: the mod's own language, translation files out and in, and the strings the
 * user loaded from a file, which go over the build's until deleted (they only exist on this phone).
 *
 * <p>Files are kitchen/i18n/&lt;lang&gt;.csv ({@link I18nCsv}) built from the catalog the build packs next to the
 * translations ({@code i18n.py pack}). State lives in prefs {@code retwitch_i18n}, so the settings backup has it:
 * {@code language} ("" = the device's) and {@code own_<lang>} (JSON, English -> text). The picker result comes back
 * to a headless fragment, as in {@link SettingsBackup}.
 */
public final class TranslationFiles {

    static final String PREFS = "retwitch_i18n";
    private static final String KEY_LANGUAGE = "language";
    private static final String OWN = "own_";
    private static final String CATALOG_ASSET = "retwitch-i18n-catalog.json";
    private static final String SHIPPED_ASSET = "retwitch-i18n.json";
    /** A picked file larger than this is not a translation. */
    private static final int MAX_BYTES = 4 << 20;
    // 16-bit codes: FragmentActivity rejects larger ones (SettingsBackup and SoundPicker take 0x5241-0x5243).
    private static final int REQ_EXPORT = 0x5244;
    private static final int REQ_IMPORT = 0x5245;
    private static final String TAG = "RTI18n";
    private static final String FRAGMENT_TAG = "retwitch_translation_files";

    private static volatile String exportLanguage;
    private static volatile Runnable onImported;
    /** English -> {Russian, where}, in the build's order; read once. */
    private static volatile Map<String, String[]> catalog;
    /** Language -> (English -> translation) of the build; read once. */
    private static volatile Map<String, Map<String, String>> shipped;

    private TranslationFiles() {
    }

    // ---- language and own strings (Strings reads these) ----

    static String chosenLanguage(Context context) {
        return prefs(context).getString(KEY_LANGUAGE, "");
    }

    static void choose(Context context, String language) {
        prefs(context).edit().putString(KEY_LANGUAGE, language).apply();
        Strings.languageChanged(language);
    }

    /** The user's strings for {@code language}: English -> text. */
    static Map<String, String> own(Context context, String language) {
        Map<String, String> strings = new HashMap<>();
        String json = prefs(context).getString(OWN + language, null);
        if (json == null) {
            return strings;
        }
        try {
            JSONObject object = new JSONObject(json);
            for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                String key = keys.next();
                strings.put(key, object.getString(key));
            }
        } catch (Exception e) {
            Log.w(TAG, "own strings of " + language + " unreadable", e);
        }
        return strings;
    }

    /** Languages the user loaded a file for. */
    static List<String> ownLanguages(Context context) {
        List<String> languages = new ArrayList<>();
        for (String key : new TreeSet<>(prefs(context).getAll().keySet())) {
            if (key.startsWith(OWN)) {
                languages.add(key.substring(OWN.length()));
            }
        }
        return languages;
    }

    static void delete(Context context, String language) {
        prefs(context).edit().remove(OWN + language).apply();
        String chosen = chosenLanguage(context);
        if (chosen.equals(language) && !"ru".equals(language) && !shipped(context).containsKey(language)) {
            choose(context, ""); // nothing left of it but English
        } else {
            Strings.languageChanged(chosen);
        }
    }

    /** For the strip on the page: "" (the device's), Russian, English, then the build's and the user's languages. */
    static String[] languages(Context context) {
        TreeSet<String> others = new TreeSet<>(shipped(context).keySet());
        others.addAll(ownLanguages(context));
        others.remove("ru");
        others.remove("en");
        List<String> codes = new ArrayList<>(java.util.Arrays.asList("", "ru", "en"));
        codes.addAll(others);
        String chosen = chosenLanguage(context);
        if (!codes.contains(chosen)) {
            codes.add(chosen);
        }
        return codes.toArray(new String[0]);
    }

    /** A language's own name ("Español", "Deutsch"); "" is the device's language. */
    static String name(String code) {
        if (code.isEmpty()) {
            return Strings.pick("Как в системе", "System language");
        }
        Locale locale = new Locale(code);
        String name = locale.getDisplayLanguage(locale);
        if (name.isEmpty() || name.equalsIgnoreCase(code)) {
            return code;
        }
        return name.substring(0, 1).toUpperCase(locale) + name.substring(1);
    }

    static List<String> shippedLanguages(Context context) {
        return new ArrayList<>(new TreeSet<>(shipped(context).keySet()));
    }

    static int shippedCount(Context context, String language) {
        Map<String, String> strings = shipped(context).get(language);
        return strings != null ? strings.size() : 0;
    }

    static int catalogSize(Context context) {
        return catalog(context).size();
    }

    // ---- files ----

    /** Asks which language, then where to save its file. */
    static void exportFile(Context context) {
        List<String> codes = new ArrayList<>(new TreeSet<>(shipped(context).keySet()));
        for (String own : ownLanguages(context)) {
            if (!codes.contains(own)) {
                codes.add(own);
            }
        }
        codes.remove("ru");
        codes.remove("en");
        codes.add("ru");
        List<String> labels = new ArrayList<>();
        for (String code : codes) {
            labels.add("ru".equals(code) ? Strings.pick("Русский: правки к русскому тексту", "Russian: fixes to the Russian text")
                    : name(code));
        }
        labels.add(Strings.pick("Новый язык...", "A new language..."));
        new AlertDialog.Builder(context)
                .setTitle(Strings.pick("Файл какого языка?", "Which language?"))
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which < codes.size()) {
                        exportTo(context, codes.get(which));
                    } else {
                        askNewLanguage(context);
                    }
                })
                .show();
    }

    private static void askNewLanguage(Context context) {
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint("de, pt, uk, tr...");
        LinearLayout box = new LinearLayout(context);
        int pad = Math.round(20 * context.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(context)
                .setTitle(Strings.pick("Код языка", "Language code"))
                .setMessage(Strings.pick("Две или три латинские буквы по ISO 639: de - немецкий, pt - португальский.",
                        "Two or three Latin letters per ISO 639: de is German, pt is Portuguese."))
                .setView(box)
                .setNegativeButton(Strings.pick("Отмена", "Cancel"), null)
                .setPositiveButton(Strings.pick("Дальше", "Next"), (dialog, which) -> {
                    String code = input.getText().toString().trim().toLowerCase(Locale.ROOT);
                    if (!code.matches("[a-z]{2,3}") || code.equals("en")) {
                        toast(context, Strings.pick("Нужен код вроде de или pt", "A code like de or pt is needed"));
                        return;
                    }
                    exportTo(context, code);
                })
                .show();
    }

    private static void exportTo(Context context, String language) {
        exportLanguage = language;
        String name = "retwitch-" + language + "-" + BuildInfo.VERSION_NAME + ".csv";
        pick(context, new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/csv").putExtra(Intent.EXTRA_TITLE, name), REQ_EXPORT);
    }

    /** Picks a file and shows what it gives before using it; {@code done} runs once it is in use. */
    static void importFile(Context context, Runnable done) {
        onImported = done;
        // */*: providers label .csv as text/plain, text/comma-separated-values or octet-stream.
        pick(context, new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                REQ_IMPORT);
    }

    /** The language's file, built as for saving, to the share sheet (Telegram, mail). */
    static void share(Context context, String language) {
        try {
            File dir = new File(context.getExternalFilesDir(null), "i18n");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new java.io.IOException("no " + dir);
            }
            File file = new File(dir, "retwitch-" + language + "-" + BuildInfo.VERSION_NAME + ".csv");
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(csv(context, language).getBytes(StandardCharsets.UTF_8));
            }
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/csv")
                    .putExtra(Intent.EXTRA_STREAM, ModUpdate.sharedUri(context, file))
                    .putExtra(Intent.EXTRA_SUBJECT, "ReTwitch " + BuildInfo.VERSION_NAME + ": " + name(language))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(Intent.createChooser(send, Strings.pick("Отправить перевод", "Send the translation")));
        } catch (Throwable t) {
            Log.w(TAG, "share failed", t);
            CrashReporter.report(TAG, "share failed", t);
            toast(context, Strings.pick("Не удалось подготовить файл", "Couldn't prepare the file"));
        }
    }

    /** The file of a language: en, ru, the language (the user's string, else the build's), where; for Russian, en, ru, where. */
    static String csv(Context context, String language) {
        boolean russian = "ru".equals(language);
        Map<String, String> built = russian ? null : shipped(context).get(language);
        Map<String, String> own = own(context, language);
        List<String[]> rows = new ArrayList<>();
        rows.add(russian ? new String[]{"en", "ru", "where"} : new String[]{"en", "ru", language, "where"});
        for (Map.Entry<String, String[]> entry : catalog(context).entrySet()) {
            String en = entry.getKey(), ru = entry.getValue()[0], where = entry.getValue()[1];
            String text = own.get(en);
            if (russian) {
                rows.add(new String[]{en, text != null ? text : ru, where});
            } else {
                if (text == null && built != null) {
                    text = built.get(en);
                }
                rows.add(new String[]{en, ru, text, where});
            }
        }
        return I18nCsv.write(rows);
    }

    private static void pick(Context context, Intent intent, int code) {
        Activity activity = SettingsBackup.activity(context);
        if (activity == null || catalog(context).isEmpty()) {
            toast(context, Strings.pick("Не удалось открыть файлы", "Couldn't open files"));
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
            toast(context, Strings.pick("Не удалось открыть файлы", "Couldn't open files"));
        }
    }

    /** Receives the picker result; public with a no-arg constructor so Android can restore it. */
    public static final class PickerFragment extends Fragment {

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            final Activity activity = getActivity();
            try {
                getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
            } catch (Throwable ignored) {
                // a leftover headless fragment is harmless
            }
            final Runnable done = onImported;
            onImported = null;
            final String language = exportLanguage;
            final Uri uri = data != null ? data.getData() : null;
            if (activity == null || resultCode != Activity.RESULT_OK || uri == null) {
                return;
            }
            final Context app = activity.getApplicationContext();
            if (requestCode == REQ_EXPORT && language != null) {
                new Thread(() -> {
                    String message;
                    try {
                        write(app, uri, csv(app, language));
                        message = Strings.pick("Файл сохранён", "File saved");
                    } catch (Throwable t) {
                        Log.w(TAG, "export failed", t);
                        message = Strings.pick("Не удалось сохранить файл", "Couldn't save the file");
                    }
                    final String text = message;
                    new Handler(Looper.getMainLooper()).post(() -> toast(app, text));
                }, "retwitch-i18n").start();
            } else if (requestCode == REQ_IMPORT) {
                new Thread(() -> {
                    I18nCsv.Result result;
                    try {
                        result = check(app, read(app, uri));
                    } catch (Throwable t) {
                        Log.w(TAG, "import failed", t);
                        result = null;
                    }
                    final I18nCsv.Result checked = result;
                    new Handler(Looper.getMainLooper()).post(() -> report(activity, checked, done));
                }, "retwitch-i18n").start();
            }
        }
    }

    static I18nCsv.Result check(Context context, String text) {
        Map<String, String> russian = new HashMap<>();
        for (Map.Entry<String, String[]> entry : catalog(context).entrySet()) {
            russian.put(entry.getKey(), entry.getValue()[0]);
        }
        Map<String, Map<String, String>> built = shipped(context);
        return I18nCsv.check(I18nCsv.parse(text), russian, language -> "ru".equals(language) ? russian
                : built.containsKey(language) ? built.get(language) : Collections.emptyMap());
    }

    /** What the file gives, and a button to use it. */
    private static void report(Activity activity, I18nCsv.Result result, Runnable done) {
        if (activity.isFinishing()) {
            return;
        }
        if (result == null || result.error != null) {
            String why = result == null ? Strings.pick("Файл не прочитался.", "The file couldn't be read.")
                    : I18nCsv.ERROR_MANY_LANGUAGES.equals(result.error)
                    ? Strings.pick("В файле несколько языков. Оставьте одну колонку перевода.",
                    "The file has several languages. Keep one translation column.")
                    : Strings.pick("Нужна строка заголовка с колонкой en и колонкой языка, например de.",
                    "A header row with an en column and a language column such as de is needed.");
            new AlertDialog.Builder(activity).setTitle(Strings.pick("Не перевод мода", "Not a mod translation"))
                    .setMessage(why).setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        StringBuilder text = new StringBuilder(Strings.pickf("Строк из файла: %d", "Strings from the file: %d",
                result.strings.size()));
        if (result.same > 0) {
            text.append('\n').append(Strings.pickf("Уже как в моде: %d", "Already as in the mod: %d", result.same));
        }
        if (!result.badPlaceholders.isEmpty()) {
            text.append('\n').append(Strings.pickf("С другими %%s или %%d, не взяты: %d",
                    "With other %%s or %%d, not taken: %d", result.badPlaceholders.size()));
            for (String bad : result.badPlaceholders.subList(0, Math.min(2, result.badPlaceholders.size()))) {
                text.append("\n   ").append(bad);
            }
        }
        if (result.edges > 0) {
            text.append('\n').append(Strings.pickf("Пробелы по краям поправлены как в английском: %d",
                    "Spaces at the ends fixed as in English: %d", result.edges));
        }
        if (result.stale > 0) {
            text.append('\n').append(Strings.pickf("Устарели, английский текст поменялся: %d",
                    "Outdated, the English text changed: %d", result.stale));
        }
        if (result.empty > 0) {
            text.append('\n').append(Strings.pickf("Пустых: %d", "Empty: %d", result.empty));
        }
        TextView body = new TextView(activity);
        body.setText(text);
        body.setTextIsSelectable(true);
        int pad = Math.round(22 * activity.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(body);
        final String language = result.language;
        final CheckBox use = new CheckBox(activity);
        if (!language.equals(Strings.language())) {
            use.setText(Strings.pickf("Включить «%s» для мода", "Use “%s” for the mod", name(language)));
            use.setChecked(true);
            box.addView(use);
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
                .setTitle(name(language) + " (" + language + ")")
                .setView(box)
                .setNegativeButton(Strings.pick("Отмена", "Cancel"), null);
        if (!result.strings.isEmpty()) {
            dialog.setPositiveButton(Strings.pick("Применить", "Apply"), (d, which) -> {
                prefs(activity).edit().putString(OWN + language, new JSONObject(result.strings).toString()).apply();
                if (use.getParent() != null && use.isChecked()) {
                    choose(activity, language);
                } else {
                    Strings.languageChanged(chosenLanguage(activity));
                }
                if (done != null) {
                    try {
                        done.run();
                    } catch (Throwable ignored) {
                        // the settings screen may be gone
                    }
                }
            });
        }
        dialog.show();
    }

    // ---- assets and storage ----

    private static Map<String, String[]> catalog(Context context) {
        Map<String, String[]> loaded = catalog;
        if (loaded != null) {
            return loaded;
        }
        loaded = new LinkedHashMap<>();
        try {
            JSONArray rows = new JSONArray(asset(context, CATALOG_ASSET));
            for (int i = 0; i < rows.length(); i++) {
                JSONArray row = rows.getJSONArray(i);
                loaded.put(row.getString(0), new String[]{row.getString(1), row.optString(2)});
            }
        } catch (Exception e) {
            Log.w(TAG, "catalog unreadable", e);
            return loaded; // read again next time
        }
        catalog = loaded;
        return loaded;
    }

    private static Map<String, Map<String, String>> shipped(Context context) {
        Map<String, Map<String, String>> loaded = shipped;
        if (loaded != null) {
            return loaded;
        }
        loaded = new HashMap<>();
        try {
            JSONObject all = new JSONObject(asset(context, SHIPPED_ASSET));
            for (Iterator<String> languages = all.keys(); languages.hasNext(); ) {
                String language = languages.next();
                JSONObject strings = all.getJSONObject(language);
                Map<String, String> map = new HashMap<>();
                for (Iterator<String> keys = strings.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    map.put(key, strings.getString(key));
                }
                loaded.put(language, map);
            }
        } catch (Exception e) {
            Log.w(TAG, "translations unreadable", e);
            return loaded;
        }
        shipped = loaded;
        return loaded;
    }

    private static String asset(Context context, String name) throws java.io.IOException {
        try (InputStream in = context.getAssets().open(name)) {
            return readAll(in);
        }
    }

    private static String read(Context context, Uri uri) throws java.io.IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new java.io.IOException("no stream for " + uri);
            }
            return readAll(in);
        }
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        for (int n; (n = in.read(buffer)) > 0; ) {
            out.write(buffer, 0, n);
            if (out.size() > MAX_BYTES) {
                throw new java.io.IOException("too large");
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
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

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }
}
