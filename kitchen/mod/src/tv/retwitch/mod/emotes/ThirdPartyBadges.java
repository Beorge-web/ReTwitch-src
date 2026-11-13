package tv.retwitch.mod.emotes;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

import tv.retwitch.mod.CrashReporter;
import tv.retwitch.mod.Toggles;

/**
 * Chat badges of other clients: FrankerFaceZ, Chatterino, DankChat, Chatsen,
 * Chatterino Homies (port of ReVive's {@code BadgeRepo}). Each ships one global
 * manifest keyed by numeric Twitch user id, fetched once per enabled provider
 * (no per-user requests); the badge image itself is drawn by Twitch from its
 * URL, like the RTE badge ({@link ChatCosmetics#injectBadges}).
 *
 * <p>Formats (ReVive, re-verified 2026-07..10):
 * <pre>
 *   FFZ        api.frankerfacez.com/v1/badges/ids  {badges:[{id,urls:{"2"}}], users:{"&lt;id&gt;":[uid..]}}
 *   Chatterino api.chatterino.com/badges           {badges:[{image2,users:["uid"..]}]}
 *   DankChat   flxrs.com/api/badges                [{url,users:["uid"..]}]
 *   Chatsen    raw.githubusercontent.com/chatsen/resources/master/assets/data.json
 *              {badges:[{name,image}], users:[{id,badges:[{badgeName}]}]}
 *   Homies     chatterinohomies.com/api/badges/list {badges:[{userId,image2|image1}]}
 * </pre>
 * BTTV ({@code /3/cached/badges/twitch}) is left out: its badges are SVG,
 * which Twitch's image loader doesn't draw.
 */
public final class ThirdPartyBadges {

    private static final String TAG = "RTBadges3p";
    private static final long RETRY_MS = TimeUnit.MINUTES.toMillis(5);

    private static final String[] URLS = {
            "https://api.frankerfacez.com/v1/badges/ids",
            "https://api.chatterino.com/badges",
            "https://flxrs.com/api/badges",
            "https://raw.githubusercontent.com/chatsen/resources/master/assets/data.json",
            "https://chatterinohomies.com/api/badges/list",
    };

    private static Toggles.Toggle toggle(int provider) {
        return Toggles.THIRD_PARTY_BADGES[provider];
    }

    /** Per provider: userId -> badge image URLs; null until loaded. Published whole, read lock-free. */
    private static final AtomicReferenceArray<Map<String, List<String>>> ROSTERS =
            new AtomicReferenceArray<>(URLS.length);
    private static final Map<Integer, Long> lastAttempt = new ConcurrentHashMap<>();

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ReTwitch-Badges3p");
        thread.setDaemon(true);
        return thread;
    });

    private ThirdPartyBadges() {
    }

    /**
     * Badge image URLs of {@code userId} from every enabled provider (empty
     * until its manifest is loaded; the first call starts the load). Called
     * from the chat build: plain map reads, never blocks.
     */
    static List<String> urlsFor(String userId) {
        List<String> out = null;
        for (int p = 0; p < URLS.length; p++) {
            if (!Toggles.on(toggle(p))) {
                continue;
            }
            Map<String, List<String>> roster = ROSTERS.get(p);
            if (roster == null) {
                ensureLoading(p);
                continue;
            }
            List<String> urls = roster.get(userId);
            if (urls != null) {
                if (out == null) {
                    out = new ArrayList<>(2);
                }
                for (String url : urls) {
                    out.add(EmoteProxy.wrap(url));
                }
            }
        }
        return out == null ? Collections.<String>emptyList() : out;
    }

    private static void ensureLoading(final int provider) {
        long now = System.currentTimeMillis();
        Long last = lastAttempt.get(provider);
        if (last != null && now - last < RETRY_MS) {
            return;
        }
        lastAttempt.put(provider, now);
        EXECUTOR.execute(() -> {
            try {
                Map<String, List<String>> roster = parse(provider, EmoteHttp.getJson(URLS[provider]));
                ROSTERS.set(provider, roster);
                Log.i(TAG, URLS[provider] + ": " + roster.size() + " users");
            } catch (Throwable t) {
                // Retried on a later chat message after RETRY_MS.
                Log.w(TAG, "badge manifest failed: " + URLS[provider] + ": " + t);
            }
        });
    }

    private static Map<String, List<String>> parse(int provider, String json) throws Exception {
        Map<String, List<String>> roster = new HashMap<>();
        switch (provider) {
            case 0: { // FFZ: badge list + {badgeId: [userIds]}
                JSONObject root = new JSONObject(json);
                Map<String, String> images = new HashMap<>();
                JSONArray badges = root.optJSONArray("badges");
                for (int i = 0; badges != null && i < badges.length(); i++) {
                    JSONObject badge = badges.optJSONObject(i);
                    JSONObject urls = badge == null ? null : badge.optJSONObject("urls");
                    String url = urls == null ? "" : urls.optString("2", "");
                    if (!url.isEmpty()) {
                        images.put(String.valueOf(badge.optInt("id")), url.startsWith("//") ? "https:" + url : url);
                    }
                }
                JSONObject users = root.optJSONObject("users");
                if (users != null) {
                    for (Iterator<String> it = users.keys(); it.hasNext(); ) {
                        String id = it.next();
                        String url = images.get(id);
                        JSONArray ids = users.optJSONArray(id);
                        for (int j = 0; url != null && ids != null && j < ids.length(); j++) {
                            add(roster, String.valueOf(ids.optLong(j)), url);
                        }
                    }
                }
                break;
            }
            case 1: // Chatterino
            case 2: { // DankChat: [{url, users}]
                JSONArray badges = provider == 1
                        ? new JSONObject(json).optJSONArray("badges") : new JSONArray(json);
                for (int i = 0; badges != null && i < badges.length(); i++) {
                    JSONObject badge = badges.optJSONObject(i);
                    if (badge == null) {
                        continue;
                    }
                    String url = provider == 1 ? badge.optString("image2", badge.optString("image1", ""))
                            : badge.optString("url", "");
                    JSONArray users = badge.optJSONArray("users");
                    for (int j = 0; !url.isEmpty() && users != null && j < users.length(); j++) {
                        add(roster, users.optString(j), url);
                    }
                }
                break;
            }
            case 3: { // Chatsen: badges by name, users name their badges
                JSONObject root = new JSONObject(json);
                Map<String, String> images = new HashMap<>();
                JSONArray badges = root.optJSONArray("badges");
                for (int i = 0; badges != null && i < badges.length(); i++) {
                    JSONObject badge = badges.optJSONObject(i);
                    if (badge != null && !badge.optString("image", "").isEmpty()) {
                        images.put(badge.optString("name"), badge.optString("image"));
                    }
                }
                JSONArray users = root.optJSONArray("users");
                for (int i = 0; users != null && i < users.length(); i++) {
                    JSONObject user = users.optJSONObject(i);
                    JSONArray owned = user == null ? null : user.optJSONArray("badges");
                    for (int j = 0; owned != null && j < owned.length(); j++) {
                        JSONObject ref = owned.optJSONObject(j);
                        String url = ref == null ? null : images.get(ref.optString("badgeName"));
                        if (url != null) {
                            add(roster, user.optString("id"), url);
                        }
                    }
                }
                break;
            }
            default: { // Homies: one personal badge per user
                JSONArray badges = new JSONObject(json).optJSONArray("badges");
                for (int i = 0; badges != null && i < badges.length(); i++) {
                    JSONObject badge = badges.optJSONObject(i);
                    if (badge != null) {
                        add(roster, badge.optString("userId"),
                                badge.optString("image2", badge.optString("image1", "")));
                    }
                }
                break;
            }
        }
        return roster;
    }

    /** Only numeric Twitch ids: Chatterino lists carry "-1" and non-Twitch ids. */
    private static void add(Map<String, List<String>> roster, String userId, String url) {
        if (userId == null || userId.isEmpty() || url == null || url.isEmpty()) {
            return;
        }
        for (int i = 0; i < userId.length(); i++) {
            if (!Character.isDigit(userId.charAt(i))) {
                return;
            }
        }
        List<String> urls = roster.get(userId);
        if (urls == null) {
            urls = new ArrayList<>(1);
            roster.put(userId, urls);
        }
        if (!urls.contains(url)) {
            urls.add(url);
        }
    }

    /** Never throws; for {@link ChatCosmetics}. */
    static List<String> urlsForSafe(String userId) {
        try {
            return urlsFor(userId);
        } catch (Throwable t) {
            Log.w(TAG, "urlsFor failed", t);
            CrashReporter.report(TAG, "urlsFor failed", t);
            return Collections.emptyList();
        }
    }
}
