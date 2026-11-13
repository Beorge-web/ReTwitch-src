package tv.retwitch.mod;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Danmaku: chat messages flying right to left over the video ({@code DANMAKU}, landscape only, not in PiP).
 * Messages come from the chat's IRC PRIVMSG ({@link ModLogs#onIrcEvent}; text, Twitch emote ranges from the
 * {@code emotes} tag, third-party emotes by word), so history and our own echo never fly. The overlay sits
 * right above the largest video surface like {@link Ambilight}'s glow, does not take touches, and keeps
 * one row per lane: a message waits for a lane whose last one has fully entered, and is dropped after
 * {@link #MAX_WAIT_MS} or past {@link #MAX_PENDING}.
 */
public final class Danmaku {

    private static final String TAG = "RTDanmaku";
    private static final int MAX_PENDING = 60;
    private static final long MAX_WAIT_MS = 4000;
    static final String KEY_SPEED = "danmaku_speed";
    static final String KEY_AREA = "danmaku_area";
    static final String KEY_OPACITY = "danmaku_opacity";
    static final String KEY_SIZE = "danmaku_size";
    /** Seconds to cross the screen per speed choice. */
    static final int[] SPEEDS = {12, 9, 7, 5};
    /** Share of the video height used by lanes. */
    static final float[] AREAS = {0.25f, 0.5f, 0.75f, 1f};
    static final int[] OPACITIES = {40, 60, 80, 100};
    static final int[] SIZES = {14, 16, 18, 22};

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean installed;
    private static volatile Overlay current;

    private Danmaku() {
    }

    static void install(Application application) {
        if (installed || application == null) {
            return;
        }
        installed = true;
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) { attach(a); }
            public void onActivityStarted(Activity a) { }
            public void onActivityResumed(Activity a) { }
            public void onActivityPaused(Activity a) { }
            public void onActivityStopped(Activity a) { }
            public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            public void onActivityDestroyed(Activity a) { }
        });
    }

    /** An overlay is on screen and wants messages. */
    static boolean active() {
        return current != null;
    }

    static int setting(String key, int fallback) {
        Context c = AppContext.get();
        return c == null ? fallback : Toggles.prefs(c).getInt(key, fallback);
    }

    private static void attach(final Activity activity) {
        if (activity.getWindow() == null || !activity.getClass().getName().startsWith("tv.twitch")) {
            return;
        }
        final View decor = activity.getWindow().getDecorView();
        decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                try {
                    update(activity, decor);
                } catch (Throwable t) {
                    Log.w(TAG, "update failed", t);
                }
            }
        });
    }

    private static void update(Activity activity, View decor) {
        Overlay existing = current;
        boolean wanted = Toggles.on(Toggles.DANMAKU) && !activity.isInPictureInPictureMode()
                && activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (!wanted) {
            if (existing != null && existing.getRootView() == decor.getRootView()) {
                existing.detach();
            }
            return;
        }
        View surface = Ambilight.largestSurface(decor);
        if (surface == null || !(surface.getParent() instanceof ViewGroup)) {
            if (existing != null && existing.getRootView() == decor.getRootView()) {
                existing.detach();
            }
            return;
        }
        ViewGroup parent = (ViewGroup) surface.getParent();
        if (existing != null && existing.getParent() == parent) {
            return;
        }
        if (existing != null) {
            existing.detach();
        }
        Overlay overlay = new Overlay(activity);
        parent.addView(overlay, parent.indexOfChild(surface) + 1, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        current = overlay;
    }

    /** An IRC PRIVMSG of the subscribed channel (IRC thread). */
    static void onMessage(Map<?, ?> tags, String content) {
        final Overlay overlay = current;
        if (overlay == null || tags == null || content == null) {
            return;
        }
        Object room = tags.get("room-id");
        String channel = ChannelTracker.getCurrentChannelId();
        if (room != null && channel != null && !channel.equals(room.toString())) {
            return;
        }
        Object name = tags.get("display-name");
        if (name != null && ChatFilters.isHiddenSender(name.toString())) {
            return;
        }
        String text = content.startsWith("\u0001ACTION ") ? content.substring(8).replace("\u0001", "") : content;
        final List<Object> parts = parts(text, tags.get("emotes"), channel);
        if (name != null && Toggles.on(Toggles.DANMAKU_NICKS)) {
            int color = 0xFFB0B0FF;
            try {
                Object tag = tags.get("color");
                if (tag != null && tag.toString().startsWith("#")) {
                    color = Color.parseColor(tag.toString());
                }
            } catch (IllegalArgumentException ignored) {
                // keep the default
            }
            parts.add(0, new Nick(name + ": ", color));
        }
        MAIN.post(() -> overlay.add(parts));
    }

    /** The text split into Strings and emote image URLs (Twitch ranges first, then third-party words). */
    private static List<Object> parts(String text, Object emotesTag, String channel) {
        TreeMap<Integer, int[]> ranges = new TreeMap<>(); // start (UTF-16) -> {end, id index}
        List<String> ids = new ArrayList<>();
        if (emotesTag != null && !emotesTag.toString().isEmpty()) {
            for (String emote : emotesTag.toString().split("/")) {
                int colon = emote.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                ids.add(emote.substring(0, colon));
                for (String range : emote.substring(colon + 1).split(",")) {
                    int dash = range.indexOf('-');
                    try {
                        // Twitch counts code points; convert to UTF-16 offsets.
                        int start = text.offsetByCodePoints(0, Integer.parseInt(range.substring(0, dash)));
                        int end = text.offsetByCodePoints(0, Integer.parseInt(range.substring(dash + 1)) + 1);
                        ranges.put(start, new int[]{end, ids.size() - 1});
                    } catch (RuntimeException ignored) {
                        // malformed range: leave it as text
                    }
                }
            }
        }
        List<Object> parts = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int[] range = ranges.get(i);
            if (range != null) {
                flush(plain, parts);
                parts.add(new EmoteUrl("https://static-cdn.jtvnw.net/emoticons/v2/" + ids.get(range[1]) + "/default/dark/1.0"));
                i = range[0];
                continue;
            }
            int end = text.indexOf(' ', i);
            if (end < 0) {
                end = text.length();
            }
            Integer next = ranges.ceilingKey(i);
            if (next != null && next < end) {
                plain.append(text, i, next);
                i = next;
                continue;
            }
            tv.retwitch.mod.emotes.Emote emote = end > i ? tv.retwitch.mod.emotes.EmoteCache.lookup(text.substring(i, end), channel) : null;
            if (emote != null && !emote.zeroWidth) {
                flush(plain, parts);
                parts.add(new EmoteUrl(emote.imageUrl));
            } else {
                plain.append(text, i, end);
            }
            if (end < text.length()) {
                plain.append(' ');
            }
            i = end + 1;
        }
        flush(plain, parts);
        return parts;
    }

    private static void flush(StringBuilder plain, List<Object> parts) {
        if (plain.length() > 0) {
            parts.add(plain.toString());
            plain.setLength(0);
        }
    }

    private static final class Nick {
        final String text;
        final int color;

        Nick(String text, int color) {
            this.text = text;
            this.color = color;
        }
    }

    private static final class EmoteUrl {
        final String url;

        EmoteUrl(String url) {
            this.url = url;
        }
    }

    private static final class Item {
        final List<Object> parts;
        float width;
        int lane;
        long start;
        final long queued = SystemClock.uptimeMillis();

        Item(List<Object> parts) {
            this.parts = parts;
        }
    }

    /** The overlay: lanes of moving items, redrawn every frame while any is on screen. */
    private static final class Overlay extends View {
        private final ArrayDeque<Item> pending = new ArrayDeque<>();
        private final List<Item> flying = new ArrayList<>();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Item[] lastInLane = new Item[0];

        Overlay(Context context) {
            super(context);
            setWillNotDraw(false);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            fill.setColor(Color.WHITE);
            fill.setTypeface(Typeface.DEFAULT_BOLD);
            stroke.setColor(Color.BLACK);
            stroke.setTypeface(Typeface.DEFAULT_BOLD);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeJoin(Paint.Join.ROUND);
        }

        void detach() {
            if (current == this) {
                current = null;
            }
            if (getParent() instanceof ViewGroup) {
                ((ViewGroup) getParent()).removeView(this);
            }
        }

        void add(List<Object> parts) {
            if (parts.isEmpty() || current != this) {
                return;
            }
            pending.addLast(new Item(parts));
            while (pending.size() > MAX_PENDING) {
                pending.removeFirst();
            }
            postInvalidateOnAnimation();
        }

        private float textSize() {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, SIZES[clamp(setting(KEY_SIZE, 1), SIZES.length)],
                    getResources().getDisplayMetrics());
        }

        private static int clamp(int i, int n) {
            return Math.max(0, Math.min(i, n - 1));
        }

        private float measure(Item item, float size) {
            float w = 0;
            for (Object part : item.parts) {
                w += part instanceof String ? fill.measureText((String) part)
                        : part instanceof Nick ? fill.measureText(((Nick) part).text) : size * 1.2f;
            }
            return w;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            try {
                draw0(canvas);
            } catch (Throwable t) {
                Log.w(TAG, "draw failed", t);
            }
        }

        private void draw0(Canvas canvas) {
            float size = textSize();
            fill.setTextSize(size);
            stroke.setTextSize(size);
            stroke.setStrokeWidth(Math.max(2f, size / 8f));
            int alpha = Math.round(255 * OPACITIES[clamp(setting(KEY_OPACITY, 2), OPACITIES.length)] / 100f);
            fill.setAlpha(alpha);
            stroke.setAlpha(alpha);
            float lineHeight = size * 1.5f;
            int lanes = Math.max(1, (int) (getHeight() * AREAS[clamp(setting(KEY_AREA, 1), AREAS.length)] / lineHeight));
            if (lastInLane.length != lanes) {
                lastInLane = new Item[lanes];
            }
            long duration = SPEEDS[clamp(setting(KEY_SPEED, 1), SPEEDS.length)] * 1000L;
            long now = SystemClock.uptimeMillis();
            int width = getWidth();
            float gap = size;

            // Start waiting items in free lanes, oldest first; drop the stale ones.
            Iterator<Item> waiting = pending.iterator();
            while (waiting.hasNext()) {
                Item item = waiting.next();
                if (now - item.queued > MAX_WAIT_MS) {
                    waiting.remove();
                    continue;
                }
                int lane = freeLane(now, duration, width, gap);
                if (lane < 0) {
                    break;
                }
                waiting.remove();
                item.width = measure(item, size);
                item.lane = lane;
                item.start = now;
                lastInLane[lane] = item;
                flying.add(item);
            }

            Paint.FontMetrics metrics = fill.getFontMetrics();
            Iterator<Item> it = flying.iterator();
            while (it.hasNext()) {
                Item item = it.next();
                float x = x(item, now, duration, width);
                if (x + item.width < 0) {
                    it.remove();
                    continue;
                }
                float top = item.lane * lineHeight + (lineHeight - size) / 2;
                float baseline = top - metrics.ascent * (size / (metrics.descent - metrics.ascent));
                for (Object part : item.parts) {
                    if (part instanceof String) {
                        String s = (String) part;
                        canvas.drawText(s, x, baseline, stroke);
                        canvas.drawText(s, x, baseline, fill);
                        x += fill.measureText(s);
                    } else if (part instanceof Nick) {
                        Nick nick = (Nick) part;
                        canvas.drawText(nick.text, x, baseline, stroke);
                        fill.setColor(nick.color);
                        fill.setAlpha(alpha);
                        canvas.drawText(nick.text, x, baseline, fill);
                        fill.setColor(Color.WHITE);
                        fill.setAlpha(alpha);
                        x += fill.measureText(nick.text);
                    } else {
                        Drawable d = tv.retwitch.mod.emotes.EmoteImages.get(((EmoteUrl) part).url);
                        if (d != null && d.getBounds().height() > 0) {
                            float scale = size * 1.2f / d.getBounds().height();
                            canvas.save();
                            canvas.translate(x, top - size * 0.1f);
                            canvas.scale(scale, scale);
                            canvas.translate(-d.getBounds().left, -d.getBounds().top);
                            d.setAlpha(alpha);
                            d.draw(canvas);
                            canvas.restore();
                        }
                        x += size * 1.2f;
                    }
                }
            }
            if (!flying.isEmpty() || !pending.isEmpty()) {
                postInvalidateOnAnimation();
            }
        }

        /** One speed for all (the screen width per duration): a longer message must not catch up with a shorter one. */
        private static float x(Item item, long now, long duration, int width) {
            return width - (now - item.start) * (width / (float) duration);
        }

        /** A lane whose last item has fully entered (plus a gap) and is gone or moving away; -1 if none. */
        private int freeLane(long now, long duration, int width, float gap) {
            for (int lane = 0; lane < lastInLane.length; lane++) {
                Item last = lastInLane[lane];
                if (last == null || !flying.contains(last) || x(last, now, duration, width) + last.width + gap < width) {
                    return lane;
                }
            }
            return -1;
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            return false; // the player's gestures stay underneath
        }
    }
}
