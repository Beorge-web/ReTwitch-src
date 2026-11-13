package tv.retwitch.mod;

import android.util.Log;

/**
 * Keeps channels in Twitch's native theatre: 31.x routes live channels into a
 * React Native theatre where none of the mod's native hooks (chat emotes and
 * badges, player buttons, usher proxy/VAFT) run. {@code ClassicTheatrePatch}
 * passes every {@code RNTheatreRouteDecision} through {@link #route}; turning a
 * {@code Route} answer into a refusal makes Twitch fall back to the native
 * theatre. No way back to the RN theatre: none of the mod's patches reach it.
 */
public final class RnTheatreHook {

    private static final String TAG = "RTRnTheatre";

    private RnTheatreHook() {
    }

    /**
     * Injected after each {@code rnTheatreRouteDecision(...)} call. Never throws.
     * Callers only compare with {@code Route}; the refusal is named
     * {@code RefuseUltralightOff} in 31.8.0 and {@code RefuseUltralightUnsupported} in 31.9.0.
     */
    public static Object route(Object decision) {
        try {
            if (decision instanceof Enum && "Route".equals(((Enum<?>) decision).name())) {
                for (Object c : ((Enum<?>) decision).getDeclaringClass().getEnumConstants()) {
                    if (((Enum<?>) c).name().startsWith("RefuseUltralight")) return c;
                }
                throw new IllegalStateException("no RefuseUltralight* constant");
            }
        } catch (Throwable t) {
            Log.w(TAG, "route failed, keeping Twitch's decision", t);
            CrashReporter.report(TAG, "route failed, keeping Twitch's decision", t);
        }
        return decision;
    }
}
