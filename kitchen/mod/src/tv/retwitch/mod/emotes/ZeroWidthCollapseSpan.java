package tv.retwitch.mod.emotes;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.style.ReplacementSpan;

/**
 * A zero-width, invisible {@link ReplacementSpan} laid over the text range of
 * the overlay ("zero-width") emote words in a stack -- including the spaces
 * separating them from the base. It reports width 0 and draws nothing, so the
 * overlay codes and their spaces don't take up any room in the line; the
 * overlay images themselves are painted by the base word's
 * {@link LayeredEmoteDrawable}.
 *
 * <p>Being a {@link ReplacementSpan} also makes {@link EmoteChatHook}'s
 * refresh pass correctly skip these ranges (they already "carry a span"), so
 * the collapsed group is never re-processed.
 */
final class ZeroWidthCollapseSpan extends ReplacementSpan {

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        return 0;
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end,
            float x, int top, int y, int bottom, Paint paint) {
        // Intentionally empty: the overlay images are drawn by the base's
        // LayeredEmoteDrawable; this range must render nothing.
    }
}
