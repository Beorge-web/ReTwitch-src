package tv.retwitch.mod;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * Player-strip button (see {@link ReloadHook}) that mirrors the video
 * horizontally: {@code setScaleX(-1)} on the playing surface. Twitch never sets
 * the surface's scale itself, so the state lives on the surface: a new player
 * (channel switch, reload) starts unmirrored and the icon follows it.
 * shortcut: the secure-playback SurfaceView path may ignore the view scale; mirror via a parent if that's ever needed.
 */
final class VideoMirror {

    private static final String TAG = "RTMirror";

    private VideoMirror() {
    }

    private static boolean isMirrored(View anyView) {
        View surface = Ambilight.largestSurface(anyView.getRootView());
        return surface != null && surface.getScaleX() < 0;
    }

    /** Material "flip" icon on a 24x24 grid (the one RTE's video_mirror button uses). */
    private static Path flipIcon() {
        Path path = new Path();
        float[][] squares = {{15, 19}, {19, 7}, {19, 15}, {15, 3}, {19, 11}};
        for (float[] sq : squares) {
            path.addRect(sq[0], sq[1], sq[0] + 2, sq[1] + 2, Path.Direction.CW);
        }
        path.addRect(11, 1, 13, 23, Path.Direction.CW); // the axis
        // Left half: a bracket with rounded outer corners.
        path.moveTo(3, 5);
        path.lineTo(3, 19);
        path.cubicTo(3, 20.1f, 3.9f, 21, 5, 21);
        path.lineTo(9, 21);
        path.lineTo(9, 19);
        path.lineTo(5, 19);
        path.lineTo(5, 5);
        path.lineTo(9, 5);
        path.lineTo(9, 3);
        path.lineTo(5, 3);
        path.cubicTo(3.9f, 3, 3, 3.9f, 3, 5);
        path.close();
        // Rounded dotted corners of the right half.
        path.moveTo(19, 3);
        path.lineTo(19, 5);
        path.lineTo(21, 5);
        path.cubicTo(21, 3.9f, 20.1f, 3, 19, 3);
        path.close();
        path.moveTo(19, 21);
        path.cubicTo(20.1f, 21, 21, 20.1f, 21, 19);
        path.lineTo(19, 19);
        path.close();
        return path;
    }

    static View createButton(final Context context) {
        final ImageView button = new ImageView(context);
        final int px = Math.round(24 * context.getResources().getDisplayMetrics().density);
        button.setImageDrawable(new android.graphics.drawable.Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path path = flipIcon();

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                p.setColor(isMirrored(button) ? 0xFFBF94FF : 0xFFFFFFFF);
                canvas.save();
                canvas.translate(b.exactCenterX() - px / 2f, b.exactCenterY() - px / 2f);
                canvas.scale(px / 24f, px / 24f);
                canvas.drawPath(path, p);
                canvas.restore();
            }

            @Override public int getIntrinsicWidth() { return px; }
            @Override public int getIntrinsicHeight() { return px; }
            @Override public void setAlpha(int alpha) { }
            @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        });
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(Strings.pick("Отразить видео", "Mirror video"));
        button.setClickable(true);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    View surface = Ambilight.largestSurface(v.getRootView());
                    if (surface != null) {
                        surface.setScaleX(-surface.getScaleX());
                    }
                    v.invalidate();
                } catch (Throwable t) {
                    Log.w(TAG, "toggle failed", t);
                    CrashReporter.report(TAG, "toggle failed", t);
                }
            }
        });
        int size = Math.round(48 * context.getResources().getDisplayMetrics().density);
        button.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return button;
    }
}
