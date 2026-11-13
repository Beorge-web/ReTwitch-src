package tv.retwitch.mod;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import java.util.Locale;

/**
 * In-process sleep/exit-timer engine: counts down to zero and then closes the
 * whole app, mirroring PurpleTV's "Таймер завершения" feature.
 *
 * <p><b>Why no foreground {@code Service}.</b> PurpleTV runs the countdown in a
 * declared foreground service so it survives background playback. The ReTwitch
 * kitchen injects only a plain-SDK extension dex and deliberately keeps manifest
 * edits minimal (see {@code kitchen/mod_builder.py}); registering a
 * {@code <service>} for its own class would be extra surface for little gain,
 * because the timer's whole job is to <i>close</i> the app while the user is
 * actively watching (foreground). So the countdown is a main-thread
 * {@link Handler} loop living in the app process, with an ongoing notification
 * (best-effort -- dropped silently if the user hasn't granted
 * {@code POST_NOTIFICATIONS}) exposing Cancel / +10m / +1h actions. If the OS
 * kills the backgrounded process before the timer fires, the goal (app not
 * running) is met anyway.
 *
 * <p>Per the mod's fail-soft rule every entry point and the tick callback is
 * wrapped in {@code catch (Throwable)} so nothing escapes into Twitch.
 */
public final class TimerController {

    private static final String TAG = "RTTimer";

    private static final String CHANNEL_ID = "retwitch_timer";
    private static final int NOTIFICATION_ID = 0x52_54_01; // "RT" + 01

    private static final String ACTION_CANCEL = "tv.retwitch.timer.CANCEL";
    private static final String ACTION_ADD = "tv.retwitch.timer.ADD";
    private static final String EXTRA_SECONDS = "tv.retwitch.timer.SECONDS";

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private static volatile boolean running;
    private static int totalSeconds;
    private static int remainingSeconds;
    private static boolean receiverRegistered;

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            try {
                if (!running) {
                    return;
                }
                if (remainingSeconds > 0) {
                    remainingSeconds--;
                    updateNotification();
                    HANDLER.postDelayed(this, 1000L);
                } else if (TimerSettings.isPauseMode()) {
                    pausePlayback();
                } else {
                    killApp();
                }
            } catch (Throwable t) {
                Log.w(TAG, "tick failed", t);
                CrashReporter.report(TAG, "tick failed", t);
            }
        }
    };

    private static final BroadcastReceiver RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                if (intent == null) {
                    return;
                }
                String action = intent.getAction();
                if (ACTION_CANCEL.equals(action)) {
                    cancel();
                } else if (ACTION_ADD.equals(action)) {
                    addSeconds(intent.getIntExtra(EXTRA_SECONDS, 0));
                }
            } catch (Throwable t) {
                Log.w(TAG, "receiver failed", t);
                CrashReporter.report(TAG, "receiver failed", t);
            }
        }
    };

    private TimerController() {
    }

    public static boolean isRunning() {
        return running;
    }

    /** Remaining time as {@code HH:MM:SS}, or {@code null} when not running. */
    public static String remaining() {
        return running ? formatTime(remainingSeconds) : null;
    }

    /**
     * (Re)starts the countdown. A running timer is replaced by the new
     * duration. Called from the timer dialog / settings menu on the main
     * thread.
     */
    public static void start(Context context, int seconds) {
        try {
            if (seconds <= 0) {
                return;
            }
            Context app = appContext(context);
            totalSeconds = seconds;
            remainingSeconds = seconds;
            running = true;
            ensureReceiver(app);
            ensureChannel(app);
            updateNotification();
            HANDLER.removeCallbacks(TICK);
            HANDLER.postDelayed(TICK, 1000L);
        } catch (Throwable t) {
            Log.w(TAG, "start failed", t);
            CrashReporter.report(TAG, "start failed", t);
        }
    }

    public static void addSeconds(int seconds) {
        try {
            if (!running || seconds == 0) {
                return;
            }
            remainingSeconds = Math.max(0, remainingSeconds + seconds);
            totalSeconds = Math.max(totalSeconds, remainingSeconds);
            updateNotification();
        } catch (Throwable t) {
            Log.w(TAG, "addSeconds failed", t);
            CrashReporter.report(TAG, "addSeconds failed", t);
        }
    }

    public static void cancel() {
        try {
            running = false;
            HANDLER.removeCallbacks(TICK);
            Context app = AppContext.get();
            if (app != null) {
                notificationManager(app).cancel(NOTIFICATION_ID);
            }
        } catch (Throwable t) {
            Log.w(TAG, "cancel failed", t);
            CrashReporter.report(TAG, "cancel failed", t);
        }
    }

    /**
     * "Pause" mode (ReVive's sleep timer): a media pause key, as from a headset,
     * which Twitch's media session answers; if the audible IVS player still
     * plays a moment later, its public {@code pause()}.
     */
    private static void pausePlayback() {
        cancel();
        try {
            Context app = AppContext.get();
            AudioManager am = app != null ? (AudioManager) app.getSystemService(Context.AUDIO_SERVICE) : null;
            if (am != null) {
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
            }
        } catch (Throwable t) {
            Log.w(TAG, "media pause failed", t);
        }
        HANDLER.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Object player = PlayerHook.currentPlayer();
                    if (player != null && "PLAYING".equals(String.valueOf(
                            player.getClass().getMethod("getState").invoke(player)))) {
                        player.getClass().getMethod("pause").invoke(player);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "player pause failed", t);
                    CrashReporter.report(TAG, "player pause failed", t);
                }
            }
        }, 700L);
    }

    private static void killApp() {
        try {
            running = false;
            HANDLER.removeCallbacks(TICK);
            Context app = AppContext.get();
            if (app != null) {
                notificationManager(app).cancel(NOTIFICATION_ID);
            }
        } catch (Throwable ignored) {
            // fall through: closing the process is the important part
        }
        // Bring the whole app down (parity with PurpleTV's CoreUtil.killApp).
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                Strings.timerNotifyChannel(),
                NotificationManager.IMPORTANCE_LOW);
        notificationManager(context).createNotificationChannel(channel);
    }

    private static void updateNotification() {
        try {
            Context app = AppContext.get();
            if (app == null || !running) {
                return;
            }
            notificationManager(app).notify(NOTIFICATION_ID, buildNotification(app));
        } catch (Throwable t) {
            Log.w(TAG, "updateNotification failed", t);
            CrashReporter.report(TAG, "updateNotification failed", t);
        }
    }

    private static Notification buildNotification(Context context) {
        Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);

        builder.setContentTitle(Strings.timerNotifyTitle())
                .setContentText(formatTime(remainingSeconds))
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        builder.addAction(actionIcon(), Strings.timerCancel(), pendingCancel(context));
        builder.addAction(actionIcon(), "+10m", pendingAdd(context, 10 * 60, 1));
        builder.addAction(actionIcon(), "+1h", pendingAdd(context, 60 * 60, 2));
        return builder.build();
    }

    private static int actionIcon() {
        return android.R.drawable.ic_menu_recent_history;
    }

    private static PendingIntent pendingCancel(Context context) {
        Intent intent = new Intent(ACTION_CANCEL).setPackage(context.getPackageName());
        return PendingIntent.getBroadcast(context, 0, intent, pendingFlags());
    }

    private static PendingIntent pendingAdd(Context context, int seconds, int requestCode) {
        Intent intent = new Intent(ACTION_ADD)
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_SECONDS, seconds);
        return PendingIntent.getBroadcast(context, requestCode, intent, pendingFlags());
    }

    private static int pendingFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    /**
     * Registers our notification-action receiver once, at runtime (no manifest
     * entry needed). Flagged {@code RECEIVER_NOT_EXPORTED} on API 33+ where the
     * flag is mandatory for context-registered receivers.
     */
    private static void ensureReceiver(Context context) {
        if (receiverRegistered || context == null) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_CANCEL);
        filter.addAction(ACTION_ADD);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(RECEIVER, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(RECEIVER, filter);
        }
        receiverRegistered = true;
    }

    private static NotificationManager notificationManager(Context context) {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static Context appContext(Context context) {
        Context app = context != null ? context.getApplicationContext() : null;
        if (app == null) {
            app = AppContext.get();
        }
        return app;
    }

    static String formatTime(int seconds) {
        int hours = seconds / 3600;
        int minutes = (seconds / 60) % 60;
        int secs = seconds % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs);
    }

    /** Shows a short "timer started/cancelled" confirmation, best-effort. */
    static void toast(Context context, String message) {
        try {
            Context c = context != null ? context : AppContext.get();
            if (c != null) {
                Toast.makeText(c, message, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }
}
