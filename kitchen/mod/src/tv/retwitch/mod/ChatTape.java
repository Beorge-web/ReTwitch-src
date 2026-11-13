package tv.retwitch.mod;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * The chat of a recording ({@link Toggles#LIVE_RECORD_CHAT}), a JSON line per message in the recording's
 * {@link Records#CHAT}: {@code {"t": clock, "u": login, "n": name, "i": user id, "c": "#color", "b": "set/version,...",
 * "m": 1 for /me, "f": ["text", ["Kappa", "25"], ...]}}. Two sources:
 * <ul>
 *   <li>archive and VOD: Twitch's chat replay ({@code video.comments}), {@code t} is the VOD offset in ms. Anonymous,
 *       but paging by cursor only with the Android Client-ID (the web one gets {@code IntegrityCheckFailed}); ~58 per
 *       page. An archive still being written has its chat up to the current minute, so the tape polls behind it;</li>
 *   <li>live: an anonymous IRC socket ({@code justinfan}) on the channel, {@code t} is {@code tmi-sent-ts}: the chat
 *       SDK hands the mod only the open channel's events.</li>
 * </ul>
 * Where it got to is saved with the recording, so a continued recording continues its chat too.
 */
final class ChatTape {

    private static final String TAG = "RTChatTape";
    private static final String CLIENT_ID = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp";
    private static final String IRC = "wss://irc-ws.chat.twitch.tv:443";
    private static final String QUERY = "query($id:ID!,$o:Int,$c:Cursor){video(id:$id){comments(contentOffsetSeconds:$o,"
            + "after:$c){edges{cursor node{id contentOffsetSeconds commenter{id login displayName} message{userColor "
            + "userBadges{setID version} fragments{text emote{emoteID}}}}} pageInfo{hasNextPage}}}}";
    private static final long POLL_MS = 30_000;
    private static final OkHttpClient HTTP = VodDownload.HTTP.newBuilder().pingInterval(60, TimeUnit.SECONDS).build();

    private final LiveRecord.Rec rec;
    private final Thread thread;
    private OutputStream out;
    private volatile boolean finishing;
    private volatile boolean stopping;
    private volatile WebSocket socket;

    private ChatTape(LiveRecord.Rec rec) {
        this.rec = rec;
        this.thread = new Thread(this::run, "RTChatTape-" + rec.id);
    }

    static ChatTape start(LiveRecord.Rec rec) {
        ChatTape tape = new ChatTape(rec);
        tape.thread.start();
        return tape;
    }

    /**
     * The video is done: with {@code complete} (it ended on its own) the replay is fetched to the end first, else
     * the tape stops now and keeps its place for a continuation.
     */
    void finish(boolean complete) {
        if (complete && !Records.KIND_LIVE.equals(rec.kind)) {
            rec.problem = Strings.pick("дописывает чат", "saving the chat");
            finishing = true;
        } else {
            stopping = true;
            WebSocket open = socket;
            if (open != null) {
                open.close(1000, null);
            }
        }
        thread.interrupt();
        try {
            thread.join(finishing ? 0 : 5000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (finishing) {
            rec.problem = null;
        }
    }

    private void run() {
        try {
            out = new FileOutputStream(new File(rec.dir, Records.CHAT), true);
            if (Records.KIND_LIVE.equals(rec.kind)) {
                irc();
            } else if (rec.vodId != null) {
                replay();
            }
        } catch (Throwable t) {
            Log.w(TAG, "chat of " + rec.id + " stopped", t);
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException ignored) {
                // nothing to save
            }
        }
    }

    private synchronized void write(String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        rec.chatLines++;
    }

    // ---- chat replay ----

    private void replay() throws InterruptedException {
        int failures = 0;
        // Ids of the written lines at chatLastT: a page asked again (the last one, or by offset) repeats them.
        Set<String> atLast = new HashSet<>();
        if ("".equals(rec.chatCursor)) {
            rec.chatCursor = null; // saved by older builds from the last page: by offset instead
        }
        while (!stopping && !rec.stopped) {
            if (rec.chatCursor == null && rec.firstPosition < 0) {
                if (finishing) {
                    return; // over without a segment: no chat to match
                }
                try {
                    pause(2000); // the first segment says where the recording begins
                } catch (InterruptedException e) {
                    // finish() of a download faster than this wait (a fragment): fetch its chat, don't drop it.
                    if (stopping) {
                        return;
                    }
                }
                continue;
            }
            try {
                JSONObject vars = new JSONObject().put("id", rec.vodId);
                if (rec.chatCursor != null) {
                    vars.put("c", rec.chatCursor);
                } else {
                    vars.put("o", (int) (Math.max(rec.chatLastT, rec.firstPosition) / 1000));
                }
                JSONObject comments = gql(vars).getJSONObject("video").getJSONObject("comments");
                JSONArray edges = comments.optJSONArray("edges");
                for (int i = 0; edges != null && i < edges.length(); i++) {
                    JSONObject edge = edges.getJSONObject(i);
                    JSONObject node = edge.getJSONObject("node");
                    long t = node.optLong("contentOffsetSeconds") * 1000;
                    if (rec.clipTo >= 0 && t >= rec.clipTo) {
                        return; // a fragment's chat ends with it
                    }
                    String id = node.optString("id");
                    if (t >= rec.firstPosition - 1000 && t >= rec.chatLastT && !(t == rec.chatLastT
                            && atLast.contains(id))) {
                        write(fromComment(node));
                        if (t != rec.chatLastT) {
                            atLast.clear();
                        }
                        atLast.add(id);
                        rec.chatLastT = t;
                    }
                    // The last page's cursors are empty, and after "" the replay starts over from the VOD's beginning:
                    // its next poll asks after the page before, again, and the check above drops what it repeats.
                    String cursor = edge.optString("cursor");
                    if (!cursor.isEmpty()) {
                        rec.chatCursor = cursor;
                    }
                }
                failures = 0;
                if (comments.getJSONObject("pageInfo").optBoolean("hasNextPage")) {
                    pause(150);
                    continue;
                }
                if (finishing) {
                    return; // caught up with a recording that is over
                }
                pause(POLL_MS);
            } catch (InterruptedException e) {
                if (stopping) {
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, rec.id + ": replay page failed: " + e);
                if (++failures >= 20) {
                    return;
                }
                if (failures == 3 && rec.chatCursor != null) {
                    rec.chatCursor = null; // a stale cursor: by offset from the last line
                }
                pause(10_000);
            }
        }
    }

    /** Sleeps; {@link #finish} wakes it: stop, or fetch the rest without waiting. */
    private void pause(long ms) throws InterruptedException {
        if (!finishing || ms < POLL_MS) {
            Thread.sleep(ms);
        }
    }

    private static JSONObject gql(JSONObject variables) throws Exception {
        String body = new JSONObject().put("query", QUERY).put("variables", variables).toString();
        Request request = new Request.Builder().url("https://gql.twitch.tv/gql").header("Client-ID", CLIENT_ID)
                .post(RequestBody.create(MediaType.parse("application/json"), body)).build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            JSONObject json = new JSONObject(response.body().string());
            if (json.optJSONObject("data") == null || json.getJSONObject("data").isNull("video")) {
                throw new IOException("no data: " + json.optJSONArray("errors"));
            }
            return json.getJSONObject("data");
        }
    }

    // ---- live IRC ----

    private void irc() throws InterruptedException {
        long backoff = 5_000;
        while (!stopping) {
            final Object done = new Object();
            final boolean[] closed = {false};
            final long opened = System.currentTimeMillis();
            String url = Toggles.on(Toggles.CHAT_PROXY) ? UsherHook.CHAT_PROXY_URL : IRC;
            socket = HTTP.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
                @Override
                public void onOpen(WebSocket ws, Response response) {
                    ws.send("CAP REQ :twitch.tv/tags twitch.tv/commands");
                    ws.send("PASS SCHMOOPIIE");
                    ws.send("NICK justinfan" + (10_000 + (int) (Math.random() * 80_000)));
                    ws.send("JOIN #" + rec.login);
                }

                @Override
                public void onMessage(WebSocket ws, String text) {
                    for (String line : text.split("\r\n")) {
                        try {
                            if (line.startsWith("PING")) {
                                ws.send("PONG" + line.substring(4));
                            } else {
                                String json = fromIrc(line);
                                if (json != null) {
                                    write(json);
                                }
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "IRC line failed: " + e);
                        }
                    }
                }

                @Override
                public void onClosed(WebSocket ws, int code, String reason) {
                    end();
                }

                @Override
                public void onFailure(WebSocket ws, Throwable t, Response response) {
                    Log.w(TAG, rec.id + ": IRC failed: " + t);
                    end();
                }

                private void end() {
                    synchronized (done) {
                        closed[0] = true;
                        done.notifyAll();
                    }
                }
            });
            try {
                synchronized (done) {
                    while (!closed[0] && !stopping) {
                        done.wait(1000);
                    }
                }
            } finally {
                socket.cancel();
                socket = null;
            }
            if (stopping) {
                return;
            }
            backoff = System.currentTimeMillis() - opened > 60_000 ? 5_000 : Math.min(60_000, backoff * 2);
            Thread.sleep(backoff);
        }
    }

    // ---- lines (pure; ChatTapeSelfCheck) ----

    /** {"t": ...} first, so the player reads the clock without parsing the line. */
    static String line(long t, JSONObject rest) {
        String body = rest.toString();
        return "{\"t\":" + t + (body.length() > 2 ? "," + body.substring(1) : "}");
    }

    /** A chat replay comment as a line; its clock is the VOD offset in ms. */
    static String fromComment(JSONObject node) throws Exception {
        JSONObject who = node.optJSONObject("commenter");
        JSONObject message = node.optJSONObject("message");
        JSONObject rest = new JSONObject();
        if (who != null) {
            rest.put("u", who.optString("login")).put("n", who.optString("displayName")).put("i", who.optString("id"));
        }
        JSONArray fragments = new JSONArray();
        if (message != null) {
            String color = message.optString("userColor", "");
            if (!color.isEmpty() && !"null".equals(color)) {
                rest.put("c", color);
            }
            JSONArray badges = message.optJSONArray("userBadges");
            StringBuilder b = new StringBuilder();
            for (int i = 0; badges != null && i < badges.length(); i++) {
                JSONObject badge = badges.getJSONObject(i);
                if (!badge.optString("setID").isEmpty()) {
                    b.append(b.length() > 0 ? "," : "").append(badge.optString("setID")).append('/')
                            .append(badge.optString("version"));
                }
            }
            if (b.length() > 0) {
                rest.put("b", b.toString());
            }
            JSONArray parts = message.optJSONArray("fragments");
            for (int i = 0; parts != null && i < parts.length(); i++) {
                JSONObject part = parts.getJSONObject(i);
                JSONObject emote = part.optJSONObject("emote");
                String text = part.optString("text");
                if (emote != null && !emote.optString("emoteID").isEmpty()) {
                    fragments.put(new JSONArray().put(text).put(emote.optString("emoteID")));
                } else {
                    fragments.put(text);
                }
            }
        }
        return line(node.optLong("contentOffsetSeconds") * 1000, rest.put("f", fragments));
    }

    /** An IRC PRIVMSG with tags as a line, its clock {@code tmi-sent-ts}; null for anything else. */
    static String fromIrc(String raw) throws Exception {
        if (!raw.startsWith("@")) {
            return null;
        }
        int space = raw.indexOf(' ');
        if (space < 0) {
            return null;
        }
        Map<String, String> tags = new HashMap<>();
        for (String tag : raw.substring(1, space).split(";")) {
            int eq = tag.indexOf('=');
            if (eq > 0) {
                tags.put(tag.substring(0, eq), unescape(tag.substring(eq + 1)));
            }
        }
        String rest = raw.substring(space + 1);
        int command = rest.indexOf(" PRIVMSG #");
        int colon = command < 0 ? -1 : rest.indexOf(" :", command + 10);
        if (!rest.startsWith(":") || command < 0 || colon < 0) {
            return null;
        }
        int bang = rest.indexOf('!');
        String login = bang > 1 && bang < command ? rest.substring(1, bang) : "";
        String text = rest.substring(colon + 2);
        boolean me = text.startsWith("\u0001ACTION ") && text.endsWith("\u0001");
        if (me) {
            text = text.substring(8, text.length() - 1);
        }
        JSONObject out = new JSONObject().put("u", login).put("n", tags.containsKey("display-name")
                && !tags.get("display-name").isEmpty() ? tags.get("display-name") : login);
        if (tags.containsKey("user-id")) {
            out.put("i", tags.get("user-id"));
        }
        if (tags.containsKey("color") && !tags.get("color").isEmpty()) {
            out.put("c", tags.get("color"));
        }
        if (tags.containsKey("badges") && !tags.get("badges").isEmpty()) {
            out.put("b", tags.get("badges"));
        }
        if (me) {
            out.put("m", 1);
        }
        out.put("f", fragments(text, tags.get("emotes")));
        long t;
        try {
            t = Long.parseLong(tags.get("tmi-sent-ts"));
        } catch (RuntimeException e) {
            t = System.currentTimeMillis();
        }
        return line(t, out);
    }

    /** The text split at Twitch's {@code emotes} tag ("25:0-4,6-10/1902:12-16", code point ranges). */
    static JSONArray fragments(String text, String emotes) {
        JSONArray out = new JSONArray();
        int[] points = text.codePoints().toArray();
        String[] at = new String[points.length];
        int[] ends = new int[points.length];
        if (emotes != null && !emotes.isEmpty()) {
            for (String emote : emotes.split("/")) {
                int colon = emote.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                for (String range : emote.substring(colon + 1).split(",")) {
                    String[] ab = range.split("-");
                    try {
                        int a = Integer.parseInt(ab[0]);
                        int b = Integer.parseInt(ab[1]);
                        if (a >= 0 && b < points.length && a <= b) {
                            at[a] = emote.substring(0, colon);
                            ends[a] = b;
                        }
                    } catch (RuntimeException ignored) {
                        // a malformed range stays text
                    }
                }
            }
        }
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < points.length; i++) {
            if (at[i] != null) {
                if (plain.length() > 0) {
                    out.put(plain.toString());
                    plain.setLength(0);
                }
                out.put(new JSONArray().put(new String(points, i, ends[i] - i + 1)).put(at[i]));
                i = ends[i];
            } else {
                plain.appendCodePoint(points[i]);
            }
        }
        if (plain.length() > 0) {
            out.put(plain.toString());
        }
        return out;
    }

    private static String unescape(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char n = value.charAt(++i);
                b.append(n == 's' ? ' ' : n == ':' ? ';' : n == 'r' ? '\r' : n == 'n' ? '\n' : n);
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
