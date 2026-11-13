package tv.retwitch.mod.emotes;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * A single {@link Drawable} that composites a base emote with one or more
 * zero-width overlay emotes drawn centered on top of it -- the way 7TV/BTTV
 * "zero-width" emotes are meant to stack (e.g. a hat/glasses drawn over a
 * face emote). Placed inside one {@code ImageSpan} over the base word's range;
 * the overlay words themselves are collapsed to zero width by
 * {@link ZeroWidthCollapseSpan} so their codes/spaces don't show.
 *
 * <p>Two properties make this fit our additive, cache-backed pipeline:
 * <ul>
 *   <li><b>Never mutates child bounds.</b> The base and overlay drawables come
 *       straight from {@link EmoteImageCache} and are shared across every
 *       message that uses them, so their {@code bounds} must not be touched.
 *       Layers are positioned purely with canvas translation.</li>
 *   <li><b>Resolves overlays lazily.</b> Overlays are held as {@link Emote}s
 *       and looked up in {@link EmoteImageCache} at {@code draw()} time. An
 *       overlay whose image hasn't finished downloading simply isn't painted
 *       yet and pops in on the next invalidate (driven by the existing
 *       {@link EmoteChatHook#requestRefresh()} pass) -- without needing to
 *       rebuild/re-set the text, which the base's own {@code ReplacementSpan}
 *       would otherwise make impossible.</li>
 * </ul>
 * Animated layers ({@link AnimatedEmoteDrawable}) are wired to the host
 * TextView via {@link #attachHost} exactly like a standalone animated emote,
 * so their frames keep playing.
 */
final class LayeredEmoteDrawable extends Drawable {

    private final Drawable base;
    private final List<Emote> overlays;
    private volatile WeakReference<TextView> host;

    LayeredEmoteDrawable(Drawable base, List<Emote> overlays) {
        this.base = base;
        this.overlays = overlays;
        updateBounds();
    }

    /**
     * Sizes the stack to the largest layer (like the extension's CSS grid
     * cell), so a zero-width overlay wider than its base widens the slot
     * instead of spilling left over the preceding text (the username).
     *
     * @return whether the bounds grew (a late overlay finished decoding)
     */
    private boolean updateBounds() {
        Rect b = base.getBounds();
        int width = b.width();
        int height = b.height();
        for (Emote overlay : overlays) {
            Drawable d = EmoteImageCache.getIfReady(overlay.imageUrl);
            if (d != null) {
                width = Math.max(width, d.getBounds().width());
                height = Math.max(height, d.getBounds().height());
            }
        }
        Rect current = getBounds();
        if (current.width() == width && current.height() == height) {
            return false;
        }
        setBounds(0, 0, width, height);
        return true;
    }

    /**
     * Propagates the host TextView to every animated layer (base + any
     * already-decoded overlays) so their {@code Drawable.Callback} frame
     * invalidations reach a real view. Safe to call repeatedly; overlays that
     * decode later are picked up by subsequent calls.
     *
     * @return whether the stack grew, i.e. the line must be re-laid out
     */
    boolean attachHost(TextView textView) {
        host = new WeakReference<>(textView);
        if (base instanceof AnimatedEmoteDrawable) {
            ((AnimatedEmoteDrawable) base).attachHost(textView);
        } else if (base instanceof ModifiedEmoteDrawable) {
            ((ModifiedEmoteDrawable) base).attachHost(textView);
        }
        for (Emote overlay : overlays) {
            Drawable d = EmoteImageCache.getIfReady(overlay.imageUrl);
            if (d instanceof AnimatedEmoteDrawable) {
                ((AnimatedEmoteDrawable) d).attachHost(textView);
            }
        }
        return updateBounds();
    }

    @Override
    public void draw(Canvas canvas) {
        // Runs inside Twitch's TextView.onDraw on the main thread -- an escaped
        // throwable would crash the app mid-frame, so guard the whole paint.
        try {
            Rect b = getBounds();
            int bw = b.width();
            int bh = b.height();

            Rect bb = base.getBounds();
            canvas.save();
            canvas.translate(b.left + (bw - bb.width()) / 2f, b.top + (bh - bb.height()) / 2f);
            base.draw(canvas);
            canvas.restore();

            for (Emote overlay : overlays) {
                Drawable d = EmoteImageCache.getIfReady(overlay.imageUrl);
                if (d == null) {
                    // Not decoded yet -- appears on a later invalidate.
                    continue;
                }
                Rect ob = d.getBounds();
                int ow = ob.width();
                int oh = ob.height();
                float dx = b.left + (bw - ow) / 2f;
                float dy = b.top + (bh - oh) / 2f;
                canvas.save();
                canvas.translate(dx, dy);
                d.draw(canvas);
                canvas.restore();
            }
        } catch (Throwable ignored) {
            // Best-effort paint; a dropped frame beats crashing the app.
        }
    }

    @Override
    public int getIntrinsicWidth() {
        return getBounds().width();
    }

    @Override
    public int getIntrinsicHeight() {
        return getBounds().height();
    }

    @Override
    public void setAlpha(int alpha) {
        base.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        base.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
