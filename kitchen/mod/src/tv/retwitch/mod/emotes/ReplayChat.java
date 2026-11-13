package tv.retwitch.mod.emotes;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import android.text.style.StyleSpan;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lines of a recording's chat ({@code ChatTape}) drawn the way the mod's live chat looks: Twitch badges, the nick in
 * its colour, Twitch emotes as images ({@link EmoteImageCache}, the same cache as everything else) and the channel's
 * 7TV, BTTV and FFZ emotes with modifiers through {@link EmoteChatHook#bindReplay}.
 * shortcut: no nick paints or RTE badges (they hook Twitch's own row building), add them if asked.
 */
public final class ReplayChat {

    private static final String EMOTE_URL = "https://static-cdn.jtvnw.net/emoticons/v2/%s/default/dark/2.0";
    /** "set/version" -> image URL: global badges, then the channel's on top. */
    private static final Map<String, String> BADGES = new ConcurrentHashMap<>();
    /** Twitch's palette for chatters who never picked a colour. */
    private static final int[] DEFAULT_COLORS = {0xFFFF0000, 0xFF0000FF, 0xFF008000, 0xFFB22222, 0xFFFF7F50,
            0xFF9ACD32, 0xFFFF4500, 0xFF2E8B57, 0xFFDAA520, 0xFFD2691E, 0xFF5F9EA0, 0xFF1E90FF, 0xFFFF69B4,
            0xFF8A2BE2, 0xFF00FF7F};

    private ReplayChat() {
    }

    /** GQL {@code {badges{setID version imageURL}, user{broadcastBadges{...}}}}. */
    public static void setBadges(JSONObject data) {
        put(data.optJSONArray("badges"));
        JSONObject user = data.optJSONObject("user");
        put(user == null ? null : user.optJSONArray("broadcastBadges"));
    }

    private static void put(JSONArray badges) {
        for (int i = 0; badges != null && i < badges.length(); i++) {
            JSONObject badge = badges.optJSONObject(i);
            if (badge != null && !badge.optString("imageURL").isEmpty()) {
                BADGES.put(badge.optString("setID") + "/" + badge.optString("version"), badge.optString("imageURL"));
            }
        }
    }

    /** A built line; {@code missing} while an image of it is still downloading. */
    public static final class Line {
        public final Spanned text;
        public final boolean missing;

        Line(Spanned text, boolean missing) {
            this.text = text;
            this.missing = missing;
        }
    }

    /** A {@code ChatTape} line: badges {@code badgePx} tall, then the nick, then the message. */
    public static Line build(JSONObject message, int badgePx) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        boolean missing = false;
        String badges = message.optString("b");
        if (!badges.isEmpty()) {
            for (String badge : badges.split(",")) {
                String url = BADGES.get(badge);
                if (url == null) {
                    continue;
                }
                Drawable image = EmoteImageCache.getIfReady(url);
                if (image == null) {
                    missing = true;
                    continue;
                }
                int at = b.length();
                b.append('￼');
                b.setSpan(new BadgeSpan(image, badgePx), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        String login = message.optString("u");
        String name = message.optString("n", login);
        if (!name.equalsIgnoreCase(login) && !login.isEmpty()) {
            name = name + " (" + login + ")"; // a localized name, as Twitch shows it
        }
        int color = readable(color(message.optString("c"), login));
        int nameStart = b.length();
        b.append(name);
        b.setSpan(new ForegroundColorSpan(color), nameStart, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new StyleSpan(Typeface.BOLD), nameStart, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        boolean me = message.optInt("m") == 1;
        b.append(me ? " " : ": ");
        int bodyStart = b.length();
        JSONArray fragments = message.optJSONArray("f");
        for (int i = 0; fragments != null && i < fragments.length(); i++) {
            Object part = fragments.opt(i);
            if (part instanceof JSONArray) {
                String code = ((JSONArray) part).optString(0);
                Drawable image = EmoteImageCache.getIfReady(String.format(Locale.ROOT, EMOTE_URL,
                        ((JSONArray) part).optString(1)));
                int at = b.length();
                b.append(code);
                if (image != null) {
                    b.setSpan(new EmoteChatHook.EmoteSpan(image, false), at, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else {
                    missing = true;
                }
            } else if (part != null) {
                b.append(String.valueOf(part));
            }
        }
        if (me) {
            b.setSpan(new ForegroundColorSpan(color), bodyStart, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return new Line(b, missing);
    }

    /** Puts a built line on its row, with the channel's third-party emotes. */
    public static void bind(TextView view, Spanned line, String channelId) {
        EmoteChatHook.bindReplay(view, line, channelId);
    }

    private static int color(String hex, String login) {
        try {
            if (hex != null && hex.startsWith("#") && hex.length() == 7) {
                return 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
            }
        } catch (NumberFormatException ignored) {
            // falls to the default palette
        }
        return DEFAULT_COLORS[Math.abs(login.hashCode() % DEFAULT_COLORS.length)];
    }

    /** Dark nicks are lifted toward white on the dark chat, as Twitch does. */
    static int readable(int color) {
        double luminance = Color.luminance(color);
        if (luminance >= 0.18) {
            return color;
        }
        float mix = (float) Math.min(0.6, (0.18 - luminance) * 4 + 0.25);
        int r = Math.round(Color.red(color) + (255 - Color.red(color)) * mix);
        int g = Math.round(Color.green(color) + (255 - Color.green(color)) * mix);
        int bl = Math.round(Color.blue(color) + (255 - Color.blue(color)) * mix);
        return Color.rgb(r, g, bl);
    }

    /** A badge drawn {@code size} px tall from the shared decoded image, its bounds untouched (RenderThread reads them). */
    private static final class BadgeSpan extends ReplacementSpan {
        private final Drawable image;
        private final int size;

        BadgeSpan(Drawable image, int size) {
            this.image = image;
            this.size = size;
        }

        private float width() {
            Rect r = image.getBounds();
            return r.height() > 0 ? size * r.width() / (float) r.height() : size;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            return Math.round(width() + size * 0.25f);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom,
                Paint paint) {
            Rect r = image.getBounds();
            if (r.height() <= 0) {
                return;
            }
            float scale = size / (float) r.height();
            canvas.save();
            canvas.translate(x, (top + bottom - size) / 2f);
            canvas.scale(scale, scale);
            canvas.translate(-r.left, -r.top);
            image.draw(canvas);
            canvas.restore();
        }
    }
}
