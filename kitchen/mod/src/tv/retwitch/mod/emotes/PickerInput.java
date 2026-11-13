package tv.retwitch.mod.emotes;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.Editable;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;

import tv.retwitch.mod.CrashReporter;
import tv.retwitch.mod.Toggles;

/**
 * Input around Twitch's emote picker ({@code emote_picker.xml}), attached from
 * {@code PickerHook.onContainerCreated} when the picker view is built (ReVive
 * PickerSend / PickerBackspace):
 * <ul>
 * <li>Outside taps: when the picker sits in a Material bottom sheet window, that
 *     window covers the screen with a {@code touch_outside} layer, so a tap on Send
 *     or on the chat input behind it only closed the sheet. A watch-only touch
 *     listener on that layer lets the sheet close as before, then presses Send or
 *     opens the keyboard. A picker shown inline (no such layer in its window) is
 *     left alone.</li>
 * <li>Backspace ({@code delete_emote_button}): deletes the whole last word/emote or
 *     one character instead of Twitch's own behaviour. A touch listener, not a click
 *     listener: Twitch sets its click listener after the view is built.</li>
 * </ul>
 * Everything here is called from Twitch's view code: never throws.
 */
public final class PickerInput {

    private static final String TAG = "RTPicker";
    private static final long TAP_MAX_MS = 700;

    /** The {@code touch_outside} layer already watched (one per sheet window). */
    private static WeakReference<View> watched = new WeakReference<>(null);

    private PickerInput() {
    }

    /** @param root the freshly built view of any emote grid; only the picker (has {@code emote_picker}) is touched. */
    public static void attach(final View root) {
        try {
            Context context = root.getContext();
            int content = id(context, "emote_picker");
            if (content == 0 || root.findViewById(content) == null) {
                return;
            }
            int delete = id(context, "delete_emote_button");
            View deleteButton = delete == 0 ? null : root.findViewById(delete);
            if (deleteButton != null) {
                deleteButton.setOnTouchListener(new BackspaceTouch());
            }
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    watchOutside(v);
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                }
            });
            if (root.isAttachedToWindow()) {
                watchOutside(root);
            }
        } catch (Throwable t) {
            Log.w(TAG, "picker input attach failed", t);
            CrashReporter.report(TAG, "picker input attach failed", t);
        }
    }

    /** Injected before the picker adds its sticky section-header decoration (PickerExtrasPatch). */
    public static boolean stickyHeaders() {
        return !Toggles.on(Toggles.PICKER_NO_STICKY_HEADERS);
    }

    // ---- backspace ----

    private static final class BackspaceTouch implements View.OnTouchListener {
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            try {
                int mode = EmoteSettings.getPickerBackspace();
                if (mode == EmoteSettings.BACKSPACE_TWITCH) {
                    return false;
                }
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        break;
                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        if (e.getX() >= 0 && e.getY() >= 0 && e.getX() <= v.getWidth() && e.getY() <= v.getHeight()) {
                            backspace(v, mode == EmoteSettings.BACKSPACE_WORD);
                        }
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        break;
                    default:
                        break;
                }
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "picker backspace failed", t);
                CrashReporter.report(TAG, "picker backspace failed", t);
                return false;
            }
        }
    }

    private static void backspace(View button, boolean word) {
        TextView input = chatInput(button);
        Editable text = input == null ? null : input.getEditableText();
        if (text == null) {
            return;
        }
        int[] range = deleteRange(text, input.getSelectionStart(), input.getSelectionEnd(), word);
        if (range != null) {
            text.delete(range[0], range[1]);
        }
    }

    /**
     * {@code [from, to)} a backspace deletes: the selection; else, before the cursor, the
     * last word or emote with the spaces after it ({@code word}) or one character (a whole
     * emoji, not half of one). Null when there is nothing to delete. A cursor of -1 means
     * the end of the text.
     */
    static int[] deleteRange(CharSequence text, int selStart, int selEnd, boolean word) {
        int length = text.length();
        int a = selStart < 0 || selStart > length ? length : selStart;
        int b = selEnd < 0 || selEnd > length ? a : selEnd;
        if (a != b) {
            return new int[]{Math.min(a, b), Math.max(a, b)};
        }
        int end = b;
        int start = end;
        if (word) {
            while (start > 0 && Character.isWhitespace(text.charAt(start - 1))) {
                start--;
            }
            while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) {
                start--;
            }
        } else if (end > 0) {
            BreakIterator characters = BreakIterator.getCharacterInstance();
            characters.setText(text.toString());
            int previous = characters.preceding(end);
            start = previous == BreakIterator.DONE ? end - 1 : previous;
        }
        return start < end ? new int[]{start, end} : null;
    }

    // ---- taps outside the picker sheet ----

    private static void watchOutside(View pickerRoot) {
        try {
            if (!Toggles.on(Toggles.PICKER_OUTSIDE_TAP)) {
                return;
            }
            int outsideId = id(pickerRoot.getContext(), "touch_outside");
            View outside = outsideId == 0 ? null : pickerRoot.getRootView().findViewById(outsideId);
            if (outside == null) {
                Log.i(TAG, "picker is not in a bottom sheet window: outside taps left to Twitch");
                return;
            }
            if (watched.get() == outside) {
                return;
            }
            watched = new WeakReference<>(outside);
            outside.setOnTouchListener(new OutsideTap(pickerRoot));
            Log.i(TAG, "watching taps outside the picker sheet");
        } catch (Throwable t) {
            Log.w(TAG, "picker outside watch failed", t);
            CrashReporter.report(TAG, "picker outside watch failed", t);
        }
    }

    /** Only watches (returns false), so the sheet still closes as before. */
    private static final class OutsideTap implements View.OnTouchListener {
        private final WeakReference<View> picker;
        private float downX;
        private float downY;
        private long downAt;

        OutsideTap(View pickerRoot) {
            picker = new WeakReference<>(pickerRoot);
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            try {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downX = e.getRawX();
                    downY = e.getRawY();
                    downAt = SystemClock.uptimeMillis();
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    int slop = ViewConfiguration.get(v.getContext()).getScaledTouchSlop() * 2;
                    View pickerRoot = picker.get();
                    if (pickerRoot != null && Toggles.on(Toggles.PICKER_OUTSIDE_TAP)
                            && Math.abs(e.getRawX() - downX) < slop && Math.abs(e.getRawY() - downY) < slop
                            && SystemClock.uptimeMillis() - downAt < TAP_MAX_MS) {
                        // Where the finger went down: the chat bar can move as the sheet closes.
                        tapBehind(pickerRoot.getContext(), (int) downX, (int) downY);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "picker outside tap failed", t);
                CrashReporter.report(TAG, "picker outside tap failed", t);
            }
            return false;
        }
    }

    private static void tapBehind(Context context, int x, int y) {
        Activity activity = activity(context);
        View root = activity == null || activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (root == null) {
            return;
        }
        int grow = Math.round(10 * context.getResources().getDisplayMetrics().density);
        // chat_message_send first: message_input_send is a dead, always-GONE leftover in the chat layout.
        for (String name : new String[]{"chat_message_send", "message_input_send"}) {
            for (final View send : shown(root, id(context, name))) {
                if (hit(send, x, y, grow)) {
                    // After the sheet has handled its own close.
                    send.post(new Runnable() {
                        @Override
                        public void run() {
                            if (send.isShown() && send.isEnabled()) {
                                send.performClick();
                                Log.i(TAG, "pressed Send from the emote picker");
                            }
                        }
                    });
                    return;
                }
            }
        }
        for (View view : shown(root, id(context, "message_input"))) {
            if (view instanceof TextView && hit(view, x, y, 0)) {
                final TextView input = (TextView) view;
                // The sheet's window hands focus back as it closes; ask for the keyboard after that.
                Runnable open = new Runnable() {
                    @Override
                    public void run() {
                        if (input.isFocused() && input.isShown()) {
                            return;
                        }
                        input.requestFocus();
                        CharSequence text = input.getText();
                        if (input.getEditableText() != null && text != null) {
                            android.text.Selection.setSelection(input.getEditableText(), text.length());
                        }
                        InputMethodManager imm = (InputMethodManager) input.getContext()
                                .getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) {
                            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
                        }
                    }
                };
                input.postDelayed(open, 150);
                input.postDelayed(open, 450);
                return;
            }
        }
    }

    /** The view's box on screen, grown by {@code grow} px on every side for fingers that land just off it. */
    private static boolean hit(View v, int x, int y, int grow) {
        if (!v.isShown() || !v.isEnabled()) {
            return false;
        }
        int[] location = new int[2];
        v.getLocationOnScreen(location);
        return new Rect(location[0] - grow, location[1] - grow, location[0] + v.getWidth() + grow,
                location[1] + v.getHeight() + grow).contains(x, y);
    }

    /** The chat input: in the picker's own window, else the activity's. */
    private static TextView chatInput(View inPicker) {
        Context context = inPicker.getContext();
        int inputId = id(context, "message_input");
        Activity activity = activity(context);
        View[] roots = {inPicker.getRootView(),
                activity == null || activity.getWindow() == null ? null : activity.getWindow().getDecorView()};
        for (View root : roots) {
            for (View view : root == null ? new ArrayList<View>() : shown(root, inputId)) {
                if (view instanceof TextView) {
                    return (TextView) view;
                }
            }
        }
        return null;
    }

    /** Every shown view with {@code id} under {@code view}. */
    private static List<View> shown(View view, int id) {
        List<View> out = new ArrayList<>();
        collectShown(view, id, out);
        return out;
    }

    private static void collectShown(View view, int id, List<View> out) {
        if (id == 0 || view.getVisibility() != View.VISIBLE) {
            return;
        }
        if (view.getId() == id && view.isShown()) {
            out.add(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectShown(group.getChildAt(i), id, out);
            }
        }
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

    private static int id(Context context, String name) {
        return context.getResources().getIdentifier(name, "id", context.getPackageName());
    }
}
