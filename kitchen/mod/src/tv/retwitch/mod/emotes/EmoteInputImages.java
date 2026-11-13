package tv.retwitch.mod.emotes;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ReplacementSpan;
import android.util.Log;
import android.widget.EditText;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import tv.retwitch.mod.ChannelTracker;
import tv.retwitch.mod.CrashReporter;
import tv.retwitch.mod.Toggles;

/**
 * Emote images right in the chat input draft (twitch-patched's "inline draft
 * images"): whole words that are ready emotes get an image span; the text
 * itself -- what gets sent -- is untouched. Attached by
 * {@code AutocompleteNoColonPatch} to Twitch's message input. Twitch's own
 * emotes come from the map of its autocomplete filter: only those the account
 * can send.
 */
public final class EmoteInputImages {

    private static final String TAG = "RTEmoteInput";
    /** A word waits for its image this long at most: nothing else redraws the field once it lands. */
    private static final long RETRY_MS = 500;
    private static final int RETRIES = 10;
    private static final String TWITCH_EMOTE = "https://static-cdn.jtvnw.net/emoticons/v2/%s/default/dark/2.0";

    /** The maps of the emote filters, one per chat component ({@code AutocompletePatch}); the newest are checked first. */
    private static final ArrayDeque<WeakReference<Map<?, ?>>> MAPS = new ArrayDeque<>();
    private static final int MAX_MAPS = 16;

    private EmoteInputImages() {
    }

    /** Injected where Twitch's emote filter stores its map, still empty. Never throws. */
    public static void noteEmotes(Object candidate) {
        if (candidate instanceof LinkedHashMap) {
            synchronized (MAPS) {
                MAPS.addFirst(new WeakReference<Map<?, ?>>((Map<?, ?>) candidate));
                if (MAPS.size() > MAX_MAPS) {
                    MAPS.removeLast();
                }
            }
        }
    }

    /** The image of a Twitch emote by its code, or null. */
    private static String twitchEmoteUrl(String code) {
        try {
            synchronized (MAPS) {
                for (WeakReference<Map<?, ?>> ref : MAPS) {
                    Map<?, ?> map = ref.get();
                    String[] emote = map != null ? EmoteModelReflect.anyIdAndToken(map.get(code)) : null;
                    if (emote != null && code.equals(emote[1])) {
                        return String.format(Locale.ROOT, TWITCH_EMOTE, emote[0]);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "twitchEmoteUrl failed", t);
        }
        return null;
    }

    /** Injected after Twitch adds its own watcher to the message input. Never throws. */
    public static void attach(Object view) {
        try {
            if (!(view instanceof EditText)) {
                return;
            }
            final EditText field = (EditText) view;
            field.addTextChangedListener(new TextWatcher() {
                private boolean busy;
                private int retries;
                private final Runnable retry = () -> applyTo(field.getText());

                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    if (busy || !Toggles.on(Toggles.EMOTE_INPUT_IMAGES)) {
                        return;
                    }
                    retries = 0;
                    applyTo(s);
                }

                private void applyTo(Editable s) {
                    busy = true;
                    try {
                        field.removeCallbacks(retry);
                        if (apply(s) && retries++ < RETRIES) {
                            field.postDelayed(retry, RETRY_MS);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "apply failed", t);
                        CrashReporter.report(TAG, "apply failed", t);
                    } finally {
                        busy = false;
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "attach failed", t);
            CrashReporter.report(TAG, "attach failed", t);
        }
    }

    /** @return whether some emote's image is still downloading (getIfReady has started it). */
    private static boolean apply(Editable s) {
        String text = s.toString();
        // Drop images whose word was edited.
        for (InputEmoteSpan span : s.getSpans(0, s.length(), InputEmoteSpan.class)) {
            int start = s.getSpanStart(span);
            int end = s.getSpanEnd(span);
            if (start < 0 || !text.substring(start, end).equals(span.code) || !isWord(text, start, end)) {
                s.removeSpan(span);
            }
        }
        String channelId = ChannelTracker.getCurrentChannelId();
        boolean waiting = false;
        int i = 0;
        while (i < text.length()) {
            if (Character.isWhitespace(text.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < text.length() && !Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (s.getSpans(start, i, ReplacementSpan.class).length > 0) {
                continue; // already an image (ours or a native emote)
            }
            String word = text.substring(start, i);
            Emote emote = EmoteCache.lookup(word, channelId);
            String url = emote != null ? emote.imageUrl : twitchEmoteUrl(word);
            Drawable drawable = url != null ? EmoteImageCache.getIfReady(url) : null;
            if (drawable != null) {
                s.setSpan(new InputEmoteSpan(drawable, word), start, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (url != null) {
                waiting = true;
            }
        }
        return waiting;
    }

    private static boolean isWord(String text, int start, int end) {
        return (start == 0 || Character.isWhitespace(text.charAt(start - 1)))
                && (end == text.length() || Character.isWhitespace(text.charAt(end)));
    }

    /**
     * Draws the shared chat drawable scaled into the text's own height. An
     * {@code ImageSpan} with the chat-sized bounds grew the single-line field's
     * line past the field, and its scrolling / cursor placement went wrong
     * (the caret wouldn't move past the image or back to the start).
     */
    private static final class InputEmoteSpan extends ReplacementSpan {
        final String code;
        private final Drawable drawable;

        InputEmoteSpan(Drawable drawable, String code) {
            this.drawable = drawable;
            this.code = code;
        }

        /** Scale from the drawable's bounds to the font's ascent..descent box. */
        private float scale(Paint paint) {
            Paint.FontMetricsInt fm = paint.getFontMetricsInt();
            int height = drawable.getBounds().height();
            return height > 0 ? (fm.descent - fm.ascent) / (float) height : 1f;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            // fm left alone: the line keeps the text's height.
            return Math.round(drawable.getBounds().width() * scale(paint));
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom,
                Paint paint) {
            float scale = scale(paint);
            Paint.FontMetricsInt fm = paint.getFontMetricsInt();
            canvas.save();
            canvas.translate(x - drawable.getBounds().left * scale, y + fm.ascent - drawable.getBounds().top * scale);
            canvas.scale(scale, scale);
            drawable.draw(canvas);
            canvas.restore();
        }
    }
}
