package tv.retwitch.mod.emotes;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import tv.retwitch.mod.CrashReporter;
import tv.retwitch.mod.SenderBodyMarker;
import tv.retwitch.mod.Toggles;

/**
 * FFZ "giant emote" rewards: a channel-points reward whose title or prompt
 * contains {@code FFZ:GE} makes the last emote of the redeemed message giant
 * (FrankerFaceZ {@code isGiantEmoteReward} + {@code pluckLastEmote}). The
 * channel's rewards come from an anonymous GQL query; the message carries the
 * reward as the IRC tag {@code custom-reward-id}. Rendering is done by
 * {@link EmoteChatHook} via {@link #isGiant}.
 */
public final class GiantEmotes {

    private static final String TAG = "RTGiantEmote";
    private static final String QUERY = "query($id: ID!) { user(id: $id) { channel { communityPointsSettings"
            + " { customRewards { id title prompt } } } } }";
    /** Twitch's own giant emote height. */
    static final int HEIGHT_DP = 112;

    private static final SenderBodyMarker MARKER = new SenderBodyMarker();
    private static volatile Set<String> rewardIds = Collections.emptySet();
    private static volatile String loadedChannel;
    /** Guarded by {@link #early}: whether {@link #rewardIds} holds the current channel's rewards. */
    private static boolean loaded;
    /** Reward row bodies built before the rewards loaded (chat history on join): body -> reward id. */
    private static final Map<String, String> early = new HashMap<>();

    private GiantEmotes() {
    }

    /** From {@code ChannelTracker} when the channel changes: loads its giant-emote rewards. Never throws. */
    public static void onChannel(final String channelId) {
        if (channelId == null || channelId.equals(loadedChannel) || !Toggles.on(Toggles.FFZ_GIANT_EMOTES)) {
            return;
        }
        loadedChannel = channelId;
        synchronized (early) {
            rewardIds = Collections.emptySet();
            loaded = false;
            early.clear();
        }
        Thread loader = new Thread(() -> {
            try {
                Set<String> ids = fetchRewardIds(channelId);
                Set<String> late = new HashSet<>();
                synchronized (early) {
                    if (!channelId.equals(loadedChannel)) {
                        return; // switched channels while loading
                    }
                    rewardIds = ids;
                    loaded = true;
                    for (Map.Entry<String, String> e : early.entrySet()) {
                        if (ids.contains(e.getValue())) {
                            late.add(e.getKey());
                        }
                    }
                    early.clear();
                }
                Log.i(TAG, "channel " + channelId + ": " + ids.size() + " giant emote reward(s), "
                        + late.size() + " early row(s)");
                if (!late.isEmpty()) {
                    for (String body : late) {
                        MARKER.markBody(body);
                    }
                    EmoteChatHook.refreshRowsContaining(late);
                }
            } catch (Throwable t) {
                loadedChannel = null; // retry on the next channel note
                Log.w(TAG, "reward load failed", t);
            }
        }, "RTGiantEmotes");
        loader.setDaemon(true);
        loader.start();
    }

    private static Set<String> fetchRewardIds(String channelId) throws Exception {
        JSONObject body = new JSONObject().put("query", QUERY)
                .put("variables", new JSONObject().put("id", channelId));
        Request request = new Request.Builder()
                .url("https://gql.twitch.tv/gql")
                .header("Client-ID", "kimne78kx3ncx6brgo4mv6wki5h1ko")
                .post(RequestBody.create(MediaType.parse("application/json"), body.toString()))
                .build();
        String json;
        try (Response response = EmoteHttp.sharedClient().newCall(request).execute()) {
            json = response.body() != null ? response.body().string() : "";
        }
        JSONArray rewards = new JSONObject(json).optJSONObject("data").optJSONObject("user")
                .optJSONObject("channel").optJSONObject("communityPointsSettings").optJSONArray("customRewards");
        Set<String> ids = new HashSet<>();
        for (int i = 0; rewards != null && i < rewards.length(); i++) {
            JSONObject reward = rewards.getJSONObject(i);
            if ((reward.optString("title") + "\n" + reward.optString("prompt")).contains("FFZ:GE")) {
                ids.add(reward.optString("id"));
            }
        }
        return ids;
    }

    /** From the chat event filter for each live chat message. Never throws. */
    public static void onChatMessage(Map<?, ?> tags, Object sender) {
        try {
            Object reward = tags != null ? tags.get("custom-reward-id") : null;
            if (reward != null) {
                Log.i(TAG, "reward message " + reward + " giant=" + rewardIds.contains(reward.toString()));
            }
            if (reward != null && sender != null && rewardIds.contains(reward.toString())) {
                MARKER.markSender(sender);
            }
        } catch (Throwable t) {
            Log.w(TAG, "onChatMessage failed", t);
            CrashReporter.report(TAG, "onChatMessage failed", t);
        }
    }

    /** A reward row's body built straight from the message model: mark it by the message's tags. */
    static void onRewardMessage(Object message, CharSequence body) throws IllegalAccessException {
        if (message == null || body == null || !Toggles.on(Toggles.FFZ_GIANT_EMOTES)) {
            return;
        }
        for (java.lang.reflect.Field f : message.getClass().getDeclaredFields()) {
            if (Map.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                Object tags = f.get(message);
                Object reward = tags instanceof Map ? ((Map<?, ?>) tags).get("custom-reward-id") : null;
                if (reward == null) {
                    return;
                }
                synchronized (early) {
                    if (!loaded) {
                        if (early.size() < 64) {
                            early.put(body.toString(), reward.toString());
                        }
                    } else if (rewardIds.contains(reward.toString())) {
                        MARKER.markBody(body);
                    }
                }
                return;
            }
        }
    }

    /** From the chat row bind. Never throws. */
    public static void onRowBound(CharSequence body) {
        try {
            if (body != null && Toggles.on(Toggles.FFZ_GIANT_EMOTES)) {
                MARKER.onBody(body);
            }
        } catch (Throwable t) {
            Log.w(TAG, "onRowBound failed", t);
            CrashReporter.report(TAG, "onRowBound failed", t);
        }
    }

    static boolean isGiant(CharSequence text) {
        return Toggles.on(Toggles.FFZ_GIANT_EMOTES) && MARKER.matches(text);
    }
}
