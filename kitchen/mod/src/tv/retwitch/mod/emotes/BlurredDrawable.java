package tv.retwitch.mod.emotes;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

/**
 * A link preview that can start blurred ("blur until tapped", the mobile
 * stand-in for the extension's blur-until-hover). The blur is a cheap
 * downscale-then-filtered-upscale of the shared cached image -- works on API
 * 26 (RenderEffect is 31+) and keeps animated previews animating.
 */
final class BlurredDrawable extends Drawable {

    private static final int DOWNSCALE = 12;

    private final Drawable base;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private volatile boolean blurred;
    private Bitmap small;

    BlurredDrawable(Drawable base, boolean blurred) {
        this.base = base;
        this.blurred = blurred;
        Rect b = base.getBounds();
        setBounds(0, 0, b.width(), b.height());
    }

    boolean isBlurred() {
        return blurred;
    }

    void reveal() {
        blurred = false;
        small = null;
    }

    void attachHost(TextView textView) {
        if (base instanceof AnimatedEmoteDrawable) {
            ((AnimatedEmoteDrawable) base).attachHost(textView);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        // Runs inside Twitch's TextView.onDraw -- never let a paint error escape.
        try {
            Rect box = getBounds();
            if (!blurred) {
                canvas.save();
                canvas.translate(box.left, box.top);
                base.draw(canvas);
                canvas.restore();
                return;
            }
            Rect bb = base.getBounds();
            int w = Math.max(1, bb.width() / DOWNSCALE);
            int h = Math.max(1, bb.height() / DOWNSCALE);
            if (small == null || small.getWidth() != w || small.getHeight() != h) {
                small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            Canvas c = new Canvas(small);
            c.drawColor(0, PorterDuff.Mode.CLEAR);
            c.scale(w / (float) Math.max(1, bb.width()), h / (float) Math.max(1, bb.height()));
            base.draw(c);
            canvas.drawBitmap(small, null, box, paint);
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
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
