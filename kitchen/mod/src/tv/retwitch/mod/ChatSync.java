package tv.retwitch.mod;

/**
 * "Chat in sync with the stream" ({@code CHAT_SYNC}, {@code ChatSyncPatch}): messages reach the app seconds before
 * the video shows what they answer, more so through a stream proxy. Each live message is held back by the
 * player's latency on top of the channel's own chat delay, the way Twitch's chat delay already holds them.
 */
public final class ChatSync {

    /** A latency past this is a stalled player, not a delay worth waiting for. */
    private static final long MAX_MS = 30_000;

    private ChatSync() {
    }

    /** Injected where the channel's chat delay is read for a message. Never throws. */
    public static long delayMs(long channelDelay) {
        try {
            if (!Toggles.on(Toggles.CHAT_SYNC)) {
                return channelDelay;
            }
            long latency = PlayerHook.liveLatencyMs();
            return latency > 0 && latency < MAX_MS ? channelDelay + latency : channelDelay;
        } catch (Throwable t) {
            return channelDelay;
        }
    }
}
