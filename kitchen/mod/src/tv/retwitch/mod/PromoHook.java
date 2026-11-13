package tv.retwitch.mod;

import android.view.View;
import android.view.ViewTreeObserver;

import java.lang.reflect.Method;

/** Gate for {@code PromotionsPatch}: Turbo offers and subscription discount banners. */
public final class PromoHook {

    private PromoHook() {
    }

    /** Injected into the promotion entry points. Never throws ({@link Toggles#on} doesn't). */
    public static boolean blocked() {
        return Toggles.on(Toggles.HIDE_PROMOTIONS);
    }

    /** Injected after the "is the Turbo chip visible" type test of feed section headers. Never throws. */
    public static boolean turboIngress(boolean visible) {
        return visible && !blocked();
    }

    /**
     * Injected at the entry of {@code ConsentDataUpdated(userHasSubmittedConsent, ...)}: Twitch shows the cookie
     * dialog only while that is false, and nothing else reads it. Never throws.
     */
    public static boolean consentSubmitted(boolean submitted) {
        return submitted || Toggles.on(Toggles.HIDE_COOKIE_CONSENT);
    }

    /** Injected after {@code isGiftingEnabled()} where the chat header buttons are built. Never throws. */
    public static boolean giftButton(boolean enabled) {
        return enabled && !ChatHeaderSetting.giftHidden();
    }

    private static final String TURBO_BUTTON_QUERY = "query TurboUpsellButton";
    private static final String TURBO_PRODUCT = "productName: \\\"turbo\\\"";
    private static final String NO_PRODUCT = "productName: \\\"retwitch\\\"";

    private static volatile Method hasKey;
    private static Method getString;
    private static Method deepClone;
    private static Method putString;

    /**
     * Injected at the entry of React Native's {@code NetworkingModule} sender: {@code data} is the
     * request body map. The RN home's Turbo button mounts only if this query finds an offer; hiding
     * the mounted view leaves its Yoga box, which pushes the sort button to a row of its own.
     * The Following tab's query goes through {@link RnFollowingHook#withAllCategories} here too.
     * Never throws.
     */
    public static Object onRnRequestData(Object data) {
        try {
            boolean promos = blocked();
            boolean categories = Toggles.on(Toggles.ALL_FOLLOWED_CATEGORIES);
            if (data == null || !promos && !categories) {
                return data;
            }
            if (hasKey == null) {
                Class<?> map = Class.forName("com.facebook.react.bridge.ReadableMap");
                Class<?> javaMap = Class.forName("com.facebook.react.bridge.JavaOnlyMap");
                getString = map.getMethod("getString", String.class);
                deepClone = javaMap.getMethod("deepClone", map);
                putString = javaMap.getMethod("putString", String.class, String.class);
                hasKey = map.getMethod("hasKey", String.class);
            }
            if (!(Boolean) hasKey.invoke(data, "string")) {
                return data;
            }
            String body = (String) getString.invoke(data, "string");
            String rewritten = promos ? withoutTurboOffers(body) : body;
            if (categories) {
                rewritten = RnFollowingHook.withAllCategories(rewritten);
            }
            if (rewritten == body) {
                return data;
            }
            Object copy = deepClone.invoke(null, data);
            putString.invoke(copy, "string", rewritten);
            return copy;
        } catch (Throwable ignored) {
            return data; // never break RN's requests
        }
    }

    /** Asks the button's query (JSON body, may be batched) about a product that doesn't exist: {@code null}, no offers. */
    static String withoutTurboOffers(String body) {
        int query = body != null ? body.indexOf(TURBO_BUTTON_QUERY) : -1;
        int product = query >= 0 ? body.indexOf(TURBO_PRODUCT, query) : -1;
        return product < 0 ? body
                : body.substring(0, product) + NO_PRODUCT + body.substring(product + TURBO_PRODUCT.length());
    }

    private static final int HIDDEN_TAG_KEY = 0x7e700d43;

    /**
     * Injected at the end of React Native's {@code BaseViewManager.setTestId}:
     * hides the RN home's Turbo button ("following-turbo-upsell"); a view RN
     * recycles for another component gets its visibility back. Never throws.
     */
    public static void onRnTestId(View view, String testId) {
        try {
            if (view == null) {
                return;
            }
            Object keeper = view.getTag(HIDDEN_TAG_KEY);
            if (testId != null && testId.contains("turbo-upsell") && blocked()) {
                view.setVisibility(View.GONE);
                if (keeper == null) {
                    KeepHidden keep = new KeepHidden(view);
                    view.setTag(HIDDEN_TAG_KEY, keep);
                    view.addOnAttachStateChangeListener(keep);
                    if (view.isAttachedToWindow()) {
                        keep.onViewAttachedToWindow(view);
                    }
                }
            } else if (keeper instanceof KeepHidden) {
                view.setTag(HIDDEN_TAG_KEY, null);
                view.removeOnAttachStateChangeListener((KeepHidden) keeper);
                ((KeepHidden) keeper).onViewDetachedFromWindow(view);
                view.setVisibility(View.VISIBLE);
            }
        } catch (Throwable ignored) {
            // never break RN's prop updates
        }
    }

    /**
     * Fabric resets visibility from the RN "display" style on every layout
     * ({@code updateLayout}), after the testID was set: re-hide before drawing.
     */
    private static final class KeepHidden implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private final View view;
        private ViewTreeObserver observer;

        KeepHidden(View view) {
            this.view = view;
        }

        @Override
        public void onViewAttachedToWindow(View v) {
            if (observer == null) {
                observer = v.getViewTreeObserver();
                observer.addOnPreDrawListener(this);
            }
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            if (observer != null && observer.isAlive()) {
                observer.removeOnPreDrawListener(this);
            }
            observer = null;
        }

        @Override
        public boolean onPreDraw() {
            if (view.getVisibility() == View.GONE) {
                return true;
            }
            view.setVisibility(View.GONE);
            return false; // skip this frame, the next one draws without it
        }
    }
}
