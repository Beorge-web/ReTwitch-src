package tv.retwitch.mod;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "If you're banned on a channel" ({@code BanBypassPatch}). The access token says the account is banned
 * before anything asks usher; Twitch stops there with its "You're banned" screen. The reason is dropped so
 * the stream goes on to usher, where {@code VaftServer} retries a refused master anonymously. A banned
 * account also gets no messages in that chat, so the IRC login of that channel goes anonymous, which the
 * chat SDK supports: no token means {@code PASS listen}.
 */
public final class ChatBan {

    private static final String TAG = "RTChatBan";
    private static final String BANNED = "CHANNEL_RESTRICTED_BANNED_USER";

    /** Logins the token refused this session. */
    private static final Set<String> CHANNELS = ConcurrentHashMap.newKeySet();
    private static volatile boolean anonymous;

    private ChatBan() {
    }

    /** Injected where the token's forbidden reason is read: {@code response} is the token model. Never throws. */
    public static Enum<?> onTokenReason(Enum<?> reason, Object response) {
        try {
            if (reason != null && !"NONE".equals(reason.name())) {
                // GEOBLOCKED also means a proxy or VPN (anonymizer_blocked), same "not in your region" screen.
                JSONObject token = token(response);
                tv.retwitch.mod.ads.VaftLog.log("token refused: " + reason.name() + " "
                        + (token == null ? "" : token.optString("channel") + " " + token.optString("geoblock_reason")));
            }
            if (reason == null || !BANNED.equals(reason.name()) || !Toggles.on(Toggles.BAN_BYPASS)) {
                return reason;
            }
            String login = login(response);
            Log.i(TAG, "token refused for " + login);
            if (login != null) {
                CHANNELS.add(login);
                sync();
            }
            return valueOf(reason);
        } catch (Throwable t) {
            Log.w(TAG, "onTokenReason failed", t);
            return reason;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Enum<?> valueOf(Enum<?> reason) {
        return Enum.valueOf((Class) reason.getDeclaringClass(), "NONE");
    }

    /** The channel in the token's JSON value, the one String field holding it. */
    /** The token's JSON: the String field of the model holding an object. */
    private static JSONObject token(Object response) throws IllegalAccessException {
        for (Field field : response.getClass().getDeclaredFields()) {
            if (field.getType() == String.class) {
                field.setAccessible(true);
                String value = (String) field.get(response);
                if (value != null && value.startsWith("{")) {
                    try {
                        return new JSONObject(value);
                    } catch (org.json.JSONException e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static String login(Object response) throws IllegalAccessException {
        JSONObject token = token(response);
        String channel = token == null ? "" : token.optString("channel");
        return channel.isEmpty() ? null : channel.toLowerCase(Locale.ROOT);
    }

    private static boolean wanted() {
        String login = ChannelTracker.getCurrentChannelLogin();
        return login != null && CHANNELS.contains(login) && Toggles.on(Toggles.BAN_BYPASS);
    }

    /** The channel or the ban changed: QUIT makes the chat SDK log in again, through the two hooks below. */
    static void sync() {
        if (wanted() != anonymous) {
            Log.i(TAG, "reconnecting chat, anonymous " + !anonymous);
            JailChat.sendIrc("QUIT");
        }
    }

    /**
     * Injected where the IRC parser meets {@code NOTICE msg_banned} (31.9.0 {@code tna.L}): Twitch then parts the
     * channel for good, "Disconnected from chat". A chat-only ban leaves the token clean, so this is where it shows.
     * The channel goes anonymous instead. Never throws.
     */
    public static boolean banNotice(boolean banned) {
        try {
            if (!banned || !Toggles.on(Toggles.BAN_BYPASS)) {
                return banned;
            }
            // shortcut: the notice comes on joining, taken for the open channel; a mini player's chat may differ.
            String login = ChannelTracker.getCurrentChannelLogin();
            if (login == null) {
                return banned;
            }
            Log.i(TAG, "chat ban for " + login);
            CHANNELS.add(login);
            // Out of the parser: QUIT goes through the same socket.
            new Handler(Looper.getMainLooper()).post(ChatBan::sync);
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "banNotice failed", t);
            return banned;
        }
    }

    /** Injected into the IRC login: the OAuth token, or null for an anonymous one. Never throws. */
    public static String loginToken(String token) {
        anonymous = wanted();
        return anonymous ? null : token;
    }

    /** Injected into the IRC login, after {@link #loginToken}. Never throws. */
    public static String loginNick(String nick) {
        return anonymous ? "justinfan" + (10000 + new Random().nextInt(80000)) : nick;
    }
}
