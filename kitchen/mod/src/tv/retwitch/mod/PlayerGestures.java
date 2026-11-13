package tv.retwitch.mod;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * Vertical swipe gestures on the fullscreen (landscape) player: left side =
 * screen brightness, right side = media volume (Thream's player gestures); the
 * middle stays Twitch's (swipe down minimizes the player). Twitch's Vertical view only drags from the
 * strip above the video, so a touch in the middle widens its drag area for that gesture.
 * Landscape only, so portrait swipes (minimize, chat) stay Twitch's -- except,
 * with their own toggle, Twitch's portrait Vertical view
 * ({@code vertical_theatre_player_view}), the fullscreen one for portrait streams.
 * In the regular portrait player a swipe up goes fullscreen, as on YouTube, and in landscape a swipe
 * down in the middle comes back ({@code PLAYER_SWIPE_FULLSCREEN}), through Twitch's own rotate button. Hooked by
 * wrapping each Twitch activity's {@link Window.Callback} in a proxy that sees
 * touches first; a drag is only taken over once it's clearly vertical and
 * starts on the video surface, and the rest of the gesture is cancelled for
 * Twitch so its own tap handling doesn't fire.
 */
public final class PlayerGestures {

    private static final String TAG = "RTGestures";
    /** Share of the video's width on each side that takes brightness and volume. */
    private static final float SIDE_ZONE = 0.35f;
    private static final int DRAG_TAG_KEY = 0x7e700d6a;
    private static boolean installed;
    /** The running touch started in the middle of the Vertical view's video. */
    private static volatile boolean dragAnywhere;
    /**
     * The finger came down where a swipe is ours: a brightness or volume zone, or the middle of the landscape video with
     * swipe-to-exit-fullscreen (down there is ours, up does nothing in Twitch). Twitch's drag-to-minimize gets no area
     * for this touch: it took the drag at its own touch slop, before ours (twice as long), and our CANCEL snapped the
     * theatre back (tester's tablet, a swipe down in the middle: the theatre jumped between full screen and smaller).
     */
    private static volatile boolean ownTouch;

    private PlayerGestures() {
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
                public void onActivityStarted(Activity a) { }
                public void onActivityResumed(Activity a) { wrap(a); }
                public void onActivityPaused(Activity a) { }
                public void onActivityStopped(Activity a) { }
                public void onActivitySaveInstanceState(Activity a, Bundle b) { }
                public void onActivityDestroyed(Activity a) { }
            });
        } catch (Throwable t) {
            Log.w(TAG, "install failed", t);
            CrashReporter.report(TAG, "install failed", t);
        }
    }

    private static void wrap(Activity activity) {
        try {
            Window window = activity.getWindow();
            // Only wrap when the feature is on: no proxy in the window for anyone else.
            if (window == null
                    || !(Toggles.on(Toggles.PLAYER_GESTURES) || Toggles.on(Toggles.PLAYER_GESTURES_VERTICAL)
                            || Toggles.on(Toggles.PLAYER_SWIPE_FULLSCREEN) || Toggles.on(Toggles.CHAT_PINCH_TEXT))
                    || !activity.getClass().getName().startsWith("tv.twitch")) {
                return;
            }
            Window.Callback original = window.getCallback();
            if (original == null || Proxy.isProxyClass(original.getClass())) {
                return;
            }
            Tracker tracker = new Tracker(activity, original);
            window.setCallback((Window.Callback) Proxy.newProxyInstance(
                    Window.Callback.class.getClassLoader(), new Class<?>[]{Window.Callback.class}, tracker));
        } catch (Throwable t) {
            Log.w(TAG, "wrap failed", t);
            CrashReporter.report(TAG, "wrap failed", t);
        }
    }

    private static final class Tracker implements InvocationHandler {
        private final Activity activity;
        private final Window.Callback original;
        private final int slop;
        private View surface;
        private boolean brightness;
        private float startX;
        private float startY;
        private float startValue;
        private boolean armed;
        /** The drag may press Twitch's rotate button: up from the portrait player, down from landscape. */
        private boolean swipeRotate;
        private boolean rotateDown;
        private Badge badge;

        Tracker(Activity activity, Window.Callback original) {
            this.activity = activity;
            this.original = original;
            this.slop = ViewConfiguration.get(activity).getScaledTouchSlop() * 2;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("dispatchTouchEvent".equals(method.getName()) && args != null && args.length == 1) {
                try {
                    if (ChatPinch.onTouch(activity, original, (MotionEvent) args[0]) || onTouch((MotionEvent) args[0])) {
                        return true;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "gesture failed", t);
                    CrashReporter.report(TAG, "gesture failed", t);
                    armed = false;
                    surface = null;
                }
            }
            try {
                return method.invoke(original, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        /** @return true when the event was consumed by our gesture. */
        private boolean onTouch(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    armed = false;
                    surface = null;
                    swipeRotate = false;
                    dragAnywhere = false;
                    ownTouch = false;
                    boolean landscape = activity.getResources().getConfiguration().orientation
                            == Configuration.ORIENTATION_LANDSCAPE;
                    boolean sides = Toggles.on(landscape ? Toggles.PLAYER_GESTURES : Toggles.PLAYER_GESTURES_VERTICAL);
                    boolean rotate = Toggles.on(Toggles.PLAYER_SWIPE_FULLSCREEN);
                    if (!sides && !rotate || sheetAt(activity, ev.getRawX(), ev.getRawY())) {
                        return false;
                    }
                    surface = findSurfaceAt(activity.getWindow().getDecorView(), ev.getRawX(), ev.getRawY());
                    if (surface == null || minimizedTheatre(activity, surface)) {
                        surface = null;
                        return false;
                    }
                    Rect r = new Rect();
                    surface.getGlobalVisibleRect(r);
                    startX = ev.getRawX();
                    startY = ev.getRawY();
                    rotateDown = landscape;
                    View vertical = null;
                    if (!landscape) {
                        vertical = verticalTheatre(activity, surface);
                        // Not a feed preview: one can lie under the mini-player at full width, and the swipe
                        // rotated the minimized theatre (bug #55).
                        swipeRotate = rotate && vertical == null && inTheatre(activity, surface);
                        if (!swipeRotate && !(sides && vertical != null && inMiddle(vertical, ev.getRawY()))) {
                            surface = null;
                            return false;
                        }
                    }
                    if (!swipeRotate) {
                        float x = (ev.getRawX() - r.left) / Math.max(1, r.width());
                        if (x > SIDE_ZONE && x < 1 - SIDE_ZONE) {
                            swipeRotate = landscape && rotate;
                            ownTouch = swipeRotate;
                            if (!swipeRotate) {
                                if (vertical != null) {
                                    widenDragArea(activity, vertical);
                                    dragAnywhere = true;
                                }
                                surface = null;
                            }
                            return false;
                        }
                        if (!sides) {
                            surface = null;
                            return false;
                        }
                        brightness = x <= SIDE_ZONE;
                        startValue = brightness ? brightness(activity.getWindow()) : volume(activity);
                        ownTouch = true;
                    }
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (surface == null) {
                        return armed; // the rest of a drag that already went fullscreen
                    }
                    float dy = startY - ev.getRawY();
                    if (!armed) {
                        if (Math.abs(dy) < slop || Math.abs(dy) < Math.abs(ev.getRawX() - startX) * 2) {
                            return false;
                        }
                        if (swipeRotate && dy > 0 == rotateDown) {
                            surface = null; // the other way stays Twitch's
                            return false;
                        }
                        armed = true;
                        MotionEvent cancel = MotionEvent.obtain(ev);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        original.dispatchTouchEvent(cancel);
                        cancel.recycle();
                    }
                    if (swipeRotate) {
                        swipeRotate = false;
                        surface = null;
                        // fullscreen_button is gone and without effect in portrait.
                        List<View> buttons = UiHider.findAll(activity, activity.getWindow().getDecorView(), "rotate_button");
                        if (!buttons.isEmpty()) {
                            buttons.get(0).performClick();
                        }
                        return true;
                    }
                    float value = clamp(startValue + dy / Math.max(1, surface.getHeight()));
                    if (brightness) {
                        setBrightness(activity.getWindow(), value);
                    } else {
                        setVolume(activity, value);
                    }
                    if (badge == null) {
                        badge = new Badge((ViewGroup) activity.getWindow().getDecorView());
                    }
                    badge.show(brightness, value);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    boolean consumed = armed;
                    armed = false;
                    surface = null;
                    dragAnywhere = false;
                    ownTouch = false;
                    if (consumed && badge != null) {
                        badge.hideLater();
                    }
                    return consumed;
                default:
                    return armed;
            }
        }

    }

    static float volume(Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return max <= 0 ? 0f : am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
    }

    static void setVolume(Context context, float value) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(value * max), 0);
    }

    static float brightness(Window window) {
        float own = window.getAttributes().screenBrightness;
        if (own >= 0f) {
            return own;
        }
        try {
            return Settings.System.getInt(window.getContext().getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS) / 255f;
        } catch (Throwable t) {
            return 0.5f;
        }
    }

    /** Window-only brightness: resets when the activity goes away, like other players. */
    static void setBrightness(Window window, float value) {
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.screenBrightness = Math.max(0.01f, value);
        window.setAttributes(lp);
    }

    /** The "☀ 40%" / "🔊 60%" label in the middle of {@code host} while a side swipe runs. */
    static final class Badge {
        private final ViewGroup host;
        private TextView view;
        private final Runnable hide = () -> view.setVisibility(View.GONE);

        Badge(ViewGroup host) {
            this.host = host;
        }

        void show(boolean brightness, float value) {
            if (view == null) {
                Context context = host.getContext();
                view = new TextView(context);
                view.setTextColor(Color.WHITE);
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                int pad = Math.round(12 * context.getResources().getDisplayMetrics().density);
                view.setPadding(pad, pad / 2, pad, pad / 2);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xAA000000);
                bg.setCornerRadius(pad);
                view.setBackground(bg);
                host.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            }
            view.removeCallbacks(hide);
            view.setText((brightness ? "☀ " : "🔊 ") + Math.round(value * 100) + "%");
            view.setVisibility(View.VISIBLE);
        }

        void hideLater() {
            if (view != null) {
                view.postDelayed(hide, 600L);
            }
        }
    }

    /**
     * Whether an open Twitch bottom sheet (player settings and the like: in
     * the same window, over the video) is under the point.
     */
    private static boolean sheetAt(Activity activity, float x, float y) {
        Rect r = new Rect();
        View decor = activity.getWindow().getDecorView();
        for (String name : SHEETS) {
            for (View sheet : UiHider.findAll(activity, decor, name)) {
                if (sheet.isShown() && sheet instanceof ViewGroup && ((ViewGroup) sheet).getChildCount() > 0
                        && sheet.getGlobalVisibleRect(r) && r.contains((int) x, (int) y)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@link #wrapDragArea} on the theatre container holding {@code from}. */
    private static void widenDragArea(Activity activity, View from) {
        int id = UiHider.id(activity, "draggable_container");
        for (View v = from; id != 0 && v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v.getId() == id) {
                wrapDragArea(v);
                return;
            }
        }
    }

    /**
     * Wraps the theatre container's drag-to-minimize area calculator (public on
     * {@code ConstraintTheatreContainerView}), once per calculator Twitch sets; from every UiHider pass. In landscape
     * the area ends where the side chat begins: a tester's chat scroll minimized the theatre (all mod gestures off,
     * zooming the video, which turns the drag off, fixed it), not reproduced on the Pixel, where Twitch's area
     * (the player pane's width) already stops at the chat. In Vertical view, while {@link #dragAnywhere}, the drag
     * may start on the video (the area is a strip above it there). Null ("can't drag now") stays null.
     */
    static void wrapDragArea(View container) {
        try {
            Method get = container.getClass().getMethod("getDraggableAreaCalculator");
            Object original = get.invoke(container);
            if (original == null || original == container.getTag(DRAG_TAG_KEY)) {
                return;
            }
            Class<?> type = get.getReturnType();
            Object wrapped = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                Object area = method.invoke(original, args);
                if (!(area instanceof Rect)) {
                    return area;
                }
                if (ownTouch) {
                    return new Rect();
                }
                return dragAnywhere
                        ? new Rect(0, 0, container.getWidth(), Math.round(container.getHeight() * 0.8f))
                        : withoutSideChat(container, (Rect) area);
            });
            container.getClass().getMethod("setDraggableAreaCalculator", type).invoke(container, wrapped);
            container.setTag(DRAG_TAG_KEY, wrapped);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "drag area not wrapped", e);
        }
    }

    /** The side chat column of the stream theatre and of the VOD player ({@code player_view}). */
    private static final String[] SIDE_CHATS = {"chat_wrapper", "landscape_chat_container"};

    /**
     * {@code area} (container coordinates) cut at the left edge of the landscape side chat, when it reaches into it.
     * Twitch subtracts the VOD column only in its Column mode; a tester's VOD chat lying over a full-width player
     * dragged the theatre down on every scroll of the chat (0.5.26, not reproduced on the Pixel's VOD layout).
     */
    private static Rect withoutSideChat(View container, Rect area) {
        if (container.getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE) {
            return area;
        }
        int[] at = new int[2];
        container.getLocationInWindow(at);
        int right = area.right;
        for (String name : SIDE_CHATS) {
            int id = UiHider.id(container.getContext(), name);
            View chat = id == 0 ? null : container.findViewById(id);
            if (chat == null || !chat.isShown() || chat.getWidth() == 0) {
                continue;
            }
            int[] chatAt = new int[2];
            chat.getLocationInWindow(chatAt);
            int left = chatAt[0] - at[0];
            if (left > container.getWidth() / 2 && left < right) {
                right = left;
            }
        }
        return right < area.right ? new Rect(area.left, area.top, right, area.bottom) : area;
    }

    /** Twitch's Vertical view player holding the surface, or null. */
    private static View verticalTheatre(Activity activity, View surface) {
        int id = UiHider.id(activity, "vertical_theatre_player_view");
        for (View v = surface; id != 0 && v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v.getId() == id) {
                return v;
            }
        }
        return null;
    }

    /** The top of the Vertical view keeps the channel row, the bottom the chat bar and the game's own HUD. */
    private static boolean inMiddle(View vertical, float y) {
        Rect r = new Rect();
        return vertical.getGlobalVisibleRect(r) && y > r.top + r.height() * 0.12f && y < r.bottom - r.height() * 0.2f;
    }

    /** Twitch's sheet host, and the player settings page itself in case it's hosted elsewhere. */
    private static final String[] SHEETS = {"bottom_sheet", "stream_settings_container"};

    /** The largest visible video surface (SurfaceView/TextureView) under the point. */
    private static boolean inTheatre(Activity activity, View view) {
        int theatre = UiHider.id(activity, "draggable_container");
        for (Object v = view; theatre != 0 && v instanceof View; v = ((View) v).getParent()) {
            if (((View) v).getId() == theatre) {
                return true;
            }
        }
        return false;
    }

    /**
     * The surface is in a minimized theatre: the theatre container keeps the whole screen, its child
     * ({@code draggable_layout}) shrinks into the mini-player. A width check on the surface let through a
     * mini-player enlarged to ~85% of the screen (bug #55).
     */
    private static boolean minimizedTheatre(Activity activity, View view) {
        int theatre = UiHider.id(activity, "draggable_container");
        View child = null;
        for (Object v = view; theatre != 0 && v instanceof View; v = ((View) v).getParent()) {
            if (((View) v).getId() == theatre) {
                Rect outer = new Rect();
                Rect inner = new Rect();
                return child != null && ((View) v).getGlobalVisibleRect(outer) && child.getGlobalVisibleRect(inner)
                        && inner.width() < outer.width() - 2;
            }
            child = (View) v;
        }
        return false;
    }

    private static View findSurfaceAt(View root, float x, float y) {
        if (root == null || root.getVisibility() != View.VISIBLE) {
            return null;
        }
        View best = null;
        if (root instanceof SurfaceView || root instanceof TextureView) {
            Rect r = new Rect();
            if (root.getGlobalVisibleRect(r) && r.contains((int) x, (int) y)) {
                best = root;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findSurfaceAt(group.getChildAt(i), x, y);
                if (found != null && (best == null
                        || found.getWidth() * found.getHeight() > best.getWidth() * best.getHeight())) {
                    best = found;
                }
            }
        }
        return best;
    }

    static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
