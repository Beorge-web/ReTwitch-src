package tv.retwitch.mod.emotes;

import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.method.MovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ImageSpan;
import android.text.style.ReplacementSpan;
import android.util.Log;
import android.util.TypedValue;
import android.text.Layout;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import tv.retwitch.mod.LinkPreviewSettings;
import tv.retwitch.mod.CrashReporter;
import tv.retwitch.mod.ChannelTracker;
import tv.retwitch.mod.ChatSettings;
/**
 * Injected as a wrapper around the return value of Twitch's own chat
 * message body renderer (the method that turns one message's token list
 * into the final {@link SpannedString} shown in a chat {@code TextView}).
 *
 * Deliberately additive-only: this never touches the tokens or the
 * existing spans Twitch already built (native emotes, mentions, bits
 * animations, links, badges...). It only looks at whitespace-delimited
 * words that carry no span at all yet, and -- if one happens to match a
 * cached third-party emote code for the current channel/global sets --
 * overlays an {@link ImageSpan} on top of that plain-text word.
 */
public final class EmoteChatHook {

    private static final String TAG = "RTEmotes";

    /**
     * How long to coalesce {@link #requestRefresh()} calls before running a
     * refresh pass. Emote sets and images finish loading in bursts (a whole
     * provider set at once; several images back-to-back), so a short debounce
     * turns each burst into a single re-injection pass over the visible chat
     * instead of one pass per arriving asset.
     */
    private static final long REFRESH_DEBOUNCE_MS = 150L;

    /**
     * Every chat {@link TextView} we've bound a body into, held weakly so a
     * recycled/detached row is free to be collected. This is the set of views
     * a {@link #requestRefresh()} pass re-injects emotes into once the
     * relevant set/image has finished loading -- the whole reason history
     * messages (rendered synchronously before any third-party data was ready)
     * ever get their emotes at all, rather than staying frozen as the plain
     * text they were first drawn as. Only ever touched on the main thread
     * (both {@link #onTextBound} and the refresh pass run there).
     */
    private static final Set<TextView> BOUND_VIEWS =
            Collections.newSetFromMap(new WeakHashMap<TextView, Boolean>());

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    /**
     * Whether a {@link #REFRESH_TASK} pass is already queued. Guarantees the
     * refresh fires at a fixed max cadence ({@link #REFRESH_DEBOUNCE_MS}) even
     * while {@link #requestRefresh()} is called continuously -- e.g. a busy
     * VOD/live chat where dozens of emote images finish downloading back to
     * back would, with a plain remove+reschedule debounce, keep pushing the
     * pass out past its delay forever (starvation) so it never actually ran.
     * Only ever touched on the main thread.
     */
    private static boolean refreshQueued = false;

    private static final Runnable REFRESH_TASK = new Runnable() {
        @Override
        public void run() {
            refreshQueued = false;
            // Runs on the main thread with no surrounding try/catch (posted
            // via a Handler) -- an escaped Throwable here would crash the
            // whole app, so swallow everything.
            long t0 = tv.retwitch.mod.UiStallLog.start();
            try {
                refreshBoundViews();
            } catch (Throwable t) {
                Log.w(TAG, "refresh pass crashed", t);
                CrashReporter.report(TAG, "refresh pass crashed", t);
            }
            tv.retwitch.mod.UiStallLog.end("EmoteRefresh", t0);
        }
    };

    private EmoteChatHook() {
    }

    /**
     * Injected directly into the return path of Twitch's own chat body
     * builder (see {@code EmoteChatPatch.kt}), so it runs on the render
     * thread inside Twitch's own code with no try/catch on the smali side --
     * an uncaught exception here would propagate straight into Twitch's chat
     * rendering and crash the app. It must therefore never throw: on any
     * failure it returns the original, unmodified {@link SpannedString}.
     */
    /**
     * Injected where Twitch builds a channel-points reward row's message body
     * (31.8.0 {@code je2.z}): that path calls the span-group builder directly,
     * skipping the body hook above, and its row never gets a refresh pass.
     * {@code message} is the chat message model (FFZ giant emote via its tags).
     * Never throws.
     */
    public static SpannedString injectRewardMessage(SpannedString body, Object message) {
        try {
            GiantEmotes.onRewardMessage(message, body);
        } catch (Throwable t) {
            Log.w(TAG, "injectRewardMessage failed", t);
            CrashReporter.report(TAG, "injectRewardMessage failed", t);
        }
        // shortcut: no 7TV personal emotes in reward rows, the sender isn't read off the model here.
        return inject(body, null);
    }

    /** The chat body builder's exit; it only runs inside the span group build, which noted the sender. */
    public static SpannedString injectThirdPartyEmotes(SpannedString original) {
        return inject(original, ChatCosmetics.buildingSender());
    }

    private static SpannedString inject(SpannedString original, String sender) {
        // Mention vibration keys off the once-per-message body build, so do it
        // here -- independent of whether any emote provider is enabled below.
        MentionHighlighter.onMessageBody(original);
        try {
            if (original == null || original.length() == 0 || !isAnythingEnabled()) {
                return original;
            }
            SpannableStringBuilder builder = buildInjected(original, ChannelTracker.getCurrentChannelId(), sender);
            return builder != null ? new SpannedString(builder) : original;
        } catch (Throwable t) {
            Log.w(TAG, "injectThirdPartyEmotes failed; leaving message unmodified", t);
            CrashReporter.report(TAG, "injectThirdPartyEmotes failed; leaving message unmodified", t);
            return original;
        }
    }

    /**
     * Runs the whitespace-word scan and overlays an {@link ImageSpan} on every
     * span-free word that matches a ready third-party emote. Around each base
     * emote it also gathers its group: BTTV prefix modifiers before it
     * ({@code w! KEKW}), and FFZ suffix modifiers plus zero-width overlay
     * emotes after it ({@code KEKW ffzX RainTime}). The group's extra words are
     * collapsed to zero width; the modifiers are painted by
     * {@link ModifiedEmoteDrawable} and the overlays stacked by
     * {@link LayeredEmoteDrawable}.
     * Returns a fresh {@link SpannableStringBuilder} if (and only if) at least one new
     * emote span was applied, otherwise {@code null} -- callers use that null
     * to mean "nothing changed, don't bother rebuilding/re-setting the text".
     * {@code sender} (Twitch user id, or null) gets its 7TV personal emotes.
     */
    private static SpannableStringBuilder buildInjected(Spanned original, String channelId, String sender) {
        String text = original.toString();
        int length = text.length();
        boolean modifiersOn = EmoteSettings.isModifiersEnabled();

        SpannableStringBuilder builder = null;
        int giantStart = GiantEmotes.isGiant(original) ? lastEmoteStart(original, text, channelId, sender) : -1;
        if (giantStart >= 0) {
            Log.d(TAG, "giant emote at " + giantStart + " in \"" + text + "\"");
        }
        int i = 0;
        while (i < length) {
            if (Character.isWhitespace(text.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < length && !Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            int end = i;

            String word = text.substring(start, end);
            if (modifiersOn && EmoteModifiers.prefix(word) != 0 && modifiesNextEmote(text, end, channelId, sender)) {
                // "w! KEKW": drawn with KEKW's group; standalone it showed as an emote until KEKW's image landed.
                continue;
            }
            boolean giantWord = start == giantStart;
            // FFZ:GE on a Twitch emote: our own span replaces Twitch's.
            Emote nativeGiant = giantWord ? nativeEmote(original, start, end) : null;
            Emote emote = nativeGiant != null ? nativeGiant : lookup(word, channelId, sender);
            if (emote == null || (hasBlockingSpan(original, start, end)
                    && !(giantWord && (nativeGiant != null || isOwnNormalEmote(original, start, end))
                    && EmoteImageCache.getIfReady(emote.imageUrl) != null))) {
                continue;
            }

            int flags = 0;
            int groupStart = start;
            // Modifiers / zero-width overlays drawn standalone by an earlier pass (the base wasn't
            // known or ready yet): taken back into the group.
            List<int[]> absorbed = null;
            if (modifiersOn) {
                // Prefix modifiers: consecutive "x!" words right before the emote.
                int scan = start;
                while (true) {
                    int we = scan;
                    while (we > 0 && Character.isWhitespace(text.charAt(we - 1))) {
                        we--;
                    }
                    int ws = we;
                    while (ws > 0 && !Character.isWhitespace(text.charAt(ws - 1))) {
                        ws--;
                    }
                    int flag = ws < we ? EmoteModifiers.prefix(text.substring(ws, we)) : 0;
                    if (flag == 0) {
                        break;
                    }
                    if (hasBlockingSpan(original, ws, we)) {
                        if (!isOwnNormalEmote(original, ws, we)) {
                            break;
                        }
                        if (absorbed == null) {
                            absorbed = new ArrayList<>(2);
                        }
                        absorbed.add(new int[]{ws, we});
                    }
                    flags |= flag;
                    groupStart = ws;
                    scan = ws;
                }
                if ((flags & EmoteModifiers.ZERO_SPACE) != 0) {
                    // z!: glue the emote to whatever precedes it.
                    while (groupStart > 0 && Character.isWhitespace(text.charAt(groupStart - 1))) {
                        groupStart--;
                    }
                }
            }

            // Detect the trailing group structurally (lookup-only, independent
            // of image readiness) BEFORE checking whether the base image is
            // ready. This is what stops a zero-width overlay / ffz modifier
            // from ever being drawn as a standalone emote just because its base
            // image hadn't downloaded yet -- which would then permanently block
            // them from merging once the base arrived.
            List<Emote> overlays = null;
            int groupEnd = end;
            int scan = end;
            while (true) {
                while (scan < length && Character.isWhitespace(text.charAt(scan))) {
                    scan++;
                }
                int ws = scan;
                while (scan < length && !Character.isWhitespace(text.charAt(scan))) {
                    scan++;
                }
                int we = scan;
                if (ws == we) {
                    break;
                }
                String next = text.substring(ws, we);
                int flag = modifiersOn ? EmoteModifiers.suffix(next) : 0;
                if (hasBlockingSpan(original, ws, we)) {
                    Emote own = isOwnNormalEmote(original, ws, we) ? lookup(next, channelId, sender) : null;
                    if (own == null || (!own.zeroWidth && flag == 0)) {
                        break;
                    }
                    if (absorbed == null) {
                        absorbed = new ArrayList<>(2);
                    }
                    absorbed.add(new int[]{ws, we});
                }
                if (flag != 0) {
                    flags |= flag;
                    groupEnd = we;
                    continue;
                }
                Emote overlay = lookup(next, channelId, sender);
                if (overlay == null || !overlay.zeroWidth) {
                    break;
                }
                if (overlays == null) {
                    overlays = new ArrayList<>(2);
                }
                overlays.add(overlay);
                groupEnd = we;
            }

            Drawable baseDrawable = EmoteImageCache.getIfReady(emote.imageUrl);
            if (giantWord && baseDrawable != null) {
                baseDrawable = new GiantDrawable(baseDrawable, emote.largeImageUrl);
            }
            if (baseDrawable == null) {
                // Base not ready -- skip the WHOLE group (don't render its
                // overlays/modifiers standalone); a later refresh pass retries
                // once getIfReady's download lands.
                i = groupEnd;
                continue;
            }
            if (builder == null) {
                builder = new SpannableStringBuilder(original);
            }
            if (absorbed != null) {
                for (int[] range : absorbed) {
                    for (Object old : builder.getSpans(range[0], range[1], Object.class)) {
                        if (old instanceof EmoteSpan || old instanceof EmoteClickSpan) {
                            builder.removeSpan(old);
                        }
                    }
                }
            }
            Drawable drawable = baseDrawable;
            if (EmoteModifiers.drawFlags(flags) != 0) {
                drawable = new ModifiedEmoteDrawable(baseDrawable, flags,
                        Resources.getSystem().getDisplayMetrics().density);
            }
            if (overlays != null) {
                drawable = new LayeredEmoteDrawable(drawable, overlays);
            }
            if (giantWord) {
                // Replacing our own normal-size span from an earlier pass, or Twitch's image (its tap stays).
                for (Object old : builder.getSpans(start, end, Object.class)) {
                    if (old instanceof EmoteSpan || old instanceof EmoteClickSpan
                            || nativeGiant != null && old instanceof ReplacementSpan) {
                        builder.removeSpan(old);
                    }
                }
            }
            builder.setSpan(new EmoteSpan(drawable, giantWord),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (nativeGiant == null) {
                applyClickSpan(builder, emote, overlays, start, end);
            }
            // Collapse the modifier/overlay words (and the spaces between them
            // and the emote) to zero width so only the emote image shows.
            if (groupStart < start) {
                builder.setSpan(new ZeroWidthCollapseSpan(), groupStart, start,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (groupEnd > end) {
                builder.setSpan(new ZeroWidthCollapseSpan(), end, groupEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            i = groupEnd;
        }
        builder = LinkPreview.append(original, builder);
        return builder;
    }

    /** {@code sender}'s 7TV personal emote, else the channel's or a global third-party one. */
    private static Emote lookup(String word, String channelId, String sender) {
        Emote personal = SevenTvPersonal.lookup(sender, word);
        return personal != null ? personal : EmoteCache.lookup(word, channelId);
    }

    /** Whether the first non-prefix word after {@code from} is a third-party emote (what prefixes modify). */
    private static boolean modifiesNextEmote(String text, int from, String channelId, String sender) {
        int i = from;
        while (true) {
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < text.length() && !Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (start == i) {
                return false;
            }
            String word = text.substring(start, i);
            if (EmoteModifiers.prefix(word) == 0) {
                return lookup(word, channelId, sender) != null;
            }
        }
    }

    private static boolean isAnythingEnabled() {
        return EmoteSettings.isAnyProviderEnabled()
                || LinkPreviewSettings.getMode() != LinkPreviewSettings.MODE_OFF;
    }

    /**
     * Overlays a tap target on a just-applied emote so tapping it opens the
     * ReTwitch emote card ({@link EmoteCardDialog}) -- our stand-in for
     * Twitch's native emote card, which only knows first-party emotes. Gated
     * by {@link EmoteSettings#isEmoteCardEnabled()}. The visible glyph is the
     * {@link ImageSpan} drawn on the same range, so the click span carries no
     * visible styling of its own.
     */
    private static void applyClickSpan(SpannableStringBuilder builder, Emote emote,
            List<Emote> overlays, int start, int end) {
        if (!EmoteSettings.isEmoteCardEnabled()) {
            return;
        }
        // Copy the overlay list: the caller's is transient/reused across words.
        List<Emote> stack = (overlays == null || overlays.isEmpty())
                ? null : new ArrayList<>(overlays);
        builder.setSpan(new EmoteClickSpan(emote, stack), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /**
     * A chat {@link TextView} only dispatches {@link ClickableSpan} taps if it
     * has a {@link MovementMethod}. Twitch sets one on rows that already have
     * clickable content (mentions/links), but a plain message we just added an
     * emote tap target to may have none -- give it a {@link LinkMovementMethod}
     * in that case. We only ever install one when it's currently {@code null},
     * so Twitch's own movement handling (where present) is left untouched.
     */
    private static void ensureClickable(Spanned spanned, TextView textView) {
        try {
            if (spanned.getSpans(0, spanned.length(), EmoteClickSpan.class).length > 0) {
                textView.setOnTouchListener(EMOTE_TAP);
            }
            if (textView.getMovementMethod() != null) {
                return;
            }
            if (spanned.getSpans(0, spanned.length(), ClickableSpan.class).length == 0) {
                return;
            }
            textView.setMovementMethod(LinkMovementMethod.getInstance());
        } catch (Throwable t) {
            Log.w(TAG, "ensureClickable failed", t);
            CrashReporter.report(TAG, "ensureClickable failed", t);
        }
    }

    /**
     * Taps on our emotes, hit-tested against the drawn image. Twitch's chat text hands the tap to
     * {@link LinkMovementMethod}, which takes the character boundary nearest to the finger and the first
     * clickable span there: on an image's left half that boundary can be the end of the sender name
     * span (inserted first, so it wins and the profile opens), and zero-width overlays have no width at
     * all. A hit cancels Twitch's gesture (no profile, no long-press menu) and opens the card.
     */
    private static final View.OnTouchListener EMOTE_TAP = new View.OnTouchListener() {
        private float downX;
        private float downY;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            try {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downX = e.getX();
                    downY = e.getY();
                    return false;
                }
                if (e.getActionMasked() != MotionEvent.ACTION_UP || !(v instanceof TextView)) {
                    return false;
                }
                int slop = ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
                if (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop
                        || e.getEventTime() - e.getDownTime() >= ViewConfiguration.getLongPressTimeout()) {
                    return false; // a scroll or a long press: Twitch's
                }
                Runnable thread = tv.retwitch.mod.ReplyThreads.pillAt((TextView) v, e.getX(), e.getY());
                EmoteClickSpan span = thread != null ? null : emoteAt((TextView) v, e.getX(), e.getY());
                if (thread == null && span == null) {
                    return false;
                }
                MotionEvent cancel = MotionEvent.obtain(e);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                v.onTouchEvent(cancel);
                cancel.recycle();
                if (thread != null) {
                    thread.run();
                } else {
                    span.onClick(v);
                }
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "emote tap failed", t);
                return false;
            }
        }
    };

    /** Short taps on {@code view} go through {@link #EMOTE_TAP} (reply pills, see ReplyThreads.pillAt). */
    public static void tapTargets(TextView view) {
        view.setOnTouchListener(EMOTE_TAP);
    }

    /** Our emote whose drawn image (plus a few dp around it) is under the point, or null. */
    private static EmoteClickSpan emoteAt(TextView view, float x, float y) {
        Layout layout = view.getLayout();
        CharSequence text = view.getText();
        if (layout == null || !(text instanceof Spanned)) {
            return null;
        }
        Spanned spanned = (Spanned) text;
        int px = Math.round(x) - view.getTotalPaddingLeft() + view.getScrollX();
        int py = Math.round(y) - view.getTotalPaddingTop() + view.getScrollY();
        int line = layout.getLineForVertical(py);
        if (py < layout.getLineTop(line) || py > layout.getLineBottom(line)) {
            return null;
        }
        int pad = Math.round(4 * view.getResources().getDisplayMetrics().density);
        EmoteClickSpan best = null;
        float bestDistance = Float.MAX_VALUE;
        for (EmoteClickSpan span : spanned.getSpans(layout.getLineStart(line), layout.getLineEnd(line), EmoteClickSpan.class)) {
            int start = spanned.getSpanStart(span);
            int end = spanned.getSpanEnd(span);
            if (layout.getLineForOffset(start) != line) {
                continue;
            }
            float left = layout.getPrimaryHorizontal(start);
            float width = 0;
            for (ImageSpan image : spanned.getSpans(start, end, ImageSpan.class)) {
                if (spanned.getSpanStart(image) == start && image.getDrawable() != null) {
                    width = Math.max(width, image.getDrawable().getBounds().width());
                }
            }
            if (width <= 0) {
                width = layout.getPrimaryHorizontal(end) - left;
            }
            float right = left + width;
            if (px >= left - pad && px <= right + pad) {
                float distance = px < left ? left - px : px > right ? px - right : 0;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = span;
                }
            }
        }
        return best;
    }

    /** Tap target on a custom emote: opens its {@link EmoteCardDialog}. */
    private static final class EmoteClickSpan extends ClickableSpan {
        private final Emote emote;
        private final List<Emote> overlays;

        EmoteClickSpan(Emote emote, List<Emote> overlays) {
            this.emote = emote;
            this.overlays = overlays;
        }

        @Override
        public void onClick(View widget) {
            try {
                EmoteCardDialog.show(widget.getContext(), emote, overlays);
            } catch (Throwable t) {
                Log.w(TAG, "emote card open failed", t);
                CrashReporter.report(TAG, "emote card open failed", t);
            }
        }

        @Override
        public void updateDrawState(TextPaint ds) {
            // The ImageSpan on the same range draws the glyph; keep the click
            // span itself invisible (no underline / colour change).
            ds.setUnderlineText(false);
        }
    }

    /** Our emote image span; {@code giant} once it shows the FFZ giant-emote image. */
    static final class EmoteSpan extends ImageSpan {
        final boolean giant;

        EmoteSpan(Drawable drawable, boolean giant) {
            super(drawable, ImageSpan.ALIGN_BOTTOM);
            this.giant = giant;
        }
    }

    /** The range only carries our own normal-size emote (and its tap target) -- safe to upgrade. */
    private static boolean isOwnNormalEmote(Spanned original, int start, int end) {
        boolean own = false;
        for (ReplacementSpan span : original.getSpans(start, end, ReplacementSpan.class)) {
            if (!(span instanceof EmoteSpan) || ((EmoteSpan) span).giant) {
                return false;
            }
            own = true;
        }
        for (ClickableSpan span : original.getSpans(start, end, ClickableSpan.class)) {
            if (!(span instanceof EmoteClickSpan)) {
                return false;
            }
        }
        return own;
    }

    /**
     * Start of the message's last emote word, third-party or Twitch's own (FFZ {@code pluckLastEmote}),
     * skipping zero-width overlays and plain words; -1 when there is none, it is giant already, or its
     * span is something else (bits).
     */
    private static int lastEmoteStart(Spanned original, String text, String channelId, String sender) {
        int end = text.length();
        while (end > 0) {
            while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
                end--;
            }
            int start = end;
            while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) {
                start--;
            }
            if (start < end) {
                ReplacementSpan[] spans = original.getSpans(start, end, ReplacementSpan.class);
                if (spans.length > 0 && !(spans[0] instanceof ZeroWidthCollapseSpan)) {
                    // Our normal-size span from an earlier pass, or Twitch's emote image.
                    return isOwnNormalEmote(original, start, end) || nativeEmote(original, start, end) != null
                            ? start : -1;
                }
                Emote emote = spans.length > 0 ? null : lookup(text.substring(start, end), channelId, sender);
                if (emote != null && !emote.zeroWidth) {
                    return start;
                }
            }
            end = start;
        }
        return -1;
    }

    /**
     * The Twitch emote drawn over the word: its image span's drawable (31.9.0 {@code ow60}, 32.0.0
     * {@code yn60}) carries the type enum ({@code Emote}; not a gigantified one, a GIF or a badge) and the
     * CDN URL. Null for anything else.
     */
    private static Emote nativeEmote(Spanned original, int start, int end) {
        for (ImageSpan span : original.getSpans(start, end, ImageSpan.class)) {
            if (span instanceof EmoteSpan || original.getSpanStart(span) != start) {
                continue;
            }
            try {
                Drawable image = span.getDrawable();
                Object type = image != null ? typeOf(image) : null;
                String url = type instanceof Enum && "Emote".equals(((Enum<?>) type).name()) ? urlOf(image) : null;
                if (url != null && url.startsWith("http")) {
                    return new Emote(original.subSequence(start, end).toString(), url, EmoteSource.TWITCH, false,
                            false, url.replaceFirst("/[1-3]\\.0$", "/3.0"));
                }
            } catch (Throwable t) {
                Log.w(TAG, "nativeEmote failed", t);
            }
        }
        return null;
    }

    /**
     * Whether the word range already carries a span we must not stack over: a
     * native emote/bits image ({@link ReplacementSpan}, which also covers our
     * own already-applied emote/stack/collapse spans -- keeping the refresh
     * pass idempotent) or a clickable mention/link ({@link ClickableSpan}).
     * Deliberately NOT "any span": the refresh pass runs on the *bound*
     * TextView's text, which also carries benign colour/size spans Twitch adds
     * at bind time; blocking on those is what once stopped already-displayed
     * rows from being repainted.
     */
    private static boolean hasBlockingSpan(Spanned original, int start, int end) {
        return original.getSpans(start, end, ReplacementSpan.class).length > 0
                || original.getSpans(start, end, ClickableSpan.class).length > 0;
    }

    /**
     * Second injection point, hooked at the one spot in Twitch's chat
     * rendering that hands a chat body {@link Spanned} to the real {@code
     * TextView} about to display it (see {@code chatMessageTextBindFingerprint}
     * in {@code EmoteChatPatch.kt}) -- called once per row bind, right
     * before {@code TextView.setText(...)}.
     *
     * {@link #injectThirdPartyEmotes} runs much earlier, while the message
     * is still just a standalone {@link SpannedString} with no TextView in
     * sight, so any {@link AnimatedEmoteDrawable} it created has nothing to
     * invalidate yet and only ever paints its first frame. This is where
     * that gets fixed: every still-present animated-emote span in the
     * Spanned actually being bound right now gets handed the live
     * TextView, so its {@code Drawable.Callback} calls have somewhere to
     * go and the animation actually plays.
     */
    public static void onTextBound(Spanned spanned, TextView textView) {
        long t0 = tv.retwitch.mod.UiStallLog.start();
        onTextBound0(spanned, textView);
        tv.retwitch.mod.UiStallLog.end("ChatRowBind", t0);
    }

    private static void onTextBound0(Spanned spanned, TextView textView) {
        // Injected directly into Twitch's per-row chat bind (see
        // EmoteChatPatch.kt), so it runs inside Twitch's own code with no
        // try/catch on the smali side -- must never throw.
        try {
            if (spanned == null || textView == null) {
                return;
            }
            BOUND_VIEWS.add(textView);
            applyFontScale(textView);
            // Rows carry "name: message": tie tag-flagged senders to them before
            // the highlight below and the refresh pass that renders giant emotes.
            GiantEmotes.onRowBound(spanned);
            attachAnimatedHosts(spanned, textView);
            ensureClickable(spanned, textView);
            MentionHighlighter.applyHighlight(textView, spanned);
            // This hook runs before Twitch's setText() in the same bind call.
            // Per-row injection must therefore happen on the next frame, after
            // the plain cached Spanned is actually on the TextView -- otherwise
            // history/VOD rows that were built before third-party data finished
            // loading stay plain text until the view is recycled and rebound.
            final Object historyLine = tv.retwitch.mod.UserHistory.takeBound();
            textView.post(new Runnable() {
                @Override
                public void run() {
                    // Also a bare main-thread callback -- guard it too.
                    try {
                        refreshTextView(textView);
                        tv.retwitch.mod.UserHistory.rendered(historyLine, textView);
                    } catch (Throwable t) {
                        Log.w(TAG, "per-row refresh crashed", t);
                        CrashReporter.report(TAG, "per-row refresh crashed", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "onTextBound failed", t);
            CrashReporter.report(TAG, "onTextBound failed", t);
        }
    }

    /**
     * The message line of a notice row (watch streak, sub, announcement) at its bind: these rows skip
     * {@link #onTextBound}, so an emote whose image wasn't ready when the body was built stayed text and
     * an animated one had no host. Not {@code onTextBound} itself: its highlight resets the view's background.
     * Injects here, not on the next frame: the body is rebuilt plain on every bind and would flash as text.
     * Never throws.
     */
    public static Spanned onNoticeBound(Spanned body, TextView view) {
        try {
            if (body == null || view == null || !isAnythingEnabled()) {
                return body;
            }
            BOUND_VIEWS.add(view);
            SpannableStringBuilder builder = buildInjected(body, ChannelTracker.getCurrentChannelId(), null);
            Spanned bound = builder != null ? new SpannedString(builder) : body;
            attachAnimatedHosts(bound, view);
            ensureClickable(bound, view);
            return bound;
        } catch (Throwable t) {
            Log.w(TAG, "onNoticeBound failed", t);
            CrashReporter.report(TAG, "onNoticeBound failed", t);
            return body;
        }
    }

    private static final int FONT_TAG_KEY = 0x7e700d42;

    /**
     * A chat line of the mod's recording player ({@link ReplayChat}): {@code channelId}'s third-party emotes,
     * animation and emote taps, as a Twitch row gets them. Not in {@link #BOUND_VIEWS}: the player rebuilds its
     * own rows (the refresh pass would use the open channel). Never throws.
     */
    static void bindReplay(TextView view, Spanned line, String channelId) {
        Spanned text = line;
        try {
            SpannableStringBuilder builder = isAnythingEnabled() ? buildInjected(line, channelId, null) : null;
            if (builder != null) {
                text = new SpannedString(builder);
            }
            view.setText(text);
            attachAnimatedHosts(text, view);
            ensureClickable(text, view);
        } catch (Throwable t) {
            Log.w(TAG, "bindReplay failed", t);
            view.setText(text);
        }
    }

    /**
     * Font size for a whole chat row, called from the adapter's bind: notices, reward and mod log
     * rows have their own binders and never reach {@link #onTextBound}. Compose rows have no TextView.
     */
    public static void scaleRow(Object row) {
        if (row instanceof TextView) {
            applyFontScale((TextView) row);
        } else if (row instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) row;
            for (int i = 0; i < group.getChildCount(); i++) {
                scaleRow(group.getChildAt(i));
            }
        }
    }

    /**
     * Chat image size in px (retwitchChatBadgeSizePatch): badges follow the font size, emotes have their own option.
     * {@code image} is the image type enum (31.9.0) or the chat image drawable holding it (32.0.0).
     */
    public static int imageSizePx(int px, Object image) {
        Object type;
        try {
            type = image instanceof Enum || !(image instanceof Drawable) ? image : typeOf((Drawable) image);
        } catch (Throwable t) {
            return px;
        }
        if (!(type instanceof Enum) || !"Badge".equals(((Enum<?>) type).name())) {
            return px;
        }
        int size = Math.round(px * ChatSettings.getFontScale() / 100f);
        badgeSize = size;
        return size;
    }

    /** The badge size cap last handed to Twitch: smaller badge images stay smaller, the box keeps it. */
    private static volatile int badgeSize;

    /** Twitch's chat image class and its type field (31.9.0 {@code ow60.b}, enum {@code ngp}), found once. */
    private static Class<?> imageClass;
    private static java.lang.reflect.Field imageType;
    /** Its image URL (31.9.0 {@code ow60.a}), the first String field. */
    private static java.lang.reflect.Field imageUrl;

    /**
     * By the image's type: once loaded, an image keeps its own proportions under the size cap
     * (31.9.0 {@code yjj.onResourceReady}), so badges have different heights and height tells nothing.
     */
    private static boolean onBaseline(Drawable image) throws IllegalAccessException {
        Object value = typeOf(image);
        return value instanceof Enum && "Badge".equals(((Enum<?>) value).name())
                && tv.retwitch.mod.Toggles.on(tv.retwitch.mod.Toggles.BADGES_ON_BASELINE);
    }

    /** The image's type enum, or null for a drawable that is not Twitch's chat image. */
    private static Object typeOf(Drawable image) throws IllegalAccessException {
        Class<?> type = image.getClass();
        if (type != imageClass) {
            imageType = null;
            imageUrl = null;
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (field.getType().isEnum() && imageType == null) {
                    field.setAccessible(true);
                    imageType = field;
                } else if (field.getType() == String.class && imageUrl == null) {
                    field.setAccessible(true);
                    imageUrl = field;
                }
            }
            imageClass = type;
        }
        return imageType != null ? imageType.get(image) : null;
    }

    /** The image URL of Twitch's chat image; call after {@link #typeOf} on the same drawable. */
    private static String urlOf(Drawable image) throws IllegalAccessException {
        java.lang.reflect.Field field = imageUrl;
        Object url = field != null && image.getClass() == imageClass ? field.get(image) : null;
        return url instanceof String ? (String) url : null;
    }

    /**
     * Twitch's image span (31.9.0 {@code bx4.draw}) centres an image on the font's box. That centres a
     * full-size badge on the name exactly (measured: 9 and 10 px around a capital), but a smaller one
     * ends higher. Takes the y Twitch worked out and returns the one standing a badge on the bottom of a
     * full-size box. Emotes stay as they are.
     */
    public static float imageTop(float y, Drawable image, android.graphics.Paint paint) {
        try {
            return onBaseline(image) ? y + (badgeBox(image) - image.getBounds().height()) / 2 : y;
        } catch (Throwable t) {
            return y;
        }
    }

    /**
     * The same for the line's height ({@code bx4.getSize}): always the full box, centred as Twitch does.
     *
     * @return the span's width, or -1 to let Twitch handle it.
     */
    public static int imageLine(ImageSpan span, android.graphics.Paint paint, android.graphics.Paint.FontMetricsInt line) {
        try {
            Drawable image = span.getDrawable();
            if (line == null || !onBaseline(image)) {
                return -1;
            }
            android.graphics.Paint.FontMetricsInt font = paint.getFontMetricsInt();
            int center = font.ascent + (font.descent - font.ascent) / 2;
            int box = badgeBox(image);
            line.top = line.ascent = center - box / 2;
            line.bottom = line.descent = center + box / 2;
            return image.getBounds().right;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int badgeBox(Drawable image) {
        android.graphics.Rect b = image.getBounds();
        return Math.max(badgeSize, Math.max(b.width(), b.height()));
    }

    /** Chat font size option: scales Twitch's own text size, remembered per recycled view. */
    private static void applyFontScale(TextView textView) {
        int scale = ChatSettings.getFontScale();
        Object base = textView.getTag(FONT_TAG_KEY);
        if (base == null) {
            if (scale == 100) {
                return;
            }
            base = textView.getTextSize();
            textView.setTag(FONT_TAG_KEY, base);
        }
        float px = (Float) base * scale / 100f;
        if (textView.getTextSize() != px) {
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        }
    }

    /** Animated emotes of a row shown outside the chat play in {@code view}. */
    public static void attachHosts(Spanned spanned, TextView view) {
        attachAnimatedHosts(spanned, view);
    }

    /** {@link #attachAnimatedHosts} result: no layered stack in the text. */
    private static final int HOSTS_PLAIN = 0;
    /** A layered stack is present -- repaint so a late overlay shows up. */
    private static final int HOSTS_LAYERED = 1;
    /** A stack grew (a wider overlay decoded) -- the line must be re-laid out. */
    private static final int HOSTS_GREW = 2;

    /**
     * Wires every animated emote span in {@code spanned} to {@code textView} so
     * its frames invalidate the right view, and re-resolves lazy overlays of
     * any {@link LayeredEmoteDrawable}.
     *
     * @return one of {@link #HOSTS_PLAIN}, {@link #HOSTS_LAYERED},
     *         {@link #HOSTS_GREW}
     */
    private static int attachAnimatedHosts(Spanned spanned, TextView textView) {
        int length = spanned.length();
        if (length == 0) {
            return HOSTS_PLAIN;
        }
        int result = HOSTS_PLAIN;
        ImageSpan[] spans = spanned.getSpans(0, length, ImageSpan.class);
        for (ImageSpan span : spans) {
            Drawable drawable = span.getDrawable();
            if (drawable instanceof AnimatedEmoteDrawable) {
                ((AnimatedEmoteDrawable) drawable).attachHost(textView);
            } else if (drawable instanceof ModifiedEmoteDrawable) {
                ((ModifiedEmoteDrawable) drawable).attachHost(textView);
            } else if (drawable instanceof BlurredDrawable) {
                ((BlurredDrawable) drawable).attachHost(textView);
            } else if (drawable instanceof LayeredEmoteDrawable) {
                boolean grew = ((LayeredEmoteDrawable) drawable).attachHost(textView);
                result = Math.max(result, grew ? HOSTS_GREW : HOSTS_LAYERED);
            }
        }
        return result;
    }

    /**
     * Schedules a (debounced) re-injection pass over every currently-bound
     * chat {@link TextView}. Safe to call from any thread -- the actual pass
     * is always posted to the main thread.
     *
     * <p>This is what makes emotes appear on messages that were already on
     * screen when their set/image was still loading. Called from:
     * <ul>
     *   <li>{@link EmoteCache} once a channel/global emote set finishes
     *       loading -- until then {@link EmoteCache#lookup} returns null and
     *       every word renders as plain text (the dominant reason freshly
     *       loaded chat history shows no third-party emotes);</li>
     *   <li>{@link EmoteImageCache} once an emote's image finishes
     *       downloading -- {@link EmoteImageCache#getIfReady} returns null on
     *       the first pass, so the first render of any message referencing a
     *       not-yet-downloaded emote leaves it as plain text.</li>
     * </ul>
     * Per-row re-injection on bind is handled separately by
     * {@link #onTextBound}'s {@code textView.post(...)} callback.
     */
    public static void requestRefresh() {
        try {
            // Refresh only drives third-party emote re-injection now: RTE
            // badges are injected natively into Twitch's own badge list at
            // message-build time (see ChatCosmetics.injectBadges), so they need
            // no bind-time repaint pass here.
            if (!isAnythingEnabled()) {
                return;
            }
            // Schedule-if-not-already-pending (fixed cadence), NOT remove+reschedule:
            // driven per finished image download, a remove+reschedule debounce would
            // be reset faster than REFRESH_DEBOUNCE_MS during any download burst and
            // so never fire until the burst paused. The flag clears when the task
            // runs, so the next request queues a fresh pass.
            MAIN_HANDLER.post(new Runnable() {
                @Override
                public void run() {
                    if (refreshQueued) {
                        return;
                    }
                    refreshQueued = true;
                    MAIN_HANDLER.postDelayed(REFRESH_TASK, REFRESH_DEBOUNCE_MS);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "requestRefresh failed", t);
            CrashReporter.report(TAG, "requestRefresh failed", t);
        }
    }

    /**
     * Re-runs injection on on-screen text views containing one of {@code bodies}:
     * rows built without the bind hook (reward rows), found from the windows of
     * bound chat rows. Any thread. Never throws.
     * shortcut: rows scrolled off screen keep their old text, fine for the few
     * rows on screen right after joining a channel.
     */
    static void refreshRowsContaining(final Set<String> bodies) {
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Set<View> roots = new java.util.HashSet<>();
                    for (TextView view : new ArrayList<>(BOUND_VIEWS)) {
                        if (view.isAttachedToWindow()) {
                            roots.add(view.getRootView());
                        }
                    }
                    for (View root : roots) {
                        refreshMatching(root, bodies);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "refreshRowsContaining failed", t);
                    CrashReporter.report(TAG, "refreshRowsContaining failed", t);
                }
            }
        });
    }

    private static boolean paintRedrawQueued;

    /**
     * A nick paint arrived: on-screen rows holding a pending {@link NickPaint.Span} draw it now.
     * The width doesn't change, so no relayout. Any thread, batched to one pass per 250 ms.
     */
    static void redrawPaintedRows() {
        MAIN_HANDLER.post(() -> {
            if (paintRedrawQueued) {
                return;
            }
            paintRedrawQueued = true;
            MAIN_HANDLER.postDelayed(() -> {
                paintRedrawQueued = false;
                try {
                    Set<View> roots = new java.util.HashSet<>();
                    for (TextView view : new ArrayList<>(BOUND_VIEWS)) {
                        if (view.isAttachedToWindow()) {
                            roots.add(view.getRootView());
                        }
                    }
                    for (View root : roots) {
                        invalidatePainted(root);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "redrawPaintedRows failed", t);
                }
            }, 250);
        });
    }

    private static void invalidatePainted(View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text instanceof Spanned && ((Spanned) text).getSpans(0, text.length(), NickPaint.Span.class).length > 0) {
                view.invalidate();
            }
        } else if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                invalidatePainted(group.getChildAt(i));
            }
        }
    }

    private static void refreshMatching(View view, Set<String> bodies) {
        if (view instanceof TextView) {
            String text = ((TextView) view).getText().toString();
            for (String body : bodies) {
                if (text.contains(body)) {
                    refreshTextView((TextView) view);
                    return;
                }
            }
        } else if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                refreshMatching(group.getChildAt(i), bodies);
            }
        }
    }

    private static void refreshBoundViews() {
        if (BOUND_VIEWS.isEmpty()) {
            return;
        }
        // Copy first: setText() below can mutate view state, and iterating the
        // weak-backed set directly while it's being trimmed is fragile.
        List<TextView> views = new ArrayList<>(BOUND_VIEWS);
        for (TextView view : views) {
            refreshTextView(view);
        }
    }

    /**
     * Re-runs emote injection against a bound view's <em>current</em> text.
     * Already-applied emote spans are preserved untouched (the word carries a
     * span now, so {@link #maybeApplyEmote} skips it); only words still shown
     * as plain text get a fresh attempt with whatever the caches now hold.
     * Does nothing -- no {@code setText}, no relayout -- unless at least one
     * new emote actually landed, so this converges once everything is loaded.
     *
     * @return whether the view's text was actually repainted with new emotes
     */
    public static boolean refreshTextView(TextView textView) {
        if (textView == null) {
            return false;
        }
        CharSequence current = textView.getText();
        if (!(current instanceof Spanned) || current.length() == 0) {
            return false;
        }
        SpannableStringBuilder builder = buildInjected((Spanned) current, ChannelTracker.getCurrentChannelId(),
                SevenTvPersonal.ownerOfRow(current));
        if (builder == null) {
            // Nothing new to span, but an already-placed stack may have had an
            // overlay image finish downloading since the last pass -- re-attach
            // its host and repaint so the late overlay actually appears. A
            // wider overlay grows the stack, which needs a fresh layout.
            int hosts = attachAnimatedHosts((Spanned) current, textView);
            if (hosts == HOSTS_GREW) {
                textView.setText(current);
            } else if (hosts == HOSTS_LAYERED) {
                textView.invalidate();
            }
            return false;
        }
        SpannedString updated = new SpannedString(builder);
        textView.setText(updated);
        attachAnimatedHosts(updated, textView);
        ensureClickable(updated, textView);
        return true;
    }
}
