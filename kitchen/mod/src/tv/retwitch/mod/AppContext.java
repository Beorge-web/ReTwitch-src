package tv.retwitch.mod;

import android.app.Application;
import android.content.Context;
import java.lang.reflect.Method;

/**
 * Grabs the process-wide {@link Application} context via reflection.
 *
 * Some of our injection points (e.g. the usher URL builder lambda) only
 * ever hand us plain data objects (a {@link android.net.Uri}), not a
 * {@link Context}. Reading/writing {@link android.content.SharedPreferences}
 * from there still needs one, so we fetch the app's own long-lived
 * Application instance instead of threading a Context through every hook.
 * {@code android.app.ActivityThread#currentApplication()} is a stable,
 * public-since-API-1 static method that every Android process can call on
 * itself; it is not part of Twitch's (obfuscated) code, so this keeps
 * working across app updates.
 */
public final class AppContext {

    private static volatile Context cached;

    private AppContext() {
    }

    public static Context get() {
        Context context = cached;
        if (context != null) {
            return context;
        }
        context = fromActivityThread();
        if (context == null) {
            context = fromAppGlobals();
        }
        if (context != null) {
            cached = context;
        }
        return context;
    }

    private static Context fromActivityThread() {
        try {
            Class<?> clazz = Class.forName("android.app.ActivityThread");
            Method method = clazz.getMethod("currentApplication");
            Object app = method.invoke(null);
            return (app instanceof Application) ? (Application) app : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Context fromAppGlobals() {
        try {
            Class<?> clazz = Class.forName("android.app.AppGlobals");
            Method method = clazz.getMethod("getInitialApplication");
            Object app = method.invoke(null);
            return (app instanceof Application) ? (Application) app : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
