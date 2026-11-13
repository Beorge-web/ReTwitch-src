package tv.retwitch.mod;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Drops a small circular-arrow "reload" button into Twitch's player controls
 * overlay; tapping it asks Twitch to re-fetch the manifest and replay the
 * current live stream / VOD. This is ReTwitch's port of PurpleTV's "refresh
 * stream" feature (see {@link RefreshSettings} for persistence).
 *
 * <p><b>Two halves.</b> The visible half (dropping the button into the overlay
 * and placing it) is a pure view-tree hook, identical in technique to
 * {@link TimerHook}: an {@link Application.ActivityLifecycleCallbacks} watches
 * the view tree and splices our button into the overlay's button chain, using
 * only the public Android SDK -- nothing references Twitch's renamed classes.
 *
 * <p>The action half (actually reloading the player) can't be done from the
 * view tree: it needs to push Twitch's own internal {@code
 * TheatreCoordinatorRequest.RetryVideoPlay} into the theatre-coordinator
 * request dispatcher. Those types are R8-renamed, so the
 * {@code retwitchRefreshStream} bytecode patch feeds this class the live,
 * per-build values it can't hardcode -- everything crosses the boundary as
 * plain objects/strings:
 * <ul>
 * <li>{@link #captureDispatcher(Object)} is called from injected smali when the
 *     theatre coordinator is constructed, stashing the dispatcher instance
 *     (an object that also implements the R8-renamed request-<em>updater</em>
 *     interface);</li>
 * <li>{@link #configureReload(Object, String, String, String)} is called once
 *     from injected smali with the {@code RetryVideoPlay} singleton, the
 *     updater's push-method name, and the two no-arg player-presenter refresh
 *     methods (all read live at patch time);</li>
 * <li>{@link #capturePlayerPresenter(Object)} stashes each live stream-player
 *     presenter as theatres open;</li>
 * <li>{@link #reloadPlayer()} (the button's click) mirrors Twitch's own native
 *     retry sequence on the main thread: push {@code RetryVideoPlay}, then call
 *     the player presenter's manifest-refresh and restart methods.</li>
 * </ul>
 * Everything fails soft (never throws into Twitch) per the mod's error rule.
 */
public final class ReloadHook {

    private static final String TAG = "RTReload";

    /**
     * Resource entry names of the buttons in the player controls <em>bottom</em>
     * row, tried in order. Our reload button is spliced in just left of the
     * first <em>currently shown</em> one, so it lands right next to the
     * volume/sound control in the bottom button bar -- the same spot PurpleTV
     * uses -- in both the portrait (mini) and theater (landscape) players.
     *
     * <p>Both bottom bars (portrait's {@code LinearLayout} widget and theater's
     * {@code ConstraintLayout}) are inflated under {@code player_overlay} at the
     * same time, so we must resolve the anchor via {@link #findShownById} (the
     * on-screen instance), not {@code findViewById} (which returns whichever is
     * first in the tree, often the hidden one).
     *
     * <p>{@code fullscreen_button} is preferred over {@code mute_button} because
     * in the theater {@code ConstraintLayout} the sound control's own left
     * neighbour ({@code video_debug_info_button}) is {@code GONE}: splicing left
     * of the sound button would have no laid-out neighbour to reflow, whereas
     * the sound button itself is the (shown) left neighbour of fullscreen, so
     * anchoring there drops the reload button right between sound and
     * fullscreen. {@code rotate_button} (always shown) is a last resort.
     */
    private static final String[] ANCHOR_NAMES = {
            "fullscreen_button",
            "mute_button",
            "rotate_button",
    };

    /**
     * Resource entry names of the player controls overlay container. We scope
     * the gear lookup to this subtree so we never match a same-named button
     * elsewhere in the tree (notably the channel profile's own
     * {@code settings_button} present alongside the VOD player).
     */
    private static final String[] OVERLAY_NAMES = {
            "player_overlay",
            "player_overlay_container",
    };

    /**
     * Resource entry names of the VOD/clip scrubber. Only the seekable (VOD or
     * clip) player inflates these; a live stream's controls overlay has none.
     * If one is present in the window we treat the player as seekable and keep
     * the reload button out (reloading only makes sense for a live stream).
     */
    private static final String[] SEEKBAR_NAMES = {
            "seekbar_container",
            "seekbar",
    };

    private static final String BUTTON_TAG = "retwitch_reload_button";
    private static final int LISTENER_TAG_KEY = 0x7e700d30;
    private static final int BUTTON_TAG_KEY = 0x7e700d31;
    /** Stores the sibling whose end-anchor we re-pointed at the button, so disabling can restore it. */
    private static final int NEIGHBOUR_TAG_KEY = 0x7e700d32;
    /** Stores (as an Integer) the anchor id the neighbour was originally end-anchored to, to restore it. */
    private static final int ANCHOR_ID_TAG_KEY = 0x7e700d33;
    /** The {@link #enabledButtons()} set a strip was built with. */
    private static final int BUTTONS_TAG_KEY = 0x7e700d34;
    private static final int CONSTRAINT_UNSET = -1;

    private static volatile boolean installed;
    private static volatile boolean toastedFailure;
    /** Throttle for the temporary anchor-state diagnostic (see {@link #logAnchorState}). */
    private static long lastAnchorDump;

    /**
     * Every theatre-request dispatcher captured (as an opaque {@link Object})
     * from injected smali -- one per theatre-request presenter constructed.
     * There can be several live at once (feed previews, PiP, the foreground
     * player, ...) and only the foreground one still has an active request
     * subscription, so the reload push is broadcast to all of them: the stale
     * ones are no-ops (their subscribers are disposed), the active one reloads.
     * Held weakly so torn-down presenters can be collected.
     */
    private static final Set<Object> dispatchers =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));

    /**
     * Live stream-player presenters ({@code playerPresenter} in Twitch's stream
     * player MVP stack). Weakly held; the native reload path calls two no-arg
     * methods on this after pushing {@code RetryVideoPlay}.
     */
    private static final Set<Object> playerPresenters =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));

    /**
     * The {@code TheatreCoordinatorRequest.RetryVideoPlay} singleton instance,
     * handed over (as an opaque {@link Object}) from injected smali. Held
     * strongly: it's a process-lifetime Kotlin {@code object} singleton.
     */
    private static volatile Object retryRequest;

    /**
     * Name of the request-updater's single-arg push method (e.g. {@code "b"}),
     * supplied live by the patch. Invoked reflectively on each dispatcher.
     */
    private static volatile String pushMethodName;

    /** Cached reflective handle to {@code dispatcher.<pushMethodName>(Object)}. */
    private static volatile Method pushMethod;

    /** Obfuscated no-arg refresh/restart methods on the player presenter (e.g. {@code r}/{@code Z1}). */
    private static volatile String playerRefreshMethod;
    private static volatile String playerRestartMethod;

    private ReloadHook() {
    }

    /** Called once from the injected {@code TwitchApplication.onCreate} ({@code p0}). */
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

    // ------------------------------------------------------------------
    // Bridge to Twitch's internal reload (called from / into injected smali)
    // ------------------------------------------------------------------

    /**
     * Records a theatre-request updater. Called from injected smali when each
     * stream-player presenter is constructed ({@code theatreRequestUpdater}).
     */
    public static void captureDispatcher(Object dispatcher) {
        try {
            if (dispatcher != null) {
                dispatchers.add(dispatcher);
            }
        } catch (Throwable t) {
            Log.w(TAG, "captureDispatcher failed", t);
            CrashReporter.report(TAG, "captureDispatcher failed", t);
        }
    }

    /**
     * Records a stream-player presenter ({@code playerPresenter}). Called from
     * injected smali at the same constructor.
     */
    public static void capturePlayerPresenter(Object presenter) {
        try {
            if (presenter != null) {
                playerPresenters.add(presenter);
            }
        } catch (Throwable t) {
            Log.w(TAG, "capturePlayerPresenter failed", t);
            CrashReporter.report(TAG, "capturePlayerPresenter failed", t);
        }
    }

    /**
     * Supplies reload primitives that can't be named from Java. Called once from
     * injected smali at {@code Application.onCreate} (all values read live at
     * patch time).
     */
    public static void configureReload(
            Object retryVideoPlayInstance,
            String updaterPushMethodName,
            String playerRefreshMethodName,
            String playerRestartMethodName) {
        try {
            if (retryVideoPlayInstance != null) {
                retryRequest = retryVideoPlayInstance;
            }
            if (updaterPushMethodName != null && !updaterPushMethodName.isEmpty()) {
                pushMethodName = updaterPushMethodName;
            }
            if (playerRefreshMethodName != null && !playerRefreshMethodName.isEmpty()) {
                playerRefreshMethod = playerRefreshMethodName;
            }
            if (playerRestartMethodName != null && !playerRestartMethodName.isEmpty()) {
                playerRestartMethod = playerRestartMethodName;
            }
        } catch (Throwable t) {
            Log.w(TAG, "configureReload failed", t);
            CrashReporter.report(TAG, "configureReload failed", t);
        }
    }

    /** The button's click: reload the current stream/VOD on the main thread. */
    public static void reloadPlayer() {
        try {
            final Object request = retryRequest;
            final String method = pushMethodName;
            final String refresh = playerRefreshMethod;
            final String restart = playerRestartMethod;
            final Object[] dispatcherTargets;
            final Object[] presenterTargets;
            synchronized (dispatchers) {
                dispatcherTargets = dispatchers.toArray();
            }
            synchronized (playerPresenters) {
                presenterTargets = playerPresenters.toArray();
            }
            if ((dispatcherTargets.length == 0 && presenterTargets.length == 0)
                    || request == null || method == null) {
                toast(Strings.reloadUnavailableToast());
                return;
            }
            toast(Strings.reloadToast());
            Log.i(TAG, "reloadPlayer: " + dispatcherTargets.length + " dispatcher(s), "
                    + presenterTargets.length + " presenter(s)");
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    for (Object dispatcher : dispatcherTargets) {
                        if (dispatcher != null) {
                            pushRetry(dispatcher, request, method);
                        }
                    }
                    if (refresh != null && restart != null) {
                        for (Object presenter : presenterTargets) {
                            if (presenter != null) {
                                refreshPlayerPresenter(presenter, refresh, restart);
                            }
                        }
                    }
                }
            });
        } catch (Throwable t) {
            onFailure(null, t);
        }
    }

    /**
     * Twitch's native retry tail: re-fetch the manifest ({@code refresh}) then
     * force a player restart ({@code restart}).
     */
    private static void refreshPlayerPresenter(Object presenter, String refresh, String restart) {
        try {
            Method refreshMethod = presenter.getClass().getMethod(refresh);
            Method restartMethod = presenter.getClass().getMethod(restart);
            refreshMethod.setAccessible(true);
            restartMethod.setAccessible(true);
            refreshMethod.invoke(presenter);
            restartMethod.invoke(presenter);
            Log.i(TAG, "presenter refresh OK: " + presenter.getClass().getName()
                    + "#" + refresh + " then #" + restart
                    + " -> " + System.identityHashCode(presenter));
        } catch (Throwable t) {
            diag("presenter refresh FAIL: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            Log.w(TAG, "refreshPlayerPresenter failed", t);
            CrashReporter.report(TAG, "refreshPlayerPresenter failed", t);
        }
    }

    /**
     * Pushes {@code request} into {@code dispatcher} via its {@code method(Object)}
     * (Twitch's request-updater interface method), by reflection so no R8-renamed
     * name is baked into this class.
     */
    private static void pushRetry(Object dispatcher, Object request, String method) {
        try {
            Method m = pushMethod;
            if (m == null || !m.getDeclaringClass().isInstance(dispatcher)) {
                m = findPushMethod(dispatcher.getClass(), method);
                if (m == null) {
                    diag("no method '" + method + "(Object)' on " + dispatcher.getClass().getName()
                            + " methods=" + describeObjectMethods(dispatcher.getClass()));
                    return;
                }
                m.setAccessible(true);
                pushMethod = m;
            }
            m.invoke(dispatcher, request);
            Log.i(TAG, "push OK: " + dispatcher.getClass().getName() + "#" + m.getName()
                    + "(" + request.getClass().getName() + ") -> " + System.identityHashCode(dispatcher));
        } catch (Throwable t) {
            diag("push FAIL: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            Log.w(TAG, "pushRetry failed", t);
            CrashReporter.report(TAG, "pushRetry failed", t);
        }
    }

    /** Resolve {@code name(Object)V} across the class, its supers and all interfaces. */
    private static Method findPushMethod(Class<?> start, String name) {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, Object.class);
            } catch (NoSuchMethodException ignored) {
            }
            for (Class<?> itf : c.getInterfaces()) {
                try {
                    return itf.getDeclaredMethod(name, Object.class);
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
        return null;
    }

    private static String describeObjectMethods(Class<?> cls) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Method mm : cls.getMethods()) {
                Class<?>[] p = mm.getParameterTypes();
                if (p.length == 1 && p[0] == Object.class && mm.getReturnType() == void.class) {
                    sb.append(mm.getName()).append(' ');
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    /** Temporary long-toast + log diagnostics for bringing the reload push up. */
    private static void diag(final String text) {
        Log.i(TAG, text);
        try {
            final Context c = AppContext.get();
            if (c == null) {
                return;
            }
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Toast.makeText(c, "RTReload: " + text, Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void toast(final String text) {
        try {
            final Context c = AppContext.get();
            if (c == null) {
                return;
            }
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Toast.makeText(c, text, Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // View-tree watcher (button injection + placement)
    // ------------------------------------------------------------------

    private static void attachWatcher(Activity activity) {
        try {
            if (activity == null || !activity.getClass().getName().startsWith("tv.twitch")) {
                return;
            }
            Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            final View root = window.findViewById(android.R.id.content);
            // Guard on the decor: AppCompat hands android.R.id.content to its own frame
            // after onActivityCreated, so a tag on the content view let onResume add a second listener.
            View decor = window.getDecorView();
            if (root == null || decor.getTag(LISTENER_TAG_KEY) != null) {
                return;
            }
            root.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            long t0 = UiStallLog.start();
                            try {
                                scanPass(root);
                                update(root);
                                updateFeed(root);
                                updateClips(root);
                            } catch (Throwable t) {
                                onFailure(root.getContext(), t);
                            } finally {
                                passViews = null;
                                passStrip = null;
                            }
                            UiStallLog.end("Reload", t0);
                        }
                    });
            decor.setTag(LISTENER_TAG_KEY, Boolean.TRUE);
        } catch (Throwable t) {
            Log.w(TAG, "attachWatcher failed", t);
            CrashReporter.report(TAG, "attachWatcher failed", t);
        }
    }

    /** This pass's views by id (all matches, tree order) for {@link #findShownById}; our strip, if any. */
    private static SparseArray<List<View>> passViews;
    private static View passStrip;

    /** One tree walk per layout pass for every lookup update/updateFeed make (was one walk per name). */
    private static void scanPass(View root) {
        Context ctx = root.getContext();
        SparseArray<List<View>> byId = new SparseArray<>();
        for (String[] names : new String[][]{SEEKBAR_NAMES, OVERLAY_NAMES, ANCHOR_NAMES, {"share_button", CLIP_PAGE}}) {
            for (String name : names) {
                int id = idOf(ctx, name);
                if (id != 0) {
                    byId.put(id, new ArrayList<View>());
                }
            }
        }
        passStrip = null;
        scan(root, byId);
        passViews = byId;
    }

    private static void scan(View v, SparseArray<List<View>> byId) {
        List<View> same = byId.get(v.getId());
        if (same != null) {
            same.add(v);
        }
        if (passStrip == null && Boolean.TRUE.equals(v.getTag(BUTTON_TAG_KEY))) {
            passStrip = v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                scan(group.getChildAt(i), byId);
            }
        }
    }

    /** One layout pass: keep exactly one reload button in the currently-shown live bottom bar. */
    private static void update(View root) {
        View existing = passStrip;
        RecordSheet.syncLabel(root, existing);

        // Live: reload + compressor; seekable VOD/clip player: chapters +
        // compressor. The strip is rebuilt when its set of buttons changes.
        int buttons = enabledButtons(isSeekablePlayer(root));
        stripButtons = buttons;
        if (buttons == 0) {
            if (existing != null) {
                detach(existing);
            }
            return;
        }
        if (existing != null && !Integer.valueOf(buttons).equals(existing.getTag(BUTTONS_TAG_KEY))) {
            detach(existing);
            existing = null;
        }

        if (existing != null) {
            ViewGroup exParent = existing.getParent() instanceof ViewGroup
                    ? (ViewGroup) existing.getParent() : null;
            if (exParent != null) {
                if (exParent.isShown()) {
                    // In the active bar: mirror the controls' fade by following
                    // the exact control we anchored to (rotate in portrait,
                    // fullscreen in theater). Falling back to any bottom-bar
                    // button would follow a GONE one (e.g. fullscreen in
                    // portrait) and wrongly hide us.
                    View ref = null;
                    Object aid = existing.getTag(ANCHOR_ID_TAG_KEY);
                    if (aid instanceof Integer) {
                        ref = childById(exParent, (Integer) aid);
                    }
                    if (ref == null || UiHider.hiddenPlayerButton(ref)) {
                        ref = firstAnchorSibling(exParent);
                    }
                    if (ref != null) {
                        existing.setVisibility(ref.getVisibility());
                    }
                    return;
                }
                // Our bar isn't on screen: either the controls are simply hidden
                // (keep the button, it reappears with them) or the layout changed
                // (portrait <-> theater) and we must move to the now-active bar.
                View target = findActiveAnchor(root);
                if (target == null || target.getParent() == exParent) {
                    return;
                }
                detach(existing);
                insertIntoChain((ViewGroup) target.getParent(), target);
                return;
            }
            detach(existing); // detached orphan; re-insert below
        }

        View anchor = findActiveAnchor(root);
        if (anchor == null || !(anchor.getParent() instanceof ViewGroup)) {
            logAnchorState(root, "no usable anchor");
            return;
        }
        ViewGroup parent = (ViewGroup) anchor.getParent();
        Log.i(TAG, "inserting: anchor=" + describe(anchor, anchor.getContext())
                + " into parent=" + describe(parent, parent.getContext())
                + " children=" + parent.getChildCount());
        insertIntoChain(parent, anchor);
    }

    /**
     * Splices the reload button into the bottom button row immediately to the
     * left of {@code anchor}. We take the sibling whose end is anchored to the
     * anchor's start and re-point it at our button, so the row reflows to make
     * room (same technique as {@link TimerHook}).
     *
     * <p>That neighbour is found by the <em>constraint graph</em>, not on-screen
     * geometry: in the portrait player only {@code rotate_button} is shown while
     * its left neighbour ({@code chat_mode_button}) is {@code GONE}, so a
     * geometry search finds nothing. Cloning the (possibly hidden) neighbour's
     * params glues our button to the anchor's left edge either way; when the
     * neighbour is hidden the slot is empty so there's nothing to overlap.
     *
     * <p>The windowed player's bottom bar is a plain {@link LinearLayout} with
     * no constraints to reflow, so there we just drop the button into the
     * anchor's slot instead.
     */
    private static void insertIntoChain(ViewGroup parent, View anchor) {
        try {
            int anchorId = anchor.getId();
            if (anchorId == View.NO_ID) {
                return;
            }
            if (parent instanceof LinearLayout) {
                insertIntoLinearRow((LinearLayout) parent, anchor);
                return;
            }
            // Prefer the on-screen neighbour (precise in theater where several
            // controls are laid out); fall back to the constraint-graph
            // neighbour (portrait: the only laid-out control is the rightmost).
            View neighbour = adjacentLeft(parent, anchor, null);
            boolean neighbourShown = neighbour != null;
            if (neighbour == null || countAnchorMatches(neighbour, anchorId) == 0) {
                neighbour = constraintLeftNeighbour(parent, anchorId, null);
                neighbourShown = false;
            }
            if (neighbour == null || countAnchorMatches(neighbour, anchorId) == 0) {
                return;
            }

            View button = createButton(parent.getContext());
            button.setId(View.generateViewId());
            ViewGroup.LayoutParams lp = cloneSiblingParams(neighbour);
            if (lp == null) {
                return;
            }
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT; // the strip may hold two buttons
            button.setLayoutParams(lp);
            parent.addView(button);

            // Only re-point our own left edge when the neighbour is actually on
            // screen; for a hidden neighbour its geometry is meaningless and the
            // cloned start-anchor already lands us against the empty slot.
            if (neighbourShown) {
                View leftOfNeighbour = adjacentLeft(parent, neighbour, button);
                if (leftOfNeighbour != null && leftOfNeighbour.getId() != View.NO_ID) {
                    retargetAnchors(button, leftOfNeighbour.getId(), CONSTRAINT_UNSET);
                    button.setLayoutParams(button.getLayoutParams());
                }
            }

            int changed = retargetAnchors(neighbour, anchorId, button.getId());
            if (changed == 0) {
                parent.removeView(button);
                return;
            }
            button.setTag(NEIGHBOUR_TAG_KEY, neighbour);
            button.setTag(ANCHOR_ID_TAG_KEY, Integer.valueOf(anchorId));
            neighbour.setLayoutParams(neighbour.getLayoutParams());
            button.setVisibility(anchor.getVisibility());
            parent.requestLayout();
        } catch (Throwable t) {
            onFailure(anchor.getContext(), t);
        }
    }

    /**
     * A sibling of {@code parent} whose end-anchor is {@code anchorId} (i.e. it
     * sits immediately left of the anchor in the constraint chain), regardless
     * of visibility. Skips the anchor itself and our own button.
     */
    private static View constraintLeftNeighbour(ViewGroup parent, int anchorId, View exclude) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c == exclude || c.getId() == anchorId || c.getId() == View.NO_ID
                    || Boolean.TRUE.equals(c.getTag(BUTTON_TAG_KEY))) {
                continue;
            }
            if (countAnchorMatches(c, anchorId) > 0) {
                return c;
            }
        }
        return null;
    }

    /**
     * Drops the reload button into a {@link LinearLayout} bottom bar at
     * {@code anchor}'s index (i.e. just before the fullscreen/sound control),
     * cloning the anchor's own layout params so it matches the row's sizing.
     */
    private static void insertIntoLinearRow(LinearLayout parent, View anchor) {
        int index = parent.indexOfChild(anchor);
        if (index < 0) {
            return;
        }
        View button = createButton(parent.getContext());
        button.setId(View.generateViewId());
        ViewGroup.LayoutParams lp = cloneSiblingParams(anchor);
        if (lp == null) {
            lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        button.setLayoutParams(lp);
        button.setTag(ANCHOR_ID_TAG_KEY, Integer.valueOf(anchor.getId()));
        button.setVisibility(anchor.getVisibility());
        parent.addView(button, index);
        parent.requestLayout();
    }

    /**
     * Removes our button and, if it was spliced into a constraint chain, restores
     * the neighbour's original end anchor (both the neighbour and the original
     * anchor id were stashed as tags at splice time). Safe for LinearLayout
     * inserts too (no tags -> just a plain remove).
     */
    private static void detach(View button) {
        try {
            Object nb = button.getTag(NEIGHBOUR_TAG_KEY);
            Object aid = button.getTag(ANCHOR_ID_TAG_KEY);
            if (nb instanceof View && aid instanceof Integer && button.getId() != View.NO_ID) {
                View neighbour = (View) nb;
                retargetAnchors(neighbour, button.getId(), (Integer) aid);
                neighbour.setLayoutParams(neighbour.getLayoutParams());
            }
            ViewGroup parent = button.getParent() instanceof ViewGroup
                    ? (ViewGroup) button.getParent() : null;
            if (parent != null) {
                parent.removeView(button);
                parent.requestLayout();
            }
        } catch (Throwable t) {
            onFailure(button.getContext(), t);
        }
    }

    /**
     * Bit set of the strip's buttons: 1 = reload (live), 2 = audio compressor, 4 = chapters (VOD),
     * 8 = ambilight, 16 = player stats, 32 = chat offset (VOD), 64 = mirror, 128 = download (VOD/clip),
     * 256 = record (live), 512 = chat on and off ({@link ChatOff}).
     */
    private static int enabledButtons(boolean vod) {
        return (!vod && RefreshSettings.isButtonEnabled() ? 1 : 0)
                | (Toggles.on(Toggles.COMPRESSOR_BUTTON) ? 2 : 0)
                | (vod && Toggles.on(Toggles.CHAPTERS_BUTTON) ? 4 : 0)
                | (Toggles.on(Toggles.AMBILIGHT) ? 8 : 0)
                | (Toggles.on(Toggles.STATS_BUTTON) ? 16 : 0)
                | (vod && Toggles.on(Toggles.VOD_CHAT_SYNC) ? 32 : 0)
                | (Toggles.on(Toggles.MIRROR_BUTTON) ? 64 : 0)
                | (vod && Toggles.on(Toggles.DOWNLOAD_BUTTON) ? 128 : 0)
                | (!vod && Toggles.on(Toggles.LIVE_RECORD) ? 256 : 0)
                | (Toggles.on(Toggles.CHAT_OFF) ? 512 : 0);
    }

    /** {@link #enabledButtons} of the current layout pass, for {@link #createButton}. */
    private static int stripButtons;

    /** Tags our vertical button column in the discovery feed (kept apart from the bar strip). */
    private static final int FEED_TAG_KEY = 0x7e700d35;

    /**
     * The discovery feed's inline player has no bottom bar, just a column of
     * round buttons on the right (share / mute / overflow in
     * {@code playing_overlay}). Our reload + compressor buttons go in a
     * vertical column right above {@code share_button}, positioned every
     * layout pass (the overlay is a ConstraintLayout we don't add constraints
     * to) and following its visibility.
     */
    private static void updateFeed(View root) {
        Context ctx = root.getContext();
        View share = findShownById(root, idOf(ctx, "share_button"));
        ViewGroup overlay = share != null && share.getParent() instanceof ViewGroup
                ? (ViewGroup) share.getParent() : null;
        if (overlay == null || overlay.getId() != idOf(ctx, "playing_overlay")) {
            return;
        }
        int buttons = enabledButtons(false) & 3; // reload + compressor
        View column = null;
        for (int i = 0; i < overlay.getChildCount(); i++) {
            if (overlay.getChildAt(i).getTag(FEED_TAG_KEY) != null) {
                column = overlay.getChildAt(i);
            }
        }
        if (column != null && !Integer.valueOf(buttons).equals(column.getTag(FEED_TAG_KEY))) {
            overlay.removeView(column);
            column = null;
        }
        if (buttons == 0) {
            return;
        }
        if (column == null) {
            column = createStrip(ctx, buttons, LinearLayout.VERTICAL);
            column.setTag(BUTTON_TAG_KEY, null); // not the bar strip update() manages
            column.setTag(FEED_TAG_KEY, Integer.valueOf(buttons));
            overlay.addView(column, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        column.setVisibility(share.getVisibility());
        column.setX(share.getX() + (share.getWidth() - column.getWidth()) / 2f);
        column.setY(share.getY() - column.getHeight());
    }

    private static final String CLIP_PAGE = "portrait_clip_player_theatre_container";
    /** Tags our download button on a clip feed page. */
    private static final int CLIP_TAG_KEY = 0x7e700d36;

    /**
     * The vertical clip feed (a pager of {@code portrait_clip_player_theatre_container}
     * pages, its buttons in Compose) has no player bar: our download button sits on each page
     * in the top-right corner, level with Twitch's close button -- off the video of a landscape
     * clip. One per page, every layout pass.
     */
    private static void updateClips(View root) {
        List<View> pages = passViews == null ? null : passViews.get(idOf(root.getContext(), CLIP_PAGE));
        if (pages == null) {
            return;
        }
        boolean on = Toggles.on(Toggles.DOWNLOAD_BUTTON);
        for (View page : pages) {
            if (!(page instanceof ViewGroup)) {
                continue;
            }
            ViewGroup group = (ViewGroup) page;
            View button = null;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (group.getChildAt(i).getTag(CLIP_TAG_KEY) != null) {
                    button = group.getChildAt(i);
                }
            }
            if (!on) {
                if (button != null) {
                    group.removeView(button);
                }
                continue;
            }
            if (button == null) {
                button = VodDownload.createButton(page.getContext());
                button.setTag(CLIP_TAG_KEY, Boolean.TRUE);
                group.addView(button, new ViewGroup.LayoutParams(dp(page.getContext(), 48), dp(page.getContext(), 48)));
            }
            button.setX(page.getWidth() - dp(page.getContext(), 56));
            button.setY(dp(page.getContext(), 3));
        }
    }

    /** Our strip of player buttons (reload, audio compressor) as one view. */
    private static View createButton(Context context) {
        return createStrip(context, stripButtons, LinearLayout.HORIZONTAL);
    }

    private static View createStrip(Context context, int buttons, int orientation) {
        LinearLayout strip = new LinearLayout(context);
        strip.setOrientation(orientation);
        strip.setGravity(android.view.Gravity.CENTER);
        strip.setTag(BUTTON_TAG);
        strip.setTag(BUTTON_TAG_KEY, Boolean.TRUE);
        strip.setTag(BUTTONS_TAG_KEY, Integer.valueOf(buttons));
        if ((buttons & 8) != 0) {
            strip.addView(Ambilight.createButton(context));
        }
        if ((buttons & 64) != 0) {
            strip.addView(VideoMirror.createButton(context));
        }
        if ((buttons & 16) != 0) {
            strip.addView(PlayerStats.createButton(context));
        }
        if ((buttons & 32) != 0) {
            strip.addView(VodChatSync.createButton(context));
        }
        if ((buttons & 4) != 0) {
            strip.addView(VodChapters.createButton(context));
        }
        if ((buttons & 128) != 0) {
            strip.addView(VodDownload.createButton(context));
        }
        if ((buttons & 256) != 0) {
            strip.addView(RecordSheet.createButton(context));
        }
        if ((buttons & 512) != 0) {
            strip.addView(ChatOff.createButton(context));
        }
        if ((buttons & 2) != 0) {
            strip.addView(AudioCompressor.createButton(context));
        }
        if ((buttons & 1) != 0) {
            ImageView button = new ImageView(context);
            button.setImageDrawable(new RefreshDrawable(dp(context, 24)));
            button.setScaleType(ImageView.ScaleType.CENTER);
            button.setContentDescription(Strings.reloadButtonTalkback());
            button.setClickable(true);
            button.setFocusable(true);
            button.setBackground(borderlessBackground(context));
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    reloadPlayer();
                }
            });
            strip.addView(button, new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
        }
        return strip;
    }

    // ------------------------------------------------------------------
    // View-tree helpers (mirrors TimerHook -- proven chain-splice placement)
    // ------------------------------------------------------------------

    /**
     * True if this window hosts a seekable (VOD/clip) player, detected by a
     * laid-out, visible scrubber. Live streams never inflate one.
     */
    private static boolean isSeekablePlayer(View root) {
        Context ctx = root.getContext();
        for (String name : SEEKBAR_NAMES) {
            if (findShownById(root, idOf(ctx, name)) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves the bottom-bar anchor in the <em>currently shown</em> player
     * overlay. Both the portrait and theater bottom bars live under
     * {@code player_overlay} at once, so we deliberately look up the on-screen
     * instance ({@link #findShownById}) rather than {@code findViewById}.
     */
    private static View findActiveAnchor(View root) {
        Context ctx = root.getContext();
        View scope = root;
        for (String name : OVERLAY_NAMES) {
            View overlay = findShownById(root, idOf(ctx, name));
            if (overlay != null) {
                scope = overlay;
                break;
            }
        }
        for (String name : ANCHOR_NAMES) {
            View v = findShownById(scope, idOf(ctx, name));
            if (v != null && v.getParent() instanceof ViewGroup
                    && isUsableAnchor((ViewGroup) v.getParent(), v)) {
                return v;
            }
        }
        return null;
    }

    /**
     * True if {@code anchor} is something we can actually splice against right
     * now: a {@link LinearLayout} child (drop in at its slot), or -- in a
     * constraint row -- a view with a laid-out left neighbour end-anchored to
     * it (so the row reflows). Bottom-bar buttons toggle {@code GONE} at
     * runtime, so this lets {@link #findActiveAnchor} skip a present-but-unspliceable
     * candidate (e.g. the sound button when its own left neighbour is hidden)
     * and fall through to the next one.
     */
    private static boolean isUsableAnchor(ViewGroup parent, View anchor) {
        if (anchor.getId() == View.NO_ID) {
            return false;
        }
        if (parent instanceof LinearLayout) {
            return ((LinearLayout) parent).indexOfChild(anchor) >= 0;
        }
        View neighbour = adjacentLeft(parent, anchor, null);
        if (neighbour != null && countAnchorMatches(neighbour, anchor.getId()) > 0) {
            return true;
        }
        return constraintLeftNeighbour(parent, anchor.getId(), null) != null;
    }

    private static int idOf(Context ctx, String name) {
        try {
            return UiHider.id(ctx, name);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * First descendant of {@code root} (inclusive) whose id is {@code id} and
     * which is actually on screen ({@link View#isShown()} and laid out). Lets us
     * pick the live instance when several same-id views coexist (e.g. the
     * portrait and theater bottom bars are both inflated at once). Reads this
     * pass's {@link #scanPass} result instead of walking the tree again.
     */
    private static View findShownById(View root, int id) {
        List<View> all = id != 0 && root != null && passViews != null ? passViews.get(id) : null;
        if (all == null) {
            return null;
        }
        for (View v : all) {
            if (v.isShown() && v.getWidth() > 0 && v.getHeight() > 0 && isWithin(v, root)) {
                return v;
            }
        }
        return null;
    }

    private static boolean isWithin(View v, View root) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) {
            if (p == root) {
                return true;
            }
        }
        return false;
    }

    /**
     * A bottom-bar button (by {@link #ANCHOR_NAMES}) that is a direct child of
     * {@code parent}, used purely as a visibility reference so our button fades
     * in/out with the rest of the controls row.
     */
    private static View childById(ViewGroup parent, int id) {
        if (id == 0 || id == View.NO_ID) {
            return null;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getId() == id) {
                return child;
            }
        }
        return null;
    }

    private static View firstAnchorSibling(ViewGroup parent) {
        Context ctx = parent.getContext();
        for (String name : ANCHOR_NAMES) {
            int id = idOf(ctx, name);
            if (id == 0) {
                continue;
            }
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                if (child.getId() == id && !UiHider.hiddenPlayerButton(child)) {
                    return child;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // TEMPORARY diagnostics (placement / portrait bring-up) -- remove once stable
    // ------------------------------------------------------------------

    /**
     * Dumps every on-tree instance of the candidate anchors (and the two bottom
     * bars + settings gear) with its shown/size/parent state, so we can see why
     * a given orientation has no usable anchor. Throttled to once every 3s.
     */
    private static void logAnchorState(View root, String why) {
        long now = android.os.SystemClock.uptimeMillis();
        if (lastAnchorDump != 0L) { // once per process: a diagnostic for layout changes, not a log stream
            return;
        }
        lastAnchorDump = now;
        Context ctx = root.getContext();
        String[] names = {
                "player_overlay", "player_overlay_container",
                "bottom_player_overlay_controls",
                "fullscreen_button", "mute_button", "rotate_button", "settings_button",
        };
        Log.i(TAG, "anchor dump (" + why + "):");
        for (String n : names) {
            int id = idOf(ctx, n);
            List<View> all = new ArrayList<>();
            if (id != 0) {
                collectById(root, id, all);
            }
            if (all.isEmpty()) {
                Log.i(TAG, "  " + n + ": (none)");
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (View v : all) {
                sb.append(describe(v, ctx)).append(' ');
            }
            Log.i(TAG, "  " + n + ": " + sb);
        }
    }

    private static void collectById(View v, int id, List<View> out) {
        if (v == null) {
            return;
        }
        if (v.getId() == id) {
            out.add(v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectById(g.getChildAt(i), id, out);
            }
        }
    }

    /** Compact "ClassName#id_name shown WxH parent=Class/id" for the diagnostic. */
    private static String describe(View v, Context ctx) {
        if (v == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(v.getClass().getSimpleName()).append('#').append(idName(ctx, v.getId()))
                .append(v.isShown() ? " shown " : " hidden ")
                .append(v.getWidth()).append('x').append(v.getHeight());
        Object p = v.getParent();
        if (p instanceof View) {
            View pv = (View) p;
            sb.append(" parent=").append(pv.getClass().getSimpleName())
                    .append('/').append(idName(ctx, pv.getId()));
        }
        return sb.toString();
    }

    private static String idName(Context ctx, int id) {
        if (id == View.NO_ID || id == 0) {
            return "no-id";
        }
        try {
            return ctx.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "0x" + Integer.toHexString(id);
        }
    }

    /**
     * Finds the laid-out sibling on {@code ref}'s row whose right edge touches
     * {@code ref}'s left edge (contiguous, immediately to the left). Our own
     * button ({@code exclude}) and zero-size children are skipped.
     */
    private static View adjacentLeft(ViewGroup parent, View ref, View exclude) {
        int refLeft = ref.getLeft();
        int refCy = (ref.getTop() + ref.getBottom()) / 2;
        int refH = Math.max(1, ref.getHeight());
        int tolerance = dp(parent.getContext(), 12);
        View best = null;
        int bestRight = Integer.MIN_VALUE;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child == ref || child == exclude
                    || child.getWidth() <= 0 || child.getHeight() <= 0) {
                continue;
            }
            int cy = (child.getTop() + child.getBottom()) / 2;
            if (Math.abs(cy - refCy) > refH / 2) {
                continue; // not on the same row
            }
            int right = child.getRight();
            if (child.getLeft() < refLeft
                    && right <= refLeft + tolerance && right >= refLeft - tolerance
                    && right > bestRight) {
                best = child;
                bestRight = right;
            }
        }
        return best;
    }

    /** Counts (without mutating) the int LayoutParams anchor fields on {@code v} equal to {@code id}. */
    private static int countAnchorMatches(View v, int id) {
        return forEachAnchor(v, id, id, false);
    }

    /** Rewrites every anchor int field on {@code v} equal to {@code fromId} to {@code toId}. */
    private static int retargetAnchors(View v, int fromId, int toId) {
        return forEachAnchor(v, fromId, toId, true);
    }

    private static int forEachAnchor(View v, int fromId, int toId, boolean write) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) {
            return 0;
        }
        int count = 0;
        for (Class<?> k = lp.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    if (f.getInt(lp) == fromId) {
                        if (write) {
                            f.setInt(lp, toId);
                        }
                        count++;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return count;
    }

    /**
     * Clones {@code sibling}'s runtime LayoutParams (whatever R8 named the
     * ConstraintLayout params class) via its {@code (int,int)} ctor + copying
     * every primitive/String field. Same approach as {@link TimerHook} /
     * {@link PickerHook}.
     */
    private static ViewGroup.LayoutParams cloneSiblingParams(View sibling) {
        ViewGroup.LayoutParams src = sibling.getLayoutParams();
        if (src == null) {
            return null;
        }
        ViewGroup.LayoutParams clone;
        try {
            Constructor<? extends ViewGroup.LayoutParams> ctor =
                    src.getClass().getConstructor(int.class, int.class);
            clone = ctor.newInstance(
                    src.width > 0 ? src.width : ViewGroup.LayoutParams.WRAP_CONTENT,
                    src.height > 0 ? src.height : ViewGroup.LayoutParams.WRAP_CONTENT);
        } catch (Throwable t) {
            Log.w(TAG, "no (int,int) ctor on " + src.getClass().getName(), t);
            CrashReporter.report(TAG, "no (int,int) ctor on " + src.getClass().getName(), t);
            return null;
        }
        for (Class<?> k = src.getClass();
                k != null && ViewGroup.LayoutParams.class.isAssignableFrom(k);
                k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Class<?> type = f.getType();
                if (!type.isPrimitive() && type != String.class) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    f.set(clone, f.get(src));
                } catch (Throwable ignored) {
                }
            }
        }
        return clone;
    }

    private static Drawable borderlessBackground(Context context) {
        try {
            TypedValue outValue = new TypedValue();
            context.getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, outValue, true);
            if (outValue.resourceId != 0) {
                return context.getResources().getDrawable(outValue.resourceId, context.getTheme());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    private static void onFailure(Context context, Throwable t) {
        Log.w(TAG, "reload hook failure", t);
        CrashReporter.report(TAG, "reload hook failure", t);
        if (toastedFailure) {
            return;
        }
        toastedFailure = true;
        try {
            Context c = context != null ? context : AppContext.get();
            if (c != null) {
                Toast.makeText(c, Strings.reloadFailedToast(), Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    private static final class Callbacks implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            attachWatcher(activity);
        }

        @Override
        public void onActivityResumed(Activity activity) {
            attachWatcher(activity);
            if (AutoRecord.waiting()) {
                RecordService.refresh(activity); // auto-record's wait starts from the foreground only
            }
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

    /**
     * A simple white circular-refresh glyph (arc + arrowhead) drawn in code so
     * we don't have to inject a drawable resource (the extension dex is
     * code-only), mirroring {@link TimerHook}'s {@code ClockDrawable}.
     */
    private static final class RefreshDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private final int sizePx;

        RefreshDrawable(int sizePx) {
            this.sizePx = sizePx;
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public int getIntrinsicWidth() {
            return sizePx;
        }

        @Override
        public int getIntrinsicHeight() {
            return sizePx;
        }

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float radius = Math.min(b.width(), b.height()) / 2f;
            if (radius <= 0f) {
                return;
            }
            float stroke = Math.max(2.5f, radius * 0.16f);
            float headHalf = stroke * 1.55f;   // half-width of the arrowhead (radial)
            float headLen = headHalf * 1.9f;   // length of the arrowhead (tangential)
            // Keep the whole glyph (ring stroke + arrowhead) inside the bounds.
            float ring = radius - headHalf - stroke * 0.5f;
            if (ring <= 0f) {
                return;
            }

            // Almost-full circle with a gap at the top where the arrowhead sits
            // (the classic "reload"/"refresh" glyph, drawn clockwise).
            final float startAngle = 100f;
            final float sweep = 300f;
            final float endAngle = startAngle + sweep;
            RectF oval = new RectF(cx - ring, cy - ring, cx + ring, cy + ring);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            canvas.drawArc(oval, startAngle, sweep, false, paint);

            // Solid triangular arrowhead at the leading (end) tip, its base laid
            // across the stroke radially and its point aimed along the tangent so
            // it reads as forward rotation.
            double e = Math.toRadians(endAngle);
            float radCos = (float) Math.cos(e);
            float radSin = (float) Math.sin(e);
            float tanX = -radSin;   // +tangent (direction of increasing angle)
            float tanY = radCos;
            float ax = cx + ring * radCos;
            float ay = cy + ring * radSin;
            arrow.reset();
            arrow.moveTo(ax + radCos * headHalf, ay + radSin * headHalf);   // outer base
            arrow.lineTo(ax + tanX * headLen, ay + tanY * headLen);         // tip
            arrow.lineTo(ax - radCos * headHalf, ay - radSin * headHalf);   // inner base
            arrow.close();
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(arrow, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
