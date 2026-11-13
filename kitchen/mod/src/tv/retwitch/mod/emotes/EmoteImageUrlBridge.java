package tv.retwitch.mod.emotes;

import android.util.Log;
import tv.retwitch.mod.CrashReporter;

/**
 * Injected at the top of Twitch's own emote-id-to-CDN-URL resolver (see
 * {@code EmoteImageUrlPatch.kt}, fingerprinted method historically named
 * {@code Lf3k.o(Context, String emoticonId, Float): String}). Twitch's
 * native emote autocomplete/picker UI calls this to turn whatever "id" an
 * {@code EmoteModel} carries into the URL its own (already-working)
 * image-loading stack fetches -- so a third-party emote's synthetic id
 * (built by {@link EmoteModelReflect#encodeImageId}) is intercepted here
 * and swapped for the real CDN URL, optionally tunneled through
 * {@link EmoteProxy} exactly like our own chat-render/data-fetch paths.
 *
 * <p>Deliberately returns {@code null} (never throws) for anything that
 * isn't one of ours, so the patched method's original resolution logic
 * runs completely unmodified for every native Twitch emote.
 */
public final class EmoteImageUrlBridge {

    private static final String TAG = "RTEmotes";

    private EmoteImageUrlBridge() {
    }

    /**
     * Injected at the top of Twitch's own emote-URL resolver (see
     * {@code EmoteImageUrlPatch.kt}) and called from that method with no
     * try/catch on the smali side. Returning {@code null} makes the patched
     * method fall through to its original logic, so on any failure we do
     * exactly that instead of throwing back into Twitch's code.
     */
    public static String resolve(String emoticonId) {
        try {
            String raw = EmoteModelReflect.decodeImageId(emoticonId);
            if (raw == null) {
                return null;
            }
            return EmoteProxy.wrap(raw);
        } catch (Throwable t) {
            Log.w(TAG, "resolve failed for emoticonId", t);
            CrashReporter.report(TAG, "resolve failed for emoticonId", t);
            return null;
        }
    }
}
