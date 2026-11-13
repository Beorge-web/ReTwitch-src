package tv.retwitch.mod.emotes;

import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * BetterTTV's public "cached" API. Also doubles as the FFZ mirror --
 * BTTV re-exposes FrankerFaceZ's emote sets under
 * {@code /3/cached/frankerfacez/...} in its own JSON shape, which is
 * simpler to consume than FFZ's own API and is what most third-party
 * clients (including PurpleTV) actually use for FFZ emotes.
 */
final class BttvClient {

    private static final String API_BASE = "https://api.betterttv.net/3/cached/";

    /**
     * BTTV has no zero-width flag in its API, so -- like frosty and most other
     * clients -- we hardcode the known set of BTTV overlay emote codes.
     */
    private static final Set<String> BTTV_ZERO_WIDTH = new HashSet<>(Arrays.asList(
            "SoSnowy", "IceCold", "SantaHat", "TopHat", "ReinDeer", "CandyCane", "cvMask", "cvHazmat"));

    private BttvClient() {
    }

    static Map<String, Emote> fetchGlobal() throws IOException, JSONException {
        return parseBttvArray(new JSONArray(EmoteHttp.getJson(API_BASE + "emotes/global")));
    }

    static Map<String, Emote> fetchChannel(String twitchUserId) throws IOException, JSONException {
        JSONObject root;
        try {
            root = new JSONObject(EmoteHttp.getJson(API_BASE + "users/twitch/" + twitchUserId));
        } catch (EmoteHttp.HttpError e) {
            // BTTV 404s channels with no BTTV presence at all -- not a real error.
            if (e.code != 404) {
                throw e;
            }
            return new LinkedHashMap<>();
        }
        JSONArray bots = root.optJSONArray("bots");
        if (bots != null) {
            java.util.List<String> logins = new java.util.ArrayList<>();
            for (int i = 0; i < bots.length(); i++) {
                logins.add(bots.optString(i, ""));
            }
            tv.retwitch.mod.ChatFilters.setChannelBots(logins);
        }
        Map<String, Emote> result = new LinkedHashMap<>();
        result.putAll(parseBttvArray(root.optJSONArray("channelEmotes")));
        result.putAll(parseBttvArray(root.optJSONArray("sharedEmotes")));
        return result;
    }

    static Map<String, Emote> fetchFfzGlobal() throws IOException, JSONException {
        return parseFfzArray(new JSONArray(EmoteHttp.getJson(API_BASE + "frankerfacez/emotes/global")));
    }

    static Map<String, Emote> fetchFfzChannel(String twitchUserId) throws IOException, JSONException {
        try {
            return parseFfzArray(new JSONArray(
                    EmoteHttp.getJson(API_BASE + "frankerfacez/users/twitch/" + twitchUserId)));
        } catch (EmoteHttp.HttpError e) {
            if (e.code != 404) {
                throw e;
            }
            return new LinkedHashMap<>();
        }
    }

    private static Map<String, Emote> parseBttvArray(JSONArray emotes) {
        Map<String, Emote> result = new LinkedHashMap<>();
        if (emotes == null) {
            return result;
        }
        for (int i = 0; i < emotes.length(); i++) {
            JSONObject emote = emotes.optJSONObject(i);
            if (emote == null) {
                continue;
            }
            String id = emote.optString("id", null);
            String code = emote.optString("code", null);
            if (id == null || code == null) {
                continue;
            }
            boolean animated = emote.optBoolean("animated", true);
            // Animated WEBP only decodes as an animation through ImageDecoder (API 28+).
            boolean webp = EmoteSettings.isBttvWebpEnabled() && (!animated || Build.VERSION.SDK_INT >= 28);
            String url = "https://cdn.betterttv.net/emote/" + id + "/" + Math.min(EmoteSettings.getQuality(), 3) + "x"
                    + (webp ? ".webp" : "");
            Emote parsed = new Emote(code, url, EmoteSource.BTTV, animated, BTTV_ZERO_WIDTH.contains(code));
            parsed.id = id;
            parsed.author = userName(emote.optJSONObject("user"));
            result.put(code, parsed);
        }
        return result;
    }

    /** BTTV/FFZ {@code user} object: {@code displayName}, else {@code name}; null if absent. */
    private static String userName(JSONObject user) {
        if (user == null) {
            return null;
        }
        String name = user.optString("displayName", "");
        return name.isEmpty() ? user.optString("name", null) : name;
    }

    private static Map<String, Emote> parseFfzArray(JSONArray emotes) {
        Map<String, Emote> result = new LinkedHashMap<>();
        if (emotes == null) {
            return result;
        }
        for (int i = 0; i < emotes.length(); i++) {
            JSONObject emote = emotes.optJSONObject(i);
            if (emote == null) {
                continue;
            }
            String code = emote.optString("code", null);
            JSONObject images = emote.optJSONObject("images");
            if (code == null || images == null) {
                continue;
            }
            // FFZ has 1x, 2x and 4x (no 3x).
            String size = EmoteSettings.getQuality() == 1 ? "1x" : EmoteSettings.getQuality() == 2 ? "2x" : "4x";
            String url = images.optString(size, images.optString("2x", images.optString("1x", null)));
            if (url == null) {
                continue;
            }
            Emote parsed = new Emote(code, url, EmoteSource.FFZ, emote.optBoolean("animated", false), false,
                    images.optString("4x", url));
            parsed.id = emote.has("id") ? String.valueOf(emote.opt("id")) : null;
            parsed.author = userName(emote.optJSONObject("user"));
            result.put(code, parsed);
        }
        return result;
    }
}
