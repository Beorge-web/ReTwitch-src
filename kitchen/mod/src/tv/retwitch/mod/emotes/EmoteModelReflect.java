package tv.retwitch.mod.emotes;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.util.List;
import tv.retwitch.mod.CrashReporter;

/**
 * Reflection bridge to several of Twitch's own internal emote-model
 * classes (obfuscated names that shift on every Twitch build). Each patch that
 * uses this (see {@code AutocompletePatch.kt}, {@code EmotePickerPatch.kt})
 * resolves the *current* build's class name once at patch-build time (by
 * fingerprinting a constructor's {@code checkNotNullParameter} debug
 * strings) and bakes it into the injected bytecode as a plain string
 * constant -- this class never hardcodes an obfuscated FQN itself, it just
 * does the actual {@code Class.forName(...).newInstance(...)} at runtime
 * with whatever name it's handed.
 *
 * <p>Building genuine instances of Twitch's own model classes (rather than
 * some lookalike of our own) means every existing bit of Twitch UI that
 * already knows how to render/filter/insert them -- the autocomplete
 * adapter, the emote picker grid -- works completely unmodified.
 */
final class EmoteModelReflect {

    private static final String TAG = "RTEmotes";

    /** Marker prefix recognized by the {@code Lf3k.o()} image-URL patch. */
    private static final String IMAGE_URL_PREFIX = "rtemote:";

    /**
     * Twitch's {@code EmoteModel.Generic}-equivalent's real constructor:
     * {@code (String id, String token, <AssetType enum>, <Type enum>)}.
     *
     * <p>An earlier version of this reflected a convenience 2-arg
     * {@code (String, String)} overload seen while manually reading one
     * build's smali (it delegated to this same 4-arg constructor with
     * hardcoded default enum values) -- but that overload is Kotlin
     * default-parameter-value sugar with zero call sites of its own
     * inside Twitch's code, so R8 tree-shakes it away entirely on most
     * builds. Only the genuinely-used 4-arg constructor is guaranteed to
     * survive; the two enum arguments are resolved from the constructor's
     * own {@link Constructor#getParameterTypes()} at call time (picking
     * each enum type's first declared constant), so no enum class name or
     * constant name ever needs to be known ahead of time.
     */
    private static volatile Constructor<?> emoteModelCtor;

    /** Twitch's {@code EmoteOwner}-equivalent: {@code (int id, String displayName, String login, String profileUrl)}. */
    private static volatile Constructor<?> emoteOwnerCtor;

    /** Twitch's {@code OwnerEmoteSet}-equivalent: {@code (EmoteOwner owner, String setId, List<EmoteModel> emotes)}. */
    private static volatile Constructor<?> ownerEmoteSetCtor;

    private EmoteModelReflect() {
    }

    static String encodeImageId(String rawImageUrl) {
        return IMAGE_URL_PREFIX + rawImageUrl;
    }

    static String decodeImageId(String id) {
        if (id == null || !id.startsWith(IMAGE_URL_PREFIX)) {
            return null;
        }
        return id.substring(IMAGE_URL_PREFIX.length());
    }

    /** @return a new instance of Twitch's emote-model class for {@code emote}, or {@code null} on any reflection failure. */
    static Object buildEmoteModel(String emoteModelClassName, Emote emote) {
        try {
            Constructor<?> ctor = emoteModelCtor;
            if (ctor == null) {
                Class<?> modelClass = Class.forName(dotted(emoteModelClassName));
                ctor = findFourArgConstructor(modelClass);
                ctor.setAccessible(true);
                emoteModelCtor = ctor;
            }
            Class<?>[] paramTypes = ctor.getParameterTypes();
            Object assetType = paramTypes[2].getEnumConstants()[0];
            Object type = paramTypes[3].getEnumConstants()[0];
            // A Twitch emote (favourite) keeps its real id, so Twitch draws and counts it as its own.
            String id = emote.source == EmoteSource.TWITCH && emote.id != null ? emote.id : encodeImageId(emote.imageUrl);
            return ctor.newInstance(id, emote.code, assetType, type);
        } catch (Throwable t) {
            Log.w(TAG, "buildEmoteModel(" + emoteModelClassName + ") failed", t);
            CrashReporter.report(TAG, "buildEmoteModel(" + emoteModelClassName + ") failed", t);
            return null;
        }
    }

    /**
     * Finds the {@code (String, String, <enum>, <enum>)} constructor --
     * the class only ever has this one 4-arg constructor (plus, on some
     * builds, the dead 2-arg delegate described above, which never has 4
     * parameters), so matching on parameter count plus the two leading
     * {@code String} parameters is unambiguous without needing to know
     * either enum type's name.
     */
    private static Constructor<?> findFourArgConstructor(Class<?> modelClass) throws NoSuchMethodException {
        for (Constructor<?> c : modelClass.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 4 && p[0] == String.class && p[1] == String.class
                    && p[2].isEnum() && p[3].isEnum()) {
                return c;
            }
        }
        throw new NoSuchMethodException("no (String,String,enum,enum) constructor on " + modelClass);
    }

    /**
     * @return a new Twitch {@code OwnerEmoteSet}-equivalent instance
     *         wrapping {@code models}, whose section header will show
     *         {@code label} verbatim (see the class doc on why this
     *         picker uses this rather than the plainer
     *         {@code GenericEmoteSet}-equivalent, whose header text
     *         Twitch's own picker presenter hardcodes instead of taking
     *         from the set itself -- see {@code EmotePickerBridge}'s
     *         class doc), or {@code null} on any reflection failure.
     */
    static Object buildOwnerEmoteSet(String ownerClassName, String ownerSetClassName, String setId,
            String label, List<Object> models) {
        try {
            Constructor<?> ownerCtor = emoteOwnerCtor;
            if (ownerCtor == null) {
                // (id, displayName, login, profileUrl); the id is an int before
                // Twitch 31.x and a Tuid since.
                for (Constructor<?> c : Class.forName(dotted(ownerClassName)).getConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 4 && p[1] == String.class && p[2] == String.class && p[3] == String.class) {
                        ownerCtor = c;
                    }
                }
                if (ownerCtor == null) {
                    throw new NoSuchMethodException("EmoteOwner(id, String, String, String)");
                }
                ownerCtor.setAccessible(true);
                emoteOwnerCtor = ownerCtor;
            }
            Class<?> idType = ownerCtor.getParameterTypes()[0];
            Object ownerId = idType == int.class ? (Object) 0 : idType.getConstructor(int.class).newInstance(0);
            // Both display-name-like arguments are set to the same plain
            // ASCII `label` -- Twitch's own header-rendering code (see
            // `EmotePickerBridge`'s class doc) shows one of the two
            // verbatim when it's plain ASCII, only falling back to the
            // other (or combining both) for non-ASCII display names.
            // Passing the same value for both sidesteps needing to know
            // which one wins on any given build.
            Object owner = ownerCtor.newInstance(ownerId, label, label, null);

            Constructor<?> setCtor = ownerEmoteSetCtor;
            if (setCtor == null) {
                Class<?> ownerClass = ownerCtor.getDeclaringClass();
                setCtor = Class.forName(dotted(ownerSetClassName)).getConstructor(ownerClass, String.class, List.class);
                setCtor.setAccessible(true);
                ownerEmoteSetCtor = setCtor;
            }
            return setCtor.newInstance(owner, setId, models);
        } catch (Throwable t) {
            Log.w(TAG, "buildOwnerEmoteSet(" + ownerClassName + ", " + ownerSetClassName + ") failed", t);
            CrashReporter.report(TAG, "buildOwnerEmoteSet(" + ownerClassName + ", " + ownerSetClassName + ") failed", t);
            return null;
        }
    }

    /** {@code "Lfoo/bar/Baz;"} (smali type descriptor) -> {@code "foo.bar.Baz"} (reflection name). */
    private static final String GENERIC_HEADER = "Generic(id=";

    /**
     * {@code {id, token}} of Twitch's {@code Generic} emote model from its data-class
     * {@code toString()} ({@code "Generic(id=1, token=Kappa, assetType=.., type=..)"}), or null
     * for anything else. Never throws.
     */
    static String[] idAndToken(Object model) {
        try {
            String type = model == null ? "java." : model.getClass().getName();
            return type.startsWith("java.") || type.startsWith("android.") ? null : parseGeneric(model.toString());
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@link #idAndToken} that also takes channel emotes (follower, sub): {@code "WithOwner(id=1, token=Kappa, ownerId=.."}. */
    static String[] anyIdAndToken(Object model) {
        String type = model == null ? "java." : model.getClass().getName();
        String text = type.startsWith("java.") || type.startsWith("android.") ? "" : model.toString();
        String header = "WithOwner(id=";
        int token = text.startsWith(header) ? text.indexOf(", token=") : -1;
        int owner = token < 0 ? -1 : text.indexOf(", ownerId=", token);
        return owner < 0 ? idAndToken(model)
                : new String[]{text.substring(header.length(), token), text.substring(token + ", token=".length(), owner)};
    }

    static String[] parseGeneric(String text) {
        if (text == null || !text.startsWith(GENERIC_HEADER)) {
            return null;
        }
        int token = text.indexOf(", token=");
        int asset = token < 0 ? -1 : text.indexOf(", assetType=", token);
        if (asset < 0) {
            return null;
        }
        String id = text.substring(GENERIC_HEADER.length(), token);
        String code = text.substring(token + ", token=".length(), asset);
        return id.isEmpty() || code.isEmpty() ? null : new String[]{id, code};
    }

    static String dotted(String smaliType) {
        String t = smaliType;
        if (t.startsWith("L") && t.endsWith(";")) {
            t = t.substring(1, t.length() - 1);
        }
        return t.replace('/', '.');
    }
}
