package tv.retwitch.mod;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;

/**
 * "Copy and send" in the chat message long-press menu (Reply / Copy): copies the
 * message like Copy does, then sends it to chat through the chat input -- for
 * copypastas. See {@code ChatCopySendPatch}: {@link #addRow} runs in the menu's
 * constructor before the popup is measured, {@link #bind} where Twitch wires Copy
 * to the message text.
 */
public final class ChatCopySend {

    private static final String TAG = "RTCopySend";
    private static final int ROW_TAG_KEY = 0x7e700d44;

    private ChatCopySend() {
    }

    /** Adds our row right below Copy (an ActionSheetTableRow clone). Never throws. */
    public static void addRow(Object copy) {
        try {
            if (!Toggles.on(Toggles.COPY_SEND) || !(copy instanceof View)
                    || !(((View) copy).getParent() instanceof ViewGroup)) {
                return;
            }
            View copyRow = (View) copy;
            ViewGroup menu = (ViewGroup) copyRow.getParent();
            View row = (View) copyRow.getClass().getConstructor(Context.class, AttributeSet.class)
                    .newInstance(copyRow.getContext(), null);
            copyRow.getClass().getMethod("setText", String.class)
                    .invoke(row, Strings.pick("Копировать и отправить", "Copy and send"));
            Drawable icon = icon(copyRow);
            if (icon != null) {
                copyRow.getClass().getMethod("setIcon", Drawable.class).invoke(row, icon);
            }
            row.setTag(ROW_TAG_KEY, Boolean.TRUE);
            menu.addView(row, menu.indexOfChild(copyRow) + 1, new LinearLayout.LayoutParams(copyRow.getLayoutParams()));
        } catch (Throwable t) {
            Log.w(TAG, "addRow failed", t);
            CrashReporter.report(TAG, "addRow failed", t);
        }
    }

    /** Wires our row to the message {@code text} Copy uses. Never throws. */
    public static void bind(Object copy, final String text) {
        try {
            if (!(copy instanceof View) || !(((View) copy).getParent() instanceof ViewGroup) || text == null) {
                return;
            }
            final View copyRow = (View) copy;
            ViewGroup menu = (ViewGroup) copyRow.getParent();
            for (int i = 0; i < menu.getChildCount(); i++) {
                View row = menu.getChildAt(i);
                if (row.getTag(ROW_TAG_KEY) != null) {
                    row.setOnClickListener(v -> {
                        try {
                            // Twitch's own copy, which also closes the menu (the row keeps
                            // its click listener on its inner text view).
                            View copyText = textView(copyRow);
                            (copyText != null ? copyText : copyRow).performClick();
                            send(copyRow.getContext(), text);
                        } catch (Throwable t) {
                            Log.w(TAG, "copy and send failed", t);
                            CrashReporter.report(TAG, "copy and send failed", t);
                        }
                    });
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "bind failed", t);
            CrashReporter.report(TAG, "bind failed", t);
        }
    }

    /**
     * Puts {@code text} into the visible chat input and taps its send button.
     * shortcut: a draft in the input is replaced; keep it if testers mind.
     */
    private static void send(Context context, String text) {
        Activity activity = activity(context);
        View root = activity != null && activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        View field = root == null ? null : shown(root, id(context, "message_input"));
        // The chat's send button is GONE while the field is empty: look it up around the
        // field. chat_message_send first: message_input_send sits closer to the field, but in
        // the chat layout it is a dead, always-GONE leftover.
        View sendButton = near(field, id(context, "chat_message_send"));
        if (sendButton == null) {
            sendButton = near(field, id(context, "message_input_send"));
        }
        if (!(field instanceof TextView) || sendButton == null) {
            Toast.makeText(context, Strings.pick("Нет поля ввода чата - сообщение только скопировано",
                    "No chat input here: the message was only copied"), Toast.LENGTH_SHORT).show();
            return;
        }
        ((TextView) field).setText(text);
        clickWhenReady(sendButton, SEND_WAIT_TRIES);
    }

    private static final int SEND_WAIT_TRIES = 60;

    /**
     * Twitch shows and enables the send button from its presenter state, which picks
     * the new text up asynchronously; a tap before that sends nothing. Taps once it is
     * shown and enabled (polled every 50 ms, ~3 s at most, then taps anyway).
     */
    private static void clickWhenReady(final View button, final int triesLeft) {
        if ((button.isShown() && button.isEnabled()) || triesLeft <= 0) {
            Log.i(TAG, "send tap: shown=" + button.isShown() + " enabled=" + button.isEnabled()
                    + " waited=" + (SEND_WAIT_TRIES - triesLeft) + " handled=" + button.performClick());
            return;
        }
        button.postDelayed(() -> clickWhenReady(button, triesLeft - 1), 50);
    }

    /** First TextView inside {@code view}, or null. */
    private static View textView(View view) {
        if (view instanceof TextView) {
            return view;
        }
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                View found = textView(((ViewGroup) view).getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The view with {@code id} in the closest ancestor of {@code view} that has one. */
    private static View near(View view, int id) {
        for (View v = view; v != null && id != 0; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            View found = v.findViewById(id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static int id(Context context, String name) {
        return context.getResources().getIdentifier(name, "id", context.getPackageName());
    }

    /** First shown view with {@code id} under {@code view}. */
    private static View shown(View view, int id) {
        if (id == 0) {
            return null;
        }
        if (view.getId() == id && view.isShown()) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = shown(group.getChildAt(i), id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** A fresh copy of the row's icon (its Drawable field), or null. */
    private static Drawable icon(View row) throws IllegalAccessException {
        for (Field f : row.getClass().getDeclaredFields()) {
            if (Drawable.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                Drawable d = (Drawable) f.get(row);
                if (d != null && d.getConstantState() != null) {
                    return d.getConstantState().newDrawable().mutate();
                }
            }
        }
        return null;
    }
}
