package tv.retwitch.mod.emotes;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import tv.retwitch.mod.CrashReporter;
import tv.retwitch.mod.ChatSettings;
import tv.retwitch.mod.TwitchAuth;

/**
 * In-memory roster of ReYohoho (RTE) chat badges, keyed by Twitch numeric
 * user id. This is the Android counterpart of the browser extension's
 * {@code reyohoho_badges} module: we bulk-fetch the whole
 * {@code userId -> badgeUrl} table in a single HTTP round-trip and look it
 * up synchronously while chat renders, instead of hammering the backend with
 * one request per chat author.
 *
 * <p>The roster is refreshed in the background every
 * {@link #REFRESH_INTERVAL_MS} and the fetch is ETag-conditional, so an
 * unchanged roster comes back as a cheap {@code 304 Not Modified} and the
 * in-memory map is left untouched. Whenever the map actually changes we nudge
 * a chat re-injection pass ({@link EmoteChatHook#requestRefresh}) so badges
 * appear on messages already on screen without waiting for new ones.
 *
 * <p>{@link #getBadgeUrl} is a plain synchronous map read safe to call from
 * the chat render path. All network I/O happens on a dedicated background
 * thread. Every entry point swallows {@link Throwable} -- this is reachable
 * from injected chat-render code that must never crash Twitch.
 */
public final class RteBadgeStore {

    private static final String TAG = "RTEBadges";

    /**
     * Same backend host the browser extension talks to for cosmetics
     * (badges/paints). Deliberately hit directly rather than through the
     * ReYohoho stream proxy -- it's our own API, not a third-party CDN.
     */
    private static final String API_BASE = "https://ext.rte.net.ru:8443";
    private static final String BADGE_USERS_PATH = "/api/badge-users";
    /** Nick paints, as the extension's seventv/cosmetics: [{twitchId, paintId}] + {paints: [...]}. */
    private static final String STAFF_USERS_PATH = "/api/staff-users";
    private static final String USER_PAINTS_PATH = "/api/user-paints";
    private static final String PAINTS_PATH = "/api/paints";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** Matches the extension: 5 min background refresh (server cache TTL is ~60s). */
    private static final long REFRESH_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5);

    /**
     * How long after self-registering to pull the roster again, so whoever
     * just marked themselves as an extension user sees their own badge quickly
     * without waiting a full {@link #REFRESH_INTERVAL_MS}. Mirrors the
     * extension's {@code POST_REGISTER_REFRESH_DELAY_MS}.
     */
    private static final long POST_REGISTER_REFRESH_DELAY_MS = TimeUnit.SECONDS.toMillis(5);

    private static final ScheduledExecutorService EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "ReTwitch-Badges");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    /**
     * Immutable snapshot of {@code userId -> badgeUrl}, swapped wholesale on
     * every successful refresh so {@link #getBadgeUrl} never sees a partially
     * populated map and needs no locking.
     */
    private static volatile Map<String, String> roster = Collections.emptyMap();

    /** {@code userId -> paint}, swapped whole like {@link #roster}. */
    private static volatile Map<String, NickPaint> paints = Collections.emptyMap();
    /** The staff roster, same shape as {@link #roster}: a second badge left of the regular one. */
    private static volatile Map<String, String> staffRoster = Collections.emptyMap();

    private static volatile String lastEtag = null;
    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static final AtomicBoolean selfRegistered = new AtomicBoolean(false);

    private RteBadgeStore() {
    }

    /**
     * Kicks off the first roster load and schedules the periodic refresh.
     * Idempotent and cheap -- safe to call from the chat render path (e.g.
     * the first time a sender is seen). Does nothing on repeat calls.
     */
    public static void ensureStarted() {
        try {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            Log.i(TAG, "starting badge roster (" + API_BASE + BADGE_USERS_PATH
                    + ", refresh every " + TimeUnit.MILLISECONDS.toMinutes(REFRESH_INTERVAL_MS) + " min)");
            EXECUTOR.execute(REFRESH_TASK);
            EXECUTOR.scheduleWithFixedDelay(
                    REFRESH_TASK,
                    REFRESH_INTERVAL_MS,
                    REFRESH_INTERVAL_MS,
                    TimeUnit.MILLISECONDS);
            EXECUTOR.execute(REGISTER_TASK);
        } catch (Throwable t) {
            Log.w(TAG, "ensureStarted failed", t);
            CrashReporter.report(TAG, "ensureStarted failed", t);
        }
    }

    private static final Runnable REGISTER_TASK = new Runnable() {
        @Override
        public void run() {
            try {
                registerSelf();
            } catch (Throwable t) {
                Log.w(TAG, "self-register crashed", t);
                CrashReporter.report(TAG, "self-register crashed", t);
            }
        }
    };

    /**
     * Self-reports the logged-in user's numeric id to the ReYohoho badge
     * backend ({@code POST /api/badge-users} with a {@code {"userId": "..."}}
     * body), which marks them as an extension/mod user so they get a badge --
     * the Android counterpart of the extension's {@code registerCurrentUser}.
     * Runs once per session from app start ({@link RteFriends#install}), as the
     * extension does; retried on each roster refresh until it succeeds. On success,
     * nudges a roster refresh shortly after so their own badge shows up quickly.
     */
    private static void registerSelf() {
        // Like the extension: sent on every start, whatever the badge setting.
        String userId = TwitchAuth.getUserId();
        if (userId == null || userId.isEmpty()) {
            Log.i(TAG, "self-register skipped: no logged-in user id");
            return;
        }
        if (!selfRegistered.compareAndSet(false, true)) {
            return;
        }

        Response response = null;
        try {
            RequestBody body = RequestBody.create(JSON, "{\"userId\":\"" + userId + "\"}");
            Request request = new Request.Builder()
                    .url(API_BASE + BADGE_USERS_PATH)
                    .header("Accept", "application/json")
                    .post(body)
                    .build();
            response = EmoteHttp.sharedClient().newCall(request).execute();
            if (response.isSuccessful()) {
                Log.i(TAG, "self-registered userId=" + userId + " (HTTP " + response.code() + ")");
                // Refresh soon so we pick up our own freshly-granted badge.
                EXECUTOR.schedule(REFRESH_TASK, POST_REGISTER_REFRESH_DELAY_MS, TimeUnit.MILLISECONDS);
            } else {
                Log.w(TAG, "self-register failed: HTTP " + response.code() + " for userId=" + userId);
                selfRegistered.set(false);
            }
        } catch (Throwable t) {
            Log.w(TAG, "self-register error for userId=" + userId + ": " + t);
            selfRegistered.set(false);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /** Whether the roster has arrived: until then {@link #getBadgeUrl} is null for everyone. */
    public static boolean isLoaded() {
        return !roster.isEmpty();
    }

    /** @return this user's RTE badge image URL, or {@code null} if they have none / roster not loaded yet. */
    public static String getBadgeUrl(String userId) {
        if (userId == null || userId.isEmpty()) {
            return null;
        }
        return roster.get(userId);
    }

    /** @return this user's "ReYohoho Staff" badge image URL, or {@code null}. */
    public static String getStaffBadgeUrl(String userId) {
        return userId == null ? null : staffRoster.get(userId);
    }

    /**
     * 7TV paints of users without an RTE one, as the extension's cosmetics fallback
     * ({@code userByConnection} on 7TV GQL): userId -> NickPaint, NO_PAINT once looked up, PENDING
     * while queued. Bounded LRU; lookups beyond a short queue are dropped and retried on that
     * user's next message.
     */
    private static final Map<String, Object> SEVENTV_PAINTS = Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, Object>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
                    return size() > 3000;
                }
            });
    private static final Object NO_PAINT = new Object();
    private static final Object PENDING = new Object();
    private static final java.util.concurrent.ThreadPoolExecutor SEVENTV_LOOKUPS = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<Runnable>(32), r -> {
                Thread t = new Thread(r, "RTSevenTvPaints");
                t.setDaemon(true);
                return t;
            });
    private static final String SEVENTV_GQL = "https://7tv.io/v3/gql";
    private static final String SEVENTV_PAINT_QUERY = "query GetUserPaint($id: String!) { userByConnection(id: $id, "
            + "platform: TWITCH) { style { paint { id color function angle shape image_url repeat stops { at color } "
            + "shadows { x_offset y_offset radius color } } } } }";

    /** @return this user's RTE nick paint, else their 7TV one if already looked up, else {@code null}. */
    static NickPaint getPaint(final String userId) {
        if (userId == null) {
            return null;
        }
        NickPaint paint = paints.get(userId);
        if (paint != null) {
            return paint;
        }
        Object cached = SEVENTV_PAINTS.get(userId);
        if (cached == null && userId.matches("[0-9]+")) {
            SEVENTV_PAINTS.put(userId, PENDING);
            try {
                SEVENTV_LOOKUPS.execute(() -> lookupSevenTv(userId));
            } catch (java.util.concurrent.RejectedExecutionException e) {
                SEVENTV_PAINTS.remove(userId);
            }
        }
        return cached instanceof NickPaint ? (NickPaint) cached : null;
    }

    /** Whether a paint may still come for this user, so a row built now may get it later: the RTE list or the 7TV lookup. */
    static boolean paintPending(String userId) {
        return userId != null && (paints.isEmpty() || SEVENTV_PAINTS.get(userId) == PENDING);
    }

    /** The paint already known, for drawing: never queues a lookup. */
    static NickPaint knownPaint(String userId) {
        NickPaint paint = paints.get(userId);
        if (paint != null) {
            return paint;
        }
        Object cached = SEVENTV_PAINTS.get(userId);
        return cached instanceof NickPaint ? (NickPaint) cached : null;
    }

    private static void lookupSevenTv(String userId) {
        try {
            JSONObject body = new JSONObject().put("operationName", "GetUserPaint")
                    .put("variables", new JSONObject().put("id", userId)).put("query", SEVENTV_PAINT_QUERY);
            Request request = new Request.Builder().url(EmoteProxy.wrap(SEVENTV_GQL))
                    .post(RequestBody.create(JSON, body.toString())).build();
            try (Response response = EmoteHttp.sharedClient().newCall(request).execute()) {
                ResponseBody rb = response.body();
                if (!response.isSuccessful() || rb == null) {
                    SEVENTV_PAINTS.remove(userId); // retried on a later message
                    return;
                }
                JSONObject user = new JSONObject(rb.string()).optJSONObject("data");
                user = user == null ? null : user.optJSONObject("userByConnection");
                JSONObject style = user == null ? null : user.optJSONObject("style");
                JSONObject def = style == null ? null : style.optJSONObject("paint");
                NickPaint paint = def == null ? null : NickPaint.parse(def);
                SEVENTV_PAINTS.put(userId, paint != null ? paint : NO_PAINT);
                if (paint != null) {
                    // Rows built while it loaded hold a pending span (NickPaint.Span#pending).
                    EmoteChatHook.redrawPaintedRows();
                }
            }
        } catch (Throwable t) {
            SEVENTV_PAINTS.remove(userId);
            Log.w(TAG, "7TV paint lookup failed for " + userId + ": " + t);
        }
    }

    private static void refreshPaints() {
        try {
            JSONArray users = new JSONArray(get(USER_PAINTS_PATH));
            JSONArray defs = new JSONObject(get(PAINTS_PATH)).optJSONArray("paints");
            Map<String, NickPaint> byId = new HashMap<>();
            for (int i = 0; defs != null && i < defs.length(); i++) {
                JSONObject def = defs.optJSONObject(i);
                NickPaint paint = def == null ? null : NickPaint.parse(def);
                if (paint != null) {
                    byId.put(def.optString("id"), paint);
                }
            }
            Map<String, NickPaint> next = new HashMap<>();
            for (int i = 0; i < users.length(); i++) {
                JSONObject entry = users.optJSONObject(i);
                NickPaint paint = entry == null ? null : byId.get(entry.optString("paintId"));
                if (paint != null && !entry.optString("twitchId").isEmpty()) {
                    next.put(entry.optString("twitchId"), paint);
                }
            }
            paints = Collections.unmodifiableMap(next);
            Log.i(TAG, "nick paints loaded: " + next.size() + " users, " + byId.size() + " paints");
            EmoteChatHook.redrawPaintedRows(); // rows built before the list came hold a pending span
        } catch (Throwable t) {
            Log.w(TAG, "nick paints refresh failed: " + t);
        }
    }

    private static String get(String path) throws IOException {
        Request request = new Request.Builder().url(API_BASE + path).header("Accept", "application/json").build();
        try (Response response = EmoteHttp.sharedClient().newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("HTTP " + response.code() + " for " + path);
            }
            return body.string();
        }
    }

    private static final Runnable REFRESH_TASK = new Runnable() {
        @Override
        public void run() {
            try {
                if (ChatSettings.isNickPaintsEnabled()) {
                    refreshPaints();
                }
                refresh();
                refreshStaff();
                // Signed in after the start: register on the next refresh.
                if (!selfRegistered.get()) {
                    registerSelf();
                }
            } catch (Throwable t) {
                // Daemon background thread: an escaped throwable would crash
                // the whole app, so swallow everything.
                Log.w(TAG, "badge roster refresh crashed", t);
                CrashReporter.report(TAG, "badge roster refresh crashed", t);
            }
        }
    };

    private static void refreshStaff() {
        try {
            Map<String, String> next = parse(get(STAFF_USERS_PATH));
            if (next != null && !next.equals(staffRoster)) {
                staffRoster = Collections.unmodifiableMap(next);
                Log.i(TAG, "staff roster loaded: " + next.size() + " users");
                EmoteChatHook.requestRefresh();
            }
        } catch (IOException e) {
            Log.w(TAG, "staff roster fetch I/O error: " + e);
        }
    }

    private static void refresh() {
        Request.Builder builder = new Request.Builder()
                .url(API_BASE + BADGE_USERS_PATH)
                .header("Accept", "application/json");
        String etag = lastEtag;
        if (etag != null) {
            builder.header("If-None-Match", etag);
        }

        Response response = null;
        try {
            response = EmoteHttp.sharedClient().newCall(builder.build()).execute();
            int code = response.code();
            if (code == 304) {
                Log.d(TAG, "badge roster unchanged (304)");
                return;
            }
            if (!response.isSuccessful()) {
                Log.w(TAG, "badge roster fetch failed: HTTP " + code);
                return;
            }

            String newEtag = response.header("ETag");
            ResponseBody body = response.body();
            String json = body != null ? body.string() : "";

            Map<String, String> next = parse(json);
            if (next == null) {
                return;
            }

            boolean changed = !next.equals(roster);
            roster = Collections.unmodifiableMap(next);
            if (newEtag != null) {
                lastEtag = newEtag;
            }
            Log.i(TAG, "badge roster loaded: " + next.size() + " users" + (changed ? " (changed)" : ""));

            if (changed) {
                // Redraw badges on messages already on screen.
                EmoteChatHook.requestRefresh();
            }
        } catch (IOException e) {
            Log.w(TAG, "badge roster fetch I/O error: " + e);
        } catch (Throwable t) {
            Log.w(TAG, "badge roster refresh failed", t);
            CrashReporter.report(TAG, "badge roster refresh failed", t);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * Parses the badge-users payload into a {@code userId -> badgeUrl} map.
     * Accepts the extension's array-of-objects shape
     * ({@code [{"userId": "...", "badgeUrl": "..."}, ...]}). Returns
     * {@code null} on a shape we don't recognise so the caller keeps the
     * previous roster rather than clobbering it with an empty map.
     */
    private static Map<String, String> parse(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            JSONArray list = new JSONArray(json);
            Map<String, String> result = new HashMap<>(Math.max(16, list.length() * 2));
            for (int i = 0; i < list.length(); i++) {
                JSONObject entry = list.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                String userId = entry.optString("userId", null);
                String badgeUrl = entry.optString("badgeUrl", null);
                if (userId == null || userId.isEmpty() || badgeUrl == null || badgeUrl.isEmpty()) {
                    continue;
                }
                result.put(userId, badgeUrl);
            }
            return result;
        } catch (Throwable t) {
            Log.w(TAG, "badge roster parse failed", t);
            CrashReporter.report(TAG, "badge roster parse failed", t);
            return null;
        }
    }
}
