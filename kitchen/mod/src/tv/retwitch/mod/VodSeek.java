package tv.retwitch.mod;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.widget.ImageView;

/**
 * VOD seek step (ReVive's VodSeek): Twitch's VOD buttons jump -10 / +30 s.
 * {@code VodPlayerPatch} passes those literals through here -- the overlay's
 * per-tap seconds (Twitch sums the taps, labels the sum and seeks once) and
 * the media session's milliseconds -- and gets the chosen steps back.
 * The buttons' icons have 10 and 30 drawn in: {@link #showStep} puts the step
 * over copies of them without the digits.
 */
public final class VodSeek {

    private static final int SHOWN_TAG_KEY = 0x7e700d68;

    private VodSeek() {
    }

    /** Injected after the overlay buttons' -10 / +30 literals. Never throws. */
    public static int stepSeconds(int twitchSeconds) {
        try {
            return twitchSeconds < 0 ? -PlayerSettings.getSeekBackSeconds() : PlayerSettings.getSeekForwardSeconds();
        } catch (Throwable t) {
            return twitchSeconds;
        }
    }

    /** Injected after the media session's -10000 / +30000 ms literals. Never throws. */
    public static int stepMillis(int twitchMillis) {
        return stepSeconds(twitchMillis) * 1000;
    }

    /** For {@code seek_forward_button} / {@code seek_rewind_button} on every {@link UiHider} pass; the stock step keeps Twitch's icon. */
    static void showStep(View button, boolean forward) {
        int seconds = forward ? PlayerSettings.getSeekForwardSeconds() : PlayerSettings.getSeekBackSeconds();
        Object shown = button.getTag(SHOWN_TAG_KEY);
        if (shown == null ? seconds == (forward ? 30 : 10) : shown.equals(seconds)) {
            return;
        }
        button.setTag(SHOWN_TAG_KEY, seconds);
        Context context = button.getContext();
        int icon = context.getResources().getIdentifier(forward ? "rt_seek_forward" : "rt_seek_rewind", "drawable",
                context.getPackageName());
        if (icon != 0 && button instanceof ImageView) {
            ((ImageView) button).setImageDrawable(new LayerDrawable(new Drawable[]{
                    context.getDrawable(icon), new Digits(String.valueOf(seconds))}));
            button.setContentDescription(Strings.seekStep(forward, seconds));
        }
    }

    /** The step where Twitch's digits stand, inside the arrow; three digits get narrower to stay in it. */
    private static final class Digits extends Drawable {
        private final String text;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect ink = new Rect();

        Digits(String text) {
            this.text = text;
            paint.setColor(Color.WHITE);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect box = getBounds();
            paint.setTextSize(box.height() * 0.49f);
            float most = box.width() * 0.6f;
            float width = paint.measureText(text);
            if (width > most) {
                paint.setTextSize(paint.getTextSize() * most / width);
            }
            paint.getTextBounds(text, 0, text.length(), ink);
            canvas.drawText(text, box.exactCenterX(), box.top + box.height() * 0.49f - ink.exactCenterY(), paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
