package tv.retwitch.mod;

import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.StrikethroughSpan;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * AutoMod in the regular chat for moderators ({@link Toggles#AUTOMOD_CHAT}). Twitch gets the
 * AutoMod queue only in its Mod Dashboard. Here a Hermes socket (Twitch's PubSub successor, the
 * app's own client id and the user's token) subscribes to {@code automod-queue.<user>.<channel>}
 * for the open channel: a held message becomes a chat line, a tap on it offers Allow / Deny
 * (GQL {@code allowRejectedChatMessage} / {@code denyRejectedChatMessage}), and the outcome
 * (whoever resolved it) is a line too. Not a moderator there: the subscription is refused and
 * the socket closes until the next channel. Lifecycle from {@link JailChat#apply}.
 */
public final class AutoModChat {

    private static final String TAG = "RTAutoMod";
    /** Twitch Android's client id: the user's token belongs to it. */
    private static final String CLIENT_ID = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp";
    private static final String URL = "wss://hermes.twitch.tv/v1?clientId=" + CLIENT_ID;
    private static final long RETRY_MS = 15_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable RECONNECT = AutoModChat::connect;

    private static OkHttpClient client;
    private static WebSocket socket;
    /** The topic wanted for the open channel, null when off. */
    private static String topic;
    /** The server refused {@link #topic} (not a moderator there): no reconnects for it. */
    private static boolean refused;

    /** Chat line of a held message -> its message id (the latest few). */
    private static final Map<String, String> HELD = new LinkedHashMap<String, String>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 50;
        }
    };

    private AutoModChat() {
    }

    /** From {@link JailChat#apply} (main thread): the channel changed, or the app came / went. */
    static void apply(boolean foreground) {
        try {
            String me = TwitchAuth.getUserId();
            String channel = ChannelTracker.getCurrentChannelId();
            String want = foreground && Toggles.on(Toggles.AUTOMOD_CHAT) && me != null && !me.isEmpty()
                    && channel != null ? "automod-queue." + me + "." + channel : null;
            if (want == null ? topic == null : want.equals(topic)) {
                return;
            }
            MAIN.removeCallbacks(RECONNECT);
            if (socket != null) {
                socket.cancel();
                socket = null;
            }
            topic = want;
            refused = false;
            if (want != null) {
                connect();
            }
        } catch (Throwable t) {
            Log.w(TAG, "apply failed", t);
        }
    }

    private static void connect() {
        if (topic == null || refused || socket != null) {
            return;
        }
        if (client == null) {
            client = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                    .pingInterval(30, TimeUnit.SECONDS).build();
        }
        socket = client.newWebSocket(new Request.Builder().url(URL).build(), new Listener(topic));
    }

    private static String frame(String type, JSONObject body) throws Exception {
        return new JSONObject().put("type", type).put("id", UUID.randomUUID().toString()).put(type, body)
                .put("timestamp", now()).toString();
    }

    private static String now() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.ROOT);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date());
    }

    private static final class Listener extends WebSocketListener {
        private final String wanted;

        Listener(String wanted) {
            this.wanted = wanted;
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            try {
                JSONObject m = new JSONObject(text);
                String type = m.optString("type");
                if (m.has("welcome")) {
                    ws.send(frame("authenticate", new JSONObject().put("token", TwitchAuth.getAuthToken())));
                } else if ("authenticateResponse".equals(type)) {
                    JSONObject r = m.getJSONObject("authenticateResponse");
                    if (!"ok".equals(r.optString("result"))) {
                        Log.w(TAG, "auth refused: " + r);
                        refuse(ws);
                        return;
                    }
                    ws.send(frame("subscribe", new JSONObject().put("id", UUID.randomUUID().toString())
                            .put("type", "pubsub").put("pubsub", new JSONObject().put("topic", wanted))));
                } else if ("subscribeResponse".equals(type)) {
                    JSONObject r = m.getJSONObject("subscribeResponse");
                    Log.i(TAG, wanted + ": " + r.optString("result") + " " + r.optString("error"));
                    if (!"ok".equals(r.optString("result"))) {
                        refuse(ws); // not a moderator of this channel
                    }
                } else if ("notification".equals(type)) {
                    Object pubsub = m.getJSONObject("notification").opt("pubsub");
                    onCaught(pubsub instanceof JSONObject ? (JSONObject) pubsub : new JSONObject(String.valueOf(pubsub)));
                } else if ("reconnect".equals(type)) {
                    ws.close(1000, null);
                    dropped(ws);
                }
            } catch (Throwable t) {
                Log.w(TAG, "bad frame: " + text, t);
            }
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            dropped(ws);
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            Log.w(TAG, "socket failed: " + t);
            dropped(ws);
        }
    }

    private static void refuse(final WebSocket ws) {
        MAIN.post(() -> {
            if (ws == socket) {
                refused = true;
                socket = null;
            }
            ws.close(1000, null);
        });
    }

    private static void dropped(final WebSocket ws) {
        MAIN.post(() -> {
            if (ws == socket) {
                socket = null;
                MAIN.postDelayed(RECONNECT, RETRY_MS);
            }
        });
    }

    /** One {@code automod_caught_message}: a held message, or how it was resolved. */
    static void onCaught(JSONObject event) {
        if (!"automod_caught_message".equals(event.optString("type"))) {
            return;
        }
        JSONObject data = event.optJSONObject("data");
        JSONObject message = data == null ? null : data.optJSONObject("message");
        if (message == null) {
            return;
        }
        JSONObject sender = message.optJSONObject("sender");
        String name = sender == null ? "?" : sender.optString("display_name", sender.optString("login", "?"));
        JSONObject content = message.optJSONObject("content");
        String text = content == null ? "" : content.optString("text");
        String status = data.optString("status");
        if ("PENDING".equals(status)) {
            Log.i(TAG, "held: " + event);
            JSONObject c = data.optJSONObject("content_classification");
            // Blocked terms come as reason_code "BlockedTermCaughtMessageReason", category "unknown" / 0.
            String why = data.optString("reason_code").contains("BlockedTerm") ? Strings.autoModBlockedTerm()
                    : c != null && c.optInt("level") > 0 && !"unknown".equals(c.optString("category"))
                    ? c.optString("category") + " " + c.optInt("level") : "";
            String line = Strings.autoModHeld(name, text, why);
            synchronized (HELD) {
                HELD.put(line, message.optString("id"));
            }
            ChatNotices.post(line);
        } else if (!status.isEmpty()) {
            resolved(message.optString("id"));
            ChatNotices.post(Strings.autoModResolved(name, status, data.optString("resolver_login")));
        }
    }

    private static volatile java.lang.reflect.Field itemView;

    /** Message ids already allowed / denied (by anyone): their buttons are struck through. */
    private static final Map<String, Boolean> RESOLVED = new LinkedHashMap<String, Boolean>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 100;
        }
    };

    private static void resolved(String messageId) {
        synchronized (HELD) {
            RESOLVED.put(messageId, Boolean.TRUE);
        }
    }

    /**
     * Injected after the chat adapter binds a row ({@code holder.u(item)}): a held message's
     * line (a system message, a bare TextView) gets its Allow / Deny buttons. Never throws.
     */
    /** Injected at the entry of the chat adapter's bind: the adapter and the bound position. */
    public static void onBindStart(Object adapter, int position) {
        ChatRows.onBindStart(adapter, position);
    }

    public static void onRowBound(Object holder) {
        if (holder != null) {
            BanDiag.onRow(holder);
        }
        if (holder == null) {
            return;
        }
        try {
            java.lang.reflect.Field f = itemView;
            if (f == null) {
                // Top-most match: the base ViewHolder's itemView, a subclass field would not fit other rows.
                for (Class<?> k = holder.getClass(); k != null; k = k.getSuperclass()) {
                    for (java.lang.reflect.Field c : k.getDeclaredFields()) {
                        if (c.getType() == View.class) {
                            f = c;
                        }
                    }
                }
                if (f != null) {
                    f.setAccessible(true);
                    itemView = f;
                }
            }
            Object view = f == null ? null : f.get(holder);
            tv.retwitch.mod.emotes.EmoteChatHook.scaleRow(view);
            if (view instanceof View) {
                DeletedMessagesHook.tintRow((View) view);
                ChatRows.onRowBound((View) view);
                RecentMessages.onRowView((View) view);
            }
            if (view instanceof TextView && !HELD.isEmpty()) {
                decorate((TextView) view);
            }
        } catch (Throwable t) {
            Log.w(TAG, "onRowBound failed", t);
        }
    }

    /**
     * Like Twitch's mod-mode card: the held message on a red tint, then Allow / Deny as buttons
     * on the last line -- struck through once resolved.
     */
    private static void decorate(final TextView view) {
        String shown = view.getText().toString();
        synchronized (HELD) {
            for (Map.Entry<String, String> held : HELD.entrySet()) {
                String line = held.getKey();
                int at = shown.indexOf(line);
                if (at < 0) {
                    continue;
                }
                final String id = held.getValue();
                SpannableString s = new SpannableString(view.getText());
                int body = at + line.indexOf('\n') + 1;
                int actions = at + line.lastIndexOf('\n') + 1;
                s.setSpan(new BackgroundColorSpan(0x40EB0400), body, actions - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (RESOLVED.containsKey(id)) {
                    s.setSpan(new StrikethroughSpan(), actions, at + line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else {
                    button(s, line, at, Strings.autoModAllow(), id, true);
                    button(s, line, at, Strings.autoModDeny(), id, false);
                    if (view.getMovementMethod() == null) {
                        view.setMovementMethod(LinkMovementMethod.getInstance());
                    }
                }
                view.setText(s);
                return;
            }
        }
    }

    private static void button(SpannableString s, String line, int at, String label, final String id, final boolean allow) {
        int start = at + line.lastIndexOf(label);
        s.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                resolve((TextView) widget, id, allow);
            }

            @Override
            public void updateDrawState(android.text.TextPaint paint) {
                paint.setColor(0xFFBF94FF);
                paint.setFakeBoldText(true);
                paint.setUnderlineText(false);
            }
        }, start, start + label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** GQL with the user's token: allow or deny the held message; Twitch's answer as a toast. */
    private static void resolve(final TextView view, final String messageId, final boolean allow) {
        new Thread(() -> {
            String result;
            boolean ok = false;
            try {
                String field = allow ? "allowRejectedChatMessage" : "denyRejectedChatMessage";
                JSONObject body = new JSONObject().put("query",
                        "mutation{" + field + "(input:{id:" + JSONObject.quote(messageId) + "}){__typename}}");
                Request request = new Request.Builder().url("https://gql.twitch.tv/gql")
                        .header("Client-ID", CLIENT_ID)
                        .header("Authorization", "OAuth " + TwitchAuth.getAuthToken())
                        .post(okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json"), body.toString()))
                        .build();
                try (Response response = client.newCall(request).execute()) {
                    JSONObject r = new JSONObject(response.body().string());
                    org.json.JSONArray errors = r.optJSONArray("errors");
                    ok = errors == null;
                    result = ok ? (allow ? Strings.autoModAllowed() : Strings.autoModDenied())
                            : errors.getJSONObject(0).optString("message");
                    Log.i(TAG, field + " " + messageId + ": " + r);
                }
            } catch (Throwable t) {
                Log.w(TAG, "resolve failed", t);
                result = String.valueOf(t.getMessage());
            }
            final String toast = result;
            final boolean done = ok;
            MAIN.post(() -> {
                if (done) {
                    resolved(messageId);
                    view.setText(view.getText().toString()); // drop the buttons, then strike them
                    decorate(view);
                }
                Toast.makeText(view.getContext(), "AutoMod: " + toast, Toast.LENGTH_SHORT).show();
            });
        }, TAG).start();
    }
}
