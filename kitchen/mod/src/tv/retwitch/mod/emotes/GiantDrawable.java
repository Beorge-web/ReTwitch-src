package tv.retwitch.mod.emotes;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * An FFZ giant emote: takes Twitch's giant size ({@link GiantEmotes#HEIGHT_DP})
 * right away and draws the emote's biggest image once it's downloaded, the
 * regular one scaled up until then -- so a row built once (reward messages
 * have no refresh pass) still ends up sharp on its next redraw.
 */
final class GiantDrawable extends Drawable {

    private final Drawable regular;
    private final String largeUrl;

    GiantDrawable(Drawable regular, String largeUrl) {
        this.regular = regular;
        this.largeUrl = largeUrl;
        Rect b = regular.getBounds();
        int height = Math.round(GiantEmotes.HEIGHT_DP * Resources.getSystem().getDisplayMetrics().density);
        int width = b.height() > 0 ? Math.round(b.width() * height / (float) b.height()) : height;
        setBounds(0, 0, width, height);
    }

    @Override
    public void draw(Canvas canvas) {
        // Runs inside Twitch's TextView.onDraw -- never let a paint error escape.
        try {
            Drawable large = EmoteImageCache.getPreviewIfReady(largeUrl,
                    GiantEmotes.HEIGHT_DP, GiantEmotes.HEIGHT_DP * 3, true);
            Drawable source = large != null ? large : regular;
            Rect box = getBounds();
            Rect src = source.getBounds();
            if (src.width() <= 0 || src.height() <= 0) {
                return;
            }
            canvas.save();
            canvas.translate(box.left, box.top);
            canvas.scale(box.width() / (float) src.width(), box.height() / (float) src.height());
            canvas.translate(-src.left, -src.top);
            source.draw(canvas);
            canvas.restore();
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
        regular.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        regular.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
