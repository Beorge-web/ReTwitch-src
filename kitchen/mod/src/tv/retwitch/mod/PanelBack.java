package tv.retwitch.mod;

import android.app.Activity;
import android.os.Build;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Back (key or swipe) in the landscape theatre while a poll or prediction is open in the side
 * chat ({@link Toggles#WIDE_CHAT_PANELS}): closes it instead of leaving the theatre, and the chat
 * goes back to its width. A highlight expanded over the chat collapses by Twitch's own tap on
 * its banner; the channel points sheet ({@code community_points_container}, header
 * "‹ Prediction ⋮ ✕") closes by its ✕. Hooked only while such a panel is open and one of the
 * two is there (from {@link UiHider}'s layout pass) -- otherwise Back stays Twitch's: an
 * {@code OnBackInvokedCallback} for predictive back (API 33+, Twitch targets 36) and a
 * {@link Window.Callback} proxy for the legacy key event.
 */
final class PanelBack {

    private static final String TAG = "RTPanelBack";

    private static Activity host;
    /** What a tap closes the open panel with: the highlight's banner or the sheet's ✕. */
    private static View target;
    private static Object invoked; // OnBackInvokedCallback; Object so API < 33 never loads it
    private static Window.Callback original;

    private PanelBack() {
    }

    /** From the layout pass: {@code open} is the expanded poll/prediction in a side chat, or null. */
    static void update(Activity activity, View open) {
        View found = open != null ? closer(activity, open) : null;
        if (found != null && host != activity) {
            release();
            host = activity;
            install(activity);
        } else if (found == null && host != null) {
            release();
        }
        target = found;
    }

    /** The banner of the highlight holding {@code open}, else the ✕ of the points sheet holding it; or null. */
    private static View closer(Activity activity, View open) {
        int banner = UiHider.id(activity, "highlight_item_banner_container");
        int dismiss = UiHider.id(activity, "dismiss_button");
        for (View v = open; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            View found = banner != 0 ? v.findViewById(banner) : null;
            if (found == null && dismiss != 0) {
                found = v.findViewById(dismiss);
            }
            if (found != null && found.isShown()) {
                return found;
            }
        }
        return null;
    }

    private static void install(Activity activity) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                OnBackInvokedCallback callback = PanelBack::collapse;
                activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
                invoked = callback;
            }
            Window window = activity.getWindow();
            final Window.Callback current = window.getCallback();
            if (current != null) {
                original = current;
                window.setCallback((Window.Callback) Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),
                        new Class<?>[]{Window.Callback.class}, new InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                                if ("dispatchKeyEvent".equals(method.getName()) && target != null
                                        && ((KeyEvent) args[0]).getKeyCode() == KeyEvent.KEYCODE_BACK) {
                                    if (((KeyEvent) args[0]).getAction() == KeyEvent.ACTION_UP) {
                                        collapse();
                                    }
                                    return true;
                                }
                                try {
                                    return method.invoke(current, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                            }
                        }));
            }
        } catch (Throwable t) {
            Log.w(TAG, "install failed", t);
            CrashReporter.report(TAG, "install failed", t);
        }
    }

    private static void release() {
        Activity activity = host;
        host = null;
        try {
            if (activity != null && Build.VERSION.SDK_INT >= 33 && invoked != null) {
                activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) invoked);
            }
            if (activity != null && original != null && Proxy.isProxyClass(activity.getWindow().getCallback().getClass())) {
                activity.getWindow().setCallback(original);
            }
        } catch (Throwable t) {
            Log.w(TAG, "release failed", t);
        }
        invoked = null;
        original = null;
        target = null;
    }

    /** Taps the banner or the ✕: Twitch closes the panel. */
    private static void collapse() {
        try {
            View found = target;
            if (found == null) {
                return;
            }
            if (!found.performClick() && found instanceof ViewGroup && ((ViewGroup) found).getChildCount() > 0) {
                ((ViewGroup) found).getChildAt(0).performClick();
            }
            Log.i(TAG, "closed the panel on back");
        } catch (Throwable t) {
            Log.w(TAG, "collapse failed", t);
            CrashReporter.report(TAG, "collapse failed", t);
        }
    }
}
