package tv.retwitch.mod.emotes;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * Paints a third-party emote with BTTV/FFZ modifiers applied (see
 * {@link EmoteModifiers}), matching the browser extension's CSS
 * ({@code modules/chat/style.css}, {@code rte-emote-modifier-*}).
 *
 * <p>Like {@link LayeredEmoteDrawable} it never touches the shared, cached
 * base drawable: geometry is done with canvas transforms and colour effects
 * with a {@code saveLayer} paint, so the same cached image stays unmodified in
 * every other message.
 */
final class ModifiedEmoteDrawable extends Drawable {

    /** w!/ffzW box: 3:1, same as the extension's WIDE_MODIFIER_ASPECT. */
    private static final float WIDE_ASPECT = 3f;
    private static final long PARTY_PERIOD_MS = 1500L;
    private static final long SHAKE_STEP_MS = 50L;
    /** Extension's rte-emote-modifier-shake keyframes, in px (scaled by density). */
    private static final float[][] SHAKE_STEPS = {
            {0, 1}, {2, 0}, {1, -2}, {-2, 1}, {0, -1}, {2, 2}, {-1, -1}, {-2, 2}, {2, 1}, {-1, -2}};

    private final Drawable base;
    private final int flags;
    private final float density;
    private final ColorMatrix cursed;
    private final Paint layerPaint = new Paint();
    private volatile WeakReference<TextView> host;

    ModifiedEmoteDrawable(Drawable base, int flags, float density) {
        this.base = base;
        this.flags = EmoteModifiers.drawFlags(flags);
        this.density = density;
        this.cursed = has(EmoteModifiers.CURSED) ? cursedMatrix() : null;
        Rect b = base.getBounds();
        int width = b.width();
        if (has(EmoteModifiers.WIDE)) {
            width = Math.max(width, Math.round(b.height() * WIDE_ASPECT));
        }
        setBounds(0, 0, width, b.height());
    }

    private boolean has(int flag) {
        return (flags & flag) != 0;
    }

    private boolean isAnimatedEffect() {
        return has(EmoteModifiers.PARTY) || has(EmoteModifiers.SHAKE);
    }

    void attachHost(TextView textView) {
        host = new WeakReference<>(textView);
        if (base instanceof AnimatedEmoteDrawable) {
            ((AnimatedEmoteDrawable) base).attachHost(textView);
        }
        if (isAnimatedEffect()) {
            textView.invalidate();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        // Runs inside Twitch's TextView.onDraw -- never let a paint error escape.
        try {
            Rect box = getBounds();
            Rect bb = base.getBounds();
            float bw = bb.width();
            float bh = bb.height();
            long now = SystemClock.uptimeMillis();

            ColorMatrix color = cursed != null ? new ColorMatrix(cursed) : null;
            if (has(EmoteModifiers.PARTY)) {
                ColorMatrix party = partyMatrix((now % PARTY_PERIOD_MS) * 360f / PARTY_PERIOD_MS);
                if (color == null) {
                    color = party;
                } else {
                    color.postConcat(party);
                }
            }
            if (color != null) {
                layerPaint.setColorFilter(new ColorMatrixColorFilter(color));
                canvas.saveLayer(box.left, box.top, box.right, box.bottom, layerPaint);
            } else {
                canvas.save();
            }

            canvas.translate(box.exactCenterX(), box.exactCenterY());
            if (has(EmoteModifiers.SHAKE)) {
                float[] step = SHAKE_STEPS[(int) ((now / SHAKE_STEP_MS) % SHAKE_STEPS.length)];
                canvas.translate(step[0] * density, step[1] * density);
            }
            if (has(EmoteModifiers.WIDE) && bw > 0) {
                canvas.scale(box.width() / bw, 1f);
            }
            canvas.scale(has(EmoteModifiers.FLIP_X) ? -1f : 1f, has(EmoteModifiers.FLIP_Y) ? -1f : 1f);
            if (has(EmoteModifiers.ROTATE_LEFT) || has(EmoteModifiers.ROTATE_RIGHT)) {
                canvas.rotate(has(EmoteModifiers.ROTATE_LEFT) ? -90f : 90f);
                // Keep a rotated non-square emote inside its original box.
                float fit = Math.min(bw, bh) / Math.max(1f, Math.max(bw, bh));
                canvas.scale(fit, fit);
            }
            canvas.translate(-bw / 2f, -bh / 2f);
            base.draw(canvas);
            canvas.restore();

            if (isAnimatedEffect()) {
                WeakReference<TextView> ref = host;
                TextView view = ref != null ? ref.get() : null;
                if (view != null) {
                    view.postInvalidateDelayed(SHAKE_STEP_MS);
                }
            }
        } catch (Throwable ignored) {
            // Best-effort paint; a dropped frame beats crashing the app.
        }
    }

    /** CSS {@code grayscale(1) brightness(0.7) contrast(2.5)}. */
    private static ColorMatrix cursedMatrix() {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(0f);
        ColorMatrix brightness = new ColorMatrix();
        brightness.setScale(0.7f, 0.7f, 0.7f, 1f);
        m.postConcat(brightness);
        float c = 2.5f;
        float offset = (0.5f - 0.5f * c) * 255f;
        m.postConcat(new ColorMatrix(new float[]{
                c, 0, 0, 0, offset,
                0, c, 0, 0, offset,
                0, 0, c, 0, offset,
                0, 0, 0, 1, 0}));
        return m;
    }

    /** CSS {@code sepia(0.5) hue-rotate(deg) saturate(2.5)} (W3C filter matrices). */
    private static ColorMatrix partyMatrix(float degrees) {
        float a = 0.5f;
        float k = 1f - a;
        ColorMatrix m = new ColorMatrix(new float[]{
                0.393f + 0.607f * k, 0.769f - 0.769f * k, 0.189f - 0.189f * k, 0, 0,
                0.349f - 0.349f * k, 0.686f + 0.314f * k, 0.168f - 0.168f * k, 0, 0,
                0.272f - 0.272f * k, 0.534f - 0.534f * k, 0.131f + 0.869f * k, 0, 0,
                0, 0, 0, 1, 0});
        double rad = Math.toRadians(degrees);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        m.postConcat(new ColorMatrix(new float[]{
                0.213f + cos * 0.787f - sin * 0.213f, 0.715f - cos * 0.715f - sin * 0.715f, 0.072f - cos * 0.072f + sin * 0.928f, 0, 0,
                0.213f - cos * 0.213f + sin * 0.143f, 0.715f + cos * 0.285f + sin * 0.140f, 0.072f - cos * 0.072f - sin * 0.283f, 0, 0,
                0.213f - cos * 0.213f - sin * 0.787f, 0.715f - cos * 0.715f + sin * 0.715f, 0.072f + cos * 0.928f + sin * 0.072f, 0, 0,
                0, 0, 0, 1, 0}));
        ColorMatrix saturate = new ColorMatrix();
        saturate.setSaturation(2.5f);
        m.postConcat(saturate);
        return m;
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
