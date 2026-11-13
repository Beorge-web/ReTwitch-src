package tv.retwitch.mod;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Overrides for Twitch's Eppo feature flags ({@code ExperimentsPatch}). Native flags are Kotlin objects
 * with a key and a typed default; their single evaluator asks {@link #enter} first and reports the
 * evaluated value to {@link #exit}. React Native reads the same overrides from the
 * {@code eppoFlagOverrides} host prop ({@link #rnOverrides}). An override is stored as
 * {@code <type>:<value>} with type {@code b} (boolean), {@code i} (integer), {@code d} (numeric),
 * {@code s} (string) or {@code j} (JSON, React Native only).
 */
public final class ExperimentHook {

    private static final String TAG = "RTExperiments";
    static final String PREFS = "retwitch_experiments";

    /** What the evaluator saw of a native flag this session. */
    static final class Flag {
        final String key;
        final String name;
        /** b, i, d, s; 0 for a flag whose value is not a plain value (JSON), which is not overridden. */
        final char type;
        final Object fallback;
        volatile Object value;

        Flag(String key, String name, char type, Object fallback) {
            this.key = key;
            this.name = name;
            this.type = type;
            this.fallback = fallback;
        }
    }

    /** Flag class -> what it is; flags are singletons, one entry per class. */
    private static final ConcurrentHashMap<Class<?>, Flag> FLAGS = new ConcurrentHashMap<>();
    /** Flag key -> native flag, for the settings page. */
    static final ConcurrentHashMap<String, Flag> SEEN = new ConcurrentHashMap<>();
    private static final ThreadLocal<Flag> CURRENT = new ThreadLocal<>();
    /** key -> stored override; null until prefs could be read. */
    private static volatile Map<String, String> overrides;

    private ExperimentHook() {
    }

    /** Injected at the evaluator's entry: the value to return instead, or null. Never throws. */
    public static Object enter(Object flag) {
        try {
            Flag f = flag(flag);
            if (f == null) {
                return null;
            }
            Map<String, String> o = overrides();
            Object value = o.isEmpty() ? null : parse(o.get(f.key), f.type);
            if (value != null) {
                f.value = value;
                return value;
            }
            CURRENT.set(f);
        } catch (Throwable t) {
            Log.w(TAG, "enter failed", t);
        }
        return null;
    }

    /** Injected before the evaluator returns: the value Twitch evaluated. Never throws. */
    public static void exit(Object value) {
        try {
            Flag f = CURRENT.get();
            if (f != null) {
                f.value = value;
                CURRENT.set(null);
            }
        } catch (Throwable ignored) {
            // display only
        }
    }

    private static Flag flag(Object flag) throws IllegalAccessException {
        if (flag == null) {
            return null;
        }
        Class<?> cls = flag.getClass();
        Flag f = FLAGS.get(cls);
        if (f == null) {
            f = describe(flag);
            if (f == null) {
                return null;
            }
            FLAGS.put(cls, f);
            SEEN.put(f.key, f);
        }
        return f;
    }

    /** Reads the base class fields: one String (the key) and the Object default/fallback. */
    private static Flag describe(Object flag) throws IllegalAccessException {
        for (Class<?> c = flag.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            String key = null;
            List<Object> values = new ArrayList<>();
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (field.getType() == String.class) {
                    field.setAccessible(true);
                    key = (String) field.get(flag);
                } else if (field.getType() == Object.class) {
                    field.setAccessible(true);
                    values.add(field.get(flag));
                }
            }
            if (key == null || values.size() != 2) {
                continue;
            }
            char type = typeOf(values.get(0));
            if (type != typeOf(values.get(1))) {
                type = 0; // JSON flags keep a sentinel next to the default
            }
            return new Flag(key, String.valueOf(flag), type, values.get(1));
        }
        return null;
    }

    static char typeOf(Object value) {
        if (value instanceof Boolean) {
            return 'b';
        }
        if (value instanceof Integer) {
            return 'i';
        }
        if (value instanceof Double) {
            return 'd';
        }
        if (value instanceof String) {
            return 's';
        }
        return 0;
    }

    /** The stored override as the native flag's type, or null. */
    static Object parse(String stored, char type) {
        if (stored == null || stored.length() < 2 || stored.charAt(0) != type || stored.charAt(1) != ':') {
            return null;
        }
        String raw = stored.substring(2);
        try {
            switch (type) {
                case 'b':
                    return Boolean.valueOf(raw);
                case 'i':
                    return Integer.valueOf(raw.trim());
                case 'd':
                    return Double.valueOf(raw.trim());
                case 's':
                    return raw;
                default:
                    return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Map<String, String> overrides() {
        Map<String, String> o = overrides;
        if (o != null) {
            return o;
        }
        Context context = AppContext.get();
        if (context == null) {
            return Collections.emptyMap(); // too early; read on a later call
        }
        Map<String, String> loaded = new HashMap<>();
        for (Map.Entry<String, ?> e : prefs(context).getAll().entrySet()) {
            if (e.getValue() instanceof String) {
                loaded.put(e.getKey(), (String) e.getValue());
            }
        }
        overrides = Collections.unmodifiableMap(loaded);
        return overrides;
    }

    /** Stores an override ({@code <type>:<value>}), or removes it for null. Applies after a restart. */
    static void set(Context context, String key, String stored) {
        SharedPreferences.Editor edit = prefs(context).edit();
        if (stored == null) {
            edit.remove(key);
        } else {
            edit.putString(key, stored);
        }
        edit.apply();
        overrides = null;
    }

    static void clear(Context context) {
        prefs(context).edit().clear().apply();
        overrides = null;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Adds every override, typed, to the React Native flag overrides object. */
    static void rnOverrides(JSONObject into) {
        for (Map.Entry<String, String> e : overrides().entrySet()) {
            String stored = e.getValue();
            if (stored.length() < 2 || stored.charAt(1) != ':') {
                continue;
            }
            String raw = stored.substring(2);
            try {
                switch (stored.charAt(0)) {
                    case 'b':
                        into.put(e.getKey(), Boolean.parseBoolean(raw));
                        break;
                    case 'i':
                        into.put(e.getKey(), Long.parseLong(raw.trim()));
                        break;
                    case 'd':
                        into.put(e.getKey(), Double.parseDouble(raw.trim()));
                        break;
                    case 's':
                        into.put(e.getKey(), raw);
                        break;
                    case 'j':
                        into.put(e.getKey(), new JSONTokener(raw).nextValue());
                        break;
                    default:
                        break;
                }
            } catch (Exception ex) {
                Log.w(TAG, "bad override " + e.getKey(), ex);
            }
        }
    }
}
