package tv.retwitch.mod;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Adds a draggable vertical handle on the player/chat boundary in Twitch's
 * landscape "chat next to player" (side-by-side) theatre mode, letting the
 * user resize the split by dragging: towards the chat makes the chat narrower
 * and the player wider, and vice versa.
 *
 * <p><b>Why a view-tree hook, not a bytecode fingerprint.</b> The three
 * landscape chat modes (no chat / overlay / side-by-side) are native Twitch
 * behaviour. Rather than splice into the obfuscated controller that lays the
 * theatre out, we install an {@link Application.ActivityLifecycleCallbacks}
 * (exactly like {@link DisplayCutoutHook}) and watch the view tree via an
 * {@link ViewTreeObserver.OnGlobalLayoutListener} on the activity's content
 * root. Everything is keyed off resource <i>entry names</i> (which survive
 * R8) and public Android SDK APIs -- nothing references Twitch's renamed
 * classes -- so it keeps working across app updates.
 *
 * <p><b>Anchors</b> (verified on-device by dumping the settled runtime
 * {@code ConstraintLayout.LayoutParams} on a 2400x1080 landscape display):
 * <ul>
 * <li>{@code chat_guideline} -- a <i>vertical</i> ConstraintLayout guideline
 *     at {@code guidePercent = 0.7} (i.e. 70% across = a 30% chat column on
 *     the right). This is the single lever we move.</li>
 * <li>{@code player_pane}, {@code chat_wrapper}, <b>and every player overlay</b>
 *     ({@code player_overlay_container}, ad/celebration/one-tap containers,
 *     ...) all constrain their inner edge to {@code chat_guideline}. Moving
 *     the guideline therefore reflows the player, the chat <i>and</i> the
 *     tap-to-show controls overlay together -- setting a fixed width on
 *     {@code player_pane} alone left the guideline-anchored overlays behind.</li>
 * <li>The draggable handle is added to the nearest {@link FrameLayout}
 *     ancestor (spanning the whole theatre) and positioned absolutely on the
 *     guideline, so we never fight the ConstraintLayout's solver.</li>
 * </ul>
 *
 * <p>Per the mod's fail-soft rule this runs inside Twitch's own layout pass,
 * so every entry point and callback is wrapped in {@code catch (Throwable)};
 * a one-shot {@link Toast} surfaces a hard failure without spamming the
 * per-frame layout loop.
 */
public final class TheatreDividerHook {

    private static final String TAG = "RTDivider";

    /**
     * Resource entry name of the chat column in the <b>live stream</b> theatre
     * (a ConstraintLayout whose split is driven by {@link #GUIDELINE_ID_NAME}).
     */
    private static final String CHAT_ID_NAME = "chat_wrapper";

    /**
     * Resource entry name of the vertical guideline that splits player from
     * chat in the stream theatre. Both columns and every player overlay anchor
     * to it, so moving its {@code guidePercent} is the one lever that resizes
     * the whole split cleanly.
     */
    private static final String GUIDELINE_ID_NAME = "chat_guideline";

    /**
     * Resource entry name of the chat column in the <b>VOD</b> player, which
     * uses a different layout family: a {@code RelativeLayout}
     * ({@code player_wrapper}) holding the chat column on one side and the
     * player/overlay on the other. Here the lever is the chat container's own
     * width; the player pane and overlay follow via their {@code toLeftOf} rules.
     * Never give them a fixed width: during a PbyP ad Twitch re-anchors
     * {@code player_pane} elsewhere, and a fixed width outlived the chat being
     * hidden (video pushed right, black strip on the left, stretch broken).
     */
    private static final String VOD_CHAT_ID_NAME = "landscape_chat_container";

    /**
     * Resource entry name of the tap-to-show player controls overlay (the root
     * {@code ConstraintLayout} of {@code player_control_overlay.xml}, holding
     * the play/pause/seek/settings controls). Twitch toggles its visibility
     * (and fades its alpha) as the controls show/hide, so we mirror it onto the
     * drag handle -- the handle only sits over the chat edge while the controls
     * are on screen, and disappears with them. Survives R8 like the other
     * entry-name anchors above.
     */
    private static final String PLAYER_OVERLAY_ID_NAME = "player_overlay";

    private static final String HANDLE_TAG = "retwitch_theatre_divider";

    // Arbitrary, unique-to-us view tag keys.
    private static final int LISTENER_TAG_KEY = 0x7e700d18;
    private static final int DRAG_TAG_KEY = 0x7e700d19;
    /** On the guideline: set while it carries our width, for {@link Split#restore}. */
    private static final int GUIDE_TAG_KEY = 0x7e700d1a;
    /** On a VOD view we resize: {@code Integer} original layout width, for restore. */
    private static final int WIDTH_TAG_KEY = 0x7e700d1b;
    /** On the chat column while it lies over the video ({@link #syncOverVideo}). */
    private static final int OVER_TAG_KEY = 0x7e700d1c;
    /** On a view whose background we fade: {@code Integer} its original alpha. */
    private static final int BG_ALPHA_TAG_KEY = 0x7e700d1d;
    /** On the chat column over the video: its own background before our scrim. */
    private static final int BG_TAG_KEY = 0x7e700d1e;

    private static volatile boolean installed;
    private static volatile boolean toastedFailure;

    /**
     * True while the user is actively dragging the handle. The layout watcher
     * bows out entirely in that window so it never fights the drag's own
     * width updates.
     */
    private static volatile boolean dragging;

    /** Chat width % while typing or with a poll/prediction open in the landscape side chat ({@link UiHider}), 0 otherwise. */
    private static final int TYPING_PERCENT = 50;
    private static volatile int typingPercent;
    /** Width of the theatre the guideline lives in, from the last layout pass; 0 before the first. */
    private static volatile int theatreWidth;
    /** Twitch's last theatre layout asked for a side chat column (a guideline percent under 1). */
    private static volatile boolean columnMode;
    /** A picture-by-picture ad is on screen: its stream sits on top of the chat column, which then stays a column. */
    private static volatile boolean pbypShown;

    private TheatreDividerHook() {
    }

    /**
     * Called once from the injected {@code TwitchApplication.onCreate}.
     *
     * @param application the process Application (smali passes {@code p0})
     */
    public static void install(Application application) {
        try {
            if (installed || application == null) {
                return;
            }
            installed = true;
            application.registerActivityLifecycleCallbacks(new Callbacks());
        } catch (Throwable t) {
            Log.w(TAG, "install failed", t);
            CrashReporter.report(TAG, "install failed", t);
        }
    }

    /**
     * Bridge for the theatre layout applier (injected by
     * {@code retwitchTheatreSplitPatch}): rewrites the {@code guidePercent}
     * Twitch is about to push onto {@code chat_guideline} (0.5 in the native
     * swipe-split, -1 when docked). With the divider on it is always unset, so
     * {@link #guideEnd(int)} governs.
     *
     * <p>This makes Twitch itself the single writer of the guideline, so we no
     * longer fight its every-frame resets from the runtime hook. Returns
     * {@code original} unchanged when the feature is off (native behaviour) or
     * on any error, so a bad build can never wedge the theatre.
     */
    public static float guidePercent(float original) {
        try {
            // Unset: the end margin from guideEnd governs (a percent of the parent
            // changed the chat width whenever the system bars came and went).
            // 1.0 is no side chat (vertical view in landscape): kept, a percent
            // wins over guideEnd's width.
            return original < 1f && widthPercent() >= 0 ? -1f : original;
        } catch (Throwable t) {
            return original;
        }
    }

    /**
     * Companion to {@link #guidePercent(float)} for the {@code guidelineEnd}
     * writes (Twitch's docked chat: a fixed ~279dp, -1 in the swipe-split).
     * With the divider on it is the persisted width in px ({@link #chatWidthPx}),
     * in every state; otherwise Twitch's value passes through.
     */
    public static int guideEnd(int original) {
        try {
            int percent = widthPercent();
            return percent >= 0 ? chatWidthPx(percent) : original;
        } catch (Throwable t) {
            return original;
        }
    }

    /**
     * Bridge for the theatre's ConstraintSet re-layout (31.8.0 {@code gi40.y},
     * on every theatre state change, the controls showing included): Twitch
     * sets {@code chat_guideline} to 0.7 (0.8 tablets, 0.5 split) and applies
     * the set straight into the LayoutParams, so the chat flashed at Twitch's
     * width until {@link #guideEnd} won again. With the divider on, a side
     * chat gets {@link #guideEnd}'s px as a share of the theatre, not {@code percent}
     * itself: display metrics can leave out a side navigation bar the theatre spans,
     * and the two writes then disagree on the seam at every tap. 1.0 (chat hidden)
     * passes through.
     */
    public static float constraintSetPercent(float original) {
        try {
            if (original > 0f) {
                columnMode = original < 1f;
            }
            if (overVideo()) {
                return 1f; // the video and every player overlay take the full width, syncOverVideo places the chat
            }
            int percent = widthPercent();
            if (percent < 0 || original <= 0f || original >= 1f) {
                return original;
            }
            // Under 3/4 of the long edge it's a width left from a small window (picture-in-picture): it gave a
            // negative percent, the chat took the whole theatre, and a full-width chat is no side column to re-measure.
            int width = theatreWidth;
            return width * 4 >= chatWidthPx(100) * 3 ? 1f - chatWidthPx(percent) / (float) width : 1f - percent / 100f;
        } catch (Throwable t) {
            return original;
        }
    }

    /** Our chat width % for Twitch's guideline writes: typing, else the divider's, else -1 (Twitch's own). */
    private static int widthPercent() {
        // The applier also lays out portrait (vertical view, mini player): our px width
        // of the long edge there left the player overlay a third of the screen.
        if (!landscape()) {
            return -1;
        }
        if (typingPercent > 0) {
            return typingPercent;
        }
        return TheatreSettings.isSideChatDividerEnabled() || TheatreSettings.hasSideChatWidth()
                ? TheatreSettings.getSideChatWidthPercent() : -1;
    }

    private static boolean landscape() {
        return AppContext.get().getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    /** The side chat lies over a full-width video now ({@link Toggles#CHAT_OVER_VIDEO}). */
    private static boolean overVideo() {
        return columnMode && !pbypShown && landscape() && Toggles.on(Toggles.CHAT_OVER_VIDEO);
    }

    /** Width % of the chat column over the video: typing's, else the divider's (Twitch's 30 by default). */
    private static int overVideoPercent() {
        return typingPercent > 0 ? typingPercent : TheatreSettings.getSideChatWidthPercent();
    }

    /** From {@link UiHider}: half-screen chat while the keyboard is up or a poll/prediction is open, the usual width after. */
    static void setTyping(View root, boolean typing) {
        int percent = typing ? TYPING_PERCENT : 0;
        if (typingPercent == percent) {
            return;
        }
        typingPercent = percent;
        update(root);
    }

    /**
     * Chat column width for {@code percent} of the display's largest edge: a
     * fixed size like Twitch's own 279dp, so it doesn't follow the theatre's
     * width when the system bars show and hide with the player controls.
     */
    static int chatWidthPx(int percent) {
        android.util.DisplayMetrics dm = AppContext.get().getResources().getDisplayMetrics();
        return Math.round(Math.max(dm.widthPixels, dm.heightPixels) * percent / 100f);
    }

    private static void attachWatcher(Activity activity) {
        try {
            if (activity == null
                    || !activity.getClass().getName().startsWith("tv.twitch")) {
                return;
            }
            Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            View root = window.findViewById(android.R.id.content);
            // Guard on the decor: AppCompat hands android.R.id.content to its own frame
            // after onActivityCreated, so a tag on the content view let onResume add a second listener.
            View decor = window.getDecorView();
            if (root == null || decor.getTag(LISTENER_TAG_KEY) != null) {
                return;
            }
            final View rootRef = root;
            // A layout-pass watcher only: it reflows the split (VOD) and keeps
            // the drag handle placed/visible in sync with the theatre.
            //
            // We deliberately do NOT install a per-frame OnPreDrawListener here.
            // The landscape guideline is now driven at the source by
            // retwitchTheatreSplitPatch (see #guidePercent/#guideEnd), so there
            // is no wrong-split frame left to correct before draw. A pre-draw
            // pass runs on every rendered frame of the window, and while the
            // player is playing that meant doing resource lookups
            // (Resources.getIdentifier) + view-tree walks up to 60x/second on
            // the UI thread -- a measurable FPS hit whenever the side-by-side
            // chat was on screen. onGlobalLayout fires only when the layout
            // actually changes (including the tap-controls overlay show/hide
            // that toggles the handle), which is enough to keep the handle in
            // sync without the per-frame cost.
            root.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            long t0 = UiStallLog.start();
                            try {
                                update(rootRef);
                            } catch (Throwable t) {
                                onFailure(rootRef.getContext(), "layout pass", t);
                            }
                            UiStallLog.end("Divider", t0);
                        }
                    });
            decor.setTag(LISTENER_TAG_KEY, Boolean.TRUE);
        } catch (Throwable t) {
            Log.w(TAG, "attachWatcher failed", t);
            CrashReporter.report(TAG, "attachWatcher failed", t);
        }
    }

    /**
     * One layout pass: (dis)assemble the divider depending on whether a
     * side-by-side chat column (stream theatre <i>or</i> VOD) is on screen.
     */
    private static void update(View root) {
        if (dragging) {
            // The drag handler owns the split + handle position while a drag is
            // in flight; stay out of its way.
            return;
        }

        pbypShown = pbypShown(root);
        syncOverVideo(root);
        syncBubble(root);

        Split split = resolveSplit(root);
        if (split == null) {
            removeHandle(root);
            return;
        }

        // Typing (see setTyping): temporary width, no handle; the next pass after
        // it ends restores the divider's width or Twitch's.
        if (typingPercent > 0) {
            split.applyPercent(typingPercent, 0.004f);
            removeHandle(root);
            return;
        }

        // Handle off: a width dragged earlier still applies (turning the handle off
        // reset it before); without one, Twitch's own split.
        if (!TheatreSettings.isSideChatDividerEnabled()) {
            if (!TheatreSettings.hasSideChatWidth()) {
                split.restore();
            } else {
                split.applyPercent(TheatreSettings.getSideChatWidthPercent(), 0.004f);
            }
            removeHandle(root);
            return;
        }

        // Re-apply the persisted split (Twitch resets it to its own default
        // each time it configures side-by-side). Guarded so setLayoutParams ->
        // relayout -> onGlobalLayout doesn't loop; after a change we return and
        // let the next (settled) pass place the handle.
        if (split.applyPercent(TheatreSettings.getSideChatWidthPercent(), 0.004f)) {
            return;
        }

        View host = findFrameHost(split.parent);
        if (host == null) {
            return;
        }
        placeHandle(host, split);
    }

    /**
     * The side chat over a full-width video. Twitch's layout gets guideline 1.0 ({@link #constraintSetPercent}),
     * so the video and every player overlay span the theatre; the column, anchored to that guideline and the
     * parent end (both at the right edge), gets our width and is shifted back by half of it (a fixed width
     * between two anchors at one x is centred on that x). Its full-width backgrounds go clear under one scrim of
     * the chosen opacity, and the player controls are lifted above it while they show. Leaving the mode puts
     * all of it back and, while Twitch still wants a column, the guideline at the column's width.
     */
    private static void syncOverVideo(View root) {
        View chat = findByName(root, CHAT_ID_NAME);
        if (chat == null || !(chat.getParent() instanceof View)) {
            return;
        }
        View guideline = findByName((View) chat.getParent(), GUIDELINE_ID_NAME);
        View controls = findByName(root, "player_overlay_container");
        if (guideline != null && overVideo() && chat.getVisibility() == View.VISIBLE) {
            setGuideline(guideline, 1f, -1);
            guideline.setTag(GUIDE_TAG_KEY, Boolean.TRUE);
            chat.setTag(OVER_TAG_KEY, Boolean.TRUE);
            placeOverVideo(chat, chatWidthPx(overVideoPercent()));
            if (controls != null) {
                View overlay = findByName(root, PLAYER_OVERLAY_ID_NAME);
                float z = overlay != null && effectiveShownAlpha(overlay) > 0.02f ? 1f : 0f;
                if (controls.getTranslationZ() != z) {
                    controls.setTranslationZ(z);
                }
            }
            fadeBackgrounds(chat, TheatreSettings.getChatBgOpacity());
            return;
        }
        if (chat.getTag(OVER_TAG_KEY) == null) {
            return;
        }
        chat.setTag(OVER_TAG_KEY, null);
        restoreWidth(chat);
        chat.setTranslationX(0f);
        if (controls != null) {
            controls.setTranslationZ(0f);
        }
        unfadeBackgrounds(chat);
        if (guideline != null && columnMode) {
            int percent = widthPercent();
            setGuideline(guideline, -1f, percent >= 0 ? chatWidthPx(percent) : dp(guideline.getContext(), 279));
        }
    }

    /** The column over the video at {@code width} px, flush with the right edge (see {@link #syncOverVideo}). */
    private static boolean placeOverVideo(View chat, int width) {
        boolean changed = snapshotWidth(chat) | setViewWidth(chat, width);
        float tx = -width / 2f;
        if (chat.getTranslationX() != tx) {
            chat.setTranslationX(tx);
        }
        return changed;
    }

    /**
     * Clears the backgrounds of the column's full-width views (list, header, input bar; not the field's pill or
     * the buttons) and puts one scrim of {@code opacity}% in the theatre's own colour on the column itself.
     * Message rows are left alone: their highlights stay.
     */
    private static void fadeBackgrounds(View chat, int opacity) {
        int[] color = {0};
        clearWide(chat, chat.getWidth(), color, true);
        if (chat.getTag(BG_TAG_KEY) == null) {
            chat.setTag(BG_TAG_KEY, chat.getBackground() != null ? chat.getBackground() : Boolean.FALSE);
        }
        int base = color[0] != 0 ? color[0] : 0xFF0E0E10;
        int scrim = (Math.round(255 * opacity / 100f) << 24) | (base & 0xFFFFFF);
        Drawable bg = chat.getBackground();
        if (!(bg instanceof ColorDrawable) || ((ColorDrawable) bg).getColor() != scrim) {
            chat.setBackground(new ColorDrawable(scrim));
        }
    }

    private static void clearWide(View view, int width, int[] color, boolean top) {
        if (!top) {
            Drawable bg = view.getBackground();
            if (bg != null && view.getWidth() >= width * 0.9f) {
                if (view.getTag(BG_ALPHA_TAG_KEY) == null) {
                    if (color[0] == 0 && bg instanceof ColorDrawable && Color.alpha(((ColorDrawable) bg).getColor()) == 255) {
                        color[0] = ((ColorDrawable) bg).getColor();
                    }
                    view.setTag(BG_ALPHA_TAG_KEY, bg.getAlpha());
                    bg.mutate();
                }
                if (bg.getAlpha() != 0) {
                    bg.setAlpha(0);
                }
            }
        }
        if (!(view instanceof ViewGroup) || isList(view)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            clearWide(group.getChildAt(i), width, color, false);
        }
    }

    private static void unfadeBackgrounds(View chat) {
        Object own = chat.getTag(BG_TAG_KEY);
        if (own != null) {
            chat.setTag(BG_TAG_KEY, null);
            chat.setBackground(own instanceof Drawable ? (Drawable) own : null);
        }
        restoreAlpha(chat);
    }

    private static void restoreAlpha(View view) {
        Object alpha = view.getTag(BG_ALPHA_TAG_KEY);
        if (alpha instanceof Integer) {
            view.setTag(BG_ALPHA_TAG_KEY, null);
            if (view.getBackground() != null) {
                view.getBackground().setAlpha((Integer) alpha);
            }
        }
        if (view instanceof ViewGroup && !isList(view)) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                restoreAlpha(group.getChildAt(i));
            }
        }
    }

    /** A message list: its rows are not walked (many, recycled, with their own highlights). */
    private static boolean isList(View view) {
        return view instanceof android.widget.AbsListView || view.getClass().getName().endsWith("RecyclerView");
    }

    /**
     * Twitch's overlay chat (one message at a time over the video, {@code one_chat_message}): its bubble
     * {@code chat_message_background} gets black of the chosen opacity, once one was chosen. Its own colour
     * ({@code opac_b_4}) is already see-through, so the opacity replaces it: a multiplied alpha left 100% at
     * Twitch's look. The expanded overlay chat gets the column's treatment.
     */
    private static void syncBubble(View root) {
        if (!TheatreSettings.hasChatBgOpacity()) {
            return;
        }
        View overlay = findByName(root, "one_chat_overlay_container");
        if (!(overlay instanceof ViewGroup) || overlay.getVisibility() != View.VISIBLE) {
            return;
        }
        int opacity = TheatreSettings.getChatBgOpacity();
        int color = Math.round(255 * opacity / 100f) << 24;
        for (View bubble : UiHider.findAll(root.getContext(), overlay, "chat_message_background")) {
            Drawable bg = bubble.getBackground();
            if (bg instanceof GradientDrawable) {
                GradientDrawable shape = (GradientDrawable) bg.mutate();
                android.content.res.ColorStateList now = shape.getColor();
                if (now == null || now.getDefaultColor() != color) {
                    shape.setColor(color);
                }
            } else if (bg != null && bg.getAlpha() != color >>> 24) {
                bg.mutate().setAlpha(color >>> 24);
            }
        }
        View expanded = findByName(overlay, "one_chat_expanded_chat_container");
        if (expanded != null && expanded.getVisibility() == View.VISIBLE && expanded.getWidth() > 0) {
            fadeBackgrounds(expanded, opacity);
        }
    }

    /** A picture-by-picture ad shows the stream in {@code pbyp_player_container}, right where the column starts. */
    private static boolean pbypShown(View root) {
        View pbyp = findByName(root, "pbyp_player_container");
        if (!(pbyp instanceof ViewGroup) || pbyp.getVisibility() != View.VISIBLE) {
            return false;
        }
        ViewGroup group = (ViewGroup) pbyp;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i).getVisibility() == View.VISIBLE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Detects the on-screen side-by-side split and wraps it in a {@link Split}
     * that abstracts the two layout families (stream-theatre guideline vs. VOD
     * RelativeLayout). Returns {@code null} when neither is present/settled.
     */
    private static Split resolveSplit(View root) {
        // Stream theatre: ConstraintLayout + vertical guideline.
        View chat = findByName(root, CHAT_ID_NAME);
        if (isSideBySideChat(chat)) {
            View parent = (View) chat.getParent();
            View guideline = findByName(parent, GUIDELINE_ID_NAME);
            if (guideline != null && parent.getWidth() > 0) {
                theatreWidth = parent.getWidth();
                Split s = new Split(parent, chat);
                s.guideline = guideline;
                s.over = chat.getTag(OVER_TAG_KEY) != null;
                return s;
            }
        }
        // VOD: RelativeLayout; the player side follows the chat via toLeftOf.
        View vodChat = findByName(root, VOD_CHAT_ID_NAME);
        if (isSideBySideChat(vodChat)) {
            View parent = (View) vodChat.getParent();
            if (parent.getWidth() > 0) {
                return new Split(parent, vodChat);
            }
        }
        return null;
    }

    /**
     * Abstracts one player/chat split across both layout families. Positions
     * are expressed as a <i>boundary fraction</i>: the x of the player/chat
     * seam divided by the parent width (0 = far left, 1 = far right).
     *
     * <ul>
     * <li><b>Stream theatre</b> ({@link #guideline} set): moving the vertical
     *     guideline's {@code guidePercent} reflows the player, chat and every
     *     anchored overlay together.</li>
     * <li><b>VOD</b> (no guideline): the chat container's own width is the
     *     lever; the player pane / overlay follow via their relative rules.</li>
     * </ul>
     */
    private static final class Split {
        final View parent;
        final View chat;
        final boolean chatOnRight;
        View guideline;
        /** The column lies over the video: its own width is the lever, the guideline stays at the edge. */
        boolean over;

        Split(View parent, View chat) {
            this.parent = parent;
            this.chat = chat;
            this.chatOnRight = chatCenterInParent(chat, parent) > parent.getWidth() / 2;
        }

        /**
         * Chat width % for a finger at window x {@code rawX}: of the largest
         * display edge in the theatre ({@link #chatWidthPx}), of the parent in VODs.
         */
        int chatPercentAt(float rawX, int parentLeft) {
            int pw = parent.getWidth();
            float chatPx = chatOnRight ? parentLeft + pw - rawX : rawX - parentLeft;
            float base = guideline != null ? chatWidthPx(100) : pw;
            return TheatreSettings.clamp(Math.round(chatPx * 100f / base));
        }

        /** Window x of the seam for a chat width of {@code percent} (see {@link #chatPercentAt}). */
        int boundaryXFor(int percent, int parentLeft) {
            int pw = parent.getWidth();
            int chatPx = guideline != null ? chatWidthPx(percent) : Math.round(pw * percent / 100f);
            return chatOnRight ? parentLeft + pw - chatPx : parentLeft + chatPx;
        }

        /**
         * Sizes the chat to {@code percent}. Returns whether anything changed
         * (VOD: skips work within {@code threshold}; pass 0 to force during a
         * drag). The guideline's own setters skip repeats, so it reports false.
         */
        boolean applyPercent(int percent, float threshold) {
            if (over) {
                return placeOverVideo(chat, chatWidthPx(percent));
            }
            if (guideline != null) {
                setGuideline(guideline, -1f, chatWidthPx(percent));
                guideline.setTag(GUIDE_TAG_KEY, Boolean.TRUE);
                return false;
            }
            float chatFrac = percent / 100f;
            return apply(chatOnRight ? 1f - chatFrac : chatFrac, threshold);
        }

        /** Current boundary fraction from the settled geometry. */
        float fraction() {
            int pw = parent.getWidth();
            if (pw <= 0) {
                return -1f;
            }
            if (over) {
                return (chat.getLeft() + chat.getTranslationX()) / pw;
            }
            if (guideline != null) {
                return guideline.getLeft() / (float) pw;
            }
            int boundary = chatOnRight ? chat.getLeft() : chat.getRight();
            return boundary / (float) pw;
        }

        /**
         * Moves the seam to {@code boundaryFraction}. Returns whether anything
         * changed (skips work when already within {@code threshold}; pass 0 to
         * force during a drag).
         */
        boolean apply(float boundaryFraction, float threshold) {
            int pw = parent.getWidth();
            if (pw <= 0) {
                return false;
            }
            if (Math.abs(fraction() - boundaryFraction) <= threshold) {
                return false;
            }
            int boundaryPx = Math.round(boundaryFraction * pw);
            int chatW = chatOnRight ? (pw - boundaryPx) : boundaryPx;
            return snapshotWidth(chat) | setViewWidth(chat, chatW);
        }

        void restore() {
            if (over) {
                return; // syncOverVideo owns the column
            }
            if (guideline != null) {
                // Twitch's docked width; its next theatre state re-applies its own value anyway.
                if (guideline.getTag(GUIDE_TAG_KEY) != null) {
                    guideline.setTag(GUIDE_TAG_KEY, null);
                    setGuideline(guideline, -1f, dp(guideline.getContext(), 279));
                }
                return;
            }
            restoreWidth(chat);
        }
    }

    /** Sets {@code view}'s layout width; returns whether it actually changed. */
    private static boolean setViewWidth(View view, int width) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp == null || lp.width == width) {
            return false;
        }
        lp.width = width;
        view.setLayoutParams(lp);
        return true;
    }

    /** Captures {@code view}'s original layout width once (for VOD restore). Returns {@code false} (never counts as a change). */
    private static boolean snapshotWidth(View view) {
        if (view != null && view.getTag(WIDTH_TAG_KEY) == null) {
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) {
                view.setTag(WIDTH_TAG_KEY, lp.width);
            }
        }
        return false;
    }

    /** Restores the width captured by {@link #snapshotWidth}, then forgets it. No-op if never snapshotted. */
    private static void restoreWidth(View view) {
        if (view == null) {
            return;
        }
        Object tag = view.getTag(WIDTH_TAG_KEY);
        if (!(tag instanceof Integer)) {
            return;
        }
        try {
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) {
                lp.width = (Integer) tag;
                view.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {
            // leave as-is; Twitch reconfigures on next entry
        } finally {
            view.setTag(WIDTH_TAG_KEY, null);
        }
    }

    /**
     * Whether {@code chat} is the on-screen side-by-side chat column: visible,
     * non-empty, inside a landscape parent, and occupying a side strip (not
     * the whole width, which would be overlay/portrait chat).
     */
    private static boolean isSideBySideChat(View chat) {
        if (chat == null || chat.getVisibility() != View.VISIBLE
                || chat.getWidth() <= 0 || chat.getHeight() <= 0) {
            return false;
        }
        ViewParent p = chat.getParent();
        if (!(p instanceof View)) {
            return false;
        }
        View parent = (View) p;
        int parentW = parent.getWidth();
        int parentH = parent.getHeight();
        if (parentW <= 0 || parentH <= 0 || parentW <= parentH) {
            return false; // portrait / not laid out
        }
        // A side column, not a full-width overlay.
        return chat.getWidth() < parentW * 0.95f;
    }

    private static int chatCenterInParent(View chat, View parent) {
        int[] cl = new int[2];
        int[] pl = new int[2];
        chat.getLocationInWindow(cl);
        parent.getLocationInWindow(pl);
        return (cl[0] - pl[0]) + chat.getWidth() / 2;
    }

    private static void placeHandle(View host, Split split) {
        Context ctx = host.getContext();
        if (!(host instanceof ViewGroup)) {
            return;
        }
        ViewGroup hostGroup = (ViewGroup) host;

        View handle = hostGroup.findViewWithTag(HANDLE_TAG);
        if (handle == null) {
            handle = createHandle(ctx);
            hostGroup.addView(handle);
        }

        DragState state = (DragState) handle.getTag(DRAG_TAG_KEY);
        if (state == null) {
            state = new DragState();
            handle.setTag(DRAG_TAG_KEY, state);
            handle.setOnTouchListener(new DragTouchListener());
        }
        state.split = split;

        View chat = split.chat;
        View parent = split.parent;
        int[] pLoc = new int[2];
        int[] chatLoc = new int[2];
        int[] hostLoc = new int[2];
        parent.getLocationInWindow(pLoc);
        chat.getLocationInWindow(chatLoc);
        host.getLocationInWindow(hostLoc);

        // Boundary x in window space = parent left + fraction across the parent
        // (works for both the guideline and the VOD chat edge).
        int boundaryXWindow = pLoc[0] + Math.round(split.fraction() * parent.getWidth());
        int handleW = dp(ctx, 18);
        int left = boundaryXWindow - hostLoc[0] - handleW / 2;
        int top = chatLoc[1] - hostLoc[1];
        int height = chat.getHeight();
        int gravity = Gravity.TOP | Gravity.START;

        // CRITICAL: this runs on every onGlobalLayout. View.setLayoutParams()
        // and View.bringToFront() both call requestLayout(), which schedules
        // another layout pass -> another onGlobalLayout -> back here, i.e. a
        // self-sustaining layout loop that pegs the UI thread every frame while
        // the divider is on (a real, measured jank source). So we only touch
        // the handle when something actually changed; on a settled frame this
        // method issues no layout/draw request and the loop can't start.
        FrameLayout.LayoutParams cur = (handle.getLayoutParams() instanceof FrameLayout.LayoutParams)
                ? (FrameLayout.LayoutParams) handle.getLayoutParams()
                : null;
        boolean geometryChanged = cur == null
                || cur.width != handleW
                || cur.height != height
                || cur.gravity != gravity
                || cur.leftMargin != left
                || cur.topMargin != top;
        if (geometryChanged) {
            FrameLayout.LayoutParams lp = cur != null
                    ? cur
                    : new FrameLayout.LayoutParams(handleW, height);
            lp.width = handleW;
            lp.height = height;
            lp.gravity = gravity;
            lp.leftMargin = left;
            lp.topMargin = top;
            handle.setLayoutParams(lp);
        }

        // Only re-raise the handle if something else was drawn on top of it;
        // calling bringToFront() unconditionally would itself relayout us into
        // the loop described above.
        if (hostGroup.getChildAt(hostGroup.getChildCount() - 1) != handle) {
            handle.bringToFront();
        }

        // Sync visibility with the tap-controls overlay on this settled pass
        // (the handle starts VISIBLE from createHandle). showHandle/setVisibility
        // below are themselves guarded to no-op when already in the target state.
        applyHandleVisibility(handle);
    }

    /**
     * Mirrors the drag handle's visibility onto the player's tap-to-show
     * controls overlay ({@link #PLAYER_OVERLAY_ID_NAME}): the handle rides in
     * with the overlay and fades out with it, so it never sits over the chat
     * edge while the controls are hidden. While a drag is in flight the handle
     * stays fully shown regardless (and is re-evaluated once the drag ends).
     * Fail-open: if the overlay view can't be located we leave the handle
     * visible -- i.e. the original always-on behaviour.
     */
    private static void applyHandleVisibility(View handle) {
        if (handle == null || !handle.isAttachedToWindow()) {
            return;
        }
        if (dragging) {
            showHandle(handle, 1f);
            return;
        }
        // The handle is raised over the whole theatre: under an open sheet (player settings) it hides.
        if (sheetOpen(handle.getRootView())) {
            if (handle.getVisibility() != View.GONE) {
                handle.setVisibility(View.GONE);
            }
            return;
        }
        View overlay = findByName(handle.getRootView(), PLAYER_OVERLAY_ID_NAME);
        if (overlay == null) {
            showHandle(handle, 1f);
            return;
        }
        float alpha = effectiveShownAlpha(overlay);
        if (alpha <= 0.02f) {
            // GONE (not just alpha 0) so the hidden handle also stops eating
            // touches on the chat edge.
            if (handle.getVisibility() != View.GONE) {
                handle.setVisibility(View.GONE);
            }
        } else {
            showHandle(handle, alpha);
        }
    }

    /**
     * Effective on-screen alpha of {@code view} as the user sees it: {@code 0}
     * if the view has zero size, or if it or <i>any</i> ancestor up to the
     * root is not {@link View#VISIBLE}; otherwise the product of every
     * ancestor's {@link View#getAlpha()}.
     *
     * <p>Twitch shows/hides the tap-to-show controls by toggling a
     * <i>parent</i> of {@code player_overlay} (its {@code player_overlay_container}
     * /animation wrapper), not {@code player_overlay} itself -- so reading the
     * overlay's own {@code getVisibility()}/{@code getAlpha()} alone always
     * reads "shown". Walking to the root captures whichever ancestor actually
     * carries the visibility/alpha toggle.
     */
    private static float effectiveShownAlpha(View view) {
        if (view.getWidth() <= 0 || view.getHeight() <= 0) {
            return 0f;
        }
        float alpha = 1f;
        for (View v = view; v != null; ) {
            if (v.getVisibility() != View.VISIBLE) {
                return 0f;
            }
            alpha *= v.getAlpha();
            ViewParent p = v.getParent();
            v = (p instanceof View) ? (View) p : null;
        }
        return alpha;
    }

    private static void showHandle(View handle, float alpha) {
        if (handle.getVisibility() != View.VISIBLE) {
            handle.setVisibility(View.VISIBLE);
        }
        if (handle.getAlpha() != alpha) {
            handle.setAlpha(alpha);
        }
    }

    private static void removeHandle(View root) {
        try {
            if (!(root instanceof ViewGroup)) {
                return;
            }
            View handle = root.findViewWithTag(HANDLE_TAG);
            if (handle != null && handle.getParent() instanceof ViewGroup) {
                ((ViewGroup) handle.getParent()).removeView(handle);
            }
        } catch (Throwable t) {
            Log.w(TAG, "removeHandle failed", t);
            CrashReporter.report(TAG, "removeHandle failed", t);
        }
    }

    /** A translucent full-height bar with a centred rounded "grabber" pill. */
    private static View createHandle(Context ctx) {
        FrameLayout handle = new FrameLayout(ctx);
        handle.setTag(HANDLE_TAG);
        handle.setBackgroundColor(0x22000000);
        handle.setContentDescription(Strings.dividerHandleDesc());

        View pill = new View(ctx);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0xCCFFFFFF);
        pillBg.setCornerRadius(dp(ctx, 3));
        pill.setBackground(pillBg);
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(
                dp(ctx, 5), dp(ctx, 56), Gravity.CENTER);
        handle.addView(pill, pillLp);
        return handle;
    }

    /** A tap on the divider: the chat-over-video switch and the background opacity, applied live. */
    private static void openQuickPanel(View handle) {
        final Context ctx = handle.getContext();
        final View root = handle.getRootView();
        int pad = dp(ctx, 20);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        Switch over = new Switch(ctx);
        over.setText(Toggles.CHAT_OVER_VIDEO.label());
        over.setChecked(Toggles.on(Toggles.CHAT_OVER_VIDEO));
        over.setOnCheckedChangeListener((button, on) -> {
            Toggles.set(ctx, Toggles.CHAT_OVER_VIDEO, on);
            update(root);
        });
        box.addView(over, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 48)));

        final int[] steps = TheatreSettings.CHAT_BG_STEPS;
        final TextView label = new TextView(ctx);
        final SeekBar bar = new SeekBar(ctx);
        bar.setMax(steps.length - 1);
        bar.setProgress(stepIndex(TheatreSettings.getChatBgOpacity()));
        label.setText(Strings.pickf("Фон чата: %s", "Chat background: %s", steps[bar.getProgress()] + "%"));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    TheatreSettings.setChatBgOpacity(ctx, steps[progress]);
                    label.setText(Strings.pickf("Фон чата: %s", "Chat background: %s", steps[progress] + "%"));
                    update(root);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        box.addView(label);
        box.addView(bar);

        new AlertDialog.Builder(ctx)
                .setView(box)
                .setPositiveButton(Strings.pick("Готово", "Done"), null)
                .show();
    }

    /** The step of {@link TheatreSettings#CHAT_BG_STEPS} nearest to {@code percent}. */
    static int stepIndex(int percent) {
        int[] steps = TheatreSettings.CHAT_BG_STEPS;
        int best = 0;
        for (int i = 1; i < steps.length; i++) {
            if (Math.abs(steps[i] - percent) < Math.abs(steps[best] - percent)) {
                best = i;
            }
        }
        return best;
    }

    /**
     * A shown player button under the point (screen px). The handle is a strip the chat's height on the seam and
     * was eating taps on whatever button lay there (feedback #73: the full screen button on the seam with the chat
     * over the video). Buttons only: small clickable views of {@code player_overlay}, not the overlay's own
     * tap-to-hide area, or the handle could never be dragged while the controls show.
     */
    private static boolean controlUnder(View handle, float x, float y) {
        View overlay = findByName(handle.getRootView(), PLAYER_OVERLAY_ID_NAME);
        if (overlay == null || effectiveShownAlpha(overlay) <= 0.02f) {
            return false;
        }
        return buttonAt(overlay, Math.round(x), Math.round(y), overlay.getWidth() / 4, overlay.getHeight() / 4);
    }

    private static boolean buttonAt(View view, int x, int y, int maxW, int maxH) {
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0.02f) {
            return false;
        }
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        if (x < at[0] || y < at[1] || x >= at[0] + view.getWidth() || y >= at[1] + view.getHeight()) {
            return false;
        }
        if (view.isClickable() && view.getWidth() <= maxW && view.getHeight() <= maxH) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                if (buttonAt(group.getChildAt(i), x, y, maxW, maxH)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The current {@link Split}, refreshed on every layout pass, read by the touch listener. */
    private static final class DragState {
        Split split;
        int lastPercent = -1;
        float downX;
        /** Past the touch slop: a drag, not a tap (a tap opens {@link #openQuickPanel}). */
        boolean moved;
    }

    private static final class DragTouchListener implements View.OnTouchListener {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            try {
                DragState s = (DragState) v.getTag(DRAG_TAG_KEY);
                if (s == null || s.split == null) {
                    return false;
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (controlUnder(v, event.getRawX(), event.getRawY())) {
                            return false; // the button takes it, see controlUnder
                        }
                        s.downX = event.getRawX();
                        s.moved = false;
                        s.lastPercent = -1;
                        dragging = true;
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!s.moved && Math.abs(event.getRawX() - s.downX)
                                < android.view.ViewConfiguration.get(v.getContext()).getScaledTouchSlop()) {
                            return true;
                        }
                        s.moved = true;
                        applyDrag(v, s, event.getRawX());
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        dragging = false;
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(false);
                        }
                        if (s.lastPercent >= 0) {
                            TheatreSettings.setSideChatWidthPercent(v.getContext(), s.lastPercent);
                        }
                        if (!s.moved && event.getActionMasked() == MotionEvent.ACTION_UP) {
                            openQuickPanel(v);
                        }
                        // Drag over: stop forcing the handle on and let it fall
                        // back in line with the overlay (hides now if the
                        // controls already faded out during the drag).
                        applyHandleVisibility(v);
                        return true;
                    default:
                        return false;
                }
            } catch (Throwable t) {
                dragging = false;
                onFailure(v.getContext(), "drag", t);
                return false;
            }
        }

        private void applyDrag(View handle, DragState s, float rawX) {
            Split split = s.split;
            View parent = split.parent;
            int[] pl = new int[2];
            parent.getLocationInWindow(pl);
            int parentW = parent.getWidth();
            if (parentW <= 0) {
                return;
            }
            // Chat width the finger is dragging to, clamped to [MIN, MAX]%.
            int chatPercent = split.chatPercentAt(rawX, pl[0]);

            // Move the seam: player, chat and every anchored overlay reflow
            // together (guideline in theatre, chat width in VOD).
            split.applyPercent(chatPercent, 0f);
            s.lastPercent = chatPercent;

            // Follow the boundary live, without waiting for the settled pass.
            View host = handle.getParent() instanceof View ? (View) handle.getParent() : null;
            if (host != null && handle.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                int[] hostLoc = new int[2];
                host.getLocationInWindow(hostLoc);
                int boundaryXWindow = split.boundaryXFor(chatPercent, pl[0]);
                FrameLayout.LayoutParams hlp = (FrameLayout.LayoutParams) handle.getLayoutParams();
                hlp.leftMargin = boundaryXWindow - hostLoc[0] - hlp.width / 2;
                handle.setLayoutParams(hlp);
            }
        }
    }

    /**
     * A Twitch sheet (player settings) is open: its content goes into one of the window's several
     * {@code bottom_sheet}s before the slide-in and is removed once collapsed. Not by on-screen
     * rect: mid-slide the sheet is still off screen and the slide brings no further layout pass.
     */
    private static boolean sheetOpen(View root) {
        for (View sheet : UiHider.findAll(root.getContext(), root, "bottom_sheet")) {
            if (sheet instanceof ViewGroup && sheet.isShown() && ((ViewGroup) sheet).getChildCount() > 0) {
                return true;
            }
        }
        return false;
    }

    private static View findByName(View parent, String name) {
        try {
            int id = UiHider.id(parent.getContext(), name);
            return id != 0 ? parent.findViewById(id) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * {@code Guideline.setGuidelinePercent} / {@code setGuidelineEnd}: public
     * ConstraintLayout API, kept by name (Twitch calls them too). Both return
     * early when the value is unchanged, so this is safe on every layout pass.
     */
    private static void setGuideline(View guideline, float percent, int end) {
        try {
            guideline.getClass().getMethod("setGuidelinePercent", float.class).invoke(guideline, percent);
            guideline.getClass().getMethod("setGuidelineEnd", int.class).invoke(guideline, end);
        } catch (Throwable t) {
            Log.w(TAG, "guideline setters unavailable", t);
        }
    }

    /** Nearest {@link FrameLayout} ancestor of {@code start} (spans the whole theatre; safe for absolute positioning). */
    private static View findFrameHost(View start) {
        for (ViewParent q = start.getParent(); q instanceof View; q = q.getParent()) {
            if (q instanceof FrameLayout) {
                return (View) q;
            }
        }
        return null;
    }

    private static void onFailure(Context ctx, String where, Throwable t) {
        Log.w(TAG, "divider " + where + " failed", t);
        CrashReporter.report(TAG, "divider " + where + " failed", t);
        if (toastedFailure) {
            return;
        }
        toastedFailure = true;
        try {
            Context c = ctx != null ? ctx : AppContext.get();
            if (c != null) {
                Toast.makeText(c, Strings.dividerFailedToast(), Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
            // never let the toast itself escape into Twitch
        }
    }

    private static int dp(Context ctx, int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, ctx.getResources().getDisplayMetrics()));
    }

    private static final class Callbacks implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            attachWatcher(activity);
        }

        @Override
        public void onActivityResumed(Activity activity) {
            attachWatcher(activity);
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }
}
