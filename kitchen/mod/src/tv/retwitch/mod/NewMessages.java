package tv.retwitch.mod;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * "New messages: N" on Twitch's "More messages below" pill while the chat is scrolled up ({@code
 * NEW_MESSAGES_COUNT}, as in reFrosty). The pill ({@code chat_more_messages_below_text}) is a plain
 * TextView Twitch only shows and hides, with its text from the layout. RecyclerView's classes are renamed
 * by R8, so rows can't be counted at the adapter: the open channel's PRIVMSG lines are, from the IRC hook
 * ({@link ModLogs#onIrcEvent}). Filtered messages and the channel's chat delay make it approximate.
 */
final class NewMessages {

    private static final int ORIGINAL_TAG_KEY = 0x7e700e01;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicInteger COUNT = new AtomicInteger();

    private static volatile WeakReference<TextView> pill = new WeakReference<>(null);
    /** Pill on screen with the toggle on: the IRC hook counts only then. */
    private static volatile boolean counting;
    private static volatile boolean updatePosted;

    private NewMessages() {
    }

    static boolean counting() {
        return counting;
    }

    /** From the UI pass: the shown pill starts counting, a hidden one resets it. */
    static void attach(Activity activity, View decor) {
        boolean on = Toggles.on(Toggles.NEW_MESSAGES_COUNT);
        TextView shown = null;
        for (View view : UiHider.findAll(activity, decor, "chat_more_messages_below_text")) {
            if (!(view instanceof TextView)) {
                continue;
            }
            TextView label = (TextView) view;
            if (label.getTag(ORIGINAL_TAG_KEY) == null) {
                label.setTag(ORIGINAL_TAG_KEY, label.getText());
            }
            if (on && label.getVisibility() == View.VISIBLE) {
                shown = label;
            } else {
                restore(label);
            }
        }
        if (shown != pill.get()) {
            COUNT.set(0);
            pill = new WeakReference<>(shown);
            if (shown != null) {
                restore(shown);
            }
        }
        counting = shown != null;
    }

    /** From the IRC hook: one PRIVMSG; the open channel's are counted while the pill is up. */
    static void onPrivmsg(Map<?, ?> tags) {
        String channel = ChannelTracker.getCurrentChannelId();
        if (!counting || tags == null || channel == null || !channel.equals(tags.get("room-id"))) {
            return;
        }
        COUNT.incrementAndGet();
        if (!updatePosted) {
            updatePosted = true;
            // At most a few redraws a second on a fast chat.
            MAIN.postDelayed(NewMessages::update, 250);
        }
    }

    private static void update() {
        updatePosted = false;
        TextView label = pill.get();
        int count = COUNT.get();
        if (label == null || !counting || label.getVisibility() != View.VISIBLE || count == 0) {
            return;
        }
        label.setText(Strings.pickf("Новых сообщений: %d", "New messages: %d", count));
    }

    private static void restore(TextView label) {
        Object original = label.getTag(ORIGINAL_TAG_KEY);
        if (original instanceof CharSequence && !original.equals(label.getText())) {
            label.setText((CharSequence) original);
        }
    }
}
