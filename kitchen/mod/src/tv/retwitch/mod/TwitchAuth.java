package tv.retwitch.mod;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Locale;
import java.util.Map;

/**
 * Reads the logged-in user's Twitch OAuth token straight out of the app's
 * own {@link SharedPreferences}.
 *
 * The Twitch Android app persists the token as a plain string under key
 * {@code authToken_v2} in the {@code twitch_user_prefs} SharedPreferences
 * file (verified in one decode: the user-data reader {@code j410.a()} does
 * {@code getString("authToken_v2", "")} against the prefs opened by
 * {@code jqr} with the name {@code "twitch_user_prefs"}). Both the file name
 * and the key are string constants baked into the app -- protocol/storage
 * data, not obfuscatable identifiers -- so they survive R8 renaming exactly
 * like the usher query-parameter names {@link UsherHook} relies on. Nothing
 * here touches any Twitch-internal (obfuscated) class.
 */
public final class TwitchAuth {

    private static final String TWITCH_USER_PREFS = "twitch_user_prefs";
    private static final String KEY_AUTH_TOKEN = "authToken_v2";

    /**
     * Candidate keys the logged-in user's login/handle may be stored under in
     * {@code twitch_user_prefs}, tried in order. The exact key isn't a stable,
     * documented contract the way {@code authToken_v2} is, so we probe a few
     * plausible names at runtime and take the first non-empty string rather
     * than hardcoding one that a Twitch release might rename. Used only for
     * @mention detection ({@code MentionHighlighter}).
     */
    private static final String[] USERNAME_KEYS = {
            // Verified against a decode: the logged-in user model persisted in
            // twitch_user_prefs stores the login under "name" and the display
            // handle under "DisplayName" (capital D). @mentions use the login,
            // so "name" is primary; "DisplayName" is a fallback for chats that
            // address people by display name. The rest are legacy guesses.
            "name", "DisplayName",
            "username", "user_name", "userName", "login", "display_name", "displayName",
    };

    private TwitchAuth() {
    }

    /**
     * @return the logged-in user's login/handle in lower case, or {@code ""}
     *         if it can't be read (anonymous, or none of the candidate keys
     *         held a string). Never throws.
     */
    public static String getUsername() {
        Context context = AppContext.get();
        if (context == null) {
            Log.w("RTMention", "getUsername: no AppContext yet -> returning ''");
            return "";
        }
        try {
            SharedPreferences prefs =
                    context.getSharedPreferences(TWITCH_USER_PREFS, Context.MODE_PRIVATE);

            // Debug aid: dump every key present so we can spot which one really
            // holds the login if none of the candidates below match.
            try {
                Log.i("RTMention", "getUsername: " + TWITCH_USER_PREFS
                        + " keys=" + prefs.getAll().keySet());
            } catch (Throwable t) {
                Log.w("RTMention", "getUsername: could not list prefs keys", t);
                CrashReporter.report("RTMention", "getUsername: could not list prefs keys", t);
            }

            for (String key : USERNAME_KEYS) {
                try {
                    String value = prefs.getString(key, "");
                    Log.i("RTMention", "getUsername: key '" + key + "' -> '" + value + "'");
                    if (value != null && !value.isEmpty()) {
                        String result = value.trim();
                        Log.i("RTMention", "getUsername: RESULT -> '" + result
                                + "' (from key '" + key + "')");
                        return result;
                    }
                } catch (Throwable t) {
                    // key holds a non-string (e.g. an int user id) -- skip it.
                    Log.i("RTMention", "getUsername: key '" + key + "' is not a String ("
                            + t.getClass().getSimpleName() + ")");
                }
            }
            Log.w("RTMention", "getUsername: no candidate key held a login -> RESULT -> ''");
        } catch (Throwable t) {
            Log.w("RTMention", "getUsername failed -> RESULT -> ''", t);
            CrashReporter.report("RTMention", "getUsername failed -> RESULT -> ''", t);
        }
        return "";
    }

    /**
     * Candidate keys the logged-in user's numeric id may be stored under in
     * {@code twitch_user_prefs}, tried in order. Like {@link #USERNAME_KEYS}
     * this isn't a documented contract, so we probe a few plausible names and
     * take the first all-digits value (stored either as a String or as an
     * int/long) rather than hardcoding one a Twitch release might rename. Used
     * only to self-report to the ReYohoho badge backend
     * ({@code RteBadgeStore.registerSelf}).
     */
    private static final String[] USER_ID_KEYS = {
            // Verified against a running build: the logged-in user's numeric id
            // is persisted under "userIdInt" (an int). The rest are fallback
            // guesses for releases that might rename it.
            "userIdInt", "user_id", "userId", "UserId", "user_id_v2", "id",
    };

    /**
     * @return the logged-in user's numeric Twitch id (all-digits string), or
     *         {@code ""} if it can't be read (anonymous, or none of the
     *         candidate keys held a numeric value). Never throws.
     */
    public static String getUserId() {
        Context context = AppContext.get();
        if (context == null) {
            return "";
        }
        try {
            SharedPreferences prefs =
                    context.getSharedPreferences(TWITCH_USER_PREFS, Context.MODE_PRIVATE);

            for (String key : USER_ID_KEYS) {
                String value = readNumericKey(prefs, key);
                if (!value.isEmpty()) {
                    Log.i("RTEBadges", "getUserId: RESULT -> '" + value + "' (from key '" + key + "')");
                    return value;
                }
            }

            // Fallback: any key whose name looks like a user id and whose value
            // is a positive int/long or an all-digits string.
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                String key = entry.getKey();
                if (key == null || !key.toLowerCase(Locale.ROOT).contains("id")) {
                    continue;
                }
                String value = coerceNumeric(entry.getValue());
                if (!value.isEmpty()) {
                    Log.i("RTEBadges", "getUserId: RESULT -> '" + value + "' (fallback key '" + key + "')");
                    return value;
                }
            }
            Log.w("RTEBadges", "getUserId: no numeric id key found -> ''");
        } catch (Throwable t) {
            Log.w("RTEBadges", "getUserId failed -> ''", t);
            CrashReporter.report("RTEBadges", "getUserId failed -> ''", t);
        }
        return "";
    }

    /** Reads {@code key} as an all-digits id, tolerating either String or int/long storage. */
    private static String readNumericKey(SharedPreferences prefs, String key) {
        try {
            String s = prefs.getString(key, "");
            return isDigits(s) ? s.trim() : "";
        } catch (Throwable notAString) {
            // Stored as a number, not a String.
            try {
                long l = prefs.getLong(key, 0L);
                if (l > 0L) {
                    return Long.toString(l);
                }
            } catch (Throwable notALong) {
                try {
                    int i = prefs.getInt(key, 0);
                    if (i > 0) {
                        return Integer.toString(i);
                    }
                } catch (Throwable ignored) {
                    // Neither String nor int/long -- give up on this key.
                }
            }
            return "";
        }
    }

    private static String coerceNumeric(Object value) {
        if (value instanceof Long && (Long) value > 0L) {
            return Long.toString((Long) value);
        }
        if (value instanceof Integer && (Integer) value > 0) {
            return Integer.toString((Integer) value);
        }
        if (value instanceof String && isDigits((String) value)) {
            return ((String) value).trim();
        }
        return "";
    }

    private static boolean isDigits(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            if (!Character.isDigit(t.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** The logged-in user's display name as Twitch keeps it ({@code DisplayName}), or {@code ""}. Never throws. */
    public static String getDisplayName() {
        Context context = AppContext.get();
        if (context == null) {
            return "";
        }
        try {
            String name = context.getSharedPreferences(TWITCH_USER_PREFS, Context.MODE_PRIVATE).getString("DisplayName", "");
            return name != null ? name.trim() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * @return the current user's Twitch OAuth token, or {@code ""} if the
     *         user isn't logged in / the token can't be read. Callers should
     *         treat empty as "anonymous" and simply skip sending it.
     */
    public static String getAuthToken() {
        Context context = AppContext.get();
        if (context == null) {
            return "";
        }
        try {
            SharedPreferences prefs =
                    context.getSharedPreferences(TWITCH_USER_PREFS, Context.MODE_PRIVATE);
            String token = prefs.getString(KEY_AUTH_TOKEN, "");
            return token != null ? token : "";
        } catch (Throwable t) {
            return "";
        }
    }
}
