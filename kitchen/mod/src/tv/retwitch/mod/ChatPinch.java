package tv.retwitch.mod;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Toast;

import tv.retwitch.mod.emotes.EmoteChatHook;

/**
 * Chat text size with two fingers right in the chat ({@link Toggles#CHAT_PINCH_TEXT}, feedback idea #54): the same
 * steps as the setting ({@link ChatSettings#FONT_SCALES}), the size following the finger spread. Fed from
 * {@link PlayerGestures}' window proxy, which sees touches before the chat rows do: rows consume the first finger,
 * so the list itself never sees a pinch. Once both fingers are on the chat the rest of the gesture is ours and the
 * rows get a cancel (no long-press menu). Visible rows are rescaled at once; their badges keep the old size until
 * they are bound again.
 */
final class ChatPinch {

    private static boolean active;
    private static double startSpan;
    private static int startScale;
    private static Toast toast;

    private ChatPinch() {
    }

    /** @return true when the event is part of a chat pinch. */
    static boolean onTouch(Activity activity, Window.Callback original, MotionEvent ev) {
        int action = ev.getActionMasked();
        if (!active) {
            if (action != MotionEvent.ACTION_POINTER_DOWN || ev.getPointerCount() != 2
                    || !Toggles.on(Toggles.CHAT_PINCH_TEXT) || !onChat(activity, ev)) {
                return false;
            }
            active = true;
            startSpan = span(ev);
            startScale = ChatSettings.getFontScale();
            MotionEvent cancel = MotionEvent.obtain(ev);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            original.dispatchTouchEvent(cancel);
            cancel.recycle();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && ev.getPointerCount() >= 2) {
            int scale = nearest((int) Math.round(startScale * span(ev) / Math.max(1, startSpan)));
            if (scale != ChatSettings.getFontScale()) {
                ChatSettings.setFontScale(activity, scale);
                rescale(activity);
                show(activity, scale);
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            active = false;
        }
        return true;
    }

    /** Both fingers on a shown chat list (window coordinates, as the window's callback gets them). */
    private static boolean onChat(Activity activity, MotionEvent ev) {
        int[] at = new int[2];
        for (View list : UiHider.findAll(activity, activity.getWindow().getDecorView(), "chat_message_recycler_view")) {
            if (!list.isShown()) {
                continue;
            }
            list.getLocationInWindow(at);
            boolean both = true;
            for (int i = 0; i < 2; i++) {
                float x = ev.getX(i) - at[0];
                float y = ev.getY(i) - at[1];
                both &= x >= 0 && y >= 0 && x < list.getWidth() && y < list.getHeight();
            }
            if (both) {
                return true;
            }
        }
        return false;
    }

    private static double span(MotionEvent ev) {
        return Math.hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1));
    }

    /** The setting's step closest to {@code scale}. */
    private static int nearest(int scale) {
        int[] scales = ChatSettings.FONT_SCALES;
        int best = 0;
        for (int i = 1; i < scales.length; i++) {
            if (Math.abs(scales[i] - scale) < Math.abs(scales[best] - scale)) {
                best = i;
            }
        }
        return scales[best];
    }

    private static void rescale(Activity activity) {
        for (View list : UiHider.findAll(activity, activity.getWindow().getDecorView(), "chat_message_recycler_view")) {
            if (list instanceof ViewGroup) {
                ViewGroup rows = (ViewGroup) list;
                for (int i = 0; i < rows.getChildCount(); i++) {
                    EmoteChatHook.scaleRow(rows.getChildAt(i));
                }
                list.requestLayout();
            }
        }
    }

    private static void show(Activity activity, int scale) {
        if (toast != null) {
            toast.cancel();
        }
        toast = Toast.makeText(activity, Strings.pickf("Текст чата: %d%%", "Chat text: %d%%", scale), Toast.LENGTH_SHORT);
        toast.show();
    }
}
