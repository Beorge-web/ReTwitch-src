package tv.retwitch.mod;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/**
 * Our own lines in the open chat, sent down Twitch's IRC NOTICE path: a
 * {@code RegularChannelNotice(noticeId, StringResource)} in a
 * {@code ChannelNoticeEvent(channelTuid, notice)} pushed into the chat
 * controller's notice stream, which Twitch turns into a {@code SystemMessage}
 * row. The push is the synthetic {@code retwitchPostNotice} bridge that
 * {@code ChatChannelEventsPatch} adds to the controller class; the controller
 * itself is captured from the channel event hook. Never throws.
 */
public final class ChatNotices {

    private static final String TAG = "RTChatNotices";
    private static final String BRIDGE = "retwitchPostNotice";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile WeakReference<Object> controller = new WeakReference<>(null);
    private static volatile Method bridge;

    private ChatNotices() {
    }

    /** From every channel event: the chat controller that owns the notice stream. */
    static void noteController(Object receiver) {
        if (receiver != null && controller.get() != receiver) {
            controller = new WeakReference<>(receiver);
            Log.i(TAG, "chat controller " + receiver.getClass().getName());
        }
    }

    /** Adds {@code text} as a line to the current channel's chat (on the main thread). */
    public static void post(final String text) {
        MAIN.post(() -> {
            try {
                Object receiver = controller.get();
                String channelId = ChannelTracker.getCurrentChannelId();
                if (receiver == null || channelId == null) {
                    Log.w(TAG, "no chat yet, dropped: " + text);
                    return;
                }
                Method m = bridge;
                if (m == null || m.getDeclaringClass() != receiver.getClass()) {
                    m = receiver.getClass().getMethod(BRIDGE, Object.class, Object.class, String.class, Object.class);
                    bridge = m;
                }
                Object tuid = Class.forName("tv.twitch.android.models.Tuid")
                        .getConstructor(String.class).newInstance(channelId);
                // SimpleString is package-private in 31.8.0: its public constructor needs setAccessible.
                java.lang.reflect.Constructor<?> simple = Class.forName("tv.twitch.android.core.strings.SimpleString")
                        .getConstructor(String.class);
                simple.setAccessible(true);
                Object message = simple.newInstance(text);
                m.invoke(null, receiver, tuid, "retwitch", message);
                Log.i(TAG, "posted to " + channelId + ": " + text);
            } catch (Throwable t) {
                Log.w(TAG, "post failed", t);
                CrashReporter.report(TAG, "post failed", t);
            }
        });
    }
}
