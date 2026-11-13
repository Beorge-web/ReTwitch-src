package tv.retwitch.mod.emotes;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import tv.retwitch.mod.AppContext;
import tv.retwitch.mod.CrashReporter;
import tv.retwitch.mod.Strings;
import tv.retwitch.mod.Toggles;

/**
 * Favourite emotes, like PurpleTV: a long press on one in the emote picker adds it
 * to a "Favorites" section at the top of the picker ({@link EmotePickerBridge}), a
 * long press on a favourite removes it. 7TV/BTTV/FFZ emotes are kept by code,
 * Twitch and sub emotes by code + Twitch id (shown through Twitch's own model, so
 * they look and send like in Twitch's sections). One list for all channels, or one
 * per channel ({@link Toggles#EMOTE_FAVORITES_PER_CHANNEL}).
 * Taking over the long press on Twitch emotes means Twitch's own long-press popup
 * (emote modifiers) doesn't open while favourites are on.
 */
public final class EmoteFavorites {

    private static final String TAG = "RTEmotes";
    /** Section title; plain ASCII like the other picker sections (see EmoteModelReflect). */
    static final String TITLE = "Favorites";

    private EmoteFavorites() {
    }

    /**
     * Injected right after the picker builds its {@code LongClick(emoteView,
     * clickedEmote)} event (EmotePickerPatch). Toggles the emote's favourite
     * state; anything that isn't an emote model falls through to Twitch.
     *
     * @return true when handled (the event is not dispatched). Never throws.
     */
    public static boolean onLongClick(Object event) {
        try {
            if (!Toggles.on(Toggles.EMOTE_FAVORITES)) {
                return false;
            }
            String code = ownEmoteCode(event);
            String twitchId = null;
            if (code == null) {
                String[] twitch = twitchEmote(event);
                if (twitch == null) {
                    return false;
                }
                twitchId = twitch[0];
                code = twitch[1];
            }
            boolean added = EmoteSettings.toggleFavorite(code, twitchId);
            EmotePickerBridge.refreshFavorites();
            Context context = AppContext.get();
            if (context != null) {
                Toast.makeText(context, added
                        ? Strings.pickf("«%s» в избранном", "\"%s\" added to favorites", code)
                        : Strings.pickf("«%s» убран из избранного",
                        "\"%s\" removed from favorites", code), Toast.LENGTH_SHORT).show();
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "favorite long press failed", t);
            CrashReporter.report(TAG, "favorite long press failed", t);
            return false;
        }
    }

    /** The favourites still available on this channel, newest first, or null if none. */
    static EmoteCache.EmoteGroup group(String channelId) {
        if (!Toggles.on(Toggles.EMOTE_FAVORITES)) {
            return null;
        }
        List<Emote> emotes = new ArrayList<>();
        for (String entry : EmoteSettings.getFavorites()) {
            String code = EmoteSettings.favoriteCode(entry);
            Emote emote = code.length() == entry.length() ? EmoteCache.lookup(code, channelId)
                    : Emote.twitch(code, entry.substring(code.length() + 1));
            if (emote != null) {
                emotes.add(emote);
            }
        }
        return emotes.isEmpty() ? null : new EmoteCache.EmoteGroup(TITLE, emotes);
    }

    /**
     * Code of the clicked emote if it is one of ours: the event holds the
     * clicked emote, which holds Twitch's emote model built by
     * {@link EmoteModelReflect} (id {@code rtemote:<url>}, token = code).
     */
    private static String ownEmoteCode(Object event) throws IllegalAccessException {
        for (Object clicked : fieldValues(event)) {
            for (Object model : fieldValues(clicked)) {
                String id = null;
                String token = null;
                for (Object value : fieldValues(model)) {
                    if (!(value instanceof String)) {
                        continue;
                    }
                    if (EmoteModelReflect.decodeImageId((String) value) != null) {
                        id = (String) value;
                    } else if (token == null && !((String) value).isEmpty()) {
                        token = (String) value;
                    }
                }
                if (id != null) {
                    return token;
                }
            }
        }
        return null;
    }

    /** {@code {id, token}} of the clicked Twitch emote: its model in the event or its clicked emote. */
    private static String[] twitchEmote(Object event) throws IllegalAccessException {
        for (Object clicked : fieldValues(event)) {
            String[] found = twitchIdAndToken(clicked);
            if (found != null) {
                return found;
            }
            for (Object model : fieldValues(clicked)) {
                found = twitchIdAndToken(model);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String[] twitchIdAndToken(Object model) {
        String[] found = EmoteModelReflect.anyIdAndToken(model);
        return found == null || EmoteModelReflect.decodeImageId(found[0]) != null ? null : found;
    }

    /** Non-null instance field values of {@code o} (whole class chain), skipping our own and JDK types' internals. */
    private static List<Object> fieldValues(Object o) throws IllegalAccessException {
        List<Object> out = new ArrayList<>();
        if (o == null || o instanceof String || o.getClass().getName().startsWith("java.")) {
            return out;
        }
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                    continue;
                }
                f.setAccessible(true);
                Object value = f.get(o);
                if (value != null) {
                    out.add(value);
                }
            }
        }
        return out;
    }
}
