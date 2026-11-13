package tv.retwitch.mod;

import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat content switches read from injected hooks. Never throw.
 */
public final class ChatFilters {

    private static final String TAG = "RTChatFilters";
    private static final String NONE = "";

    /**
     * Event class -> its Kotlin data-class name (the {@code toString()} header
     * before '('), e.g. "AnnouncementNotice". Class names are R8-renamed but
     * the toString header is not; caching per class means one toString per
     * event type, not per message.
     */
    private static final ConcurrentHashMap<Class<?>, String> KINDS = new ConcurrentHashMap<>();

    private ChatFilters() {
    }

    /**
     * Injected at the entry of Twitch's chat event dispatcher
     * ({@code ChatEventFilterPatch}): returns {@code null} to drop the event,
     * otherwise the event unchanged.
     */
    public static Object filterEvent(Object event) {
        long t0 = UiStallLog.start();
        Object result = filterEvent0(event);
        UiStallLog.end("ChatEvent", t0);
        return result;
    }

    private static Object filterEvent0(Object event) {
        try {
            if (event == null) {
                return null;
            }
            String kind = kindOf(event);
            if (kind.equals("LiveChatMessage")
                    && (Toggles.on(Toggles.FIRST_MESSAGE) || Toggles.on(Toggles.FFZ_GIANT_EMOTES)
                            || Toggles.on(Toggles.REPLY_ALERTS))) {
                noteMessageTags(event);
            }
            boolean bots = Toggles.on(Toggles.HIDE_BOTS);
            java.util.regex.Pattern words = ChatBlacklist.words();
            java.util.Set<String> users = ChatBlacklist.users();
            if (bots || words != null || !users.isEmpty()) {
                if (kind.equals("LiveChatMessage") && isHidden(event, bots, words, users)) {
                    return null;
                }
                if (kind.equals("ChatHistory")) {
                    removeHiddenMessages(event, bots, words, users);
                }
            }
            if (kind.equals("RaidNotice")) {
                RaidCard.onRealCard();
            }
            if (kind.equals("SystemMessage") && Toggles.on(Toggles.HIDE_SERVICE_MESSAGES) && isConnectionStatus(event)) {
                return null;
            }
            if (Toggles.on(Toggles.HIDE_ANNOUNCEMENTS) && kind.equals("AnnouncementNotice")) {
                return null;
            }
            if (Toggles.on(Toggles.HIDE_SUB_NOTICES) && kind.endsWith("SubscriptionNotice")) {
                return null;
            }
            if (Toggles.on(Toggles.HIDE_REDEMPTIONS) && kind.endsWith("RewardRedemptionNotice")) {
                return null;
            }
            if (Toggles.on(Toggles.HIDE_MILESTONES) && kind.equals("ViewerMilestoneNotice")) {
                return null;
            }
            return event;
        } catch (Throwable t) {
            Log.w(TAG, "filterEvent failed", t);
            CrashReporter.report(TAG, "filterEvent failed", t);
            return event;
        }
    }

    /** Room-mode IRC NOTICE ids: emote-only, followers-only, slow, sub-only, unique chat. */
    private static final java.util.Set<String> MODE_NOTICES = new java.util.HashSet<>(java.util.Arrays.asList(
            "emote_only_on", "emote_only_off", "followers_on", "followers_on_zero", "followers_off",
            "slow_on", "slow_off", "subs_on", "subs_off", "r9k_on", "r9k_off"));

    /**
     * Injected at the entry of Twitch's chat channel event handler
     * ({@code ChatChannelEventsPatch}; 31.8.0 {@code b1.d}, the
     * {@code ChannelMessagesCleared}/{@code ChannelNoticeReceived}/... family,
     * not the message dispatcher above): true drops the event. Timeouts and bans
     * of single users ({@code ChannelUserMessagesCleared}) are left alone.
     * {@code receiver} is the chat controller, kept for {@link ChatNotices}.
     */
    public static boolean dropChannelEvent(Object event, Object receiver) {
        try {
            ChatNotices.noteController(receiver);
            String kind = event != null ? kindOf(event) : NONE;
            if (kind.contains("Cleared") || kind.contains("Deleted")) {
                BanDiag.log("channel event " + event);
            }
            if (kind.equals("ChannelMessagesCleared") && Toggles.on(Toggles.PREVENT_MOD_CLEAR)) {
                ChatNotices.post(Strings.chatClearedNotice());
                return true;
            }
            if (kind.equals("ChannelNoticeReceived") && Toggles.on(Toggles.HIDE_SERVICE_MESSAGES)) {
                for (java.lang.reflect.Field f : fieldsOf(event.getClass())) {
                    Object v = f.get(event);
                    if (v instanceof String) {
                        return MODE_NOTICES.contains(v);
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "dropChannelEvent failed", t);
            CrashReporter.report(TAG, "dropChannelEvent failed", t);
            return false;
        }
    }

    /** "Connecting...", "Welcome to X's chat room!", "Reconnecting...": Twitch's own string ids. */
    private static boolean isConnectionStatus(Object message) throws Exception {
        for (java.lang.reflect.Field f : fieldsOf(message.getClass())) {
            Object v = f.get(message);
            if (v != null && v.getClass().getName().equals("tv.twitch.android.core.strings.Id")) {
                int id = (Integer) v.getClass().getMethod("getStringId").invoke(v);
                String name = AppContext.get().getResources().getResourceEntryName(id);
                return name.startsWith("chat_connect") || name.equals("chat_reconnecting");
            }
        }
        return false;
    }

    /** Pinned chat messages above chat: 0 show, 1 hide, 2 hide after 30 s. */
    static final String KEY_PINNED = "pinned_messages";
    private static final String PINNED = "creator_pinned_message";
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final java.util.Set<String> PIN_TIMERS = new java.util.HashSet<>();

    static int pinnedMode() {
        try {
            android.content.Context c = AppContext.get();
            return c != null ? Toggles.prefs(c).getInt(KEY_PINNED, 0) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    static void setPinnedMode(android.content.Context c, int mode) {
        Toggles.prefs(c).edit().putInt(KEY_PINNED, mode).apply();
    }

    /**
     * Injected at the entries of Twitch's community-highlight presenter (the
     * banners above chat, {@code CommunityHighlightsPatch}): true drops the
     * highlight. Its type id ("hype_train", "prediction", ...) is a String
     * field a few levels inside the event, found reflectively by value.
     */
    public static boolean blockHighlight(Object presenter, Object event) {
        try {
            String id = findHighlightId(event, 0);
            if (id == null) {
                return false;
            }
            boolean block = (id.startsWith("hype_train") && Toggles.on(Toggles.HIDE_HYPE_TRAIN))
                    || ((id.equals("prediction") || id.equals("polls")) && Toggles.on(Toggles.HIDE_PREDICTIONS))
                    || (id.equals("raid") && Toggles.on(Toggles.HIDE_RAID_BANNER))
                    || (id.equals("shoutout") && Toggles.on(Toggles.HIDE_SHOUTOUT))
                    || (id.endsWith("drops") && Toggles.on(Toggles.HIDE_DROPS_BANNER))
                    || ((id.equals("gift_sub") || id.equals("gift_promotion") || id.equals("subtember")
                    || id.equals("spender_rewards")) && Toggles.on(Toggles.HIDE_GIFT_BANNERS))
                    || ((id.equals("shared_chat_started") || id.equals("request_to_join"))
                    && Toggles.on(Toggles.HIDE_SHARED_CHAT))
                    || (id.equals(PINNED) && blockPinned(presenter, event));
            if (block) {
                Log.d(TAG, "blocked highlight " + id);
            }
            return block;
        } catch (Throwable t) {
            Log.w(TAG, "blockHighlight failed", t);
            CrashReporter.report(TAG, "blockHighlight failed", t);
            return false;
        }
    }

    private static boolean blockPinned(Object presenter, Object event) throws IllegalAccessException {
        int mode = pinnedMode();
        if (mode == 2 && presenter != null) {
            Object[] pin = pinOf(event, 0);
            String key = pin != null ? System.identityHashCode(presenter) + "|" + pin[1] : null;
            synchronized (PIN_TIMERS) {
                if (PIN_TIMERS.size() > 200) {
                    PIN_TIMERS.clear();
                }
                if (key == null || !PIN_TIMERS.add(key)) {
                    return false;
                }
            }
            final java.lang.ref.WeakReference<Object> ref = new java.lang.ref.WeakReference<>(presenter);
            MAIN.postDelayed(() -> removeHighlight(ref.get(), pin[0], (String) pin[1]), 30_000);
        }
        return mode == 1;
    }

    /**
     * {type, id} of a pinned-message highlight: the object holding both the
     * highlight type singleton (a String field "creator_pinned_message") and the
     * highlight's own String id (31.8.0 {@code h99(id, type, ...)}). Removal
     * events (type, null id) give null.
     */
    private static Object[] pinOf(Object value, int depth) throws IllegalAccessException {
        if (!isAppObject(value) || depth > 2) {
            return null;
        }
        Object type = null;
        String id = null;
        for (java.lang.reflect.Field f : fieldsOf(value.getClass())) {
            Object v = f.get(value);
            if (v instanceof String) {
                id = id == null ? (String) v : id;
            } else if (isAppObject(v) && hasString(v, PINNED)) {
                type = v;
            }
        }
        if (type != null) {
            return id != null ? new Object[]{type, id} : null;
        }
        for (java.lang.reflect.Field f : fieldsOf(value.getClass())) {
            Object[] pin = pinOf(f.get(value), depth + 1);
            if (pin != null) {
                return pin;
            }
        }
        return null;
    }

    private static boolean isAppObject(Object v) {
        if (v == null) {
            return false;
        }
        String name = v.getClass().getName();
        return !name.startsWith("java.") && !name.startsWith("android.") && !name.startsWith("kotlin.")
                && !v.getClass().isArray();
    }

    private static boolean hasString(Object v, String value) throws IllegalAccessException {
        for (java.lang.reflect.Field f : fieldsOf(v.getClass())) {
            if (value.equals(f.get(v))) {
                return true;
            }
        }
        return false;
    }

    /** Twitch's own "remove highlight" for the pin, through the patch's synthetic bridge. */
    private static void removeHighlight(Object presenter, Object type, String id) {
        try {
            if (presenter != null) {
                presenter.getClass().getMethod("retwitchRemoveHighlight", Object.class, Object.class, String.class)
                        .invoke(null, presenter, type, id);
            }
        } catch (Throwable t) {
            Log.w(TAG, "removeHighlight failed", t);
            CrashReporter.report(TAG, "removeHighlight failed", t);
        }
    }

    private static final java.util.Set<String> HIGHLIGHT_IDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "creator_goal", "creator_pinned_message", "drops", "gift_promotion", "gift_sub", "hype_train",
            "hype_train_all_time_high", "hype_train_approaching", "hype_train_approaching_all_time_high",
            "hype_train_level_up", "polls", "prediction", "raid", "request_to_join", "shared_chat_started",
            "shoutout", "spender_rewards", "subtember", "time_based_drops"));

    private static String findHighlightId(Object value, int depth) throws IllegalAccessException {
        if (value == null || depth > 3 || value instanceof java.util.Map || value instanceof java.util.Collection) {
            return null;
        }
        if (value instanceof String) {
            return HIGHLIGHT_IDS.contains(value) ? (String) value : null;
        }
        for (Class<?> c = value.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().startsWith("java.") || c.getName().startsWith("android.")) {
                return null;
            }
            for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                if (field.getType().isPrimitive() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                String id = findHighlightId(field.get(value), depth + 1);
                if (id != null) {
                    return id;
                }
            }
        }
        return null;
    }

    /** Well-known chat bots; the channel's own BTTV bot list is added on top. */
    private static final java.util.Set<String> DEFAULT_BOTS = new java.util.HashSet<>(java.util.Arrays.asList(
            "nightbot", "streamelements", "moobot", "fossabot", "wizebot", "streamlabs", "sery_bot",
            "botisimo", "deepbot", "coebot", "phantombot", "own3d", "kofistreambot", "soundalerts",
            "pokemoncommunitygame", "blerp", "frostytoolsdotcom", "songlistbot", "creatisbot"));
    private static volatile java.util.Set<String> channelBots = java.util.Collections.emptySet();
    private static final ConcurrentHashMap<Class<?>, java.lang.reflect.Field[]> FIELDS = new ConcurrentHashMap<>();

    /** The current channel's bot logins from BTTV ({@code BttvClient}). */
    public static void setChannelBots(java.util.Collection<String> logins) {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String login : logins) {
            set.add(login.toLowerCase(java.util.Locale.ROOT));
        }
        channelBots = set;
    }

    /** Whether messages from this login are hidden from chat (bots), for views fed outside the chat. */
    static boolean isHiddenSender(String login) {
        return Toggles.on(Toggles.HIDE_BOTS) && isBotLogin(login);
    }

    private static boolean isBotLogin(String value) {
        String v = value.toLowerCase(java.util.Locale.ROOT);
        return DEFAULT_BOTS.contains(v) || channelBots.contains(v);
    }

    /**
     * Whether a chat message is hidden: its sender is a bot or a blacklisted
     * user, or its text has a blacklisted word. Only the sender counts: the
     * message's direct field whose model carries a {@code Tuid} (31.8.0
     * {@code (Tuid, login, displayName)}). Logins elsewhere -- a reply's parent
     * author ({@code ReplyParentInfo}), mentions -- must not hide a normal
     * user's message.
     */
    private static boolean isHidden(Object message, boolean bots, java.util.regex.Pattern words,
            java.util.Set<String> users) throws IllegalAccessException {
        java.util.List<String> names = new java.util.ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (java.lang.reflect.Field f : fieldsOf(message.getClass())) {
            Object v = f.get(message);
            if (v instanceof java.util.List && words != null) {
                appendTokenText((java.util.List<?>) v, text);
            }
            if (v == null || v instanceof CharSequence || v instanceof java.util.Collection
                    || v instanceof java.util.Map || !hasTuidField(v.getClass())) {
                continue;
            }
            for (java.lang.reflect.Field g : fieldsOf(v.getClass())) {
                Object s = g.get(v);
                if (s instanceof String) {
                    if (bots && isBotLogin((String) s)) {
                        return true;
                    }
                    names.add((String) s);
                }
            }
        }
        return ChatBlacklist.hidesSender(names, users) || (words != null && words.matcher(text).find());
    }

    /** The message text: every String of its tokens ("TextToken(", "EmoteToken(", "MentionToken(", ...). */
    private static void appendTokenText(java.util.List<?> tokens, StringBuilder out) throws IllegalAccessException {
        for (Object token : tokens) {
            if (token == null || !kindOf(token).endsWith("Token")) {
                continue;
            }
            for (java.lang.reflect.Field f : fieldsOf(token.getClass())) {
                Object s = f.get(token);
                if (s instanceof String) {
                    out.append(s).append(' ');
                }
            }
        }
    }

    /** Hands the message's IRC tag map and sender model to the tag-driven features. */
    private static void noteMessageTags(Object message) throws IllegalAccessException {
        java.util.Map<?, ?> tags = null;
        Object sender = null;
        for (java.lang.reflect.Field f : fieldsOf(message.getClass())) {
            Object v = f.get(message);
            if (v instanceof java.util.Map) {
                tags = (java.util.Map<?, ?>) v;
            } else if (v != null && !(v instanceof CharSequence) && !(v instanceof java.util.Collection)
                    && hasTuidField(v.getClass())) {
                sender = v;
            }
        }
        FirstMessageHighlight.onChatMessage(tags);
        tv.retwitch.mod.emotes.MentionHighlighter.onChatMessage(tags);
        tv.retwitch.mod.emotes.GiantEmotes.onChatMessage(tags, sender);
    }

    private static boolean hasTuidField(Class<?> type) {
        for (java.lang.reflect.Field f : fieldsOf(type)) {
            if (f.getType().getName().endsWith(".Tuid")) {
                return true;
            }
        }
        return false;
    }

    /** Drops hidden messages from a chat-history batch's list in place. */
    private static void removeHiddenMessages(Object batch, boolean bots, java.util.regex.Pattern words,
            java.util.Set<String> users) throws IllegalAccessException {
        for (java.lang.reflect.Field f : fieldsOf(batch.getClass())) {
            Object v = f.get(batch);
            if (v instanceof java.util.List) {
                java.util.Iterator<?> it = ((java.util.List<?>) v).iterator();
                while (it.hasNext()) {
                    Object message = it.next();
                    if (message != null && isHidden(message, bots, words, users)) {
                        it.remove();
                    }
                }
            }
        }
    }

    /** Non-static, non-primitive fields of a class and its supers, accessible, cached. */
    private static java.lang.reflect.Field[] fieldsOf(Class<?> type) {
        java.lang.reflect.Field[] cached = FIELDS.get(type);
        if (cached != null) {
            return cached;
        }
        java.util.List<java.lang.reflect.Field> out = new java.util.ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (!f.getType().isPrimitive() && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    out.add(f);
                }
            }
        }
        cached = out.toArray(new java.lang.reflect.Field[0]);
        FIELDS.put(type, cached);
        return cached;
    }

    private static String kindOf(Object event) {
        Class<?> type = event.getClass();
        String kind = KINDS.get(type);
        if (kind == null) {
            String text = String.valueOf(event);
            int paren = text.indexOf('(');
            kind = paren > 0 && text.substring(0, paren).matches("[A-Za-z0-9]+") ? text.substring(0, paren) : NONE;
            KINDS.put(type, kind);
        }
        return kind;
    }

    /**
     * Gates every read of Twitch's own {@code "is_gifs_enabled"} preference
     * ({@code NativeGifPatch}): with "hide native GIFs" on, Tier 2+ GIF
     * messages fall back to Twitch's caption text ("[... GIF by ...]")
     * instead of the animation -- the extension's hide-native-GIFs option.
     */
    public static boolean gifsEnabled(boolean original) {
        return original && !Toggles.on(Toggles.HIDE_NATIVE_GIFS);
    }
}
