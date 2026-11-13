package tv.retwitch.mod.ads;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import tv.retwitch.mod.AppContext;
import tv.retwitch.mod.BuildInfo;
import tv.retwitch.mod.CrashReporter;
import tv.retwitch.mod.Toggles;

/**
 * Detailed VAFT log in a text file in Downloads, for freeze reports (toggle
 * {@link Toggles#VAFT_LOG}, off by default; idea from Overhypedcat's
 * DebugLog): blocker decisions, sources, sequence numbers, timings, the
 * player's buffering and quality ({@code PlayerStats}). URLs go in
 * only through {@link #describe} -- host and the start of the path, never the
 * query with its tokens. Nothing is collected while it is off.
 */
public final class VaftLog {

    private static final String TAG = "RTVaftLog";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "RTVaftLog");
            t.setDaemon(true);
            return t;
        }
    });

    /** The open log file, or null while logging is off. */
    private static volatile Writer writer;
    private static volatile String fileName;
    /** The toggle was read once (logging resumes after an app restart). */
    private static volatile boolean checked;

    private VaftLog() {
    }

    /** Appends a line when the log is on; cheap no-op otherwise. Never throws. */
    public static void log(String message) {
        try {
            Writer target = writer;
            if (target == null) {
                if (checked) {
                    return;
                }
                resumeIfEnabled();
                target = writer;
                if (target == null) {
                    return;
                }
            }
            final Writer out = target;
            final String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                    + " [" + Thread.currentThread().getName() + "] " + message + "\n";
            IO.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Flushed per line: at most a few lines a second, and nothing lost if the app dies.
                        out.write(line);
                        out.flush();
                    } catch (IOException ignored) {
                        // file closed by setEnabled(false) meanwhile
                    }
                }
            });
        } catch (Throwable t) {
            // never let logging break playback
        }
    }

    /** Like {@link #log}, opens the file of a log left on: on a VOD or without VAFT nothing else may write first. */
    public static boolean isOn() {
        if (writer == null && !checked) {
            resumeIfEnabled();
        }
        return writer != null;
    }

    /** Host and the start of the path, for the log; never the query (tokens, auth). */
    public static String describe(String url) {
        if (url == null) {
            return "null";
        }
        int scheme = url.indexOf("://");
        String rest = scheme < 0 ? url : url.substring(scheme + 3);
        int query = rest.indexOf('?');
        if (query >= 0) {
            rest = rest.substring(0, query);
        }
        return rest.length() > 64 ? rest.substring(0, 64) + "..." : rest;
    }

    private static synchronized void resumeIfEnabled() {
        if (checked) {
            return;
        }
        checked = true;
        Context context = AppContext.get();
        if (context != null && Toggles.on(Toggles.VAFT_LOG) && writer == null) {
            start(context);
        }
    }

    /**
     * From the settings switch.
     *
     * @return the new file's name when turned on, the finished file's when turned off, or null
     */
    public static synchronized String setEnabled(Context context, boolean on) {
        checked = true;
        if (on) {
            if (writer == null) {
                start(context);
            }
            return fileName;
        }
        String name = fileName;
        final Writer current = writer;
        if (current != null) {
            log("log stopped");
            writer = null;
            fileName = null;
            IO.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        current.close();
                    } catch (IOException ignored) {
                        // best-effort
                    }
                }
            });
        }
        return name;
    }

    private static void start(Context context) {
        String name = "ReTwitch-vaft-" + new SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(new Date()) + ".txt";
        try {
            writer = new OutputStreamWriter(openDownload(context, name), StandardCharsets.UTF_8);
            fileName = name;
            log("log started: ReTwitch " + BuildInfo.VERSION_NAME + ", Android " + Build.VERSION.RELEASE
                    + " (SDK " + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL);
        } catch (Throwable t) {
            Log.w(TAG, "could not create the log file", t);
            CrashReporter.report(TAG, "could not create the log file", t);
        }
    }

    /** A new text file in Downloads (MediaStore on Android 10+, the app folder before). Also used by UiStallLog. */
    public static OutputStream openDownload(Context context, String name) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            ContentResolver resolver = context.getContentResolver();
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            OutputStream out = uri == null ? null : resolver.openOutputStream(uri, "w");
            if (out == null) {
                throw new IOException("MediaStore refused " + name);
            }
            return out;
        }
        // Android 8-9: the app's own folder, Android/data/<package>/files/Download (no permission needed).
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            throw new IOException("no external storage");
        }
        return new FileOutputStream(new File(dir, name));
    }
}
