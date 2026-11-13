package tv.retwitch.mod;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Chat off ({@link Toggles#CHAT_OFF}), for phones that can't keep up with a busy chat: the chat stays connected
 * (points, mentions and mod logs work), but its list is neither laid out nor drawn, so no emote is decoded, until
 * the viewer asks for it for the rest of the app run: "Show" in the chat's place or the chat button on the player
 * strip. Live theatre: {@code chat_view_container} in {@code chat_wrapper}, and in landscape the column's guideline
 * goes to the edge, as in Twitch's own No chat mode ({@link TheatreDividerHook#constraintSetPercent}). VOD
 * ({@code player_view}): {@code bottom_container} under the player in portrait, {@code landscape_chat_container}
 * beside it. Vertical view isn't covered.
 */
final class ChatOff {

    private static final String TAG = "RTChatOff";
    /** On a view ChatOff hid: its visibility before. */
    private static final int HIDDEN_TAG_KEY = 0x7e700d6f;
    private static final String PLACEHOLDER = "retwitch-chat-off";

    private static volatile boolean shown;

    private ChatOff() {
    }

    static boolean hidden() {
        return !shown && Toggles.on(Toggles.CHAT_OFF);
    }

    /** From each UiHider pass, per theatre ({@code draggable_container}). */
    static void apply(View theatre) {
        try {
            boolean hide = hidden();
            boolean land = theatre.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
            View live = find(theatre, "chat_view_container");
            if (live != null) {
                setHidden(live, hide);
                placeholder(live.getParent(), hide && !land);
                View guideline = live.getParent() instanceof View ? find((View) live.getParent().getParent(), "chat_guideline") : null;
                if (guideline != null && land) {
                    edgeGuideline(guideline, hide);
                }
            }
            View below = find(theatre, "bottom_container");
            if (below instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) below;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    if (!PLACEHOLDER.equals(child.getTag())) {
                        setHidden(child, hide);
                    }
                }
                placeholder(group, hide && !land);
            }
            View column = find(theatre, "landscape_chat_container");
            if (column != null) {
                setHidden(column, hide); // the player beside it takes the width: toLeftOf, alignWithParentIfMissing
            }
        } catch (Throwable t) {
            Log.w(TAG, "apply failed", t);
            CrashReporter.report(TAG, "apply failed", t);
        }
    }

    /** Bit 512 of the player strip: shows or hides the chat for the rest of the app run. */
    static View createButton(final Context context) {
        final ImageView button = new ImageView(context);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(Strings.pick("Чат", "Chat"));
        button.setClickable(true);
        button.setFocusable(true);
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)) {
            button.setBackgroundResource(value.resourceId);
        }
        setIcon(button);
        button.setOnClickListener(v -> {
            toggle(v);
            setIcon(button);
        });
        button.setLayoutParams(new ViewGroup.LayoutParams(dp(context, 48), dp(context, 48)));
        return button;
    }

    private static void toggle(View from) {
        shown = !shown;
        View root = from.getRootView();
        // The next UiHider pass applies it; ask for one now
        root.requestLayout();
        root.post(root::requestLayout);
    }

    private static void setIcon(ImageView button) {
        int id = UiHider.res(button.getContext(), hidden() ? "ic_chat_show" : "ic_chat_hide", "drawable");
        if (id != 0) {
            Drawable icon = button.getContext().getDrawable(id).mutate();
            icon.setTint(Color.WHITE);
            button.setImageDrawable(icon);
        }
    }

    private static void setHidden(View view, boolean hide) {
        Object before = view.getTag(HIDDEN_TAG_KEY);
        if (hide) {
            if (before == null) {
                view.setTag(HIDDEN_TAG_KEY, view.getVisibility());
            }
            if (view.getVisibility() != View.GONE) {
                view.setVisibility(View.GONE);
            }
        } else if (before instanceof Integer) {
            view.setTag(HIDDEN_TAG_KEY, null);
            view.setVisibility((Integer) before);
        }
    }

    /** "Chat is off · Show" in the chat's place; a tap shows the chat for the rest of the app run. */
    private static void placeholder(ViewParent parent, boolean show) {
        if (!(parent instanceof FrameLayout)) {
            return;
        }
        FrameLayout frame = (FrameLayout) parent;
        View existing = frame.findViewWithTag(PLACEHOLDER);
        if (!show) {
            if (existing != null) {
                frame.removeView(existing);
            }
            return;
        }
        if (existing != null) {
            return;
        }
        Context context = frame.getContext();
        TextView text = new TextView(context);
        text.setTag(PLACEHOLDER);
        text.setText(Strings.pick("Чат выключен · Показать", "Chat is off · Show"));
        text.setTextColor(0xFFBF94FF);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setGravity(Gravity.CENTER);
        int pad = dp(context, 16);
        text.setPadding(pad, pad, pad, pad);
        text.setOnClickListener(ChatOff::toggle);
        frame.addView(text, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    /**
     * Landscape live theatre: the guideline at the edge while the chat is off, so the video takes the width; back to
     * the divider's width or Twitch's docked 279dp when it shows (Twitch's next state change sets its own anyway).
     */
    private static void edgeGuideline(View guideline, boolean hide) {
        try {
            boolean ours = guideline.getTag(HIDDEN_TAG_KEY) != null;
            if (hide) {
                guideline.setTag(HIDDEN_TAG_KEY, Boolean.TRUE);
                setGuideline(guideline, 1f, -1);
            } else if (ours) {
                guideline.setTag(HIDDEN_TAG_KEY, null);
                setGuideline(guideline, -1f, TheatreDividerHook.guideEnd(dp(guideline.getContext(), 279)));
            }
        } catch (Throwable t) {
            Log.w(TAG, "guideline", t);
        }
    }

    private static void setGuideline(View guideline, float percent, int end) throws ReflectiveOperationException {
        guideline.getClass().getMethod("setGuidelinePercent", float.class).invoke(guideline, percent);
        guideline.getClass().getMethod("setGuidelineEnd", int.class).invoke(guideline, end);
    }

    private static View find(View root, String name) {
        int id = root == null ? 0 : UiHider.id(root.getContext(), name);
        return id != 0 ? root.findViewById(id) : null;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
