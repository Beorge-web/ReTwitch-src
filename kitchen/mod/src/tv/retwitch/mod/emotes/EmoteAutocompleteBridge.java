package tv.retwitch.mod.emotes;

import android.util.Log;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import tv.retwitch.mod.CrashReporter;
import tv.retwitch.mod.ChannelTracker;
import tv.retwitch.mod.Toggles;

/**
 * Injected around Twitch's own {@code :code} chat-input autocomplete
 * lookup (see {@code AutocompletePatch.kt}, fingerprinted method
 * historically named {@code Ldca.g(CharSequence): ArrayList}, the
 * {@code Filter} backing the emote suggestion dropdown above the chat
 * input box). Twitch's own emotes are matched by that method against its
 * own {@code LinkedHashMap<String, EmoteModel>}; this appends
 * third-party (7TV/BTTV/FFZ) matches to the same {@code ArrayList} it
 * returns, built as genuine instances of Twitch's own
 * {@code EmoteModel.Generic}-equivalent class (see
 * {@link EmoteModelReflect}) so the existing suggestion adapter renders
 * them identically to a native emote -- same image widget, same
 * insert-token-on-tap behaviour.
 *
 * <p>Two-call design instead of a single "process the query" call: the
 * fingerprinted method reassigns its own {@code CharSequence} parameter
 * register several times internally (it becomes scratch space for
 * intermediate result lists), so there is no single register guaranteed
 * to still hold the original query text by the time execution reaches the
 * final {@code return}. Splitting into {@link #begin} (hooked at method
 * entry, where the raw parameter is still guaranteed valid) and
 * {@link #finish} (hooked right before the final {@code return-object},
 * where the result {@code ArrayList} register is known) sidesteps that
 * entirely -- the computed match list is handed off through a
 * {@link ThreadLocal} rather than through any Dalvik register.
 */
public final class EmoteAutocompleteBridge {

    private static final String TAG = "RTEmotes";
    private static final int MAX_MATCHES = 25;

    /**
     * Minimum length of a plain (colon-less) word before the emote
     * autocomplete dropdown is offered for it. 1-char words match far too
     * many emotes to be useful and would pop the dropdown on almost every
     * keystroke; the native {@code :}-prefixed path stays 1-char as before
     * (it goes through {@code original == true}).
     */
    private static final int MIN_NO_COLON_LEN = 2;

    private static final ThreadLocal<List<Object>> PENDING = new ThreadLocal<>();

    private EmoteAutocompleteBridge() {
    }

    /**
     * Decides whether the chat-input emote autocomplete should activate for
     * the current token. Injected right after Twitch's own "does the current
     * word start with {@code :}?" check in the message-input TextWatcher (see
     * {@code AutocompleteNoColonPatch}); {@code original} is that check's
     * result. When it's already {@code true} (a real {@code :}-triggered
     * emote token) we keep Twitch's behaviour untouched. Otherwise, if the
     * feature is enabled, we also light up the emote dropdown for a plain
     * word -- but never for command ({@code /}) or mention ({@code @}) tokens,
     * which have their own providers and are matched before/after us.
     *
     * Called directly from injected Twitch bytecode with no surrounding
     * try/catch, so it must never throw back into Twitch's input handling.
     */
    /**
     * Adjusts the chat-input tokenizer's token-start so a colon-less word is
     * treated as its own token instead of being swallowed into "everything
     * since the message start". Injected into Twitch's tokenizer
     * {@code findTokenStart} (see {@code AutocompleteNoColonPatch}): Twitch
     * computes the start as the max of its {@code @}/{@code :}/command trigger
     * positions, which is 0 for a plain word -- so a plain word after a space
     * yields the constraint "word1 word2" that matches no emote and never pops
     * the dropdown. We raise the start to just after the last whitespace so the
     * constraint becomes just the current word. Returns {@code currentStart}
     * untouched when the feature is off or on any error (never throws back into
     * Twitch's input handling). {@code @}/{@code :}/command tokens are
     * unaffected: their trigger position is already >= the word start, so the
     * {@code max} keeps them.
     */
    public static int adjustTokenStart(CharSequence textUpToCursor, int currentStart) {
        try {
            if (!EmoteSettings.isAutocompleteNoColonEnabled() || textUpToCursor == null) {
                return currentStart;
            }
            String text = textUpToCursor.toString();
            int wordStart = text.length();
            while (wordStart > 0 && !Character.isWhitespace(text.charAt(wordStart - 1))) {
                wordStart--;
            }
            return Math.max(currentStart, wordStart);
        } catch (Throwable t) {
            Log.w(TAG, "adjustTokenStart failed", t);
            CrashReporter.report(TAG, "adjustTokenStart failed", t);
            return currentStart;
        }
    }

    public static boolean shouldTriggerEmoteAutocomplete(CharSequence textUpToCursor, boolean original) {
        try {
            if (original) {
                return true;
            }
            if (!EmoteSettings.isAutocompleteNoColonEnabled()) {
                return false;
            }
            if (textUpToCursor == null) {
                return false;
            }
            String text = textUpToCursor.toString();
            int end = text.length();
            int start = end;
            while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) {
                start--;
            }
            int wordLen = end - start;
            if (wordLen < MIN_NO_COLON_LEN) {
                return false;
            }
            char first = text.charAt(start);
            // '/' -> command provider, '@' -> mention provider, ':' -> already
            // the native emote path (original would have been true). Leave all
            // three to their own handlers.
            if (first == '/' || first == '@' || first == ':') {
                return false;
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "shouldTriggerEmoteAutocomplete failed", t);
            CrashReporter.report(TAG, "shouldTriggerEmoteAutocomplete failed", t);
            return original;
        }
    }

    // Both entry points are called directly from injected Twitch bytecode
    // with no surrounding try/catch on the smali side -- an uncaught
    // exception here would propagate straight into (and likely crash)
    // Twitch's own autocomplete Filter. Every path below must return
    // normally no matter what goes wrong internally.
    public static void begin(CharSequence rawQuery, String emoteModelClassName) {
        try {
            beginUnsafe(rawQuery, emoteModelClassName);
        } catch (Throwable t) {
            Log.w(TAG, "autocomplete begin() failed", t);
            CrashReporter.report(TAG, "autocomplete begin() failed", t);
            PENDING.remove();
        }
    }

    private static void beginUnsafe(CharSequence rawQuery, String emoteModelClassName) {
        PENDING.remove();
        if (rawQuery == null || !EmoteSettings.isAnyProviderEnabled()) {
            return;
        }
        String query = rawQuery.toString();
        if (query.startsWith(":")) {
            query = query.substring(1);
        }

        String channelId = ChannelTracker.getCurrentChannelId();
        List<Emote> matches = EmoteCache.findMatches(query, channelId, MAX_MATCHES);
        if (matches.isEmpty()) {
            return;
        }

        List<Object> models = new ArrayList<>(matches.size());
        for (Emote emote : matches) {
            Object model = EmoteModelReflect.buildEmoteModel(emoteModelClassName, emote);
            if (model != null) {
                models.add(model);
            }
        }
        if (!models.isEmpty()) {
            PENDING.set(models);
        }
    }

    /** Moves the matches that are favourites to the top, in favourites order (newest first). */
    @SuppressWarnings("unchecked")
    private static void favoritesFirst(List<Object> matches) {
        List<String> favorites = new ArrayList<>();
        for (String entry : EmoteSettings.getFavorites()) {
            favorites.add(EmoteSettings.favoriteCode(entry));
        }
        if (favorites.isEmpty()) {
            return;
        }
        List<Object> pinned = new ArrayList<>();
        for (String code : favorites) {
            for (Iterator<Object> it = matches.iterator(); it.hasNext(); ) {
                Object model = it.next();
                String[] idToken = EmoteModelReflect.anyIdAndToken(model);
                if (idToken != null && code.equals(idToken[1])) {
                    pinned.add(model);
                    it.remove();
                    break;
                }
            }
        }
        matches.addAll(0, pinned);
    }

    @SuppressWarnings("unchecked")
    public static void finish(ArrayList existing) {
        try {
            List<Object> models = PENDING.get();
            PENDING.remove();
            if (existing == null) {
                return;
            }
            if (Toggles.on(Toggles.FREQUENT_EMOTES)) {
                for (Object model : existing) {
                    FrequentEmotes.note(model); // Twitch's own matches only: ours aren't added yet
                }
            }
            if (models != null) {
                existing.addAll(models);
            }
            if (Toggles.on(Toggles.EMOTE_FAVORITES) && Toggles.on(Toggles.FAVORITES_FIRST_AUTOCOMPLETE)) {
                favoritesFirst(existing);
            }
        } catch (Throwable t) {
            Log.w(TAG, "autocomplete finish() failed", t);
            CrashReporter.report(TAG, "autocomplete finish() failed", t);
        }
    }
}
