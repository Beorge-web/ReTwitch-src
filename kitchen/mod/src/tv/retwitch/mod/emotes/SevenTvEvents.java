package tv.retwitch.mod.emotes;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import tv.retwitch.mod.ChannelTracker;
import tv.retwitch.mod.ChatNotices;
import tv.retwitch.mod.Strings;

/**
 * 7TV emotes added, removed or renamed while the chat is open, as in RTE: a socket to 7TV's EventAPI (through
 * the emote proxy when it is on) subscribed to {@code emote_set.update} of the open channel's set. Changes go
 * straight into {@link EmoteCache} and a line into the chat. The same socket subscribes to {@code entitlement.*}
 * and {@code emote_set.*} in the channel's context: personal emote sets of its chatters ({@link SevenTvPersonal}).
 * Runs while 7TV emotes are on, the app is in front and a channel is open; failed connects alternate proxy and
 * direct, with a growing pause.
 */
public final class SevenTvEvents {

    private static final String TAG = "RTSevenTvEvents";
    private static final String DIRECT_URL = "wss://events.7tv.io/v3";
    private static final long RETRY_MS = 5_000;
    private static final long MAX_RETRY_MS = 60_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable RECONNECT = SevenTvEvents::connect;

    private static OkHttpClient client;
    private static WebSocket socket;
    private static boolean foreground;
    private static String channel;
    /** The open channel's emote set, null when it has none. */
    private static String setId;
    /** Whether the socket is wanted: 7TV on, app in front, a channel open. */
    private static boolean on;
    private static int failures;

    private SevenTvEvents() {
    }

    /** The emote set of a channel just became known (any thread). */
    static void refresh() {
        MAIN.post(() -> apply(foreground));
    }

    /** From {@code JailChat.apply} (main thread): the channel changed, or the app came or went. */
    public static void apply(boolean inFront) {
        try {
            foreground = inFront;
            String current = ChannelTracker.getCurrentChannelId();
            boolean want = inFront && current != null && EmoteSettings.isSevenTvEnabled();
            String set = want ? SevenTvClient.emoteSetId(current) : null;
            if (want == on && java.util.Objects.equals(current, channel) && java.util.Objects.equals(set, setId)) {
                return;
            }
            if (!want || !java.util.Objects.equals(current, channel)) {
                SevenTvPersonal.clear();
            }
            MAIN.removeCallbacks(RECONNECT);
            if (socket != null) {
                socket.cancel();
                socket = null;
            }
            on = want;
            setId = set;
            channel = current;
            failures = 0;
            connect();
        } catch (Throwable t) {
            Log.w(TAG, "apply failed", t);
        }
    }

    private static void connect() {
        if (!on || socket != null) {
            return;
        }
        if (client == null) {
            client = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                    .pingInterval(30, TimeUnit.SECONDS).build();
        }
        String proxy = failures % 2 == 0 ? EmoteProxy.sevenTvEventsUrl() : null;
        String url = proxy != null ? proxy : DIRECT_URL;
        Log.i(TAG, "connecting " + url + " channel " + channel + " set " + setId);
        socket = client.newWebSocket(new Request.Builder().url(url).build(), new Listener(channel, setId));
    }

    private static void lost(WebSocket ws, String why) {
        if (ws != socket) {
            return;
        }
        socket = null;
        failures++;
        long pause = Math.min(MAX_RETRY_MS, RETRY_MS << Math.min(failures - 1, 4));
        Log.i(TAG, "lost (" + why + "), retry in " + pause + " ms");
        MAIN.removeCallbacks(RECONNECT);
        MAIN.postDelayed(RECONNECT, pause);
    }

    private static final class Listener extends WebSocketListener {
        private final String channelId;
        private final String set;
        /** The last channel set update: the set id and the channel context subscriptions may both deliver it. */
        private String lastBody;

        Listener(String channelId, String set) {
            this.channelId = channelId;
            this.set = set;
        }

        private void subscribe(WebSocket ws, String type, JSONObject condition) throws org.json.JSONException {
            ws.send(new JSONObject().put("op", 35).put("d", new JSONObject()
                    .put("type", type).put("condition", condition)).toString());
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            try {
                JSONObject message = new JSONObject(text);
                int op = message.optInt("op", -1);
                JSONObject d = message.optJSONObject("d");
                if (op == 1) { // hello
                    if (set != null) {
                        subscribe(ws, "emote_set.update", new JSONObject().put("object_id", set));
                    }
                    // Personal emote sets of the channel's chatters.
                    for (String type : new String[]{"entitlement.*", "emote_set.*"}) {
                        subscribe(ws, type, new JSONObject().put("ctx", "channel").put("platform", "TWITCH")
                                .put("id", channelId));
                    }
                } else if (op == 5) { // ack
                    Log.i(TAG, "subscribed: " + d);
                    MAIN.post(() -> {
                        if (ws == socket) {
                            failures = 0;
                        }
                    });
                } else if (op == 7) { // the server asks to reconnect
                    ws.close(1000, null);
                } else if (op == 6) {
                    Log.w(TAG, "server error: " + d);
                } else if (op == 0 && d != null) {
                    String type = d.optString("type");
                    final JSONObject body = d.optJSONObject("body");
                    if (body == null) {
                        return;
                    }
                    if (type.equals("entitlement.create") || type.equals("entitlement.delete")) {
                        JSONObject object = body.optJSONObject("object");
                        Log.i(TAG, type + " " + (object != null ? object.optString("kind") + " "
                                + object.optString("ref_id") : "?"));
                        MAIN.post(() -> {
                            if (ws == socket) {
                                SevenTvPersonal.onEntitlement(type, object);
                            }
                        });
                    } else if (type.equals("emote_set.update")) {
                        boolean channelSet = body.optString("id").equals(set);
                        String bodyText = body.toString();
                        if (channelSet && bodyText.equals(lastBody)) {
                            return;
                        }
                        if (channelSet) {
                            lastBody = bodyText;
                        }
                        MAIN.post(() -> {
                            if (ws != socket) {
                                return;
                            }
                            if (channelSet) {
                                onUpdate(body);
                            } else {
                                SevenTvPersonal.onSetUpdate(body);
                            }
                        });
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "bad message", t);
            }
        }

        @Override
        public void onClosing(WebSocket ws, int code, String reason) {
            ws.close(1000, null);
            MAIN.post(() -> lost(ws, "closed " + code));
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            MAIN.post(() -> lost(ws, String.valueOf(t)));
        }
    }

    private static void onUpdate(JSONObject body) {
        try {
            JSONObject actor = body.optJSONObject("actor");
            String who = actor != null ? actor.optString("display_name", "?") : "?";
            List<String> removed = new ArrayList<>();
            Map<String, Emote> added = new LinkedHashMap<>();
            List<String> lines = new ArrayList<>();
            JSONArray pulled = body.optJSONArray("pulled");
            for (int i = 0; pulled != null && i < pulled.length(); i++) {
                String old = name(pulled.optJSONObject(i), "old_value");
                if (old != null) {
                    removed.add(old);
                    lines.add(Strings.sevenTvRemoved(old, who));
                }
            }
            JSONArray pushed = body.optJSONArray("pushed");
            for (int i = 0; pushed != null && i < pushed.length(); i++) {
                JSONObject item = pushed.optJSONObject(i);
                if (isEmote(item) && item.optJSONObject("value") != null) {
                    added.putAll(SevenTvClient.parseEmoteArray(new JSONArray().put(item.optJSONObject("value"))));
                    lines.add(Strings.sevenTvAdded(name(item, "value"), who));
                }
            }
            JSONArray updated = body.optJSONArray("updated");
            for (int i = 0; updated != null && i < updated.length(); i++) {
                JSONObject item = updated.optJSONObject(i);
                String old = name(item, "old_value");
                if (old != null && item.optJSONObject("value") != null) {
                    removed.add(old);
                    added.putAll(SevenTvClient.parseEmoteArray(new JSONArray().put(item.optJSONObject("value"))));
                    lines.add(Strings.sevenTvRenamed(old, name(item, "value"), who));
                }
            }
            if (removed.isEmpty() && added.isEmpty()) {
                return;
            }
            EmoteCache.updateChannelSevenTv(channel, removed, added);
            for (String line : lines) {
                ChatNotices.post(line);
            }
        } catch (Throwable t) {
            Log.w(TAG, "update failed", t);
        }
    }

    private static boolean isEmote(JSONObject item) {
        return item != null && "emotes".equals(item.optString("key"));
    }

    /** The emote name in an {@code emotes} change, or null for any other change of the set. */
    private static String name(JSONObject item, String field) {
        JSONObject emote = isEmote(item) ? item.optJSONObject(field) : null;
        return emote != null ? emote.optString("name", null) : null;
    }
}
