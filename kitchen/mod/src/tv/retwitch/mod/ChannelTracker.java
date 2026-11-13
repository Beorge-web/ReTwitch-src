package tv.retwitch.mod;

import android.os.SystemClock;
import android.util.Log;

import tv.retwitch.mod.emotes.EmoteCache;

/**
 * Tracks the Twitch channel the user is currently watching, purely to feed
 * the third-party emote lookup (the chat body renderer we inject into gets
 * no channel/room id of its own, so it reads
 * "current channel" from here).
 *
 * The id is fed exclusively from Twitch's own once-per-channel-open signals,
 * both carrying the broadcaster user id:
 *
 *  - live: the chat connection controller binding a broadcaster
 *    ({@code setBroadcaster}, see ChatConnectPatch); and
 *  - VOD: the chomments "prepare for VOD" path (see VodChannelTrackPatch).
 *
 * We used to also track it from the per-message-batch IRC converter, but that
 * fires once per message batch, so a previously watched channel whose chat is
 * still connected in the background (a busy chat floods it many times a
 * second) kept dragging the tracker back to the old channel. The
 * connection/broadcaster signal fires exactly when the foreground channel is
 * (re)opened, so it's the only source now.
 *
 * The mini-player is the one case with no such signal: its chat stays connected
 * while another channel's chat opens under it, so expanding it binds nothing.
 * See {@link #onPlayerMode}.
 */
public final class ChannelTracker {

    private static final String TAG = "RTEmotes";

    private static volatile String currentChannelId;
    private static volatile String currentVodId;
    /** The minimized theatre's channel, and the channel of the chat under it while it is expanded. */
    private static volatile String theatreChannelId;
    private static volatile String underChannelId;
    /** Channel id -> login, from the channel models that carry both (for {@link JailChat}). */
    private static final java.util.Map<String, String> LOGINS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * {@link SystemClock#elapsedRealtime()} of the last channel open/switch,
     * used by {@code MentionHighlighter} to suppress the vibration burst that
     * would otherwise fire for every already-mentioned message in the backlog
     * a channel loads with. 0 until the first channel is opened.
     */
    private static volatile long lastChannelOpenAt = 0L;

    private ChannelTracker() {
    }

    /**
     * Reports the broadcaster id of the channel the user just opened/switched
     * to. Injected directly into Twitch's own code (ChatConnectPatch /
     * VodChannelTrackPatch) and runs on its thread with no try/catch on the
     * smali side -- must never throw back into Twitch. Non-positive ids (no
     * channel yet) are ignored.
     */
    public static void noteChannelId(int channelId) {
        try {
            if (channelId <= 0) {
                Log.w(TAG, "noteChannelId ignored: non-positive id " + channelId);
                return;
            }
            String id = Integer.toString(channelId);
            if (!id.equals(currentChannelId)) {
                Log.i(TAG, "channel switched: " + currentChannelId + " -> " + id);
                currentChannelId = id;
                lastChannelOpenAt = SystemClock.elapsedRealtime();
            }
            // Always (re)kick the fetch even when the id is unchanged: VOD
            // reopen, prepareForVod racing ahead of the first chat bind, or a
            // cache entry that aged out while we were on another screen all
            // need a load attempt without requiring a channel-id change first.
            EmoteCache.ensureChannelLoaded(id);
            tv.retwitch.mod.emotes.GiantEmotes.onChannel(id);
            RaidCard.onChannel(id);
            JailChat.sync();
            ChatBan.sync();
        } catch (Throwable t) {
            Log.w(TAG, "noteChannelId failed", t);
            CrashReporter.report(TAG, "noteChannelId failed", t);
        }
    }

    /**
     * Same as {@link #noteChannelId(int)} for a Twitch {@code ChannelInfo} /
     * {@code ChannelModel}: 31.x replaced their {@code getId(): int} with
     * {@code getTuid(): Tuid} (whose {@code toString()} is the numeric id), so
     * the id is read reflectively off whichever accessor exists. Public model
     * API, not R8-renamed. Injected into Twitch's code -- must never throw.
     */
    public static void noteChannel(Object channel) {
        try {
            if (channel == null) {
                return;
            }
            Object id;
            try {
                id = channel.getClass().getMethod("getTuid").invoke(channel);
            } catch (NoSuchMethodException e) {
                id = channel.getClass().getMethod("getId").invoke(channel);
            }
            int channelId = Integer.parseInt(String.valueOf(id));
            try {
                Object name = channel.getClass().getMethod("getName").invoke(channel);
                if (name instanceof String && !((String) name).isEmpty()) {
                    LOGINS.put(Integer.toString(channelId), ((String) name).toLowerCase(java.util.Locale.ROOT));
                }
            } catch (NoSuchMethodException e) {
                Log.w(TAG, "no getName() on " + channel.getClass().getName());
            }
            noteChannelId(channelId);
        } catch (Throwable t) {
            Log.w(TAG, "noteChannel failed", t);
            CrashReporter.report(TAG, "noteChannel failed", t);
        }
    }

    /**
     * Channel id from a chat connection key ({@code ChannelChatConnectionKey(Tuid, name)}):
     * fires for every chat connection, including the discovery feed's inline
     * chat, which doesn't go through the theatre's setBroadcaster. Never throws.
     */
    public static void noteChannelTuid(Object tuid) {
        try {
            if (tuid != null) {
                noteChannelId(Integer.parseInt(tuid.toString()));
            }
        } catch (Throwable t) {
            Log.w(TAG, "noteChannelTuid failed", t);
            CrashReporter.report(TAG, "noteChannelTuid failed", t);
        }
    }

    /**
     * The theatre's player mode is changing (ChatConnectPatch). Minimizing and expanding swap
     * which chat is on screen, so the current channel swaps with them. Injected -- never throws.
     */
    public static void onPlayerMode(Enum<?> from, Enum<?> to) {
        try {
            boolean wasMinimized = from.name().startsWith("MINIMIZED");
            boolean minimized = to.name().startsWith("MINIMIZED");
            if (wasMinimized == minimized) {
                return;
            }
            String back;
            if (minimized) {
                theatreChannelId = currentChannelId;
                back = underChannelId;
                underChannelId = null;
            } else {
                underChannelId = currentChannelId;
                back = theatreChannelId;
            }
            if (back != null && !back.equals(currentChannelId)) {
                noteChannelId(Integer.parseInt(back));
                EmoteCache.refreshViews();
            }
        } catch (Throwable t) {
            Log.w(TAG, "onPlayerMode failed", t);
            CrashReporter.report(TAG, "onPlayerMode failed", t);
        }
    }

    /**
     * Remembers the VOD being opened ({@code VodModel.getId()}, public model
     * API; "v123" or "123"), for the chapters list. Injected -- never throws.
     */
    public static void noteVod(Object vod) {
        try {
            Object id = vod != null ? vod.getClass().getMethod("getId").invoke(vod) : null;
            String text = id != null ? id.toString() : null;
            currentVodId = text != null && text.startsWith("v") ? text.substring(1) : text;
        } catch (Throwable t) {
            Log.w(TAG, "noteVod failed", t);
            CrashReporter.report(TAG, "noteVod failed", t);
        }
    }

    public static String getCurrentVodId() {
        return currentVodId;
    }

    public static String getCurrentChannelId() {
        return currentChannelId;
    }

    /** Login of the current channel, or null if no model with its login was seen yet. */
    /** A channel's login by its id, if it was opened in this process. */
    public static String loginOf(String channelId) {
        return channelId != null ? LOGINS.get(channelId) : null;
    }

    public static String getCurrentChannelLogin() {
        String id = currentChannelId;
        return id != null ? LOGINS.get(id) : null;
    }

    /**
     * Milliseconds since the current channel was opened/switched to, or
     * {@link Long#MAX_VALUE} if no channel has been opened yet. Used to tell a
     * live message apart from the backlog loaded right after a channel opens.
     */
    public static long getMillisSinceChannelOpen() {
        long opened = lastChannelOpenAt;
        return opened == 0L ? Long.MAX_VALUE : (SystemClock.elapsedRealtime() - opened);
    }
}
