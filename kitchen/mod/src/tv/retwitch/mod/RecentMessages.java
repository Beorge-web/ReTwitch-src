package tv.retwitch.mod;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Chat history from Robotty's recent-messages service ({@link Toggles#ROBOTTY_HISTORY}, feedback #39): more of it than
 * Twitch keeps.
 * <ul>
 *   <li>In the chat: Twitch's own history comes as GQL {@code ChatHistory}; while its answer is parsed
 *       ({@code RobottyHistoryPatch}, 31.9.0 {@code g06.fromJson} building {@code vz5(id, recentChatMessages)}) its list
 *       is swapped for an empty one when Robotty answered, and Robotty's lines go into the chat as IRC lines
 *       ({@link JailChat#injectHistory}) once the channel is joined (its ROOMSTATE). Robotty silent: Twitch's history
 *       stays. The lines carry {@code historical=1}: danmaku and the new-messages count skip them.</li>
 *   <li>On a viewer card: the same lines go into {@link UserHistory}, which otherwise knows only what came while the
 *       chat was open.</li>
 * </ul>
 * The channel's login goes to a third-party service, hence a switch of its own, off by default.
 */
public final class RecentMessages {

    private static final String TAG = "RTRecentMessages";
    private static final String URL = "https://recent-messages.robotty.de/api/v2/recent-messages/";
    private static final long FRESH_MS = 60_000;
    /** Lines put into the chat: Twitch keeps only the newest of a backlog anyway. */
    private static final int CHAT_LINES = 200;
    private static final OkHttpClient HTTP = VodDownload.HTTP.newBuilder().callTimeout(4, TimeUnit.SECONDS).build();
    /** login -> {time fetched, lines}. */
    private static final Map<String, Object[]> CACHE = new HashMap<>();
    /** login -> when a card last loaded it: the card's layout listener calls {@link #load} on every pass. */
    private static final Map<String, Long> CARD = new HashMap<>();
    /** room id -> when its last ROOMSTATE came: a join sends one, so a recent one means the channel is joined now. */
    private static final Map<String, Long> JOINED = new HashMap<>();
    private static final long JOINED_MS = 20_000;
    /** {room id, time, lines}: the backlog waiting for its chat list (main thread reads it unlocked: a hint only). */
    private static volatile Object[] pending;
    /** room id -> when its backlog went in: the theatre's preload and ChatHistory both carry history for one open. */
    private static final Map<String, Long> INJECTED = new HashMap<>();
    private static final long WAIT_MS = 30_000;
    private static final long SAME_OPEN_MS = 15_000;
    /** No chat row bound this long after the history came: in anyway (a quiet chat, or the row hook missing). */
    private static final long FALLBACK_MS = 8000;
    /**
     * Twitch applies its (emptied) history to the list a while after the parse and drops what came before: on a
     * channel switch lines sent 0.3-0.6 s after the parse vanished, 8 s after it they stayed. So never sooner.
     */
    private static final long AFTER_PARSE_MS = 2000;
    private static Handler main;

    private RecentMessages() {
    }

    static boolean wanted() {
        return Toggles.on(Toggles.ROBOTTY_HISTORY);
    }

    /** The channel's recent raw IRC lines, oldest first, cached for a minute; null if Robotty doesn't answer. Network. */
    private static List<String> fetch(String login) {
        synchronized (CACHE) {
            Object[] cached = CACHE.get(login);
            if (cached != null && System.currentTimeMillis() - (Long) cached[0] < FRESH_MS) {
                @SuppressWarnings("unchecked")
                List<String> lines = (List<String>) cached[1];
                return lines;
            }
        }
        // Through the emote proxy: directly the TLS answer stalled past the timeout on the user's phone (TCP fine).
        String url = tv.retwitch.mod.emotes.EmoteProxy.proxied(URL + login + "?limit=800&hide_moderation_messages=true");
        try {
            Request request = new Request.Builder().url(url).header("User-Agent", "ReTwitch").build();
            try (Response response = HTTP.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new java.io.IOException("HTTP " + response.code());
                }
                JSONArray messages = new JSONObject(response.body().string()).optJSONArray("messages");
                List<String> lines = new ArrayList<>();
                for (int i = 0; messages != null && i < messages.length(); i++) {
                    lines.add(messages.optString(i));
                }
                synchronized (CACHE) {
                    CACHE.put(login, new Object[]{System.currentTimeMillis(), lines});
                }
                Log.i(TAG, login + ": " + lines.size() + " lines");
                return lines;
            }
        } catch (Throwable t) {
            Log.w(TAG, login + ": failed via " + url.substring(0, url.indexOf('/', 8) + 1) + ": " + t);
            tv.retwitch.mod.emotes.EmoteProxy.failed(url);
            return null;
        }
    }

    /** Viewer card: loads the channel's recent lines into the history (background), then {@code done} on the main thread. */
    static void load(String login, Runnable done) {
        if (login == null || login.isEmpty() || !wanted()) {
            return;
        }
        synchronized (CARD) {
            Long at = CARD.get(login);
            if (at != null && System.currentTimeMillis() - at < FRESH_MS) {
                return;
            }
            CARD.put(login, System.currentTimeMillis());
        }
        new Thread(() -> {
            List<String> lines = fetch(login);
            int added = 0;
            for (int i = 0; lines != null && i < lines.size(); i++) {
                added += UserHistory.noteHistorical(parse(lines.get(i))) ? 1 : 0;
            }
            if (added > 0 && done != null) {
                if (main == null) {
                    main = new Handler(Looper.getMainLooper());
                }
                main.post(done);
            }
        }, "RTRecentMessages").start();
    }

    /**
     * Injected while Twitch parses its chat history ({@code RobottyHistoryPatch}), on a parser thread: Twitch's list,
     * or an empty one when Robotty's lines replace it. Never throws.
     */
    public static java.util.ArrayList<?> onTwitchHistory(String channelId, java.util.ArrayList<?> twitch) {
        try {
            if (!wanted() || channelId == null || Looper.myLooper() == Looper.getMainLooper()) {
                return twitch;
            }
            String login = ChannelTracker.loginOf(channelId);
            List<String> lines = login == null ? null : fetch(login);
            List<String> chat = new ArrayList<>();
            for (int i = 0; lines != null && i < lines.size(); i++) {
                if (parse(lines.get(i)) != null) {
                    chat.add(lines.get(i));
                }
            }
            if (chat.isEmpty()) {
                return twitch;
            }
            List<String> last = new ArrayList<>(chat.subList(Math.max(0, chat.size() - CHAT_LINES), chat.size()));
            long now = System.currentTimeMillis();
            synchronized (JOINED) {
                Long injected = INJECTED.get(channelId);
                if (injected != null && now - injected < SAME_OPEN_MS
                        || pending != null && channelId.equals(pending[0]) && now - (Long) pending[1] < SAME_OPEN_MS) {
                    return new java.util.ArrayList<>(); // this open's backlog is in or on its way
                }
                Log.i(TAG, login + ": " + last.size() + " lines into the chat instead of Twitch's " + (twitch == null ? 0 : twitch.size()));
                LIVE_IDS.clear();
                pending = new Object[]{channelId, now, last, login};
            }
            Long ready = READY.get(login);
            if (ready != null && now - ready < JOINED_MS) {
                later(() -> flush(false), AFTER_PARSE_MS); // its list showed the welcome line before the history came
            }
            later(() -> flush(true), FALLBACK_MS);
            return new java.util.ArrayList<>();
        } catch (Throwable t) {
            Log.w(TAG, "onTwitchHistory failed", t);
            return twitch;
        }
    }

    /** ROOMSTATE of a channel (ModLogs): it's joined. */
    static void onRoomState(Map<?, ?> tags) {
        Object room = tags == null ? null : tags.get("room-id");
        if (room == null) {
            return;
        }
        synchronized (JOINED) {
            JOINED.put(room.toString(), System.currentTimeMillis());
            Log.i(TAG, "ROOMSTATE " + room + (pending != null && room.toString().equals(pending[0]) ? ", backlog waiting" : ""));
        }
    }

    /** login -> when a chat row naming the channel was bound: its own list is up (the welcome line has its name). */
    private static final Map<String, Long> READY = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Any chat row bound (AutoModChat.onRowBound, main thread). The welcome line ("Welcome to the chat room!" in any
     * language, with the channel's name) is the first row of a channel's own list; the old list of the channel left
     * behind keeps binding rows for a while, so a row only counts when it names the channel. Never throws.
     */
    public static void onRowView(View view) {
        try {
            if (!(view instanceof TextView) || !wanted()) {
                return;
            }
            String text = ((TextView) view).getText().toString().toLowerCase(java.util.Locale.ROOT);
            Object[] p = pending;
            String login = p != null ? (String) p[3] : ChannelTracker.getCurrentChannelLogin();
            if (login != null && text.contains(login)) {
                READY.put(login, System.currentTimeMillis());
                if (p != null) {
                    later(() -> flush(false), Math.max(300, (Long) p[1] + AFTER_PARSE_MS - System.currentTimeMillis()));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "onRowView failed", t);
        }
    }

    /** Ids of live messages of the waiting backlog's channel (IRC thread adds, main thread reads). */
    private static final Set<String> LIVE_IDS = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private static final Map<Class<?>, List<java.lang.reflect.Field>> ROW_FIELDS = new HashMap<>();

    /** A live PRIVMSG (ModLogs): its id, if it's of the channel whose backlog waits. */
    static void onLivePrivmsg(Map<?, ?> tags) {
        Object[] p = pending;
        Object room = tags.get("room-id");
        Object id = tags.get("id");
        if (p != null && room != null && id != null && p[0].equals(room.toString()) && LIVE_IDS.size() < 200) {
            LIVE_IDS.add(id.toString());
        }
    }

    /**
     * A chat message row is being bound (FirstMessageHighlight.onRowBind, main thread, its model). Lines sent before
     * the channel's own chat list takes messages were parsed by the SDK and never shown (Pixel: at the join, and on
     * a switch from another channel, where the old list was still binding rows). A live message of the waiting
     * channel reaching a row means its list is up. Never throws.
     */
    public static void onMessageRowBound(Object row) {
        if (pending == null || LIVE_IDS.isEmpty() || row == null) {
            return;
        }
        try {
            List<java.lang.reflect.Field> fields = ROW_FIELDS.get(row.getClass());
            if (fields == null) {
                fields = new ArrayList<>();
                for (java.lang.reflect.Field f : row.getClass().getDeclaredFields()) {
                    if (f.getType() == String.class) {
                        f.setAccessible(true);
                        fields.add(f);
                    }
                }
                ROW_FIELDS.put(row.getClass(), fields);
            }
            for (java.lang.reflect.Field f : fields) {
                Object v = f.get(row);
                if (v != null && LIVE_IDS.contains(v.toString())) {
                    Object[] p = pending;
                    if (p != null) {
                        later(() -> flush(false), Math.max(300, (Long) p[1] + AFTER_PARSE_MS - System.currentTimeMillis()));
                    }
                    return;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "onMessageRowBound failed", t);
        }
    }

    /** The waiting backlog into the chat, once its channel is joined; {@code fallback}: no row was bound in time. */
    private static void flush(boolean fallback) {
        List<String> lines;
        synchronized (JOINED) {
            Object[] p = pending;
            if (p == null) {
                return;
            }
            long now = System.currentTimeMillis();
            Long joined = JOINED.get((String) p[0]);
            if (now - (Long) p[1] > WAIT_MS) {
                pending = null; // the channel never got joined: stale
                LIVE_IDS.clear();
                return;
            }
            if (joined == null || joined < (Long) p[1] - JOINED_MS || now - (Long) p[1] < AFTER_PARSE_MS - 50) {
                return; // not joined in this open yet, or too soon after the parse
            }
            pending = null;
            LIVE_IDS.clear();
            INJECTED.put((String) p[0], now);
            @SuppressWarnings("unchecked")
            List<String> held = (List<String>) p[2];
            lines = held;
        }
        Log.i(TAG, "backlog of " + lines.size() + " lines goes into the next IRC frame" + (fallback ? " (no live message shown yet)" : ""));
        JailChat.injectHistory(lines);
    }

    private static void later(Runnable r, long delayMs) {
        if (main == null) {
            main = new Handler(Looper.getMainLooper());
        }
        main.postDelayed(r, delayMs);
    }

    /** A line Robotty marked as from the backlog. */
    static boolean historical(Map<?, ?> tags) {
        return tags != null && "1".equals(String.valueOf(tags.get("historical")));
    }

    /** An IRC PRIVMSG line as {tags, content}; null for anything else. Pure: {@code RecentMessagesSelfCheck}. */
    static Object[] parse(String line) {
        if (line == null || !line.startsWith("@")) {
            return null;
        }
        int space = line.indexOf(' ');
        if (space < 0) {
            return null;
        }
        Map<String, String> tags = new HashMap<>();
        for (String pair : line.substring(1, space).split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                tags.put(pair.substring(0, eq), unescape(pair.substring(eq + 1)));
            }
        }
        String rest = line.substring(space + 1);
        if (rest.startsWith(":")) {
            int end = rest.indexOf(' ');
            if (end < 0) {
                return null;
            }
            rest = rest.substring(end + 1);
        }
        if (!rest.startsWith("PRIVMSG ")) {
            return null;
        }
        int colon = rest.indexOf(" :");
        return colon < 0 ? null : new Object[]{tags, rest.substring(colon + 2)};
    }

    /** IRCv3 tag value escapes. */
    static String unescape(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 == value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 's': out.append(' '); break;
                case ':': out.append(';'); break;
                case 'r': out.append('\r'); break;
                case 'n': out.append('\n'); break;
                default: out.append(next);
            }
        }
        return out.toString();
    }
}
