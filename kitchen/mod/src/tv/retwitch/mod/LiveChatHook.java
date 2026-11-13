package tv.retwitch.mod;

import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/**
 * Bridges for two live-chat behaviour fixes, both toggleable in the ReTwitch
 * "Chat" menu. Kept deliberately free of Twitch-internal (obfuscated) types:
 * the injected smali only ever hands us a plain {@code boolean} or a
 * {@link View} (the chat {@code RecyclerView}, passed as its {@code View}
 * supertype), so these stay stable across app updates.
 *
 * <p>See {@code AutoscrollPatch} and {@code AnimatorPatch}.
 */
public final class LiveChatHook {

    private static final String TAG = "RTlivechat";

    private LiveChatHook() {
    }

    /**
     * Per-adapter "user is holding the list scrolled up" latch. Keyed weakly on
     * the chat adapter so it doesn't leak across chat sessions / channels.
     */
    private static final WeakHashMap<Object, Boolean> SCROLLED_UP_LATCH = new WeakHashMap<>();

    /**
     * Autoscroll fix. Twitch's chat scroll listener decides, on every
     * onScrolled, whether the list should be treated as "scrolled up" (which
     * shows the "more messages below" pill and pauses autoscroll-to-end). Its
     * original rule -- {@code isScrolling && !atBottom} -- clears the
     * scrolled-up state the instant the list goes idle, so a new message
     * arriving while you're reading history re-enables autoscroll and snaps you
     * back down.
     *
     * <p>We replace that decision with a latch that only engages on a real user
     * drag and only releases when the list is genuinely back at the bottom:
     * <ul>
     *   <li>at bottom -> not scrolled up (clear latch);
     *   <li>dragging/settling off-bottom -> scrolled up (set latch);
     *   <li>idle off-bottom -> keep the previous decision (don't auto-clear).
     * </ul>
     * Crucially, the latch starts cleared, so on chat open (before the initial
     * autoscroll settles at the bottom) it reports "not scrolled up" and lets
     * autoscroll run -- avoiding the deadlock where a paused autoscroll can
     * never reach the bottom to clear itself.
     *
     * <p>When the toggle is off we reproduce Twitch's original rule exactly.
     *
     * @param adapter     the chat list adapter (latch key; may be null)
     * @param isScrolling whether a drag/settle is currently in flight
     * @param lastVisible last visible item position
     * @param lastItem    last item index in the adapter
     * @return whether the list should be treated as scrolled up
     */
    public static boolean scrolledUp(Object adapter, boolean isScrolling, int lastVisible, int lastItem) {
        boolean atBottom = lastVisible >= lastItem;
        try {
            // Latched even with the toggle off: holdAutoscroll reads it for VOD chat.
            boolean latched = !atBottom && (isScrolling || getLatch(adapter));
            setLatch(adapter, latched);
            return ChatSettings.isStickyScrollEnabled() ? latched : isScrolling && !atBottom;
        } catch (Throwable t) {
            Log.w(TAG, "scrolledUp failed", t);
            CrashReporter.report(TAG, "scrolledUp failed", t);
            return isScrolling && !atBottom;
        }
    }

    /** Adapters of VOD chat lists (the VOD presenter binds them; live chat never does). */
    private static final WeakHashMap<Object, Boolean> VOD_ADAPTERS = new WeakHashMap<>();
    private static Field listView;

    /** Injected right after the VOD chat presenter binds its adapter to the chat list. */
    public static synchronized void markVodAdapter(Object adapter) {
        if (adapter != null) {
            VOD_ADAPTERS.put(adapter, Boolean.TRUE);
        }
    }

    /**
     * Gate of the chat list's scroll to the newest message (new messages, list resized):
     * {@code held} is Twitch's "paused" flag, which only live chat sets from the scrolled-up
     * state. With {@link Toggles#VOD_STICKY_SCROLL} a VOD list held up by the latch stays put too.
     *
     * @param chatList the chat list delegate whose flag was read
     */
    public static boolean holdAutoscroll(boolean held, Object chatList) {
        if (held || chatList == null) {
            return held;
        }
        try {
            if (!Toggles.on(Toggles.VOD_STICKY_SCROLL)) {
                return false;
            }
            if (listView == null) {
                for (Field f : chatList.getClass().getDeclaredFields()) {
                    if (f.getType().getName().equals("androidx.recyclerview.widget.RecyclerView")) {
                        f.setAccessible(true);
                        listView = f;
                    }
                }
            }
            Object list = listView.get(chatList);
            Object adapter = list.getClass().getMethod("getAdapter").invoke(list);
            return isVod(adapter) && getLatch(adapter);
        } catch (Throwable t) {
            Log.w(TAG, "holdAutoscroll failed", t);
            CrashReporter.report(TAG, "holdAutoscroll failed", t);
            return false;
        }
    }

    private static synchronized boolean isVod(Object adapter) {
        return adapter != null && VOD_ADAPTERS.containsKey(adapter);
    }

    private static synchronized void setLatch(Object key, boolean value) {
        if (key != null) {
            SCROLLED_UP_LATCH.put(key, value);
        }
    }

    private static synchronized boolean getLatch(Object key) {
        if (key == null) {
            return false;
        }
        Boolean value = SCROLLED_UP_LATCH.get(key);
        return value != null && value;
    }

    /**
     * Real-time chat ({@code ChatBatchingPatch}): Twitch's "queue this message for the next
     * once-a-second batch" decision. False sends the message on at once, as on the web.
     */
    public static boolean batchChatMessages(boolean batch) {
        return batch && !Toggles.on(Toggles.CHAT_REALTIME);
    }

    /**
     * Smooth message appearance. Twitch calls {@code setItemAnimator(null)} on
     * the chat {@code RecyclerView}, so inserted messages just pop in (and on
     * fast chats a burst of them appears as one batch). When "smooth messages"
     * is on we skip that null-out and keep the RecyclerView's built-in
     * {@code DefaultItemAnimator}, restoring the per-item add animation older
     * builds had. When off we replicate the original null-out.
     *
     * <p>Done reflectively so we never reference the obfuscated
     * {@code ItemAnimator} subtype (whose R8 name changes every release).
     *
     * @param recyclerView the chat {@code RecyclerView} (typed as {@link View})
     */
    public static void applyChatAnimator(View recyclerView) {
        if (recyclerView == null) {
            return;
        }
        try {
            if (ChatSettings.isSmoothMessagesEnabled()) {
                if (Toggles.on(Toggles.CHAT_REALTIME)) {
                    instantMoves(recyclerView);
                }
                return; // keep the default (smooth) animator
            }
            setItemAnimatorNull(recyclerView);
        } catch (Throwable t) {
            // On any failure, fall back to Twitch's original behaviour.
            Log.w(TAG, "applyChatAnimator failed, restoring default null-out", t);
            CrashReporter.report(TAG, "applyChatAnimator failed, restoring default null-out", t);
            try {
                setItemAnimatorNull(recyclerView);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Without batches a fast chat outruns the 250 ms slide of the older rows: the slides pile up and rows
     * land on top of each other. New rows keep their fade, the others jump. The durations are public fields
     * of the obfuscated ItemAnimator, 250 ms are move and change (add and remove are 120).
     */
    private static void instantMoves(View recyclerView) throws Exception {
        Object animator = recyclerView.getClass().getMethod("getItemAnimator").invoke(recyclerView);
        for (java.lang.reflect.Field field : animator.getClass().getFields()) {
            if (field.getType() == long.class && field.getLong(animator) == 250) {
                field.setLong(animator, 0);
            }
        }
    }

    private static void setItemAnimatorNull(View recyclerView) throws Exception {
        for (Method m : recyclerView.getClass().getMethods()) {
            if (m.getName().equals("setItemAnimator") && m.getParameterTypes().length == 1) {
                m.invoke(recyclerView, new Object[]{null});
                return;
            }
        }
    }
}
