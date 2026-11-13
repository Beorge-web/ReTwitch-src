package tv.retwitch.mod;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;

import java.io.Writer;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tv.retwitch.mod.ads.VaftLog;

/**
 * Diagnostics for "the player drops to ~15 fps after 10-15 minutes" (Android 10, not in stock
 * Twitch, minimising the app clears it). While the switch is on, every 5 s one line goes to
 * Downloads/{@code ReTwitch-ui-*.txt}: UI frames and janky frames (Choreographer), how many times
 * and how long each of our hot hooks ran ({@link #start}/{@link #end}), and the main thread's most
 * common stacks while it was blocked for more than {@value #STALL_MS} ms. A layout loop shows up
 * as layout-pass counts near the frame count while nothing changes on screen.
 */
public final class UiStallLog {

    private static final String TAG = "RTStall";
    private static final long STALL_MS = 40;
    private static final long SAMPLE_MS = 10;
    private static final long REPORT_MS = 5000;
    private static final int STACK_DEPTH = 8;

    private static volatile Writer writer;
    private static volatile String fileName;
    /** Hook name -> {calls, nanos}. */
    private static final Map<String, long[]> HOOKS = new HashMap<>();
    /** Stack signature -> samples (each ~{@value #SAMPLE_MS} ms of a stall). */
    private static final Map<String, Integer> STACKS = new HashMap<>();
    private static volatile long pingAt;
    private static int frames;
    private static int janky;
    private static long lastFrameNanos;

    private UiStallLog() {
    }

    /** From app start: resume logging if the switch was left on. */
    static void startIfEnabled(Context context) {
        if (Toggles.on(Toggles.UI_STALL_LOG)) {
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
        String name = "ReTwitch-ui-" + new SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(new Date()) + ".txt";
        try {
            writer = new OutputStreamWriter(VaftLog.openDownload(context, name), StandardCharsets.UTF_8);
            fileName = name;
            write("log started: ReTwitch " + BuildInfo.VERSION_NAME + ", Android " + Build.VERSION.RELEASE
                    + " (SDK " + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL);
            startWatchdog();
            new Handler(Looper.getMainLooper()).post(UiStallLog::startFrameCounter);
        } catch (Throwable t) {
            Log.w(TAG, "could not create the log file", t);
            CrashReporter.report(TAG, "could not create the log file", t);
        }
        return fileName;
    }

    /** Start of a measured hook; 0 (free) while logging is off. */
    public static long start() {
        return writer != null ? System.nanoTime() : 0L;
    }

    /** End of a measured hook started with {@link #start}. Never throws. */
    public static void end(String hook, long startNanos) {
        if (startNanos == 0L) {
            return;
        }
        long spent = System.nanoTime() - startNanos;
        synchronized (HOOKS) {
            long[] stat = HOOKS.get(hook);
            if (stat == null) {
                stat = new long[2];
                HOOKS.put(hook, stat);
            }
            stat[0]++;
            stat[1] += spent;
        }
    }

    private static void startWatchdog() {
        final Thread main = Looper.getMainLooper().getThread();
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable pong = () -> pingAt = 0L;
        Thread watchdog = new Thread(() -> {
            long lastReport = SystemClock.uptimeMillis();
            while (writer != null) {
                try {
                    long now = SystemClock.uptimeMillis();
                    long sent = pingAt;
                    if (sent == 0L) {
                        pingAt = now;
                        handler.post(pong);
                    } else if (now - sent >= STALL_MS) {
                        String signature = signature(main.getStackTrace());
                        synchronized (STACKS) {
                            Integer n = STACKS.get(signature);
                            STACKS.put(signature, n == null ? 1 : n + 1);
                        }
                    }
                    if (now - lastReport >= REPORT_MS) {
                        report(now - lastReport);
                        lastReport = now;
                    }
                    Thread.sleep(SAMPLE_MS);
                } catch (Throwable t) {
                    Log.w(TAG, "watchdog", t);
                }
            }
        }, "RTStall");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** Runs on the main thread: counts UI frames and those over two vsyncs. */
    private static void startFrameCounter() {
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                if (writer == null) {
                    return;
                }
                synchronized (HOOKS) {
                    frames++;
                    if (lastFrameNanos != 0L && frameTimeNanos - lastFrameNanos > 34_000_000L) {
                        janky++;
                    }
                }
                lastFrameNanos = frameTimeNanos;
                Choreographer.getInstance().postFrameCallback(this);
            }
        });
    }

    /** The innermost frames of a stack, skipping the message loop itself. */
    static String signature(StackTraceElement[] stack) {
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        for (StackTraceElement e : stack) {
            if (taken == STACK_DEPTH) {
                break;
            }
            String cls = e.getClassName();
            if (cls.startsWith("android.os.MessageQueue") || cls.startsWith("android.os.Looper")) {
                break;
            }
            if (taken > 0) {
                sb.append(" < ");
            }
            sb.append(cls.substring(cls.lastIndexOf('.') + 1)).append('.').append(e.getMethodName())
                    .append(':').append(e.getLineNumber());
            taken++;
        }
        return taken == 0 ? "(idle)" : sb.toString();
    }

    private static void report(long windowMs) {
        StringBuilder line = new StringBuilder();
        synchronized (HOOKS) {
            line.append("ui ").append(windowMs / 1000).append("s: frames=").append(frames)
                    .append(" janky=").append(janky);
            List<String> names = new ArrayList<>(HOOKS.keySet());
            Collections.sort(names);
            for (String name : names) {
                long[] stat = HOOKS.get(name);
                line.append(" | ").append(name).append(' ').append(stat[0]).append("x/")
                        .append(stat[1] / 1_000_000L).append("ms");
            }
            HOOKS.clear();
            frames = 0;
            janky = 0;
        }
        List<Map.Entry<String, Integer>> top;
        synchronized (STACKS) {
            top = new ArrayList<>(STACKS.entrySet());
            STACKS.clear();
        }
        int stalled = 0;
        for (Map.Entry<String, Integer> e : top) {
            stalled += e.getValue();
        }
        line.append(" | stalled~").append(stalled * SAMPLE_MS).append("ms");
        Collections.sort(top, (a, b) -> b.getValue() - a.getValue());
        for (int i = 0; i < Math.min(5, top.size()); i++) {
            line.append("\n    [").append(top.get(i).getValue()).append("x] ").append(top.get(i).getKey());
        }
        write(line.toString());
    }

    private static void write(String message) {
        Writer out = writer;
        if (out == null) {
            return;
        }
        try {
            synchronized (UiStallLog.class) {
                out.write(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + message + "\n");
                out.flush();
            }
        } catch (Throwable ignored) {
            // file closed meanwhile
        }
    }
}
