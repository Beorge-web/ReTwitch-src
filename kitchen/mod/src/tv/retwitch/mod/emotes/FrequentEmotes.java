package tv.retwitch.mod.emotes;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import tv.retwitch.mod.CrashReporter;

/**
 * Twitch emotes sent from our autocomplete count towards Twitch's "Frequently Used"
 * row, like ones tapped in Twitch's own picker (ReVive FrequentEmotes). Twitch counts
 * at send time, but only emotes clicked in its picker since the last send: the picker
 * pushes {@code EmoteClicked(emoteModel)} to the frequent-emotes repository's event
 * dispatcher, and the repository's {@code (String message)} step matches the message
 * against them. {@link #beforeSend} (injected at the start of that step,
 * PickerExtrasPatch) pushes the same event for every noted emote that is in the text;
 * Twitch then counts, caps and stores them itself.
 * Favourites need nothing: they sit in Twitch's picker, whose clicks Twitch counts.
 * shortcut: notes every Twitch emote our autocomplete list showed, so one typed by hand
 * after it was suggested counts too; hook the dropdown's tap if that matters.
 */
public final class FrequentEmotes {

    private static final String TAG = "RTEmotes";
    private static final String DISPATCHER = "tv.twitch.android.core.mvp.viewdelegate.EventDispatcher";
    private static final int MAX_PENDING = 200;

    /** Token -> Twitch emote id of emotes our UI offered since the last send. */
    private static final Map<String, String> PENDING = new LinkedHashMap<>();

    private FrequentEmotes() {
    }

    /** Remembers a Twitch emote model our UI offered ({@code Generic(id, token)}); callers check the toggle. */
    static void note(Object model) {
        String[] idToken = EmoteModelReflect.idAndToken(model);
        if (idToken == null || EmoteModelReflect.decodeImageId(idToken[0]) != null) {
            return;
        }
        synchronized (PENDING) {
            if (PENDING.size() >= MAX_PENDING) {
                PENDING.clear();
            }
            PENDING.put(idToken[1], idToken[0]);
        }
    }

    /**
     * @param repository the frequent-emotes repository
     * @param message    the message being sent
     * @param classes    {@code "<EmoteClicked>|<Generic emote model>"} (smali types)
     */
    public static void beforeSend(Object repository, String message, String classes) {
        try {
            Map<String, String> offered;
            synchronized (PENDING) {
                if (PENDING.isEmpty()) {
                    return;
                }
                offered = new LinkedHashMap<>(PENDING);
                PENDING.clear(); // one send consumes the list, like Twitch's own clicked-emotes list
            }
            if (message == null || classes == null || repository == null) {
                return;
            }
            String[] types = classes.split("\\|");
            Object dispatcher = dispatcher(repository);
            Method push = dispatcher == null ? null : pushMethod(dispatcher.getClass());
            Constructor<?> event = types.length == 2 ? oneArgConstructor(Class.forName(EmoteModelReflect.dotted(types[0])))
                    : null;
            if (push == null || event == null) {
                return;
            }
            Set<String> words = new HashSet<>(Arrays.asList(message.split(" ")));
            int counted = 0;
            for (Map.Entry<String, String> e : offered.entrySet()) {
                if (!words.contains(e.getKey())) {
                    continue;
                }
                Object model = EmoteModelReflect.buildEmoteModel(types[1], Emote.twitch(e.getKey(), e.getValue()));
                if (model != null) {
                    push.invoke(dispatcher, event.newInstance(model));
                    counted++;
                }
            }
            if (counted > 0) {
                Log.i(TAG, "counted " + counted + " emote(s) towards Frequently Used");
            }
        } catch (Throwable t) {
            Log.w(TAG, "frequent emotes push failed", t);
            CrashReporter.report(TAG, "frequent emotes push failed", t);
        }
    }

    private static Object dispatcher(Object repository) throws IllegalAccessException {
        for (Field f : repository.getClass().getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && f.getType().getName().equals(DISPATCHER)) {
                f.setAccessible(true);
                return f.get(repository);
            }
        }
        return null;
    }

    /** {@code EventDispatcher}'s push: its only public {@code (Object) -> void} method. */
    private static Method pushMethod(Class<?> dispatcherClass) {
        Method found = null;
        for (Method m : dispatcherClass.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getReturnType() == void.class && p.length == 1 && p[0] == Object.class) {
                if (found != null) {
                    return null;
                }
                found = m;
            }
        }
        return found;
    }

    private static Constructor<?> oneArgConstructor(Class<?> type) {
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            if (c.getParameterTypes().length == 1) {
                c.setAccessible(true);
                return c;
            }
        }
        return null;
    }
}
