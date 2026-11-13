package tv.retwitch.mod.emotes;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 7TV personal emotes, as in RTE ({@code personal-emotes.js}): sets that 7TV pushes over the open channel's
 * EventAPI socket ({@link SevenTvEvents}) for chatters whose own 7TV client announced presence there.
 * {@code entitlement.create/delete} ties a Twitch user to a set, {@code emote_set.update} fills the set; only
 * emotes 7TV approved for personal use ({@code data.state} has {@code PERSONAL}) are kept. They resolve only in
 * their owner's messages and never show in the picker. Cleared when the channel changes.
 */
final class SevenTvPersonal {

    private static final String TAG = "RTSevenTvPersonal";
    private static final String SET_URL = "https://7tv.io/v3/emote-sets/";

    private static final Object LOCK = new Object();
    /** 7TV set id -> code -> emote. */
    private static final Map<String, Map<String, Emote>> SETS = new HashMap<>();
    /** Twitch user id -> 7TV set ids. */
    private static final Map<String, Set<String>> USER_SETS = new HashMap<>();
    /** Twitch user id -> lowercase login and display name, to find the author of a bound row. */
    private static final Map<String, Set<String>> NAMES = new HashMap<>();
    /** Sets already asked over REST this channel. */
    private static final Set<String> FETCHED = new HashSet<>();
    /** Bumped by {@link #clear}: a REST answer for the previous channel is dropped. */
    private static int generation;

    private SevenTvPersonal() {
    }

    /** {@code userId}'s personal emote for {@code word}, or null. */
    static Emote lookup(String userId, String word) {
        if (userId == null || word == null || word.isEmpty()) {
            return null;
        }
        synchronized (LOCK) {
            Set<String> sets = USER_SETS.get(userId);
            if (sets == null) {
                return null;
            }
            for (String set : sets) {
                Map<String, Emote> emotes = SETS.get(set);
                Emote emote = emotes != null ? emotes.get(word) : null;
                if (emote != null) {
                    return EmoteSettings.isSevenTvEnabled() ? emote : null;
                }
            }
        }
        return null;
    }

    static void clear() {
        synchronized (LOCK) {
            SETS.clear();
            USER_SETS.clear();
            NAMES.clear();
            FETCHED.clear();
            generation++;
        }
    }

    /** {@code entitlement.create} / {@code entitlement.delete}: {@code object} is the entitlement. */
    static void onEntitlement(String type, JSONObject object) {
        if (object == null || !"EMOTE_SET".equals(object.optString("kind"))) {
            return;
        }
        String setId = object.optString("ref_id", "");
        JSONObject user = object.optJSONObject("user");
        JSONObject twitch = twitchConnection(user);
        String userId = twitch != null ? twitch.optString("id", "") : "";
        if (setId.isEmpty() || userId.isEmpty()) {
            return;
        }
        boolean create = "entitlement.create".equals(type);
        boolean fetch = false;
        boolean filled;
        int gen;
        synchronized (LOCK) {
            Set<String> sets = USER_SETS.get(userId);
            if (create) {
                if (sets == null) {
                    sets = new LinkedHashSet<>();
                    USER_SETS.put(userId, sets);
                }
                sets.add(setId);
                Set<String> names = new HashSet<>();
                addName(names, twitch.optString("username", null));
                addName(names, twitch.optString("display_name", null));
                addName(names, user.optString("username", null));
                addName(names, user.optString("display_name", null));
                NAMES.put(userId, names);
            } else if (sets != null) {
                sets.remove(setId);
                if (sets.isEmpty()) {
                    USER_SETS.remove(userId);
                    NAMES.remove(userId);
                }
            }
            Map<String, Emote> emotes = SETS.get(setId);
            filled = emotes != null && !emotes.isEmpty();
            // Sets filled before we subscribed may only show up as entitlements: load them once over REST.
            if (create && !filled && FETCHED.add(setId)) {
                fetch = true;
            }
            gen = generation;
        }
        if (filled) {
            refreshRows();
        } else if (fetch) {
            fetchSet(setId, gen);
        }
    }

    private static void fetchSet(final String setId, final int gen) {
        Thread loader = new Thread(() -> {
            try {
                JSONObject set = new JSONObject(EmoteHttp.getJson(SET_URL + setId));
                JSONArray emotes = set.optJSONArray("emotes");
                JSONArray pushed = new JSONArray();
                for (int i = 0; emotes != null && i < emotes.length(); i++) {
                    pushed.put(new JSONObject().put("key", "emotes").put("value", emotes.get(i)));
                }
                synchronized (LOCK) {
                    Map<String, Emote> known = SETS.get(setId);
                    if (gen != generation || known != null && !known.isEmpty()) {
                        return; // another channel by now, or the socket filled it meanwhile
                    }
                }
                onSetUpdate(new JSONObject().put("id", setId).put("pushed", pushed));
            } catch (Throwable t) {
                synchronized (LOCK) {
                    if (gen == generation) {
                        FETCHED.remove(setId); // retried on the next entitlement
                    }
                }
                Log.w(TAG, "set " + setId + " load failed", t);
            }
        }, "RTSevenTvPersonal");
        loader.setDaemon(true);
        loader.start();
    }

    /** {@code emote_set.update} of a set other than the channel's. */
    static void onSetUpdate(JSONObject body) {
        String setId = body != null ? body.optString("id", "") : "";
        if (setId.isEmpty()) {
            return;
        }
        Map<String, Emote> added = new LinkedHashMap<>();
        Set<String> removed = new HashSet<>();
        for (String key : new String[]{"pulled", "updated"}) {
            JSONArray items = body.optJSONArray(key);
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject old = emoteChange(items.optJSONObject(i), "old_value");
                if (old != null && old.optString("name", null) != null) {
                    removed.add(old.optString("name"));
                }
            }
        }
        for (String key : new String[]{"pushed", "updated"}) {
            JSONArray items = body.optJSONArray(key);
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject value = emoteChange(items.optJSONObject(i), "value");
                if (value != null && isPersonal(value)) {
                    added.putAll(SevenTvClient.parseEmoteArray(new JSONArray().put(value)));
                }
            }
        }
        boolean owned;
        synchronized (LOCK) {
            Map<String, Emote> emotes = SETS.get(setId);
            if (emotes == null) {
                emotes = new HashMap<>();
                SETS.put(setId, emotes);
            }
            emotes.keySet().removeAll(removed);
            emotes.putAll(added);
            owned = false;
            for (Set<String> sets : USER_SETS.values()) {
                owned |= sets.contains(setId);
            }
        }
        if (owned && !(added.isEmpty() && removed.isEmpty())) {
            refreshRows();
        }
    }

    /**
     * The owner of a bound chat row ("badges name: message"): the user with personal sets whose login or display
     * name ends the text before the first ": ", or is in brackets there (the "Name (login)" format). Null for a
     * /me row, which has no ": ".
     */
    static String ownerOfRow(CharSequence row) {
        if (row == null) {
            return null;
        }
        synchronized (LOCK) {
            if (NAMES.isEmpty()) {
                return null;
            }
            String text = row.toString();
            int colon = text.indexOf(": ");
            if (colon <= 0) {
                return null;
            }
            String author = text.substring(0, colon).toLowerCase(Locale.ROOT);
            for (Map.Entry<String, Set<String>> user : NAMES.entrySet()) {
                for (String name : user.getValue()) {
                    if (author.endsWith("(" + name + ")") || endsWithWord(author, name)) {
                        return user.getKey();
                    }
                }
            }
        }
        return null;
    }

    private static boolean endsWithWord(String author, String name) {
        if (!author.endsWith(name)) {
            return false;
        }
        int before = author.length() - name.length() - 1;
        return before < 0 || !Character.isLetterOrDigit(author.charAt(before)) && author.charAt(before) != '_';
    }

    private static void addName(Set<String> names, String name) {
        if (name != null && !name.isEmpty()) {
            names.add(name.toLowerCase(Locale.ROOT));
        }
    }

    private static JSONObject twitchConnection(JSONObject user) {
        JSONArray connections = user != null ? user.optJSONArray("connections") : null;
        for (int i = 0; connections != null && i < connections.length(); i++) {
            JSONObject connection = connections.optJSONObject(i);
            if (connection != null && "TWITCH".equals(connection.optString("platform"))) {
                return connection;
            }
        }
        return null;
    }

    private static JSONObject emoteChange(JSONObject item, String field) {
        return item != null && "emotes".equals(item.optString("key")) ? item.optJSONObject(field) : null;
    }

    /** 7TV only renders emotes approved for personal use from these sets. */
    private static boolean isPersonal(JSONObject value) {
        JSONObject data = value.optJSONObject("data");
        JSONArray state = data != null ? data.optJSONArray("state") : null;
        for (int i = 0; state != null && i < state.length(); i++) {
            if ("PERSONAL".equals(state.optString(i))) {
                return true;
            }
        }
        return false;
    }

    private static void refreshRows() {
        try {
            EmoteChatHook.requestRefresh();
        } catch (Throwable ignored) {
            // It logs its own failures; off Android (the self-check) it has nothing to refresh.
        }
    }
}
