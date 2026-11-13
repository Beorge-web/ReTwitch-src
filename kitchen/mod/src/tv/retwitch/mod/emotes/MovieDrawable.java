package tv.retwitch.mod.emotes;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Movie;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/**
 * Minimal {@link Animatable} {@link Drawable} wrapping the legacy (API 1,
 * deprecated since API 28 in favor of {@link android.graphics.ImageDecoder})
 * {@link Movie} class, used only as the animated-GIF decoder on API 26/27
 * devices where {@code ImageDecoder}/{@code AnimatedImageDrawable} don't
 * exist yet (see {@link EmoteImageCache}).
 *
 * {@code Movie} is a plain Android SDK class -- not a vendored library --
 * so, unlike Glide, it can never collide with whatever image-loading code
 * Twitch's own build happens to bundle.
 *
 * Same playback caveat as documented on {@link EmoteImageCache}: {@link
 * #start()} flips the running flag and the next {@link #draw} advances the
 * frame, but nothing here can force a redraw on its own without a {@link
 * Callback} wired up by whatever eventually hosts this drawable (a chat
 * {@code TextView} via {@code ImageSpan}, in our case) -- so today it only
 * actually advances frames on whichever redraws Twitch's own UI triggers
 * for unrelated reasons, same as the animated case on API 28+.
 */
final class MovieDrawable extends Drawable implements Animatable {

    private final Movie movie;
    private final int durationMs;
    private long startTimeMs;
    private boolean running;

    MovieDrawable(Movie movie) {
        this.movie = movie;
        int d = movie.duration();
        this.durationMs = d > 0 ? d : 1000;
    }

    @Override
    public void draw(Canvas canvas) {
        if (running) {
            long elapsed = (SystemClock.uptimeMillis() - startTimeMs) % durationMs;
            movie.setTime((int) elapsed);
        }

        Rect bounds = getBounds();
        int movieWidth = movie.width();
        int movieHeight = movie.height();
        float scaleX = movieWidth > 0 ? bounds.width() / (float) movieWidth : 1f;
        float scaleY = movieHeight > 0 ? bounds.height() / (float) movieHeight : 1f;

        int saveCount = canvas.save();
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(scaleX, scaleY);
        movie.draw(canvas, 0, 0);
        canvas.restoreToCount(saveCount);

        if (running) {
            invalidateSelf();
        }
    }

    @Override
    public void start() {
        if (!running) {
            running = true;
            startTimeMs = SystemClock.uptimeMillis();
            invalidateSelf();
        }
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getIntrinsicWidth() {
        return movie.width();
    }

    @Override
    public int getIntrinsicHeight() {
        return movie.height();
    }

    @Override
    public void setAlpha(int alpha) {
        // Movie/Canvas#drawMovie has no alpha-compositing hook, and it's
        // not worth a full offscreen-layer workaround for a chat emote.
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // Same rationale as setAlpha above.
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
