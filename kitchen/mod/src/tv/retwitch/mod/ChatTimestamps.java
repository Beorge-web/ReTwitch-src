package tv.retwitch.mod;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * Chat timestamp format (with seconds). Twitch's span-group builder (31.8.0
 * {@code va6.b}) formats the message's {@code timestampSeconds} with its own
 * {@code H:mm} {@link SimpleDateFormat}; {@code ChatTimestampPatch} routes that
 * format through {@link #format}, and the live-message wrapper's "show
 * timestamps" flag through {@link #forceShow}, so a picked format shows even
 * when Twitch's own timestamps are off. Both run inside Twitch's code: never throw.
 */
public final class ChatTimestamps {

    private static final String TAG = "RTTimestamps";

    /** SimpleDateFormat isn't thread-safe; chat rows are built off the main thread too. */
    private static final ThreadLocal<SimpleDateFormat> FORMAT = new ThreadLocal<>();

    private ChatTimestamps() {
    }

    /** @return the format to use instead of Twitch's {@code original}. */
    public static SimpleDateFormat format(SimpleDateFormat original) {
        try {
            String pattern = ChatSettings.TIMESTAMP_PATTERNS[ChatSettings.getTimestampFormat()];
            if (pattern.isEmpty()) {
                return original;
            }
            SimpleDateFormat cached = FORMAT.get();
            if (cached == null || !pattern.equals(cached.toPattern())) {
                cached = new SimpleDateFormat(pattern, Locale.getDefault());
                FORMAT.set(cached);
            }
            return cached;
        } catch (Throwable t) {
            Log.w(TAG, "format failed", t);
            return original;
        }
    }

    /** @return whether the live message's timestamp is passed on: Twitch's choice, or ours when a format is picked. */
    public static boolean forceShow(boolean original) {
        try {
            return original || ChatSettings.getTimestampFormat() != 0;
        } catch (Throwable t) {
            return original;
        }
    }

    /**
     * The message model's send time (seconds), read where the model is built: the local echo of
     * one's own message has no server time, so it got no timestamp. It is being built now.
     */
    public static Long orNow(Long seconds) {
        return seconds != null ? seconds : System.currentTimeMillis() / 1000L;
    }

    /** Settings chips: "Twitch default", then each pattern as a sample time; 12-hour ones say so. */
    static String[] samples() {
        String[] patterns = ChatSettings.TIMESTAMP_PATTERNS;
        String[] labels = new String[patterns.length];
        labels[0] = Strings.timestampDefault();
        java.util.Calendar sample = java.util.Calendar.getInstance();
        sample.set(2026, 0, 1, 9, 5, 30);
        for (int i = 1; i < patterns.length; i++) {
            labels[i] = new SimpleDateFormat(patterns[i], Locale.getDefault()).format(sample.getTime())
                    + (patterns[i].startsWith("h") ? Strings.pick(" (12 ч)", " (12 h)") : "");
        }
        return labels;
    }
}
