package tv.retwitch.mod.emotes;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.text.style.ReplacementSpan;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * An RTE nickname paint ({@code /api/paints} entry, the 7TV paint shape the
 * extension's {@code seventv/cosmetics} renders as a CSS gradient on the name):
 * {@code function} LINEAR_GRADIENT / RADIAL_GRADIENT, or URL: an image (often animated
 * WEBP) stretched over the name ({@code background-size: 100% 100%}), drawn through a
 * SRC_IN layer -- the RenderThread animates it -- with {@code color} or the text colour
 * until it loads. {@code angle} in CSS degrees, {@code repeat}, {@code stops} of
 * {@code {at: 0..1, color}}, {@code shadows} (CSS {@code drop-shadow}, px) as text
 * shadow layers. Colours are packed RGBA ints.
 */
final class NickPaint {

    final boolean radial;
    final boolean repeat;
    final float angle;
    /** ARGB; 0 = none. */
    final int color;
    final int[] colors;
    final float[] positions;
    /** URL paints: the image; else null. */
    String imageUrl;
    /** {dx, dy, radius} in CSS px per shadow, and their ARGB colours; null = none. */
    float[][] shadows;
    int[] shadowColors;

    private NickPaint(boolean radial, boolean repeat, float angle, int color, int[] colors, float[] positions) {
        this.radial = radial;
        this.repeat = repeat;
        this.angle = angle;
        this.color = color;
        this.colors = colors;
        this.positions = positions;
    }

    /** @return the paint, or null if it draws nothing we can show. */
    static NickPaint parse(JSONObject json) {
        NickPaint paint = parseFill(json);
        JSONArray list = json.optJSONArray("shadows");
        if (paint != null && list != null && list.length() > 0) {
            paint.shadows = new float[list.length()][];
            paint.shadowColors = new int[list.length()];
            for (int i = 0; i < list.length(); i++) {
                JSONObject sh = list.optJSONObject(i);
                paint.shadows[i] = sh == null ? new float[3] : new float[]{(float) sh.optDouble("x_offset"),
                        (float) sh.optDouble("y_offset"), (float) sh.optDouble("radius")};
                paint.shadowColors[i] = sh == null ? 0 : rgbaToArgb(sh.optLong("color"));
            }
        }
        return paint;
    }

    private static NickPaint parseFill(JSONObject json) {
        String function = json.optString("function", "");
        int color = json.isNull("color") ? 0 : rgbaToArgb(json.optLong("color"));
        String image = json.isNull("image_url") ? "" : json.optString("image_url", "");
        if ("URL".equals(function) && !image.isEmpty()) {
            NickPaint paint = new NickPaint(false, false, 0, color, null, null);
            // 7TV may send a CDN-relative path (the extension prefixes its CDN).
            paint.imageUrl = image.startsWith("//") ? "https:" + image
                    : image.startsWith("/") ? "https://cdn.7tv.app" + image : image;
            return paint;
        }
        JSONArray stops = json.optJSONArray("stops");
        int n = stops == null ? 0 : stops.length();
        boolean gradient = ("LINEAR_GRADIENT".equals(function) || "RADIAL_GRADIENT".equals(function)) && n >= 2;
        if (!gradient) {
            return color != 0 ? new NickPaint(false, false, 0, color, null, null) : null;
        }
        int[] colors = new int[n];
        float[] positions = new float[n];
        for (int i = 0; i < n; i++) {
            JSONObject stop = stops.optJSONObject(i);
            colors[i] = stop == null ? 0 : rgbaToArgb(stop.optLong("color"));
            positions[i] = stop == null ? 0 : (float) stop.optDouble("at", 0);
        }
        return new NickPaint("RADIAL_GRADIENT".equals(function), json.optBoolean("repeat"),
                (float) json.optDouble("angle", 90), color, colors, positions);
    }

    /** 7TV/CSS packed {@code 0xRRGGBBAA} (may arrive as a negative or > 2^31 number) to Android ARGB. */
    static int rgbaToArgb(long rgba) {
        int v = (int) rgba;
        return (v << 24) | (v >>> 8);
    }

    /**
     * Gradient start/end along the CSS angle (0deg = to top, 90deg = to right)
     * over a {@code w x h} box at the origin: the CSS gradient line, length
     * {@code |w sin a| + |h cos a|} through the centre. Returns {x0, y0, x1, y1}.
     */
    static float[] linearEnds(float angleDeg, float w, float h) {
        double a = Math.toRadians(angleDeg);
        double dx = Math.sin(a);
        double dy = -Math.cos(a);
        double half = (Math.abs(w * dx) + Math.abs(h * dy)) / 2;
        float cx = w / 2;
        float cy = h / 2;
        return new float[]{(float) (cx - dx * half), (float) (cy - dy * half),
                (float) (cx + dx * half), (float) (cy + dy * half)};
    }

    Shader shader(float left, float top, float w, float h) {
        float[] pos = positions;
        Shader.TileMode mode = Shader.TileMode.CLAMP;
        float from = 0;
        float span = 1;
        if (repeat && pos[pos.length - 1] > pos[0]) {
            // CSS repeating gradients repeat the [first stop, last stop] stretch.
            from = pos[0];
            span = pos[pos.length - 1] - pos[0];
            pos = new float[positions.length];
            for (int i = 0; i < pos.length; i++) {
                pos[i] = (positions[i] - from) / span;
            }
            mode = Shader.TileMode.REPEAT;
        }
        if (radial) {
            float radius = (float) Math.max(1, Math.hypot(w / 2, h / 2) * span);
            return new RadialGradient(left + w / 2, top + h / 2, radius, colors, pos, mode);
        }
        float[] e = linearEnds(angle, w, h);
        float x0 = e[0] + (e[2] - e[0]) * from;
        float y0 = e[1] + (e[3] - e[1]) * from;
        return new LinearGradient(left + x0, top + y0,
                left + x0 + (e[2] - e[0]) * span, top + y0 + (e[3] - e[1]) * span, colors, pos, mode);
    }

    /** Draws the covered name with the paint; one word, so it doesn't need to wrap. */
    static final class Span extends ReplacementSpan {
        private final NickPaint fixed;
        /** Pending: the user whose paint was still loading when the row was built. */
        private final String userId;
        /** Pending: the name's colour. A ReplacementSpan's paint skips the ForegroundColorSpan over it. */
        private final int color;

        Span(NickPaint paint) {
            this(paint, null, 0);
        }

        private Span(NickPaint paint, String userId, int color) {
            this.fixed = paint;
            this.userId = userId;
            this.color = color;
        }

        /** The name in {@code color} until this user's paint arrives, then the paint: rows on screen aren't rebuilt. */
        static Span pending(String userId, int color) {
            return new Span(null, userId, color);
        }

        @Override
        public int getSize(Paint p, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) {
                p.getFontMetricsInt(fm);
            }
            return Math.round(p.measureText(text, start, end));
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y,
                int bottom, Paint p) {
            NickPaint paint = fixed != null ? fixed : RteBadgeStore.knownPaint(userId);
            Shader oldShader = p.getShader();
            int oldColor = p.getColor();
            if (userId != null) {
                p.setColor(color);
            }
            if (paint == null) {
                canvas.drawText(text, start, end, x, y, p);
                p.setColor(oldColor);
                return;
            }
            float w = p.measureText(text, start, end);
            Drawable image = paint.imageUrl != null ? EmoteImageCache.getIfReady(paint.imageUrl) : null;
            if (paint.shadows != null) {
                // Under the fill: each CSS drop-shadow as the text's shadow layer.
                float density = Resources.getSystem().getDisplayMetrics().density;
                for (int i = 0; i < paint.shadows.length; i++) {
                    float[] sh = paint.shadows[i];
                    p.setShadowLayer(Math.max(0.5f, sh[2] * density), sh[0] * density, sh[1] * density,
                            paint.shadowColors[i]);
                    canvas.drawText(text, start, end, x, y, p);
                }
                p.clearShadowLayer();
            }
            if (image != null && w > 0) {
                Rect b = image.getBounds();
                int layer = canvas.saveLayer(x, top, x + w, bottom, null);
                canvas.drawText(text, start, end, x, y, p);
                Paint mask = new Paint();
                mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
                canvas.saveLayer(x, top, x + w, bottom, mask);
                // A shared drawable: its bounds stay, the canvas maps them onto the name.
                canvas.translate(x, top);
                canvas.scale(w / Math.max(1, b.width()), (bottom - top) / (float) Math.max(1, b.height()));
                canvas.translate(-b.left, -b.top);
                image.draw(canvas);
                canvas.restoreToCount(layer);
                p.setColor(oldColor);
                return;
            }
            if (paint.colors != null) {
                p.setShader(paint.shader(x, top, w, bottom - top));
                p.setColor(0xFF000000);
            } else if (paint.color != 0) {
                p.setColor(paint.color);
            }
            canvas.drawText(text, start, end, x, y, p);
            p.setShader(oldShader);
            p.setColor(oldColor);
        }
    }
}
