package tv.retwitch.mod;

import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Hides sections of the React Native Following tab below the live channels and sorts its categories
 * ({@code RnFollowingSectionsPatch}). The tab is JS, so its GQL answers are emptied instead: every
 * RN {@code fetch} body passes the blob response handler as bytes. Categories and Continue Watching
 * are fields of {@code MwebFollowingTabSections}, offline channels the {@code follows} of
 * {@code MwebFollowedOfflineChannelsQuery}. An empty list is a normal state, the section then
 * renders nothing. Requests are batched, so a body may be an array of answers.
 */
public final class RnFollowingHook {

    private static final String TAG = "ReTwitch/RnFollowing";
    /** Twitch's own watch streak tag on Following cards, off behind its flag and experiment. */
    private static final String STREAK_FLAGS = "{\"ultralight_following_ws_tag\":true,\"ws-following-tab\":\"treatment\"}";
    private static final String SECTIONS_QUERY = "query MwebFollowingTabSections";
    private static final String CATEGORIES_FIELD = "followedGames {";

    private RnFollowingHook() {
    }

    /**
     * The tab's query (JSON body, may be batched) sets no limit, and the server stops at the 10 oldest
     * follows. 100 is the most it accepts, more fails the field, and the field has no further pages.
     */
    static String withAllCategories(String body) {
        int query = body != null ? body.indexOf(SECTIONS_QUERY) : -1;
        int field = query >= 0 ? body.indexOf(CATEGORIES_FIELD, query) : -1;
        return field < 0 ? body : body.substring(0, field) + "followedGames(first: 100) {"
                + body.substring(field + CATEGORIES_FIELD.length());
    }

    /**
     * Injected before {@code TwitchRNHostPropsBuilder.build} returns: JS reads flag overrides from the
     * {@code eppoFlagOverrides} prop, which the host never sets. Never throws.
     */
    public static void onHostProps(Bundle props) {
        try {
            if (props == null) {
                return;
            }
            JSONObject flags = new JSONObject(Toggles.on(Toggles.FOLLOWING_STREAK_TAG) ? STREAK_FLAGS : "{}");
            ExperimentHook.rnOverrides(flags);
            if (flags.length() > 0) {
                props.putString("eppoFlagOverrides", flags.toString());
            }
        } catch (Throwable t) {
            Log.w(TAG, "onHostProps failed", t);
        }
    }

    /** Injected at the entry of the blob response handler: the body to keep. Never throws. */
    public static byte[] onResponse(byte[] body) {
        try {
            boolean categories = Toggles.on(Toggles.HIDE_FOLLOWING_CATEGORIES);
            boolean vods = Toggles.on(Toggles.HIDE_FOLLOWING_CONTINUE);
            boolean offline = Toggles.on(Toggles.HIDE_FOLLOWING_OFFLINE);
            boolean sort = Toggles.on(Toggles.SORT_FOLLOWED_CATEGORIES);
            boolean arrows = Toggles.on(Toggles.STREAM_ARROWS);
            if (body == null || !categories && !vods && !offline && !sort && !arrows) {
                return body;
            }
            String text = new String(body, StandardCharsets.UTF_8);
            String kept = strip(text, categories, vods, offline, sort);
            return kept == text ? body : kept.getBytes(StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.w(TAG, "onResponse failed", t);
            return body;
        }
    }

    /**
     * The answer with the hidden lists emptied and, with {@code sort}, the categories by name: the server
     * sends them oldest follow first. The same instance if there was nothing to change.
     */
    static String strip(String json, boolean categories, boolean vods, boolean offline, boolean sort)
            throws JSONException {
        if (!json.contains("\"followedLiveUsers\"") && !json.contains("\"followedVideos\"")) {
            return json;
        }
        Object root = new JSONTokener(json).nextValue();
        boolean changed = false;
        if (root instanceof JSONArray) {
            JSONArray batch = (JSONArray) root;
            for (int i = 0; i < batch.length(); i++) {
                changed |= stripOne(batch.optJSONObject(i), categories, vods, offline, sort);
            }
        } else if (root instanceof JSONObject) {
            changed = stripOne((JSONObject) root, categories, vods, offline, sort);
        }
        return changed ? root.toString() : json;
    }

    private static boolean stripOne(JSONObject answer, boolean categories, boolean vods, boolean offline,
            boolean sort) throws JSONException {
        JSONObject data = answer == null ? null : answer.optJSONObject("data");
        JSONObject user = data == null ? null : data.optJSONObject("user");
        if (user == null) {
            return false;
        }
        boolean changed = false;
        if (user.has("followedLiveUsers")) {
            StreamHistory.noteFollowing(user);
            changed |= categories ? empty(user.optJSONObject("followedGames"), "nodes")
                    : sort && sortByName(user.optJSONObject("followedGames"));
            changed |= vods && empty(user.optJSONObject("viewedVideos"), "edges");
        } else if (offline) {
            // Only this query has currentUser.followedVideos next to follows: others need their follows.
            JSONObject me = data.optJSONObject("currentUser");
            JSONObject follows = user.optJSONObject("follows");
            if (me != null && me.has("followedVideos") && empty(follows, "edges")) {
                JSONObject page = follows.optJSONObject("pageInfo");
                if (page != null) {
                    page.put("hasNextPage", false); // the section walks all pages
                }
                changed = true;
            }
        }
        return changed;
    }

    /** Categories by their shown name, in the device's language order. */
    private static boolean sortByName(JSONObject games) throws JSONException {
        JSONArray nodes = games == null ? null : games.optJSONArray("nodes");
        if (nodes == null || nodes.length() < 2) {
            return false;
        }
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null) {
                return false; // not the shape we know: leave it
            }
            list.add(node);
        }
        Collator collator = Collator.getInstance();
        collator.setStrength(Collator.SECONDARY);
        Collections.sort(list, (a, b) -> collator.compare(name(a), name(b)));
        games.put("nodes", new JSONArray(list));
        return true;
    }

    private static String name(JSONObject game) {
        String shown = game.optString("displayName", "");
        return shown.isEmpty() ? game.optString("name", "") : shown;
    }

    private static boolean empty(JSONObject holder, String list) throws JSONException {
        if (holder == null || !holder.has(list)) {
            return false;
        }
        holder.put(list, new JSONArray());
        return true;
    }
}
