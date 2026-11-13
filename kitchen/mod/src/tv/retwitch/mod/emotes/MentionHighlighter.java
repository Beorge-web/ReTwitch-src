package tv.retwitch.mod.emotes;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import tv.retwitch.mod.CrashReporter;
import tv.retwitch.mod.AppContext;
import tv.retwitch.mod.ChannelTracker;
import tv.retwitch.mod.ChatSettings;
import tv.retwitch.mod.FirstMessageHighlight;
import tv.retwitch.mod.SenderBodyMarker;
import tv.retwitch.mod.Toggles;
import tv.retwitch.mod.TwitchAuth;
import tv.retwitch.mod.UiHider;

/**
 * Highlights chat messages that @mention the logged-in user and buzzes the
 * device when a new one arrives -- the ReTwitch counterpart to Twitch's own
 * (server-gated, often unavailable) mention treatment.
 *
 * <h3>Token-based detection (like PurpleTV)</h3>
 * The mention decision is taken from Twitch's own structured message tokens,
 * not by scanning rendered text. The chat body builder (the method that turns
 * one message's token list into its {@code SpannedString}) iterates every
 * token; for a mention token it reads the target login off the token and
 * prepends '@'. Our patch ({@code EmoteChatPatch}) injects into that exact
 * spot:
 * <ul>
 *   <li>{@link #beginMessage()} at the builder's entry resets a per-build
 *       (thread-local) "mentions me" flag;</li>
 *   <li>{@link #noteMention(String)} at the mention branch flips that flag on
 *       when the token's login equals the logged-in user's (exact,
 *       case-insensitive -- no substring/'@' guessing);</li>
 *   <li>{@link #onMessageBody(CharSequence)} at the builder's return consumes
 *       the flag: it buzzes (subject to the gates below) and remembers the
 *       message <em>body</em> so the row bind can paint it.</li>
 * </ul>
 *
 * <h3>Why the highlight is correlated by body text</h3>
 * The builder produces the message <em>body</em> only ("@name ..."), while the
 * row {@code TextView} bound later shows the full row ("sender: @name ..."):
 * different strings, and the tokens aren't available at bind time. So a build
 * that mentions us records its body in a tiny LRU (only our own mentions ever
 * land there, so it stays a handful of entries); {@link #applyHighlight} then
 * flags a row whose text contains one of those bodies. This keeps the
 * highlight decision token-accurate without a second, fragile fingerprint on
 * the message factory / row model.
 *
 * <p>Deliberately plain-SDK only (no Twitch-internal classes): every entry
 * point runs inside Twitch's own code with no try/catch on the smali side, so
 * each swallows all throwables.
 */
public final class MentionHighlighter {

    private static final String TAG = "RTMention";

    /** Don't vibrate for messages processed within this window of a channel open (backlog). */
    private static final long HISTORY_GRACE_MS = 3000L;
    /** Floor between two buzzes, so a burst of mentions can't machine-gun the motor. */
    private static final long MIN_VIBRATE_INTERVAL_MS = 700L;

    private static volatile String cachedUsername;
    private static volatile String cachedUserId;
    private static long lastVibrateAt = 0L;
    /** The row model's {@code ReplyParentInfo} field. */
    private static Field replyField;
    /** The row being bound replies to the user, read once by {@link #applyHighlight}. UI thread. */
    private static boolean rowReply;

    /**
     * Per-build "this message mentions me" flag, set while Twitch's chat body
     * builder walks one message's tokens (see {@link #beginMessage} /
     * {@link #noteMention}) and consumed at the builder's return
     * ({@link #onMessageBody}). Thread-local because, while chat rendering is
     * effectively main-thread, nothing here relies on that -- and a stray
     * build on another thread must never leak its flag into a different one.
     */
    private static final ThreadLocal<Boolean> buildMentionsMe = new ThreadLocal<>();

    /** Small LRU of recently-buzzed message hashes so a rebind/repeat won't re-buzz. */
    private static final Map<Integer, Boolean> recentlyVibrated =
            new LinkedHashMap<Integer, Boolean>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest) {
                    return size() > 128;
                }
            };

    /**
     * LRU of message <em>bodies</em> (as rendered by the builder) that mention
     * the logged-in user. Only our own mentions ever get inserted, so this
     * stays tiny; {@link #applyHighlight} scans it to decide whether a bound
     * row (whose text contains the body) should be washed with
     * {@link ChatSettings#getMentionColor()}.
     */
    private static final Map<String, Boolean> mentionedBodies =
            new LinkedHashMap<String, Boolean>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 128;
                }
            };

    /** Bodies that contain a highlight word ({@link Terms}); same LRU shape as {@link #mentionedBodies}. */
    private static final Map<String, Boolean> wordBodies =
            new LinkedHashMap<String, Boolean>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 128;
                }
            };

    private MentionHighlighter() {
    }

    private static String username() {
        String cached = cachedUsername;
        if (cached == null) {
            cached = TwitchAuth.getUsername().toLowerCase(Locale.US);
            cachedUsername = cached;
            Log.i(TAG, "resolved username='" + cached + "'"
                    + (cached.isEmpty() ? " (EMPTY -> mention detection disabled)" : ""));
        }
        return cached;
    }

    private static boolean isMe(Object userId) {
        String me = cachedUserId;
        if (me == null) {
            me = TwitchAuth.getUserId();
            cachedUserId = me;
        }
        return !me.isEmpty() && me.equals(userId);
    }

    /** Short, single-line preview of a message for logcat (no newlines, capped). */
    private static String snippet(CharSequence text) {
        if (text == null) {
            return "<null>";
        }
        String s = text.toString().replace('\n', ' ');
        return s.length() > 80 ? s.substring(0, 80) + "\u2026" : s;
    }

    // ------------------------------------------------------------------
    // Token-based detection (injected into Twitch's chat body builder)
    // ------------------------------------------------------------------

    /**
     * Injected at the entry of Twitch's chat body builder (once per message):
     * clears the per-build "mentions me" flag before the token loop runs. Runs
     * inside Twitch's own code with no try/catch on the smali side -- must
     * never throw.
     */
    public static void beginMessage() {
        try {
            buildMentionsMe.set(Boolean.FALSE);
        } catch (Throwable t) {
            Log.w(TAG, "beginMessage failed", t);
            CrashReporter.report(TAG, "beginMessage failed", t);
        }
    }

    /**
     * Injected at the mention-token branch of the builder: {@code rawLogin} is
     * the token's target login (no '@'). Flips the per-build flag when it's the
     * logged-in user. Runs inside Twitch's own code -- must never throw.
     */
    public static void noteMention(String rawLogin) {
        try {
            if (rawLogin == null) {
                return;
            }
            String user = username();
            if (user.isEmpty()) {
                return;
            }
            if (rawLogin.trim().equalsIgnoreCase(user)) {
                buildMentionsMe.set(Boolean.TRUE);
                Log.d(TAG, "noteMention: matched '" + rawLogin + "'");
            }
        } catch (Throwable t) {
            Log.w(TAG, "noteMention failed", t);
            CrashReporter.report(TAG, "noteMention failed", t);
        }
    }

    /** Reads and clears the per-build flag. */
    private static boolean takeBuildFlag() {
        Boolean flag = buildMentionsMe.get();
        buildMentionsMe.set(Boolean.FALSE);
        return flag != null && flag;
    }

    /** Whether a bound row's text contains one of the recorded bodies (token-detected mentions, or highlight words). */
    private static boolean isRecorded(Map<String, Boolean> bodies, CharSequence text) {
        if (text == null) {
            return false;
        }
        String haystack = text.toString();
        synchronized (bodies) {
            if (bodies.isEmpty()) {
                return false;
            }
            for (String body : bodies.keySet()) {
                if (!body.isEmpty() && haystack.contains(body)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Replies: Twitch strips a reply's "@parent" from its tokens (31.9.0
    // ac6.f), so noteMention never sees them. Ids, not logins: on that path
    // ReplyParentInfo.parentUserLogin holds the id too.
    // ------------------------------------------------------------------

    /** For each live chat message. Tags, not the row: rebinding a row while scrolling must not alert. Never throws. */
    public static void onChatMessage(Map<?, ?> tags) {
        try {
            if (tags != null && Toggles.on(Toggles.REPLY_ALERTS) && !"1".equals(String.valueOf(tags.get("historical")))
                    && isMe(tags.get("reply-parent-user-id")) && !isMe(tags.get("user-id"))) {
                alert(String.valueOf(tags.get("id")));
            }
        } catch (Throwable t) {
            Log.w(TAG, "onChatMessage failed", t);
            CrashReporter.report(TAG, "onChatMessage failed", t);
        }
    }

    /** At the entry of the message row bind. The row model has the parent, history rows included. Never throws. */
    public static void onRowBind(Object row) {
        rowReply = false;
        tv.retwitch.mod.ReplyThreads.onRowBind(null, null);
        try {
            boolean alerts = ChatSettings.isMentionHighlightEnabled() && Toggles.on(Toggles.REPLY_ALERTS);
            if (row == null || !alerts && !tv.retwitch.mod.ReplyThreads.wanted()) {
                return;
            }
            if (replyField == null) {
                for (Field f : row.getClass().getDeclaredFields()) {
                    if (f.getType().getName().endsWith(".ReplyParentInfo")) {
                        f.setAccessible(true);
                        replyField = f;
                    }
                }
            }
            Object reply = replyField == null ? null : replyField.get(row);
            tv.retwitch.mod.ReplyThreads.onRowBind(row, reply);
            rowReply = alerts && reply != null && isMe(reply.getClass().getMethod("getParentUserID").invoke(reply));
        } catch (Throwable t) {
            Log.w(TAG, "onRowBind failed", t);
            CrashReporter.report(TAG, "onRowBind failed", t);
        }
    }

    // ------------------------------------------------------------------
    // Consumers (hooked from EmoteChatHook)
    // ------------------------------------------------------------------

    /**
     * Per-row-bind: paints {@code textView}'s background with the highlight
     * colour if {@code text} contains a token-detected mention of the user, or
     * clears it to transparent otherwise (so a recycled row never keeps a
     * previous message's highlight). No-op when the feature is off.
     */
    public static void applyHighlight(TextView textView, CharSequence text) {
        try {
            if (textView == null) {
                Log.w(TAG, "applyHighlight: null textView");
                return;
            }
            tv.retwitch.mod.ReplyThreads.bindHeader(textView);
            boolean first = FirstMessageHighlight.isFirst();
            boolean reply = rowReply;
            rowReply = false;
            Terms terms = terms();
            // Leave Twitch's own row background alone unless one of our highlights is on.
            if (!ChatSettings.isMentionHighlightEnabled() && !Toggles.on(Toggles.FIRST_MESSAGE) && terms.isEmpty()) {
                return;
            }
            boolean mentioned = reply || ChatSettings.isMentionHighlightEnabled() && isRecorded(mentionedBodies, text);
            boolean word = !mentioned && !terms.isEmpty() && (isRecorded(wordBodies, text) || terms.byNick(text));
            tintReplyHeader(textView, reply);
            FirstMessageHighlight.apply(textView, first, mentioned ? ChatSettings.getMentionColor()
                    : word ? ChatSettings.getWordsColor() : Color.TRANSPARENT);
        } catch (Throwable t) {
            Log.w(TAG, "applyHighlight failed", t);
            CrashReporter.report(TAG, "applyHighlight failed", t);
        }
    }

    /** The "Replying to" line above the text. Not the row container: the end of Twitch's bind resets its background. */
    private static void tintReplyHeader(TextView text, boolean reply) {
        if (text.getParent() instanceof View) {
            View header = ((View) text.getParent()).findViewById(UiHider.id(text.getContext(), "chat_message_header"));
            if (header != null) {
                header.setBackground(reply ? new ColorDrawable(ChatSettings.getMentionColor()) : null);
            }
        }
    }

    /**
     * Once per message body build: consumes the token-detected "mentions me"
     * flag. If set, records the body for the row bind's highlight and buzzes --
     * subject to the feature toggle, the channel-open grace window (skip
     * backlog), a global rate limit, and a per-message dedupe.
     */
    public static void onMessageBody(CharSequence text) {
        try {
            boolean mentioned = takeBuildFlag();
            if (!mentioned) {
                if (text != null && terms().inBody(text)) {
                    synchronized (wordBodies) {
                        wordBodies.put(text.toString(), Boolean.TRUE);
                    }
                }
                return;
            }
            if (text != null) {
                synchronized (mentionedBodies) {
                    mentionedBodies.put(text.toString(), Boolean.TRUE);
                }
            }
            alert(text);
        } catch (Throwable t) {
            Log.w(TAG, "onMessageBody failed", t);
            CrashReporter.report(TAG, "onMessageBody failed", t);
        }
    }

    /** Vibration and sound, subject to the settings and the gates in {@link #onMessageBody}. {@code text} is the dedupe key. */
    private static void alert(CharSequence text) {
        boolean buzz = ChatSettings.isMentionVibrateEnabled();
        boolean sound = ChatSettings.isMentionSoundEnabled();
        if (!buzz && !sound) {
            return;
        }
        long sinceOpen = ChannelTracker.getMillisSinceChannelOpen();
        if (sinceOpen < HISTORY_GRACE_MS) {
            Log.d(TAG, "alert: within channel-open grace (" + sinceOpen + "ms) -> skip vibrate");
            return; // still loading the channel's backlog -- don't buzz for it
        }
        int key = text == null ? 0 : text.toString().hashCode();
        synchronized (recentlyVibrated) {
            if (recentlyVibrated.containsKey(key)) {
                Log.d(TAG, "alert: already vibrated -> skip");
                return;
            }
            recentlyVibrated.put(key, Boolean.TRUE);
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastVibrateAt < MIN_VIBRATE_INTERVAL_MS) {
            Log.d(TAG, "alert: rate-limited -> skip vibrate");
            return;
        }
        lastVibrateAt = now;
        Log.i(TAG, "alert: snippet=\"" + snippet(text) + "\"");
        if (buzz) {
            vibrate();
        }
        if (sound) {
            sound();
        }
    }

    private static void sound() {
        try {
            Context context = AppContext.get();
            String picked = ChatSettings.getMentionSoundUri();
            android.net.Uri uri = picked.isEmpty()
                    ? android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                    : android.net.Uri.parse(picked);
            android.media.Ringtone tone = context == null ? null : android.media.RingtoneManager.getRingtone(context, uri);
            if (tone == null) {
                return;
            }
            // As media: a notification sound with headphones plugged in also plays on the speaker.
            tone.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            if (Build.VERSION.SDK_INT >= 28) {
                tone.setVolume(Math.max(5, Math.min(100, ChatSettings.getMentionSoundVolume())) / 100f);
            }
            tone.play();
        } catch (Throwable t) {
            Log.w(TAG, "sound failed", t);
        }
    }

    private static void vibrate() {
        try {
            Context context = AppContext.get();
            if (context == null) {
                return;
            }
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) {
                Log.w(TAG, "vibrate: no vibrator available (vibrator="
                        + (vibrator == null ? "null" : "present, hasVibrator=false") + ")");
                return;
            }
            long ms = Math.max(10, Math.min(1000, ChatSettings.getMentionVibrateMs()));
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(ms);
            }
        } catch (Throwable t) {
            // Missing VIBRATE permission or a vendor quirk -- never propagate.
            Log.w(TAG, "vibrate failed", t);
            CrashReporter.report(TAG, "vibrate failed", t);
        }
    }

    private static volatile Terms cachedTerms;

    /** Parsed {@link ChatSettings#getHighlightWords()}, re-parsed only when it changes. */
    private static Terms terms() {
        String raw = ChatSettings.getHighlightWords();
        Terms cached = cachedTerms;
        if (cached == null || !cached.raw.equals(raw)) {
            cached = new Terms(raw);
            cachedTerms = cached;
        }
        return cached;
    }

    /**
     * Highlight terms: comma-separated words (whole word, any case, in the
     * message body) and {@code @nick}s (rows authored by that viewer).
     */
    static final class Terms {
        final String raw;
        final Pattern words;
        final List<String> nicks = new ArrayList<>();

        Terms(String raw) {
            this.raw = raw;
            StringBuilder alternatives = new StringBuilder();
            for (String part : raw.split(",")) {
                String term = part.trim().toLowerCase(Locale.ROOT);
                if (term.startsWith("@")) {
                    if (term.length() > 1) {
                        nicks.add(term.substring(1));
                    }
                } else if (!term.isEmpty()) {
                    alternatives.append(alternatives.length() == 0 ? "" : "|").append(Pattern.quote(term));
                }
            }
            words = alternatives.length() == 0 ? null : Pattern.compile(
                    "(?<![\\p{L}\\p{N}_])(?:" + alternatives + ")(?![\\p{L}\\p{N}_])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }

        boolean isEmpty() {
            return words == null && nicks.isEmpty();
        }

        boolean inBody(CharSequence body) {
            return words != null && words.matcher(body).find();
        }

        boolean byNick(CharSequence row) {
            if (nicks.isEmpty() || row == null) {
                return false;
            }
            String lower = row.toString().toLowerCase(Locale.ROOT);
            for (String nick : nicks) {
                if (SenderBodyMarker.authoredBy(lower, nick)) {
                    return true;
                }
            }
            return false;
        }
    }
}
