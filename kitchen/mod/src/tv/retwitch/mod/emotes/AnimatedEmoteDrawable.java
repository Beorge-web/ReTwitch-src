package tv.retwitch.mod.emotes;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * Wraps a decoded, {@link Animatable} emote {@link Drawable} so it can be
 * bound, after the fact, to whichever {@code TextView} ends up hosting the
 * {@code ImageSpan} that holds it.
 *
 * A plain {@code ImageSpan} is enough for a single static paint -- {@code
 * TextView.onDraw} always calls {@code Drawable.draw()} for whatever is
 * currently in the span, on every redraw the TextView does for any reason.
 * But nothing ever asks the TextView to redraw *again* just because this
 * drawable's frame changed: the real, decoded drawable's {@code
 * invalidateSelf()}/{@code scheduleSelf()} calls only reach a {@link
 * Callback}, and that callback is null until someone calls {@code
 * setCallback()} with the actual View doing the hosting -- which doesn't
 * exist yet at the point {@link EmoteChatHook} builds the {@code
 * ImageSpan} (see its Javadoc). This class is registered as that callback
 * on the real drawable at construction time, and {@link
 * EmoteChatHook#onTextBound} -- hooked at the one spot in Twitch's chat
 * rendering that hands a chat body {@code Spanned} to a real {@code
 * TextView} (see {@code EmoteChatPatch.kt}) -- supplies the host once it's
 * known, for every span still visible in that render pass.
 */
final class AnimatedEmoteDrawable extends Drawable implements Drawable.Callback {

    /**
     * {@code AnimatedImageDrawable} (the API 28+ decode result for an
     * animated GIF/WEBP) drives its own frame timing entirely through
     * {@code Drawable.Callback#scheduleDrawable}, so that has to actually
     * work even in the split second before {@link #attachHost} is first
     * called (the drawable is already {@code start()}ed in {@link
     * EmoteImageCache} well before any TextView is known) -- hence a
     * fallback main-thread handler here rather than silently dropping
     * those calls.
     */
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private final Drawable real;
    private volatile WeakReference<TextView> host;

    AnimatedEmoteDrawable(Drawable real) {
        this.real = real;
        real.setCallback(this);
    }

    /** Called from the chat-render bind hook once the actual TextView is known. */
    void attachHost(TextView textView) {
        WeakReference<TextView> current = host;
        TextView previous = current != null ? current.get() : null;
        if (previous == textView) {
            return;
        }
        host = new WeakReference<>(textView);
        // No start() here: EmoteImageCache starts it once at decode, and a finite-loop emote that
        // finished stays on its last frame like in browsers instead of replaying on every new row.
        textView.invalidate();
    }

    private TextView currentHost() {
        WeakReference<TextView> ref = host;
        return ref != null ? ref.get() : null;
    }

    @Override
    public void draw(Canvas canvas) {
        real.draw(canvas);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        real.setBounds(bounds);
    }

    @Override
    public void setAlpha(int alpha) {
        real.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        real.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return real.getOpacity();
    }

    @Override
    public int getIntrinsicWidth() {
        return real.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return real.getIntrinsicHeight();
    }

    @Override
    public void invalidateDrawable(Drawable who) {
        TextView view = currentHost();
        if (view != null) {
            view.invalidate();
        }
    }

    @Override
    public void scheduleDrawable(Drawable who, Runnable what, long when) {
        handlerFor(currentHost()).postAtTime(what, who, when);
    }

    @Override
    public void unscheduleDrawable(Drawable who, Runnable what) {
        handlerFor(currentHost()).removeCallbacks(what, who);
    }

    private static Handler handlerFor(TextView view) {
        if (view != null) {
            Handler handler = view.getHandler();
            if (handler != null) {
                return handler;
            }
        }
        return MAIN_HANDLER;
    }
}
