package tv.retwitch.mod.emotes;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import tv.retwitch.mod.CrashReporter;
import tv.retwitch.mod.ChatSettings;
import tv.retwitch.mod.Toggles;

/**
 * Adds chat cosmetics -- ReYohoho (RTE) and other clients' badges
 * ({@link ThirdPartyBadges}), RTE nickname paints ({@link #paintName}) -- to Twitch's own chat rows. The Android
 * counterpart of the browser extension's {@code reyohoho_badges} +
 * {@code seventv/cosmetics} modules.
 *
 * <h3>Native badge injection</h3>
 * Rather than draw our own {@code ImageSpan} on top of the rendered row, we do
 * exactly what PurpleTV's {@code injectBadges} does: hand Twitch an extra
 * badge model so its own chat pipeline downloads and lays out the badge, at the
 * native badge size, with Twitch's own image loader. Twitch's chat span builder
 * (obfuscated {@code xt5.b} / {@code createChatMessageSpanGroup}) receives the
 * message's badge list and, for each entry, reads a badge-image URL off it and
 * builds a native badge span (see {@code EmoteChatPatch}). We inject at that
 * method's entry: {@link #injectBadges} gets the badge list + sender model,
 * and, if the sender has an RTE badge, returns a copy of the list with our
 * badge model appended.
 *
 * <p>The badge model class is obfuscated and version-specific, so we never name
 * it: we learn it at runtime from whatever badges Twitch already put in the
 * list (sub/mod/etc.) and reuse its {@code (String name, String badgeUrl)}
 * constructor. Until a message with at least one native badge is seen, RTE
 * badges for users with no native badges can't be built yet; that resolves
 * within seconds on any active channel.
 *
 * <p>Deliberately plain-SDK + reflection only (no Twitch-internal classes):
 * {@link #injectBadges} runs inside Twitch's own chat build with no try/catch on
 * the smali side, so it swallows all throwables and fails soft (the row just
 * renders without our badge).
 */
public final class ChatCosmetics {

    private static final String TAG = "RTECosmetics";

    /** Twitch numeric user ids are all-digits strings. */
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** Cosmetic {@code name} field on our injected badge model (Twitch renders from the URL). */
    private static final String BADGE_NAME = "reyohoho";

    /**
     * Learned once from a native badge Twitch itself put in a chat row: the
     * badge-model class and its {@code (String name, String badgeUrl)}
     * constructor. Volatile single-writer publish so the render thread sees a
     * fully-initialised constructor or none.
     */
    private static volatile Constructor<?> badgeCtor;

    private ChatCosmetics() {
    }

    /**
     * Injected at the entry of Twitch's chat span builder (see
     * {@code EmoteChatPatch}); {@code badges} is the message's badge-model list
     * and {@code sender} its sender model. Returns the list Twitch should
     * render: unchanged when the features are off / the sender has no extra badge /
     * the badge-model class hasn't been learned yet, otherwise a copy with our
     * badge appended. Runs inside Twitch's own code -- must never throw.
     *
     * @return the (possibly augmented) badge list to render
     */
    public static List<?> injectBadges(List<?> badges, Object sender) {
        try {
            RteBadgeStore.ensureStarted();
            String userId = sender == null ? null : extractUserId(sender);
            // The span group built right after this (its sender span) gets this user's nick paint.
            PAINT_USER.set(userId);
            ChatDecor.noteSender(sender);
            if (userId == null) {
                return badges;
            }

            learnBadgeClass(badges);

            List<String> urls = new ArrayList<>(ThirdPartyBadges.urlsForSafe(userId));
            if (ChatSettings.isRteBadgesEnabled()) {
                String rteUrl = RteBadgeStore.getBadgeUrl(userId);
                if (rteUrl != null) {
                    urls.add(0, rteUrl);
                }
                String staffUrl = RteBadgeStore.getStaffBadgeUrl(userId);
                if (staffUrl != null) {
                    urls.add(0, staffUrl);
                }
            }
            if (urls.isEmpty()) {
                return badges;
            }
            Constructor<?> ctor = badgeCtor;
            if (ctor == null) {
                // No native badge seen yet this session -> can't build the
                // model. A later message with a native badge teaches us the
                // class, and Twitch re-renders rows as they rebind.
                Log.v(TAG, "injectBadges: badge model class not learned yet, userId=" + userId);
                return badges;
            }

            List<Object> augmented = new ArrayList<>((badges != null ? badges.size() : 0) + urls.size());
            if (badges != null) {
                augmented.addAll(badges);
            }
            for (String url : urls) {
                augmented.add(ctor.newInstance(BADGE_NAME, url));
            }
            Log.d(TAG, "injectBadges: added " + urls.size() + " badge(s) for userId=" + userId);
            return augmented;
        } catch (Throwable t) {
            Log.w(TAG, "injectBadges failed", t);
            CrashReporter.report(TAG, "injectBadges failed", t);
            return badges;
        }
    }

    /**
     * Learns the obfuscated badge-model class + its {@code (String, String)}
     * constructor from an existing native badge, once. No-op after learned or
     * when the list is empty.
     */
    private static void learnBadgeClass(List<?> badges) {
        if (badgeCtor != null || badges == null || badges.isEmpty()) {
            return;
        }
        for (Object element : badges) {
            if (element == null) {
                continue;
            }
            try {
                Constructor<?> ctor = element.getClass().getDeclaredConstructor(String.class, String.class);
                ctor.setAccessible(true);
                badgeCtor = ctor;
                Log.i(TAG, "learned badge model class: " + element.getClass().getName());
                return;
            } catch (Throwable ignored) {
                // Not the (name, url) shape -- try the next element.
            }
        }
        if (!badgeShapeWarned) {
            badgeShapeWarned = true;
            Object first = badges.get(0);
            Log.w(TAG, "no (String, String) badge model ctor in " + (first != null ? first.getClass().getName() : "null"));
        }
    }

    private static boolean badgeShapeWarned;

    /**
     * From the launch task gate (31.9.0 {@code kei.b}, {@code GlobalBadgesPatch}): its "wait for
     * the end of the launch trace" flag for the task that loads global chat badges (VIP,
     * moderator, broadcaster, Prime...). That end may never come with the RN home, and the
     * badges are loaded nowhere else; false runs the task at once, as on a warm start.
     */
    public static boolean deferLaunchTasks(boolean defer) {
        return defer && !Toggles.on(Toggles.GLOBAL_BADGES_NOW);
    }

    /** Sender id of the span group being built on this thread, from {@link #injectBadges}. */
    private static final ThreadLocal<String> PAINT_USER = new ThreadLocal<>();

    /** The sender id of the span group being built on this thread (its body is built inside it), or null. */
    static String buildingSender() {
        return PAINT_USER.get();
    }

    /**
     * Wraps the sender span handed to Twitch's span group (31.8.0 {@code wa6}
     * constructor, "name: " with the name under a {@link ForegroundColorSpan}):
     * applies the name format ({@link ChatSettings#getNameFormat}) and draws the
     * name of a user with an RTE paint with it. The user id comes from
     * {@link #injectBadges} at the start of the same build. Never throws.
     */
    public static CharSequence paintName(CharSequence senderSpan) {
        String userId = PAINT_USER.get();
        PAINT_USER.remove();
        try {
            int nameFormat = ChatSettings.getNameFormat();
            NickPaint paint = userId != null && ChatSettings.isNickPaintsEnabled()
                    ? RteBadgeStore.getPaint(userId) : null;
            // The RTE list or the 7TV lookup is still on its way: without this the first message stayed plain.
            boolean pending = paint == null && userId != null && ChatSettings.isNickPaintsEnabled()
                    && RteBadgeStore.paintPending(userId);
            if (!(senderSpan instanceof Spanned) || paint == null && !pending && nameFormat == ChatSettings.NAME_BOTH) {
                return senderSpan;
            }
            Spanned spanned = (Spanned) senderSpan;
            ForegroundColorSpan[] colors = spanned.getSpans(0, spanned.length(), ForegroundColorSpan.class);
            if (colors.length == 0) {
                return senderSpan;
            }
            int start = spanned.getSpanStart(colors[0]);
            int end = spanned.getSpanEnd(colors[0]);
            if (start < 0 || end <= start) {
                return senderSpan;
            }
            SpannableStringBuilder out = new SpannableStringBuilder(senderSpan);
            String renamed = reformatName(spanned.subSequence(start, end).toString(), nameFormat);
            if (renamed != null) {
                // Re-laid over the new name: replace() would squeeze the old colour/bold spans.
                for (Object span : out.getSpans(start, end, Object.class)) {
                    if (span == colors[0] || span instanceof StyleSpan
                            && out.getSpanStart(span) >= start && out.getSpanEnd(span) <= end) {
                        out.removeSpan(span);
                    }
                }
                out.replace(start, end, renamed);
                end = start + renamed.length();
                out.setSpan(new ForegroundColorSpan(colors[0].getForegroundColor()), start, end,
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            }
            if (paint != null || pending) {
                out.setSpan(paint != null ? new NickPaint.Span(paint)
                                : NickPaint.Span.pending(userId, colors[0].getForegroundColor()), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return new SpannableString(out);
        } catch (Throwable t) {
            Log.w(TAG, "paintName failed", t);
            CrashReporter.report(TAG, "paintName failed", t);
            return senderSpan;
        }
    }

    /**
     * Twitch shows a non-ASCII display name as "Display (login)" (string
     * {@code displayname_and_username}): keep one of them per {@code format}.
     *
     * @return the new name, or null to keep {@code name}
     */
    static String reformatName(String name, int format) {
        int open = name.lastIndexOf(" (");
        if (format == ChatSettings.NAME_BOTH || open <= 0 || !name.endsWith(")")) {
            return null;
        }
        String login = name.substring(open + 2, name.length() - 1);
        if (login.isEmpty() || !LOGIN.matcher(login).matches()) {
            return null;
        }
        return format == ChatSettings.NAME_LOGIN ? login : name.substring(0, open);
    }

    private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9_]+");

    /**
     * Reads the numeric Twitch user id off a sender-shaped object: a small data
     * class whose string fields are {@code userId}, {@code userName} and
     * {@code displayName} in some order. Picks the all-digits field.
     */
    static String extractUserId(Object sender) {
        if (sender == null) {
            return null;
        }
        for (Field field : idFields(sender.getClass())) {
            Object raw;
            try {
                raw = field.get(sender);
            } catch (Throwable ignored) {
                continue;
            }
            if (raw != null) {
                String value = raw.toString().trim();
                if (!value.isEmpty() && DIGITS.matcher(value).matches()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static final Map<Class<?>, Field[]> ID_FIELDS = new ConcurrentHashMap<>();

    /** The sender class's candidate id fields, in declaration order, made accessible once per class. */
    private static Field[] idFields(Class<?> cls) {
        Field[] fields = ID_FIELDS.get(cls);
        if (fields == null) {
            List<Field> out = new ArrayList<>();
            for (Field field : cls.getDeclaredFields()) {
                // 31.x keeps the id in a Tuid (toString() = numeric id), older builds in a String.
                if (field.getType() != String.class && !field.getType().getName().endsWith(".Tuid")) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    out.add(field);
                } catch (Throwable ignored) {
                }
            }
            fields = out.toArray(new Field[0]);
            ID_FIELDS.put(cls, fields);
        }
        return fields;
    }
}
