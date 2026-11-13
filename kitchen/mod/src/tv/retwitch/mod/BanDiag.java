package tv.retwitch.mod;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Diagnostics for "a permanently banned user's message disappears" ({@link Toggles#BAN_DIAG_LOG},
 * Downloads/{@code ReTwitch-bans-*.txt} and logcat {@code RTBanDiag}): the clear events, and for
 * a minute after a ban / timeout / delete every chat row bound with that login in it (holder,
 * visibility, text) and what the deleted-message styling made of it. Off: nothing runs.
 * shortcut: a debugging aid for one report -- remove once the cause is found.
 */
final class BanDiag {

    private static final String TAG = "RTBanDiag";
    private static volatile String login;
    private static volatile long until;
    private static volatile java.lang.reflect.Field itemView;
    private static volatile Writer writer;
    private static volatile String fileName;

    private BanDiag() {
    }

    /** From app start: resume logging if the switch was left on. */
    static void startIfEnabled(Context context) {
        if (Toggles.on(Toggles.BAN_DIAG_LOG)) {
            setEnabled(context, true);
        }
    }

    /** From the settings switch; returns the log file's name, or null. */
    static synchronized String setEnabled(Context context, boolean on) {
        if (!on) {
            String name = fileName;
            Writer current = writer;
            writer = null;
            fileName = null;
            if (current != null) {
                try {
                    current.close();
                } catch (Throwable ignored) {
                    // best-effort
                }
            }
            return name;
        }
        if (writer != null) {
            return fileName;
        }
        String name = "ReTwitch-bans-" + new SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(new Date()) + ".txt";
        try {
            writer = new OutputStreamWriter(tv.retwitch.mod.ads.VaftLog.openDownload(context, name), StandardCharsets.UTF_8);
            fileName = name;
            log("log started: ReTwitch " + BuildInfo.VERSION_NAME + ", Android " + Build.VERSION.RELEASE + ", "
                    + Build.MANUFACTURER + " " + Build.MODEL + ", deleted mode " + ChatSettings.getDeletedMode());
        } catch (Throwable t) {
            Log.w(TAG, "could not create the log file", t);
        }
        return fileName;
    }

    static void watch(String who, String why) {
        if (writer == null || who == null || who.isEmpty()) {
            return;
        }
        login = who.toLowerCase(Locale.ROOT);
        until = SystemClock.elapsedRealtime() + 60_000;
        log("watch " + who + ": " + why);
    }

    static boolean enabled() {
        return writer != null;
    }

    static boolean active() {
        return writer != null && login != null && SystemClock.elapsedRealtime() < until;
    }

    static void log(String what) {
        Writer out = writer;
        if (out == null) {
            return;
        }
        Log.i(TAG, what);
        try {
            synchronized (BanDiag.class) {
                out.write(new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + what + "\n");
                out.flush();
            }
        } catch (Throwable ignored) {
            // file closed meanwhile
        }
    }

    /** From the chat adapter's row bind: rows with the watched login in them. */
    static void onRow(Object holder) {
        if (!active()) {
            return;
        }
        try {
            java.lang.reflect.Field f = itemView;
            for (Class<?> k = holder.getClass(); f == null && k != null; k = k.getSuperclass()) {
                for (java.lang.reflect.Field c : k.getDeclaredFields()) {
                    if (c.getType() == View.class) {
                        c.setAccessible(true);
                        itemView = f = c;
                        break;
                    }
                }
            }
            View root = f == null ? null : (View) f.get(holder);
            if (root == null) {
                return;
            }
            StringBuilder text = new StringBuilder();
            texts(root, text, 0);
            if (!text.toString().toLowerCase(Locale.ROOT).contains(login)) {
                return;
            }
            log("row " + holder + " view=" + root.getClass().getSimpleName() + " vis=" + root.getVisibility()
                    + " h=" + root.getHeight() + " text=\"" + text + "\"");
        } catch (Throwable t) {
            Log.w(TAG, "onRow failed", t);
        }
    }

    private static void texts(View v, StringBuilder out, int depth) {
        if (v instanceof TextView) {
            out.append(v.getVisibility() == View.VISIBLE ? "" : "[hidden]").append(((TextView) v).getText()).append(" | ");
        } else if (v instanceof ViewGroup && depth < 6) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                texts(((ViewGroup) v).getChildAt(i), out, depth + 1);
            }
        }
    }
}
