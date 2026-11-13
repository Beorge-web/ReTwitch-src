package tv.retwitch.mod.emotes;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** 7TV's public v3 API, plus Homies (a 7TV-hosted list of extra emotes). */
final class SevenTvClient {

    private static final String GLOBAL_URL = "https://7tv.io/v3/emote-sets/global";
    private static final String USER_URL_PREFIX = "https://7tv.io/v3/users/twitch/";
    private static final String HOMIES_URL = "https://itzalex.github.io/emotes";
    private static final Map<String, String> EMOTE_SETS = new java.util.concurrent.ConcurrentHashMap<>();

    private SevenTvClient() {
    }

    static Map<String, Emote> fetchGlobal() throws IOException, JSONException {
        JSONObject emoteSet = new JSONObject(EmoteHttp.getJson(GLOBAL_URL));
        return parseEmoteArray(emoteSet.optJSONArray("emotes"));
    }

    static Map<String, Emote> fetchChannel(String twitchUserId) throws IOException, JSONException {
        JSONObject user;
        try {
            user = new JSONObject(EmoteHttp.getJson(USER_URL_PREFIX + twitchUserId));
        } catch (EmoteHttp.HttpError e) {
            // 7TV 404s users that have never linked a 7TV account -- not a real error.
            // Timeouts and 5xx are, and must not be cached as "no emotes".
            if (e.code != 404) {
                throw e;
            }
            return new LinkedHashMap<>();
        }
        JSONObject emoteSet = user.optJSONObject("emote_set");
        if (emoteSet == null) {
            return new LinkedHashMap<>();
        }
        String setId = emoteSet.optString("id", null);
        if (setId != null) {
            EMOTE_SETS.put(twitchUserId, setId);
        }
        return parseEmoteArray(emoteSet.optJSONArray("emotes"));
    }

    /** The active 7TV emote set of a channel loaded this session, or null. */
    static String emoteSetId(String twitchUserId) {
        return twitchUserId == null ? null : EMOTE_SETS.get(twitchUserId);
    }

    /**
     * Homies emotes like PurpleTV: one file, {@code {data: {global_emotes: [{id, name}],
     * channel_emotes: {"<twitch id>": {emotes: [{id, name}]}}}}}; ids are 7TV emote ids.
     * shortcut: no animated flag in the list, so the WEBP is used (animates on Android 9+ only).
     *
     * @param twitchUserId null for the global list
     */
    static Map<String, Emote> fetchHomies(String twitchUserId) throws IOException, JSONException {
        JSONObject data = new JSONObject(EmoteHttp.getJson(HOMIES_URL)).optJSONObject("data");
        JSONArray emotes = data == null ? null : twitchUserId == null ? data.optJSONArray("global_emotes")
                : optArray(data.optJSONObject("channel_emotes"), twitchUserId);
        Map<String, Emote> result = new LinkedHashMap<>();
        if (emotes == null) {
            return result;
        }
        for (int i = 0; i < emotes.length(); i++) {
            JSONObject emote = emotes.optJSONObject(i);
            String id = emote == null ? "" : emote.optString("id", "");
            String code = emote == null ? "" : emote.optString("name", "");
            if (id.isEmpty() || code.isEmpty()) {
                continue;
            }
            Emote parsed = new Emote(code, "https://cdn.7tv.app/emote/" + id + "/" + EmoteSettings.getQuality() + "x.webp",
                    EmoteSource.HOMIES, false);
            parsed.id = id;
            result.put(code, parsed);
        }
        return result;
    }

    private static JSONArray optArray(JSONObject channels, String twitchUserId) {
        JSONObject channel = channels == null ? null : channels.optJSONObject(twitchUserId);
        return channel == null ? null : channel.optJSONArray("emotes");
    }

    static Map<String, Emote> parseEmoteArray(JSONArray emotes) {
        Map<String, Emote> result = new LinkedHashMap<>();
        if (emotes == null) {
            return result;
        }
        for (int i = 0; i < emotes.length(); i++) {
            JSONObject emote = emotes.optJSONObject(i);
            if (emote == null) {
                continue;
            }
            String code = emote.optString("name", null);
            JSONObject data = emote.optJSONObject("data");
            if (code == null || data == null) {
                continue;
            }
            JSONObject host = data.optJSONObject("host");
            if (host == null) {
                continue;
            }
            String baseUrl = host.optString("url", null);
            if (baseUrl == null) {
                continue;
            }
            if (baseUrl.startsWith("//")) {
                baseUrl = "https:" + baseUrl;
            }
            boolean animated = data.optBoolean("animated", false);
            String file = pickBestFile(host.optJSONArray("files"), animated);
            if (file == null) {
                continue;
            }
            // Zero-width: the emote's own ZERO_WIDTH flag (1 << 8 on data, as frosty checks) or the set
            // entry's override (bit 1 on the active emote, as RTE and the FFZ 7TV addon check).
            boolean zeroWidth = (data.optInt("flags", 0) & 256) != 0 || (emote.optInt("flags", 0) & 1) != 0;
            Emote parsed = new Emote(code, baseUrl + "/" + file, EmoteSource.SEVENTV, animated, zeroWidth);
            parsed.id = emote.optString("id", null);
            JSONObject owner = data.optJSONObject("owner");
            if (owner != null) {
                String name = owner.optString("display_name", "");
                parsed.author = name.isEmpty() ? owner.optString("username", null) : name;
            }
            result.put(code, parsed);
        }
        return result;
    }

    /**
     * For animated emotes, prefers a GIF of the configured size ({@link EmoteSettings#getQuality},
     * then 2x, then 1x): 7TV always lists a GIF variant alongside AVIF/WEBP for animated emotes,
     * and it's the only animated format the API 26/27 decode path ({@code Movie}) handles. For
     * static emotes, prefers WEBP (smaller), then PNG, same size order.
     */
    private static String pickBestFile(JSONArray files, boolean animated) {
        if (files == null || files.length() == 0) {
            return null;
        }
        String[] sizes = {EmoteSettings.getQuality() + "x", "2x", "1x"};
        String[] formats = animated ? new String[]{"GIF", "WEBP", "PNG"} : new String[]{"WEBP", "PNG"};
        for (String format : formats) {
            for (String size : sizes) {
                for (int i = 0; i < files.length(); i++) {
                    JSONObject file = files.optJSONObject(i);
                    String name = file == null ? null : file.optString("name", null);
                    if (name != null && name.startsWith(size) && format.equalsIgnoreCase(file.optString("format", ""))) {
                        return name;
                    }
                }
            }
        }
        JSONObject first = files.optJSONObject(0);
        return first == null ? null : first.optString("name", null);
    }
}
