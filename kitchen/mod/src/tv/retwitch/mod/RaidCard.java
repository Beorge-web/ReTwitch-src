package tv.retwitch.mod;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * A raid line for viewers who arrive WITH a raid. Twitch shows its raid card
 * only from the chat event sent the moment the raid lands; raiders are moved a
 * few seconds later, so their chat joins after it and never sees it. The raid
 * is remembered from Twitch's {@code RaidEventInfo} (names not obfuscated)
 * while still in the raiding channel; when the channel then switches to the
 * raid's target, our own line is added -- unless Twitch's own raid card
 * ({@code RaidNotice} chat event) did arrive. Never throws.
 */
public final class RaidCard {

    private static final String TAG = "RTRaidCard";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile String source;
    private static volatile String target;
    private static volatile int viewers;
    private static volatile long raidAt;
    private static volatile long realCardAt;
    private static volatile String doneFor;

    private RaidCard() {
    }

    /**
     * Injected at the entry of {@code RaidEventInfo.getRaidId()}, which Twitch
     * reads wherever it handles a raid. The model is filled from JSON (no
     * constructor run), so its fields are read here, not its getters (a getter
     * would also re-enter this hook).
     */
    public static void onRaidInfo(Object info) {
        try {
            if (info == null || !Toggles.on(Toggles.RAID_CARD)) {
                return;
            }
            String src = String.valueOf(field(info, "sourceChannelId"));
            String dst = String.valueOf(field(info, "targetChannelId"));
            // Only a raid out of the channel being watched brings us along.
            if (!src.equals(ChannelTracker.getCurrentChannelId()) || dst.equals(target)) {
                return;
            }
            source = src;
            viewers = (Integer) field(info, "numUsersInRaid");
            raidAt = SystemClock.elapsedRealtime();
            target = dst;
            Log.i(TAG, "raid seen: " + src + " -> " + dst + " with " + viewers);
        } catch (Throwable t) {
            Log.w(TAG, "onRaidInfo failed", t);
            CrashReporter.report(TAG, "onRaidInfo failed", t);
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        java.lang.reflect.Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }

    /** From the chat event filter: Twitch's own raid card arrived. */
    static void onRealCard() {
        realCardAt = SystemClock.elapsedRealtime();
    }

    /** From {@code ChannelTracker} on every channel note. */
    static void onChannel(final String channelId) {
        final String dst = target;
        final long at = raidAt;
        if (dst == null || !dst.equals(channelId) || (dst + at).equals(doneFor)
                || SystemClock.elapsedRealtime() - at > 5 * 60_000L || !Toggles.on(Toggles.RAID_CARD)) {
            return;
        }
        doneFor = dst + at;
        final String src = source;
        final int count = viewers;
        MAIN.postDelayed(() -> {
            if (realCardAt > at || !dst.equals(ChannelTracker.getCurrentChannelId())) {
                return;
            }
            Thread loader = new Thread(() -> {
                try {
                    String name = displayName(src);
                    if (name != null && realCardAt <= at) {
                        ChatNotices.post(Strings.raidArrivedNotice(name, count));
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "raider lookup failed", t);
                }
            }, "RTRaidCard");
            loader.setDaemon(true);
            loader.start();
        }, 3500);
    }

    /** The raiding channel's display name (anonymous GQL, web Client-ID). */
    private static String displayName(String userId) throws Exception {
        String body = new JSONObject().put("query", "query($id:ID!){user(id:$id){displayName}}")
                .put("variables", new JSONObject().put("id", userId)).toString();
        HttpURLConnection c = (HttpURLConnection) new URL("https://gql.twitch.tv/gql").openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            c.setDoOutput(true);
            c.setRequestProperty("Client-ID", "kimne78kx3ncx6brgo4mv6wki5h1ko");
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] chunk = new byte[4096];
                for (int n; (n = in.read(chunk)) > 0; ) {
                    buf.write(chunk, 0, n);
                }
            }
            JSONObject user = new JSONObject(buf.toString("UTF-8")).optJSONObject("data");
            user = user != null ? user.optJSONObject("user") : null;
            String name = user != null ? user.optString("displayName") : "";
            return name.isEmpty() ? null : name;
        } finally {
            c.disconnect();
        }
    }
}
