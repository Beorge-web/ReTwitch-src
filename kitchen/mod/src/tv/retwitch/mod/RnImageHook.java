package tv.retwitch.mod;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live stream previews on React Native screens (the Following tab) refresh
 * ({@code RnLivePreviewsPatch}). The GQL preview URL of a stream never changes
 * ({@code static-cdn.jtvnw.net/previews-ttv/live_user_<login>-440x248.jpg}) and Fresco caches by
 * URI without expiry, so a card kept its first frame across refreshes and app restarts. Preview
 * URIs carry a generation ({@link #generation}): {@code rt=<generation>} for Fresco, and the size in
 * the path nudged by a few pixels for the CDN. The CDN keeps a frame {@code max-age=300} and leaves
 * the query out of its cache key, but renders any {@code WxH} fresh: a size nobody asked for in the
 * last 5 minutes is the current frame.
 *
 * <p>React sends {@code source} to native only when it changes, so a mounted card never sees the
 * same URL again: a new generation re-applies the source of the attached preview views. It comes
 * every {@link #PERIOD_MS} while a preview is on screen, and on a refresh: the Following pull is a JS
 * gesture of the stories shelf, not a RefreshControl, seen by its GQL refetch ({@link #onRequest});
 * a RefreshControl ending ({@code SwipeRefreshLayoutManager.setRefreshing(false)}) does the same.
 *
 * <p>With the toggle off a preview still gets {@code rt=} (no timer, no nudged size): Fresco keeps
 * whatever came first for the URI, and a channel that had just gone live gets the CDN's
 * {@code 404_preview} redirect, so the grey placeholder would stay until the cache is cleared.
 *
 * <p>The mod is compiled without React Native classes: RN's bridge types go through reflection
 * (all names below are kept in 31.8.0). Everything but {@link #onRequest} runs on the UI thread
 * (view prop updates).
 */
public final class RnImageHook {

    private static final String TAG = "ReTwitch/RnImage";
    private static final String PREVIEW = "static-cdn.jtvnw.net/previews-ttv/";
    /** Operation refetched by the Following pull (with the mount of the tab). */
    private static final String FOLLOWING_QUERY = "MwebFollowingTabSections";
    private static final long PERIOD_MS = 2 * 60 * 1000L;
    private static final Pattern SIZE = Pattern.compile("-(\\d+)x(\\d+)\\.");

    /** Seconds-based so a restart never reuses a URI Fresco has on disk from an earlier session. UI thread. */
    private static long generation = System.currentTimeMillis() / 1000L;
    private static boolean ticking;
    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            // ticking stays set during refresh: its onSetSource calls must not post a second timer.
            if (refresh()) {
                main().postDelayed(this, PERIOD_MS);
            } else {
                ticking = false;
            }
        }
    };

    /** Preview image views -> {ReactImageManager, original source}. */
    private static final Map<View, Object[]> previews = new WeakHashMap<View, Object[]>();

    /** Set last by {@link #hasPreview}: non-null means the reflection below is ready on any thread. */
    private static volatile Method size;
    private static Method getMap;
    private static Method getString;
    private static Method deepClone;
    private static Method putString;
    private static Method fromList;
    private static Method managerSetSource;
    private static Method managerAfterUpdate;
    /** Set on the first failure: previews then behave as stock for the session. */
    private static boolean broken;

    private RnImageHook() {
    }

    /**
     * Injected at the entry of {@code ReactImageManager.setSource(view, ReadableArray)}: returns the
     * source to use, the same object unless it has a live preview. Never throws.
     */
    public static Object onSetSource(Object manager, View view, Object sources) {
        if (sources == null || broken) {
            return sources;
        }
        try {
            if (!hasPreview(sources)) {
                previews.remove(view); // a recycled view now showing something else
                return sources;
            }
            if (!Toggles.on(Toggles.FRESH_RN_PREVIEWS)) {
                previews.remove(view);
                return withBucket(sources, generation, false);
            }
            previews.put(view, new Object[]{manager, sources});
            if (!ticking) {
                ticking = true;
                main().postDelayed(TICK, PERIOD_MS);
            }
            return withBucket(sources, generation, true);
        } catch (Throwable t) {
            fail("onSetSource", t);
            return sources;
        }
    }

    /**
     * Injected at the entry of {@code SwipeRefreshLayoutManager.setRefreshing(view, Z)} (the prop
     * and the {@code setNativeRefreshing} command): a finished refresh reloads stale previews.
     * Never throws.
     */
    public static void onRefreshing(boolean refreshing) {
        if (!refreshing) {
            refresh();
        }
    }

    /**
     * Injected at the entry of {@code NetworkingModule.sendRequestInternalReal} with its body map
     * (off the UI thread): the Following refetch reloads stale previews right away, they do not
     * depend on its response. Never throws.
     */
    public static void onRequest(Object data) {
        if (data == null || broken || size == null) {
            return;
        }
        try {
            String body = (String) getString.invoke(data, "string");
            if (body != null && body.contains(FOLLOWING_QUERY)) {
                main().post(RnImageHook::refresh);
            }
        } catch (Throwable t) {
            fail("onRequest", t);
        }
    }

    /**
     * New generation, re-applied to the preview views on screen, UI thread. False when no preview
     * view is left (the timer then stops until the next preview source); views off screen are kept,
     * a tab shown again gets the next tick (React does not re-send their source).
     */
    private static boolean refresh() {
        if (broken || previews.isEmpty()) {
            return false;
        }
        try {
            if (!Toggles.on(Toggles.FRESH_RN_PREVIEWS)) {
                return false;
            }
            generation = Math.max(generation + 1, System.currentTimeMillis() / 1000L);
            // Snapshot: re-applying goes back through onSetSource, which updates the map.
            for (View view : new ArrayList<View>(previews.keySet())) {
                Object[] entry = previews.get(view);
                if (view == null || entry == null || !view.isAttachedToWindow()) {
                    continue;
                }
                if (view.getWindowVisibility() != View.VISIBLE) {
                    continue; // app in background
                }
                if (managerSetSource == null) {
                    for (Method m : entry[0].getClass().getMethods()) {
                        if (m.getName().equals("setSource") && m.getParameterTypes().length == 2) {
                            managerSetSource = m;
                        }
                    }
                    managerAfterUpdate = entry[0].getClass().getMethod("onAfterUpdateTransaction", View.class);
                }
                managerSetSource.invoke(entry[0], view, entry[1]);
                managerAfterUpdate.invoke(entry[0], view);
            }
            return !previews.isEmpty();
        } catch (Throwable t) {
            fail("refresh", t);
            return false;
        }
    }

    /** True for a live stream preview URI (static-cdn previews-ttv). */
    static boolean isPreview(String uri) {
        return uri != null && uri.contains(PREVIEW);
    }

    /**
     * A live preview URI for a generation: {@code rt=} added, the {@code WxH} in the path grown by
     * 0..3 px each way (16 sizes in turn, under 1% off the aspect). Any other URI comes back unchanged.
     */
    static String bucketed(String uri, long generation) {
        if (!isPreview(uri)) {
            return uri;
        }
        Matcher m = SIZE.matcher(uri);
        if (m.find(uri.indexOf(PREVIEW))) {
            int k = (int) (generation & 15);
            uri = uri.substring(0, m.start()) + "-" + (Integer.parseInt(m.group(1)) + (k & 3))
                    + "x" + (Integer.parseInt(m.group(2)) + (k >> 2)) + uri.substring(m.end() - 1);
        }
        return withGeneration(uri, generation);
    }

    /** A live preview URI with {@code rt=} only: a new Fresco key, the same CDN frame. */
    static String withGeneration(String uri, long generation) {
        // shortcut: appends after a #fragment too; preview URLs from GQL have none.
        return uri + (uri.indexOf('?') < 0 ? "?rt=" : "&rt=") + generation;
    }

    private static boolean hasPreview(Object sources) throws Exception {
        if (size == null) {
            Class<?> array = Class.forName("com.facebook.react.bridge.ReadableArray");
            Class<?> map = Class.forName("com.facebook.react.bridge.ReadableMap");
            Class<?> javaMap = Class.forName("com.facebook.react.bridge.JavaOnlyMap");
            getMap = array.getMethod("getMap", int.class);
            getString = map.getMethod("getString", String.class);
            deepClone = javaMap.getMethod("deepClone", map);
            putString = javaMap.getMethod("putString", String.class, String.class);
            fromList = Class.forName("com.facebook.react.bridge.JavaOnlyArray").getMethod("from", List.class);
            size = array.getMethod("size");
        }
        int n = (Integer) size.invoke(sources);
        for (int i = 0; i < n; i++) {
            Object map = getMap.invoke(sources, i);
            if (map != null && isPreview((String) getString.invoke(map, "uri"))) {
                return true;
            }
        }
        return false;
    }

    /** A JavaOnlyArray of JavaOnlyMap copies (all keys kept) with the preview URIs of a generation. */
    private static Object withBucket(Object sources, long bucket, boolean nudge) throws Exception {
        int n = (Integer) size.invoke(sources);
        List<Object> copy = new ArrayList<Object>(n);
        for (int i = 0; i < n; i++) {
            Object map = getMap.invoke(sources, i);
            Object clone = null;
            if (map != null) {
                clone = deepClone.invoke(null, map);
                String uri = (String) getString.invoke(map, "uri");
                if (isPreview(uri)) {
                    putString.invoke(clone, "uri", nudge ? bucketed(uri, bucket) : withGeneration(uri, bucket));
                }
            }
            copy.add(clone);
        }
        return fromList.invoke(null, copy);
    }

    /** Not a static field: the class is loaded by the JVM self-check, where Looper is a stub. */
    private static Handler main() {
        return new Handler(Looper.getMainLooper());
    }

    private static void fail(String where, Throwable t) {
        broken = true;
        Log.w(TAG, where + " failed, live preview refresh off", t);
        CrashReporter.report(TAG, where + " failed, live preview refresh off", t);
    }
}
