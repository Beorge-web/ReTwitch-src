package tv.retwitch.mod;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * The foreground service that keeps live recordings and VOD downloads ({@link LiveRecord}) and
 * auto-record's wait ({@link AutoRecord}) alive with the app in the background: one ongoing notification for all of
 * it, a partial wake lock and a Wi-Fi lock while something downloads, and an alarm a minute apart for auto-record.
 * Type {@code specialUse} (declared by {@code RecordServicePatch}): {@code dataSync} stops after 6 hours a day on
 * Android 15 with Twitch's target SDK. It is started only from the foreground (a tap, the app coming up) --
 * Android 12+ refuses a foreground service start from the background -- and updated in place afterwards.
 */
public final class RecordService extends Service {

    private static final String TAG = "RTRecordService";
    private static final String CHANNEL = "retwitch_record";
    private static final int NOTIFICATION_ID = 0x7e7d0b;
    private static final int FINISHED_ID = 0x7e7d0c;
    private static final String ACTION_STOP = "tv.retwitch.mod.RECORD_STOP";
    private static final String ACTION_PAUSE = "tv.retwitch.mod.RECORD_PAUSE";
    private static final String ACTION_AUTO_OFF = "tv.retwitch.mod.RECORD_AUTO_OFF";
    private static final String ACTION_CHECK = "tv.retwitch.mod.RECORD_CHECK";
    private static final String EXTRA_ID = "id";
    private static final long CHECK_MS = 60_000;
    /** {@code ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE}, API 34. */
    private static final int TYPE_SPECIAL_USE = 1 << 30;

    private static volatile RecordService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::update;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private long lastCheck;

    /** Starts the service when there is work for it, else updates or stops the running one. Any thread. */
    static void refresh(Context context) {
        final RecordService running = instance;
        if (running != null) {
            running.main.post(running::update);
            return;
        }
        if (context == null || !needed()) {
            return;
        }
        try {
            Intent intent = new Intent(context, RecordService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            // From the background Android 12+ refuses it (ForegroundServiceStartNotAllowedException).
            Log.w(TAG, "start refused: " + t);
        }
    }

    static boolean isRunning() {
        return instance != null;
    }

    private static boolean busy() {
        return !LiveRecord.ACTIVE.isEmpty();
    }

    private static boolean needed() {
        return busy() || AutoRecord.waiting();
    }

    /** A recording ended: a notification to open it (or say why it stopped), then the service follows. */
    static void finished(Context context, LiveRecord.Rec rec) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            channel(context, nm);
            boolean vod = rec.isVod();
            String title = rec.count == 0 && !rec.resumed
                    ? Strings.pickf("Запись %s не началась", "Recording %s didn't start", rec.display)
                    : rec.ended ? vod ? Strings.pick("Видео скачано", "Video downloaded")
                    : Strings.pick("Запись сохранена", "Recording saved")
                    : rec.stopped ? vod ? Strings.pick("Скачивание остановлено", "Download stopped")
                    : Strings.pick("Запись остановлена", "Recording stopped")
                    : vod ? Strings.pick("Скачивание прервалось", "Download cut short")
                    : Strings.pick("Запись прервалась", "Recording cut short");
            String text = rec.count > 0 || rec.resumed ? rec.file + " · " + LiveRecord.clock(rec.seconds)
                    + (rec.problem != null ? " · " + rec.problem : "")
                    : rec.problem != null ? rec.problem : "";
            nm.notify(FINISHED_ID + Math.abs(rec.id.hashCode() % 1000), new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(openApp(context))
                    .build());
        } catch (Throwable t) {
            Log.w(TAG, "finished notification failed: " + t);
        }
        refresh(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            // First, within Android's 5-10 s, whatever the action.
            Notification notification = build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            act(intent);
        } catch (Throwable t) {
            Log.w(TAG, "start failed", t);
            CrashReporter.report(TAG, "start failed", t);
        }
        update();
        return START_STICKY;
    }

    private void act(Intent intent) {
        String action = intent == null ? null : intent.getAction();
        String id = intent == null ? null : intent.getStringExtra(EXTRA_ID);
        if (ACTION_STOP.equals(action)) {
            LiveRecord.Rec rec = id == null ? null : LiveRecord.byId(id);
            if (id == null) {
                LiveRecord.stopAll();
            } else if (rec != null) {
                LiveRecord.stop(rec); // a stale notification's id: nothing else to stop
            }
        } else if (ACTION_PAUSE.equals(action)) {
            LiveRecord.Rec rec = id == null ? null : LiveRecord.byId(id);
            if (rec != null) {
                LiveRecord.togglePause(rec);
            }
        } else if (ACTION_AUTO_OFF.equals(action)) {
            AutoRecord.setOn(this, false);
        } else if (ACTION_CHECK.equals(action)) {
            check();
        }
    }

    /** Auto-record's look at the channels, off the main thread under a short wake lock. */
    private void check() {
        lastCheck = SystemClock.elapsedRealtime();
        final PowerManager.WakeLock brief = ((PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReTwitch:record-check");
        brief.acquire(30_000);
        final Context app = getApplicationContext();
        new Thread(() -> {
            try {
                AutoRecord.check(app);
            } catch (Throwable t) {
                Log.w(TAG, "auto-record check failed", t);
            } finally {
                brief.release();
                refresh(app);
            }
        }, "RTRecordCheck").start();
    }

    /** Notification, locks and the alarm for the current state; stops the service when nothing is left. */
    private void update() {
        try {
            main.removeCallbacks(tick);
            if (!needed()) {
                locks(false);
                alarm(false);
                stopForeground(true);
                stopSelf();
                return;
            }
            boolean busy = busy();
            locks(busy);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, build());
            boolean waiting = AutoRecord.waiting();
            alarm(waiting && !busy); // while busy this runs every second and checks itself
            if (waiting && SystemClock.elapsedRealtime() - lastCheck > CHECK_MS) {
                check();
            }
            if (busy) {
                main.postDelayed(tick, 1000); // the clock in the notification
            }
        } catch (Throwable t) {
            Log.w(TAG, "update failed", t);
            CrashReporter.report(TAG, "update failed", t);
        }
    }

    private void locks(boolean on) {
        if (on && wake == null) {
            wake = ((PowerManager) getSystemService(POWER_SERVICE))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReTwitch:record");
            wake.acquire();
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ReTwitch:record");
                wifi.acquire();
            }
        } else if (!on && wake != null) {
            wake.release();
            wake = null;
            if (wifi != null) {
                wifi.release();
                wifi = null;
            }
        }
    }

    /** Inexact and allowed while idle: in Doze it comes every ~9 minutes, fine for an archive that starts at the start. */
    private void alarm(boolean on) {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        PendingIntent intent = PendingIntent.getService(this, 1, new Intent(this, RecordService.class)
                .setAction(ACTION_CHECK), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (on) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + CHECK_MS,
                    intent);
        } else {
            am.cancel(intent);
        }
    }

    private Notification build() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        channel(this, nm);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openApp(this));
        List<LiveRecord.Rec> recs = new ArrayList<>(LiveRecord.ACTIVE);
        if (recs.size() == 1) {
            LiveRecord.Rec rec = recs.get(0);
            b.setSmallIcon(rec.isVod() ? rec.paused ? android.R.drawable.ic_media_pause : android.R.drawable.stat_sys_download
                    : android.R.drawable.presence_video_online)
                    .setContentTitle(rec.isVod() ? Strings.pickf("Скачивание · %s", "Downloading · %s", rec.display)
                            : Strings.pickf("Запись · %s", "Recording · %s", rec.display))
                    .setContentText(LiveRecord.line(this, rec, false));
            if (rec.archive && rec.behind > 0) {
                b.setProgress(100, LiveRecord.percent(rec), false);
            }
            if (rec.archive) {
                b.addAction(action(ACTION_PAUSE, rec.id, rec.paused ? android.R.drawable.ic_media_play
                        : android.R.drawable.ic_media_pause, rec.paused ? Strings.downloadResume() : Strings.downloadPause()));
            }
            b.addAction(action(ACTION_STOP, rec.id, android.R.drawable.ic_menu_close_clear_cancel,
                    Strings.pick("Стоп", "Stop")));
        } else if (!recs.isEmpty()) {
            Notification.InboxStyle lines = new Notification.InboxStyle();
            for (LiveRecord.Rec rec : recs) {
                lines.addLine(LiveRecord.line(this, rec, true));
            }
            b.setSmallIcon(android.R.drawable.presence_video_online)
                    .setContentTitle(Strings.pickf("Записи: %d", "Recordings: %d", recs.size()))
                    .setContentText(LiveRecord.line(this, recs.get(0), true))
                    .setStyle(lines)
                    .addAction(action(ACTION_STOP, null, android.R.drawable.ic_menu_close_clear_cancel,
                            Strings.pick("Стоп всё", "Stop all")));
        } else {
            int channels = AutoRecord.enabledCount();
            b.setSmallIcon(android.R.drawable.presence_video_away)
                    .setContentTitle(Strings.pick("ReTwitch ждёт эфира", "ReTwitch is waiting for streams"))
                    .setContentText(AutoRecord.waitingLine(this, channels))
                    .addAction(action(ACTION_AUTO_OFF, null, android.R.drawable.ic_menu_close_clear_cancel,
                            Strings.pick("Выключить", "Turn off")));
        }
        return b.build();
    }

    private Notification.Action action(String action, String id, int icon, String label) {
        Intent intent = new Intent(this, RecordService.class).setAction(action);
        if (id != null) {
            intent.putExtra(EXTRA_ID, id);
        }
        int code = (action + id).hashCode();
        return new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), label,
                PendingIntent.getService(this, code, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .build();
    }

    private static void channel(Context context, NotificationManager nm) {
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, Strings.pick("Запись трансляций",
                "Stream recording"), NotificationManager.IMPORTANCE_LOW));
    }

    private static PendingIntent openApp(Context context) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        return launch == null ? null : PendingIntent.getActivity(context, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onDestroy() {
        instance = null;
        main.removeCallbacks(tick);
        locks(false);
        super.onDestroy();
        refresh(getApplicationContext()); // work that came between stopSelf() and here
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
