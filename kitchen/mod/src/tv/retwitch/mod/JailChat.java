package tv.retwitch.mod;

import android.app.Activity;
import android.app.Application;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * RTE "jail" chat, receive side (RTE {@code modules/jail_chat}): the RTE backend relays
 * a shadow chat per channel over {@code wss://ext.rte.net.ru:8443/chat-relay/ws?channel=<login>}
 * as {@code {"type":"jail_message","channel","twitch_id","login","display_name","color","message"}}.
 * Each message becomes a synthetic PRIVMSG ({@link JailLine}) appended to the next IRC frame
 * Twitch's chat SDK reads ({@code JailChatPatch} -> {@link #onIrcFrame}), so it is parsed and
 * drawn like any chatter's line: name color, RTE badge and paint (by user id), emotes. A PING
 * on the IRC socket makes the server send that frame right away. Without the patch (or before
 * any frame) it falls back to a notice line "name: text" ({@link ChatNotices}). Sending (the
 * operator's cookie token) is not ported.
 *
 * <p>Always on, no switch: the backend is the switch. A refused handshake, a dropped socket
 * or a frame we don't understand only schedules a reconnect (backoff up to a minute).
 * Connected only to the current channel and only while a Twitch activity is started.
 * State lives on the main thread. Never throws.
 */
public final class JailChat {

    private static final String TAG = "RTJailChat";
    private static final String URL = "wss://ext.rte.net.ru:8443/chat-relay/ws?channel=";
    private static final long MAX_BACKOFF_MS = 60_000;
    /** A socket that lived this long was a real session: the next drop starts the backoff over. */
    private static final long STABLE_MS = 60_000;
    /** Activity recreation (rotation) briefly has no started activity: don't drop the socket for it. */
    private static final long BACKGROUND_GRACE_MS = 5_000;
    /** Queued lines older than this are dropped rather than shown late. */
    private static final long MAX_PENDING_MS = 60_000;
    private static final int MAX_PENDING = 50;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable APPLY = JailChat::apply;
    private static final Runnable RECONNECT = JailChat::reconnect;

    private static OkHttpClient client;
    private static WebSocket socket;
    /** Channel login being served: connected or a reconnect is scheduled. */
    private static String login;
    private static long openedAt;
    private static int attempts;
    private static int started;
    private static boolean installed;

    /** Lines waiting for the next IRC frame: {elapsedRealtime, line}. */
    private static final ConcurrentLinkedQueue<Object[]> PENDING = new ConcurrentLinkedQueue<>();
    /** A channel's backlog (RecentMessages): rides the next frame before anything else, no cap of PENDING's. */
    private static final ConcurrentLinkedQueue<String> HISTORY = new ConcurrentLinkedQueue<>();
    /** The chat SDK's frame collector that read the last frame (it holds the IRC socket). */
    private static volatile Object ircCollector;
    private static volatile Method sendBridge;

    private JailChat() {
    }

    /** Called once from the injected {@code TwitchApplication.onCreate}. */
    public static void install(Application application) {
        try {
            if (installed || application == null) {
                return;
            }
            installed = true;
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                public void onActivityCreated(Activity a, Bundle b) { }
                public void onActivityStarted(Activity a) {
                    started++;
                    MAIN.removeCallbacks(APPLY);
                    apply();
                }
                public void onActivityResumed(Activity a) { }
                public void onActivityPaused(Activity a) { }
                public void onActivityStopped(Activity a) {
                    started = Math.max(0, started - 1);
                    if (started == 0) {
                        MAIN.postDelayed(APPLY, BACKGROUND_GRACE_MS);
                    }
                }
                public void onActivitySaveInstanceState(Activity a, Bundle b) { }
                public void onActivityDestroyed(Activity a) { }
            });
        } catch (Throwable t) {
            Log.w(TAG, "install failed", t);
            CrashReporter.report(TAG, "install failed", t);
        }
    }

    /** The current channel changed or was reopened (any thread). */
    static void sync() {
        MAIN.post(APPLY);
    }

    private static void apply() {
        MAIN.removeCallbacks(WATCHDOG);
        if (started > 0) {
            MAIN.postDelayed(WATCHDOG, WATCHDOG_TICK_MS);
        }
        // Same triggers (channel, foreground) for the AutoMod socket.
        AutoModChat.apply(started > 0);
        tv.retwitch.mod.emotes.SevenTvEvents.apply(started > 0);
        try {
            String want = started > 0 ? ChannelTracker.getCurrentChannelLogin() : null;
            if (want != null && want.equals(login)) {
                return;
            }
            stop();
            if (want != null) {
                login = want;
                attempts = 0;
                connect();
            }
        } catch (Throwable t) {
            Log.w(TAG, "apply failed", t);
            CrashReporter.report(TAG, "apply failed", t);
        }
    }

    private static void stop() {
        MAIN.removeCallbacks(RECONNECT);
        PENDING.clear();
        WebSocket ws = socket;
        socket = null;
        login = null;
        openedAt = 0;
        if (ws != null) {
            ws.cancel();
        }
    }

    private static void reconnect() {
        if (login != null && socket == null) {
            connect();
        }
    }

    private static void connect() {
        try {
            if (client == null) {
                client = new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .pingInterval(30, TimeUnit.SECONDS)
                        .build();
            }
            Request request = new Request.Builder().url(URL + Uri.encode(login)).build();
            socket = client.newWebSocket(request, new Listener());
            Log.i(TAG, "connecting #" + login);
        } catch (Throwable t) {
            Log.w(TAG, "connect failed", t);
            CrashReporter.report(TAG, "connect failed", t);
            socket = null;
            scheduleReconnect();
        }
    }

    private static void dropped(WebSocket ws, String why) {
        if (ws != socket) {
            return;
        }
        socket = null;
        if (openedAt != 0 && SystemClock.elapsedRealtime() - openedAt >= STABLE_MS) {
            attempts = 0;
        }
        openedAt = 0;
        Log.i(TAG, "#" + login + " dropped: " + why);
        scheduleReconnect();
    }

    private static void scheduleReconnect() {
        long delay = Math.min(1000L << Math.min(attempts, 6), MAX_BACKOFF_MS);
        attempts++;
        MAIN.removeCallbacks(RECONNECT);
        MAIN.postDelayed(RECONNECT, delay);
    }

    private static void show(String text) {
        try {
            JSONObject p = new JSONObject(text);
            if (!"jail_message".equals(str(p, "type"))) {
                return;
            }
            String channel = str(p, "channel");
            if (login == null || !login.equals(ChannelTracker.getCurrentChannelLogin())
                    || (!channel.isEmpty() && !channel.equalsIgnoreCase(login))) {
                return;
            }
            String name = str(p, "display_name");
            if (name.isEmpty()) {
                name = str(p, "login");
            }
            String line = JailLine.privmsg(login, ChannelTracker.getCurrentChannelId(), str(p, "twitch_id"),
                    str(p, "login"), name, str(p, "color"), str(p, "id"), str(p, "message"),
                    System.currentTimeMillis());
            if (line == null) {
                return;
            }
            if (!injectLine(line)) {
                String message = str(p, "message");
                if (message.length() > JailLine.MAX_TEXT) {
                    message = message.substring(0, JailLine.MAX_TEXT);
                }
                ChatNotices.post((name.isEmpty() ? "rte" : name) + ": " + message);
            }
        } catch (Throwable t) {
            // Whatever the backend sends, a bad frame is skipped, not reported.
            Log.w(TAG, "bad frame skipped", t);
        }
    }

    /** IRC quiet this long: the watchdog pings. Twitch's server pings every ~5 min, a quiet chat sends nothing. */
    private static final long WATCHDOG_QUIET_MS = 30_000;
    /** No frame this long after the watchdog's PING: the socket is dropped. */
    private static final long WATCHDOG_ANSWER_MS = 10_000;
    /** After a drop: the SDK's reconnect gets this long before the watchdog looks again. */
    private static final long WATCHDOG_REST_MS = 60_000;
    private static final long WATCHDOG_TICK_MS = 5_000;
    /** When the watchdog's PING went out (0: none waiting), and until when it rests after a drop. */
    private static long watchdogPing;
    private static long watchdogRestUntil;

    /**
     * Chat watchdog (main thread, while a Twitch activity is started). A tester's chat stopped after the VPN
     * dropped and came back, with no "reconnecting" line, until the stream was reopened; on the Pixel a cut of
     * the app's network (8 s to 50 s) never did that: OkHttp's own ping (20 s) fails a dead socket and the SDK
     * reconnects. A socket that swallows the pings (a VPN core holding the local end open) isn't caught that
     * way. So: no IRC frame for 30 s, a PING; no frame 10 s after it, the socket is failed the way OkHttp
     * fails it, and the SDK writes its "Reconnecting to chat..." and reconnects.
     */
    private static final Runnable WATCHDOG = new Runnable() {
        @Override
        public void run() {
            try {
                watchdog();
            } catch (Throwable t) {
                Log.w(TAG, "watchdog failed", t);
            }
            if (started > 0) {
                MAIN.postDelayed(this, WATCHDOG_TICK_MS);
            }
        }
    };

    private static void watchdog() {
        Object collector = ircCollector;
        long now = SystemClock.elapsedRealtime();
        long last = UsherHook.lastChatFrame;
        // Only with the theatre open: a socket the SDK closed on purpose must not be failed into a reconnect.
        if (collector == null || !PlayerHook.theatreOpen() || ChannelTracker.getCurrentChannelLogin() == null
                || now < watchdogRestUntil
                || watchdogPing > 0 && last >= watchdogPing) {
            watchdogPing = 0;
            return;
        }
        if (watchdogPing == 0) {
            if (now - last >= WATCHDOG_QUIET_MS) {
                watchdogPing = now;
                sendIrc("PING :retwitch");
            }
            return;
        }
        if (now - watchdogPing < WATCHDOG_ANSWER_MS) {
            return;
        }
        watchdogPing = 0;
        watchdogRestUntil = now + WATCHDOG_REST_MS;
        Log.w(TAG, "watchdog: no IRC frame for " + (now - last) / 1000 + " s and no answer to a PING, dropping the chat socket");
        new Thread(() -> failIrcSocket(collector), "ReTwitch-chat-watchdog").start();
    }

    /**
     * OkHttp's {@code RealWebSocket.failWebSocket(Exception, Response)} on the SDK's IRC socket (closes it, tells its
     * listener). The bridge gives the SDK's socket wrapper (31.9.0 {@code pe2}, the receiver of the PONG send); the
     * OkHttp socket is the one of its fields with such a method.
     */
    private static void failIrcSocket(Object collector) {
        try {
            Object wrapper = collector.getClass().getMethod("retwitchSocket", Object.class).invoke(null, collector);
            for (java.lang.reflect.Field f : wrapper == null ? new java.lang.reflect.Field[0] : wrapper.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                    continue;
                }
                f.setAccessible(true);
                Object socket = f.get(wrapper);
                Method fail = socket == null ? null : failMethod(socket.getClass());
                if (fail != null) {
                    fail.invoke(socket, new java.io.IOException("ReTwitch chat watchdog: no answer to PING"), null);
                    return;
                }
            }
            Log.w(TAG, "watchdog: no socket to fail on " + (wrapper == null ? null : wrapper.getClass().getName()));
        } catch (Throwable t) {
            Log.w(TAG, "watchdog: drop failed", t);
        }
    }

    /** {@code failWebSocket(Exception, Response)} of a class that also has {@code send(String): Boolean} (a websocket), or null. */
    private static Method failMethod(Class<?> type) {
        Method fail = null;
        boolean send = false;
        for (Method m : type.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getReturnType() == void.class && p.length == 2 && p[0] == Exception.class) {
                fail = m;
            } else if (m.getReturnType() == boolean.class && p.length == 1 && p[0] == String.class) {
                send = true;
            }
        }
        return send ? fail : null;
    }

    /** Sends a raw line on the chat SDK's IRC socket, if one has shown up yet. */
    static void sendIrc(String line) {
        Object collector = ircCollector;
        if (collector == null) {
            return;
        }
        try {
            collector.getClass().getMethod("retwitchSend", Object.class, String.class).invoke(null, collector, line);
        } catch (Throwable t) {
            Log.w(TAG, "sendIrc failed", t);
        }
    }

    /** Queues a channel's backlog lines (oldest first) for the next IRC frame and pings for one. */
    static void injectHistory(List<String> lines) {
        HISTORY.addAll(lines);
        sendIrc("PING :retwitch");
    }

    /** Queues {@code line} for the next IRC frame and pings for one; false if the IRC hook isn't there. */
    static boolean injectLine(String line) {
        Object collector = ircCollector;
        if (collector == null) {
            return false;
        }
        try {
            Method send = sendBridge;
            if (send == null || send.getDeclaringClass() != collector.getClass()) {
                send = collector.getClass().getMethod("retwitchSend", Object.class, String.class);
                sendBridge = send;
            }
            while (PENDING.size() >= MAX_PENDING) {
                PENDING.poll();
            }
            // One PING per frame gap: later lines ride the same PONG frame.
            boolean wasEmpty = PENDING.isEmpty();
            PENDING.add(new Object[]{SystemClock.elapsedRealtime(), line});
            if (wasEmpty) {
                send.invoke(null, collector, "PING :retwitch");
            }
            return true;
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "no IRC send bridge on " + collector.getClass().getName());
            return false;
        } catch (Throwable t) {
            // Queued already: the next frame still carries it, just not right away.
            Log.w(TAG, "IRC ping failed", t);
            return true;
        }
    }

    /**
     * Injected at the entry of the chat SDK's IRC frame collector ({@code JailChatPatch}); runs
     * on its coroutine for every frame and returns the frame with queued lines appended.
     * Resumptions of the collector pass null: returned as is. Must never throw.
     */
    public static String onIrcFrame(Object collector, String frame) {
        try {
            ircCollector = collector;
            if (frame != null) {
                UsherHook.lastChatFrame = SystemClock.elapsedRealtime();
            }
            if (frame == null || (PENDING.isEmpty() && HISTORY.isEmpty())) {
                return frame;
            }
            long now = SystemClock.elapsedRealtime();
            List<String> lines = new ArrayList<>();
            for (String h; (h = HISTORY.poll()) != null; ) {
                lines.add(h);
            }
            if (!lines.isEmpty()) {
                Log.i(TAG, lines.size() + " backlog lines merged into a frame of " + frame.length() + " chars");
            }
            for (Object[] p; (p = PENDING.poll()) != null; ) {
                if (now - (Long) p[0] <= MAX_PENDING_MS) {
                    lines.add((String) p[1]);
                }
            }
            return lines.isEmpty() ? frame : JailLine.merge(frame, lines);
        } catch (Throwable t) {
            return frame;
        }
    }

    /** Field as trimmed text; "" when missing or JSON null (optString gives "null" there). */
    private static String str(JSONObject p, String key) {
        return p.isNull(key) ? "" : p.optString(key).trim();
    }

    private static final class Listener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket ws, Response response) {
            MAIN.post(() -> {
                if (ws == socket) {
                    openedAt = SystemClock.elapsedRealtime();
                    Log.i(TAG, "#" + login + " connected");
                }
            });
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            MAIN.post(() -> {
                if (ws == socket) {
                    show(text);
                }
            });
        }

        @Override
        public void onClosing(WebSocket ws, int code, String reason) {
            ws.close(1000, null);
            MAIN.post(() -> dropped(ws, "closing " + code));
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            MAIN.post(() -> dropped(ws, "closed " + code));
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            String why = response != null ? "HTTP " + response.code() : String.valueOf(t);
            MAIN.post(() -> dropped(ws, why));
        }
    }
}
