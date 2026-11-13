package tv.retwitch.mod;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One-shot, on-demand reflection dump of the real (obfuscated) Twitch
 * Application/Dagger object graph, used to re-locate classes whose original
 * tv.twitch.android.* package moved or was renamed between the Twitch
 * version the "monolith" bridge (MotherModule/PurpleTVAppContainer) was
 * written against and the version currently being rebuilt.
 *
 * Triggered manually (from the "ReTwitch" settings row) so it never runs
 * during normal app startup. Every reflective call is individually
 * try/caught so a single misbehaving provider can't take the whole probe
 * (or the app) down; still, this *does* eagerly resolve every Dagger
 * singleton binding, which is inherently a bit invasive -- that's why it's
 * opt-in only.
 */
public final class DiagProbe {
    private static final String TAG = "RTdiag";

    private DiagProbe() {
    }

    public static void dumpAsync(final Context context) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    dump(context);
                } catch (Throwable t) {
                    Log.e(TAG, "probe crashed", t);
                    CrashReporter.report(TAG, "probe crashed", t);
                }
            }
        }, "RTdiag-probe").start();
    }

    private static void dump(Context context) {
        Application app = (Application) context.getApplicationContext();
        Log.i(TAG, "==== BEGIN app=" + app.getClass().getName() + " ====");

        Object bestComponent = null;
        int bestFieldCount = -1;

        for (Field f : app.getClass().getDeclaredFields()) {
            String type = f.getType().getName();
            if (f.getType().isPrimitive()) {
                Log.i(TAG, "app.field " + f.getName() + " : " + type + " (primitive, skipped)");
                continue;
            }
            Object value;
            try {
                f.setAccessible(true);
                value = f.get(app);
            } catch (Throwable t) {
                Log.w(TAG, "app.field " + f.getName() + " : " + type + " (unreadable: " + t + ")");
                continue;
            }
            if (value == null) {
                Log.i(TAG, "app.field " + f.getName() + " : " + type + " = null");
                continue;
            }
            Log.i(TAG, "app.field " + f.getName() + " : " + type + " -> value class " + value.getClass().getName());
            if (isBoring(value.getClass())) continue;

            for (Method m : value.getClass().getDeclaredMethods()) {
                if (m.getParameterTypes().length != 0) continue;
                if (m.getReturnType().isPrimitive() || m.getReturnType() == void.class) continue;
                try {
                    m.setAccessible(true);
                    Object result = m.invoke(value);
                    if (result == null) continue;
                    int fieldCount = result.getClass().getDeclaredFields().length;
                    Log.i(TAG, "  candidate: " + f.getName() + "." + m.getName() + "() -> "
                            + result.getClass().getName() + " (" + fieldCount + " declared fields)");
                    if (fieldCount > bestFieldCount) {
                        bestFieldCount = fieldCount;
                        bestComponent = result;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        if (bestComponent == null) {
            Log.e(TAG, "no component-like object found (nothing with a 0-arg accessor)");
            Log.i(TAG, "==== END (no component) ====");
            return;
        }
        Log.i(TAG, "picked component = " + bestComponent.getClass().getName() + " with " + bestFieldCount + " fields");

        Field[] fields = bestComponent.getClass().getDeclaredFields();
        List<Object> resolvedValues = new ArrayList<Object>();
        Map<String, Integer> classFrequency = new HashMap<String, Integer>();
        int i = 0;
        for (Field f : fields) {
            i++;
            try {
                f.setAccessible(true);
                Object raw = f.get(bestComponent);
                if (raw == null) {
                    Log.i(TAG, "[" + i + "/" + fields.length + "] " + f.getName() + " (" + f.getType().getSimpleName() + ") = null");
                    continue;
                }
                Object resolved = tryGet(raw);
                if (resolved == null) {
                    Log.i(TAG, "[" + i + "/" + fields.length + "] " + f.getName() + " (" + f.getType().getSimpleName()
                            + ") raw=" + raw.getClass().getName() + " get()=<failed/null>");
                } else {
                    Log.i(TAG, "[" + i + "/" + fields.length + "] " + f.getName() + " (" + f.getType().getSimpleName()
                            + ") -> " + resolved.getClass().getName());
                    resolvedValues.add(resolved);
                    String cn = resolved.getClass().getName();
                    Integer cnt = classFrequency.get(cn);
                    classFrequency.put(cn, cnt == null ? 1 : cnt + 1);
                }
            } catch (Throwable t) {
                Log.w(TAG, "[" + i + "/" + fields.length + "] " + f.getName() + " threw " + t);
            }
        }

        Log.i(TAG, "==== END dump (" + fields.length + " fields) ====");

        dumpShapeCandidates(resolvedValues, classFrequency);
    }

    /**
     * Second pass: scan resolved objects for small, "unique" (rarely repeated)
     * classes with a POJO-like shape (a couple of primitive/String fields) --
     * that's the profile of things like an account manager or device
     * identifier holder, as opposed to the many shared utility/plugin
     * singletons that get reused across dozens of bindings.
     */
    private static void dumpShapeCandidates(List<Object> values, Map<String, Integer> classFrequency) {
        Log.i(TAG, "==== BEGIN shape-candidate scan ====");
        Set<String> seen = new HashSet<String>();
        for (Object value : values) {
            Class<?> clazz = value.getClass();
            String cn = clazz.getName();
            if (!seen.add(cn)) continue; // already dumped this class
            Integer freq = classFrequency.get(cn);
            if (freq != null && freq > 4) continue; // shared/common utility, not a unique domain object
            if (cn.indexOf('.') >= 0 && !cn.startsWith("tv.twitch")) continue; // keep only obfuscated (no package) or tv.twitch.* classes
            if (isBoring(clazz)) continue;

            Field[] ownFields = clazz.getDeclaredFields();
            if (ownFields.length == 0 || ownFields.length > 12) continue;

            int scalarCount = 0;
            StringBuilder sb = new StringBuilder();
            sb.append(cn).append(" (freq=").append(freq).append(") fields: ");
            for (Field f : ownFields) {
                Class<?> t = f.getType();
                boolean scalar = t == int.class || t == long.class || t == boolean.class
                        || t == String.class || CharSequence.class.isAssignableFrom(t);
                if (scalar) scalarCount++;
                sb.append(f.getName()).append(':').append(t.getSimpleName());
                if (scalar) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(value);
                        sb.append('=').append(v);
                    } catch (Throwable ignored) {
                        sb.append("=<err>");
                    }
                }
                sb.append(' ');
            }
            if (scalarCount == 0) continue; // no primitive/String fields at all, unlikely to be a data holder we care about
            Log.i(TAG, sb.toString());
        }
        Log.i(TAG, "==== END shape-candidate scan ====");
    }

    private static Object tryGet(Object provider) {
        // Every Dagger Provider/DoubleCheck implementation seen in this build
        // exposes a literal get() method (javax.inject.Provider contract
        // survives minification since it's such a common short name).
        try {
            Method get = findMethod(provider.getClass(), "get");
            if (get != null) {
                get.setAccessible(true);
                return get.invoke(provider);
            }
        } catch (Throwable ignored) {
        }
        for (Method m : provider.getClass().getDeclaredMethods()) {
            if (m.getParameterTypes().length != 0) continue;
            if (m.getReturnType().isPrimitive() || m.getReturnType() == void.class) continue;
            try {
                m.setAccessible(true);
                Object result = m.invoke(provider);
                if (result != null) return result;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == 0) {
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static final Set<String> BORING_PREFIXES = new HashSet<String>();

    static {
        BORING_PREFIXES.add("java.");
        BORING_PREFIXES.add("android.");
        BORING_PREFIXES.add("androidx.");
        BORING_PREFIXES.add("kotlin.");
    }

    private static boolean isBoring(Class<?> clazz) {
        String name = clazz.getName();
        for (String prefix : BORING_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
}
