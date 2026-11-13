package tv.retwitch.mod;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;

import java.lang.ref.WeakReference;

/**
 * Decides which discovery-feed pager tab is selected by default on a normal
 * app launch.
 *
 * <p>Twitch builds the pager pages as {@code [Following, Live, Clips]} and
 * uses a small "default position" provider that returns {@code 1} (Live) as
 * the fallback when no specific page was requested (deeplink/push still pass
 * their own index, which is left untouched). A one-instruction patch on that
 * provider (see {@code LandingTabPatch.kt}) routes its result through here.
 *
 * <p>When the ReTwitch toggle is on (the default) we return {@code 0}, the
 * Following page's fixed index in that list, so the app lands on Following.
 * When off, we return whatever Twitch originally computed.
 *
 * <p>This is called directly from injected smali with no surrounding
 * try/catch, so -- per the mod's fail-soft rule -- any error here must be
 * swallowed and the original value returned rather than propagated into
 * Twitch (a throw here would crash the whole main screen on launch).
 */
public final class LandingTabHook {

    private static final String TAG = "RTlandingtab";

    /** Index of the Following page inside Twitch's discovery-feed pager list. */

    private LandingTabHook() {
    }

    /**
     * Injected right before the discovery-feed view state is constructed with
     * {@code (position, z, z, z, pages, ...)}: returns the index of the
     * Following page ({@code DiscoveryFeedPage$FollowingPage}, a public model
     * class) when the toggle is on -- its position changed between Twitch
     * versions, so it's looked up rather than hardcoded. Never throws.
     */
    public static int resolveDefaultPosition(int original, boolean a, boolean b, boolean c, java.util.List<?> pages) {
        try {
            if (LandingSettings.isFollowingDefaultEnabled()) {
                for (int i = 0; pages != null && i < pages.size(); i++) {
                    Object page = pages.get(i);
                    if (page != null && page.getClass().getName().endsWith("$FollowingPage")) {
                        return i;
                    }
                }
                // The initial state is built with an empty page list: Following is the first tab.
                return 0;
            }
        } catch (Throwable t) {
            Log.w(TAG, "resolveDefaultPosition failed, keeping original tab", t);
            CrashReporter.report(TAG, "resolveDefaultPosition failed, keeping original tab", t);
        }
        return original;
    }

    private static final String RN_TAB_KEY = "tv.twitch.android.rn.feed.subfeedTab";
    private static WeakReference<Object> rnHome;
    /** The RN home opens on Following: the Live content the launch splash waits for won't come. */
    private static volatile boolean startedOnFollowing;

    /** Injected at the entry of {@code DiscoveryFeedRNFragment.onCreateView}. Never throws. */
    public static void onRnHomeCreated(Object fragment) {
        rnHome = new WeakReference<>(fragment);
    }

    /** Injected after the React Native home reads its starting tab argument (31.x). Never throws. */
    public static String resolveRnTab(String tab) {
        try {
            if (!LandingSettings.isFollowingDefaultEnabled()) {
                return tab;
            }
            // Native passes its default page explicitly ("live").
            if (tab == null || "live".equals(tab)) {
                // The argument too: a re-attached home view (back from settings) re-emits it when it
                // differs from the last tab sent, and "live" would switch the home to Live.
                Object fragment = rnHome != null ? rnHome.get() : null;
                Bundle args = fragment != null
                        ? (Bundle) fragment.getClass().getMethod("getArguments").invoke(fragment) : null;
                if (args != null) {
                    args.putString(RN_TAB_KEY, "following");
                }
                tab = "following";
            }
            startedOnFollowing = "following".equals(tab);
        } catch (Throwable t) {
            Log.w(TAG, "resolveRnTab failed, keeping original tab", t);
            CrashReporter.report(TAG, "resolveRnTab failed, keeping original tab", t);
        }
        return tab;
    }

    /** Injected before the content-ready holder's getters return: what the launch splash waits for. */
    public static boolean feedReady(boolean ready) {
        return ready || startedOnFollowing && followingShown();
    }

    /** The RN pager lays the tabs out side by side, Following first: mounted and at 0. */
    private static boolean followingShown() {
        try {
            Object fragment = rnHome != null ? rnHome.get() : null;
            View root = fragment != null ? (View) fragment.getClass().getMethod("getView").invoke(fragment) : null;
            HorizontalScrollView pager = root != null ? findPager(root) : null;
            return pager != null && pager.getScrollX() == 0;
        } catch (Throwable t) {
            Log.w(TAG, "pager check failed", t);
            return false;
        }
    }

    /** Depth first: the category chips scroll sideways too, but inside the pager. */
    private static HorizontalScrollView findPager(View view) {
        if (view instanceof HorizontalScrollView) {
            HorizontalScrollView scroll = (HorizontalScrollView) view;
            if (scroll.getChildCount() > 0 && scroll.getChildAt(0).getWidth() >= 2 * scroll.getWidth()) {
                return scroll;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                HorizontalScrollView pager = findPager(group.getChildAt(i));
                if (pager != null) {
                    return pager;
                }
            }
        }
        return null;
    }
}
