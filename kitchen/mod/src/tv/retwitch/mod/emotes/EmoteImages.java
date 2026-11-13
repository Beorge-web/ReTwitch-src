package tv.retwitch.mod.emotes;

import android.graphics.drawable.Drawable;

/** Chat emote images for views outside the chat (danmaku): the shared cache, fetched on first use. */
public final class EmoteImages {

    private EmoteImages() {
    }

    /** The decoded image, or null while it loads. */
    public static Drawable get(String url) {
        return EmoteImageCache.getIfReady(url);
    }
}
