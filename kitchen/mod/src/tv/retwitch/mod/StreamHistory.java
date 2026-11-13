package tv.retwitch.mod;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recently opened live channels with a cursor, like a browser's back and forward ({@link StreamSwitch}).
 * Opening a channel by hand drops what was ahead of the cursor; going back or forward with the arrows
 * only moves the cursor. Pure: no Android calls (StreamSwitchSelfCheck, and RnFollowingSelfCheck runs noteFollowing).
 */
final class StreamHistory {

    static final int MAX = 50;

    /** Following's live channels in the order the tab shows; null until the tab loaded once. */
    static volatile List<String> following;

    final List<String> logins = new ArrayList<>();
    /** Index of the channel playing now; -1 while empty. */
    int cursor = -1;
    /** The channel an arrow just opened: when it starts, the cursor moves to it and the history stays. */
    String pending;

    /** The live player now plays {@code login}. Returns whether the history changed (to be saved). */
    boolean opened(String login) {
        if (login == null || login.isEmpty()) {
            return false;
        }
        // The old channel can report again while the arrow's target loads: pending stays.
        if (cursor >= 0 && login.equals(logins.get(cursor))) {
            return false;
        }
        if (login.equals(pending)) {
            pending = null;
            int at = logins.indexOf(login);
            if (at >= 0) {
                cursor = at;
                return false;
            }
        }
        pending = null;
        while (logins.size() > cursor + 1) {
            logins.remove(logins.size() - 1);
        }
        logins.remove(login);
        logins.add(login);
        while (logins.size() > MAX) {
            logins.remove(0);
        }
        cursor = logins.size() - 1;
        return true;
    }

    /** Channels before the cursor, nearest first. */
    List<String> back() {
        List<String> out = new ArrayList<>();
        for (int i = cursor - 1; i >= 0; i--) {
            out.add(logins.get(i));
        }
        return out;
    }

    /** Channels after the cursor, nearest first. */
    List<String> forward() {
        List<String> out = new ArrayList<>();
        for (int i = cursor + 1; i < logins.size(); i++) {
            out.add(logins.get(i));
        }
        return out;
    }

    /**
     * From RnFollowingHook: the {@code user} of Following's own answer (MwebFollowingTabSections). A request of our
     * own (currentUser.followedLiveUsers with the Android Client-ID) answers "service error" even with RN's
     * recommendation context.
     */
    static void noteFollowing(JSONObject user) {
        JSONObject live = user == null ? null : user.optJSONObject("followedLiveUsers");
        if (live == null) {
            return;
        }
        JSONArray edges = live.optJSONArray("edges");
        JSONArray nodes = live.optJSONArray("nodes");
        int count = edges != null ? edges.length() : nodes != null ? nodes.length() : 0;
        List<String> logins = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            JSONObject node = edges != null
                    ? (edges.optJSONObject(i) == null ? null : edges.optJSONObject(i).optJSONObject("node"))
                    : nodes.optJSONObject(i);
            // Following's query asks node.stream.broadcaster.login; a plain node.login also counts.
            JSONObject stream = node == null ? null : node.optJSONObject("stream");
            JSONObject broadcaster = stream == null ? null : stream.optJSONObject("broadcaster");
            String login = node == null ? "" : node.optString("login",
                    broadcaster == null ? "" : broadcaster.optString("login"));
            if (!login.isEmpty()) {
                logins.add(login.toLowerCase(Locale.ROOT));
            }
        }
        following = logins;
    }

    /** Restores a saved list: the cursor on its last channel. */
    void restore(List<String> saved) {
        logins.clear();
        for (String login : saved) {
            if (login != null && !login.isEmpty() && !logins.contains(login)) {
                logins.add(login);
            }
        }
        while (logins.size() > MAX) {
            logins.remove(0);
        }
        cursor = logins.size() - 1;
        pending = null;
    }

    /**
     * The list from {@code current}'s neighbour on, going round in that direction and without {@code current}; from the
     * first (the last going back) when it isn't there.
     */
    static List<String> round(List<String> list, String current, boolean next) {
        List<String> out = new ArrayList<>();
        int at = current == null ? -1 : list.indexOf(current);
        int n = list.size();
        int start = at < 0 ? (next ? 0 : n - 1) : at + (next ? 1 : -1);
        for (int k = 0; k < n; k++) {
            String login = list.get(((start + (next ? k : -k)) % n + n) % n);
            if (!login.equals(current) && !out.contains(login)) {
                out.add(login);
            }
        }
        return out;
    }

    /** The neighbour of {@code current} in a list going round, or the first (last going back) when it isn't there. */
    static String neighbour(List<String> list, String current, boolean next) {
        if (list.isEmpty()) {
            return null;
        }
        int at = current == null ? -1 : list.indexOf(current);
        if (at < 0) {
            return list.get(next ? 0 : list.size() - 1);
        }
        if (list.size() == 1) {
            return null;
        }
        return list.get((at + (next ? 1 : -1) + list.size()) % list.size());
    }
}
