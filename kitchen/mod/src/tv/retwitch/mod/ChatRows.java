package tv.retwitch.mod;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Method;
import java.util.WeakHashMap;

/**
 * Alternating chat row backgrounds ({@code CHAT_ALTERNATING}). The stripe is the row's foreground, a faint
 * overlay, so Twitch's own row backgrounds (mentions, first message, deleted) stay. Twitch trims old rows from
 * the top of the list, so the parity belongs to the row's item, not its position: a new item takes the opposite
 * of a neighbour already striped. The chat adapter's bind hands its position at entry ({@link #onBindStart}),
 * and {@code retwitchItemAt} (bridge on the adapter) reads the neighbours. Main thread only.
 */
final class ChatRows {

    private static final String TAG = "RTChatRows";
    private static final int ORIGINAL_TAG_KEY = 0x7e700d60;

    private static final WeakHashMap<Object, Boolean> PARITY = new WeakHashMap<>();
    private static Object adapter;
    private static int position = -1;
    private static Method itemAt;
    private static int stripe;

    private ChatRows() {
    }

    static void onBindStart(Object boundAdapter, int boundPosition) {
        adapter = boundAdapter;
        position = boundPosition;
    }

    /** The model of the row being bound, until {@link #onRowBound}. */
    static Object boundItem() {
        return adapter == null ? null : item(adapter, position);
    }

    static void onRowBound(View view) {
        Object a = adapter;
        int pos = position;
        adapter = null;
        if (view == null || a == null) {
            return;
        }
        try {
            boolean on = Toggles.on(Toggles.CHAT_ALTERNATING);
            Object original = view.getTag(ORIGINAL_TAG_KEY);
            if (!on) {
                if (original != null) {
                    view.setForeground(original instanceof Drawable ? (Drawable) original : null);
                    view.setTag(ORIGINAL_TAG_KEY, null);
                }
                return;
            }
            Object item = item(a, pos);
            if (item == null) {
                return;
            }
            Boolean odd = PARITY.get(item);
            if (odd == null) {
                Boolean before = PARITY.get(item(a, pos - 1));
                Boolean after = before != null ? null : PARITY.get(item(a, pos + 1));
                odd = before != null ? !before : after != null ? !after : (pos & 1) == 1;
                PARITY.put(item, odd);
            }
            if (original == null) {
                Drawable own = view.getForeground();
                view.setTag(ORIGINAL_TAG_KEY, own != null ? own : Boolean.FALSE);
                original = view.getTag(ORIGINAL_TAG_KEY);
            }
            // A drawable per row: a shared one would keep only the last view as its callback.
            view.setForeground(odd ? new ColorDrawable(stripe(view)) : original instanceof Drawable ? (Drawable) original : null);
        } catch (Throwable t) {
            Log.w(TAG, "onRowBound failed", t);
        }
    }

    private static Object item(Object a, int pos) {
        if (pos < 0) {
            return null;
        }
        try {
            if (itemAt == null) {
                Method m = a.getClass().getMethod("retwitchItemAt", Object.class, int.class);
                m.setAccessible(true); // declared on a package-private Twitch class
                itemAt = m;
            }
            return itemAt.invoke(null, a, pos);
        } catch (Throwable ignored) {
            return null; // past the end of the list
        }
    }

    private static int stripe(View view) {
        if (stripe == 0) {
            int bg = SettingsScreen.themeColor(view.getContext(), android.R.attr.colorBackground, 0xFF0E0E10);
            stripe = Color.luminance(bg) < 0.5f ? 0x0FFFFFFF : 0x0A000000;
        }
        return stripe;
    }
}
