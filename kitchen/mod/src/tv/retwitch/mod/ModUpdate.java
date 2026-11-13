package tv.retwitch.mod;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Html;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Mod updates from the public {@code latest.json} published next to the APK.
 * The file's SHA-256 comes from that JSON, and Android itself refuses an APK signed by another key.
 * The system installer always asks: an app can't update itself silently.
 */
final class ModUpdate {

    private static final String TAG = "RTUpdate";
    private static final String BASE_URL = "https://storage.yandexcloud.net/rte/ReTwitch/";
    private static final String PREFS = "retwitch_update";
    private static final String KEY_LATER = "later_until";
    private static final long LATER_MS = 24 * 60 * 60 * 1000L;
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final String FILE_NAME = "ReTwitch-update.apk";
    private static final int TIMEOUT_MS = 15000;
    /** Connections one download may take: a dropped one goes on from the byte it stopped at. */
    private static final int CONNECTIONS = 8;
    /**
     * Yandex's S3 doesn't answer for some users: once a direct connection fails, the rest of the session goes through
     * the RTE emote proxy ({@link tv.retwitch.mod.emotes.EmoteProxy#proxied}, direct when that proxy is off).
     */
    private static volatile boolean viaProxy;

    private static final class Release {
        String version;
        String apk;
        String sha256;
        long size;
        String notes;
    }

    private interface Progress {
        void at(int percent);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> top = new WeakReference<>(null);
    private static boolean checked;
    /** Found while no activity could show it. */
    private static Release waiting;
    /** Downloaded, waiting for the "install unknown apps" permission. */
    private static File ready;

    private ModUpdate() {
    }

    static void install(Application application) {
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) { }
            public void onActivityStarted(Activity a) { }
            public void onActivityResumed(Activity a) { resumed(a); }
            public void onActivityPaused(Activity a) { }
            public void onActivityStopped(Activity a) { }
            public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            public void onActivityDestroyed(Activity a) { }
        });
    }

    /** First resume, not Application.onCreate: that one runs in Twitch's background processes too. */
    private static void resumed(Activity activity) {
        try {
            top = new WeakReference<>(activity);
            if (!checked) {
                checked = true;
                apkFile(activity).delete();
                if (Toggles.on(Toggles.UPDATE_CHECK) && System.currentTimeMillis() >= prefs(activity).getLong(KEY_LATER, 0)) {
                    check(activity, false);
                }
            }
            if (ready != null && activity.getPackageManager().canRequestPackageInstalls()) {
                File file = ready;
                ready = null;
                openInstaller(activity, file);
            } else if (waiting != null) {
                Release release = waiting;
                waiting = null;
                offer(release);
            }
        } catch (Throwable t) {
            Log.w(TAG, "resume failed", t);
        }
    }

    /** From the settings: says what it found, even if there is nothing. */
    static void checkNow(Context context) {
        check(context, true);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void check(Context context, boolean manual) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                Release release = parse(new String(read(BASE_URL + "latest.json"), StandardCharsets.UTF_8));
                MAIN.post(() -> {
                    if (BuildInfo.isNewer(release.version, BuildInfo.VERSION_NAME)) {
                        offer(release);
                    } else if (manual) {
                        Toast.makeText(app, Strings.updateLatest(), Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (IOException | JSONException | RuntimeException e) {
                Log.w(TAG, "check failed", e);
                if (manual) {
                    MAIN.post(() -> Toast.makeText(app, Strings.updateCheckFailed(), Toast.LENGTH_SHORT).show());
                }
            }
        }, "RTUpdateCheck").start();
    }

    private static byte[] read(String url) throws IOException {
        HttpURLConnection connection = open(url, 0);
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    /** {@code url} directly or, after a direct failure, through the proxy ({@link #viaProxy}). */
    private static HttpURLConnection open(String url, long from) throws IOException {
        if (!viaProxy) {
            try {
                return connect(url, from);
            } catch (IOException e) {
                Log.w(TAG, "direct failed, going through the proxy", e);
                viaProxy = true;
            }
        }
        String proxied = tv.retwitch.mod.emotes.EmoteProxy.proxied(url);
        try {
            return connect(proxied, from);
        } catch (IOException e) {
            tv.retwitch.mod.emotes.EmoteProxy.failed(proxied);
            throw e;
        }
    }

    /**
     * From byte {@code from} on: a later part must come as one (206), or whole (200, the proxy ignores Range: see
     * {@link #body}).
     */
    private static HttpURLConnection connect(String url, long from) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setUseCaches(false);
        if (from > 0) {
            connection.setRequestProperty("Range", "bytes=" + from + "-");
        }
        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK && (from == 0 || code != HttpURLConnection.HTTP_PARTIAL)) {
            connection.disconnect();
            throw new IOException("HTTP " + connection.getResponseCode());
        }
        return connection;
    }

    /** The body from byte {@code from}: a whole file (200 to a ranged request) has its first bytes skipped. */
    private static InputStream body(HttpURLConnection connection, long from) throws IOException {
        InputStream in = connection.getInputStream();
        if (from > 0 && connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
            for (long left = from; left > 0; ) {
                long skipped = in.skip(left);
                if (skipped <= 0) {
                    if (in.read() < 0) {
                        throw new IOException("whole file shorter than " + from);
                    }
                    skipped = 1;
                }
                left -= skipped;
            }
        }
        return in;
    }

    /** The JSON comes from the network: only a plain file name, and no update without a hash. */
    private static Release parse(String body) throws JSONException, IOException {
        JSONObject json = new JSONObject(body);
        Release release = new Release();
        release.version = json.getString("version");
        release.apk = json.getString("apk");
        release.sha256 = json.getString("sha256");
        release.size = json.getLong("size");
        if (!release.apk.matches("[A-Za-z0-9._-]+\\.apk") || !release.sha256.matches("[0-9a-f]{64}") || release.size <= 0) {
            throw new IOException("malformed latest.json");
        }
        StringBuilder notes = new StringBuilder();
        JSONArray history = json.optJSONArray("history");
        for (int i = 0; history != null && i < history.length(); i++) {
            JSONObject entry = history.getJSONObject(i);
            String version = entry.getString("version");
            if (BuildInfo.isNewer(version, BuildInfo.VERSION_NAME)) {
                notes.append(notes.length() == 0 ? "" : "<br><br>");
                Changelog.entry(notes, version, Strings.pick(entry.optString("ru"), entry.optString("en")), false);
            }
        }
        release.notes = notes.toString();
        return release;
    }

    private static void offer(Release release) {
        Activity activity = top.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            waiting = release;
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(Strings.updateTitle(release.version))
                .setMessage(Html.fromHtml(release.notes, Html.FROM_HTML_MODE_LEGACY))
                .setPositiveButton(Strings.updateNow(), (d, which) -> download(activity, release))
                .setNegativeButton(Strings.later(), (d, which) -> prefs(activity).edit()
                        .putLong(KEY_LATER, System.currentTimeMillis() + LATER_MS).apply())
                .show();
    }

    private static File apkFile(Context context) {
        return new File(context.getExternalFilesDir("updates"), FILE_NAME);
    }

    private static void download(Activity activity, Release release) {
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        TextView percent = new TextView(activity);
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, 0);
        box.addView(bar);
        box.addView(percent);
        boolean[] cancelled = {false};
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(Strings.updateDownloading(release.version))
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(Strings.cancel(), (d, which) -> cancelled[0] = true)
                .show();
        final Context app = activity.getApplicationContext();
        final File file = apkFile(app);
        new Thread(() -> {
            boolean ok = false;
            try {
                ok = fetch(release, file, cancelled, value -> MAIN.post(() -> {
                    bar.setProgress(value);
                    percent.setText(value + "%");
                }));
            } catch (IOException | java.security.NoSuchAlgorithmException | RuntimeException e) {
                Log.w(TAG, "download failed", e);
                if (!cancelled[0]) {
                    CrashReporter.report(TAG, "download failed", e);
                }
            }
            final boolean done = ok;
            MAIN.post(() -> {
                try {
                    dialog.dismiss();
                } catch (RuntimeException e) {
                    Log.w(TAG, "dismiss failed", e); // the activity went away
                }
                if (done) {
                    finish(app, file);
                } else {
                    file.delete();
                    if (!cancelled[0]) {
                        Toast.makeText(app, Strings.updateFailed(), Toast.LENGTH_LONG).show();
                    }
                }
            });
        }, "RTUpdateDownload").start();
    }

    /**
     * @return whether the complete file with the published hash is on disk. 200 MB over one connection broke on any
     * drop or 15 s stall, and the user only saw "update failed": a dropped connection now goes on from where it stopped.
     */
    private static boolean fetch(Release release, File file, boolean[] cancelled, Progress progress)
            throws IOException, java.security.NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        int shown = -1;
        try (OutputStream out = new FileOutputStream(file)) {
            for (int connections = 1; total < release.size; connections++) {
                try {
                    HttpURLConnection connection = open(BASE_URL + release.apk, total);
                    try (InputStream in = body(connection, total)) {
                        byte[] buffer = new byte[64 * 1024];
                        for (int n; (n = in.read(buffer)) > 0; ) {
                            if (cancelled[0]) {
                                return false;
                            }
                            out.write(buffer, 0, n);
                            digest.update(buffer, 0, n);
                            total += n;
                            int percent = (int) (total * 100 / release.size);
                            if (percent != shown) {
                                shown = percent;
                                progress.at(percent);
                            }
                        }
                    } finally {
                        connection.disconnect();
                    }
                } catch (IOException e) {
                    if (connections >= CONNECTIONS || cancelled[0]) {
                        throw new IOException("stopped at " + total + " of " + release.size + " after " + connections
                                + " connections" + (viaProxy ? " via the proxy" : ""), e);
                    }
                    Log.w(TAG, "download cut at " + total + ", going on", e);
                    try {
                        Thread.sleep(2000L * connections);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
                if (total < release.size && connections >= CONNECTIONS) {
                    throw new IOException("stream ended at " + total + " of " + release.size);
                }
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        if (total != release.size || !hex.toString().equals(release.sha256)) {
            throw new IOException("got " + total + " bytes, sha256 " + hex);
        }
        return true;
    }

    private static void finish(Context app, File file) {
        Activity activity = top.get();
        if (activity == null) {
            return;
        }
        if (activity.getPackageManager().canRequestPackageInstalls()) {
            openInstaller(activity, file);
            return;
        }
        ready = file; // resumed() goes on once the permission screen closes
        Toast.makeText(app, Strings.updateAllowInstall(), Toast.LENGTH_LONG).show();
        activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName())));
    }

    /** Twitch's FileProvider exports external storage as "shared"; its class is obfuscated, so the URI is built here. */
    static Uri sharedUri(Context context, File file) throws IOException {
        String root = Environment.getExternalStorageDirectory().getCanonicalPath();
        Uri.Builder uri = new Uri.Builder().scheme("content")
                .authority(context.getPackageName() + ".fileprovider").appendPath("shared");
        for (String part : file.getCanonicalPath().substring(root.length() + 1).split("/")) {
            uri.appendPath(part);
        }
        return uri.build();
    }

    private static void openInstaller(Activity activity, File file) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(sharedUri(activity, file), APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "installer failed", e);
            CrashReporter.report(TAG, "installer failed", e);
            Toast.makeText(activity, Strings.updateFailed(), Toast.LENGTH_LONG).show();
        }
    }
}
