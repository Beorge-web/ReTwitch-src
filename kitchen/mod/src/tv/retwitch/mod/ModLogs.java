package tv.retwitch.mod;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mod logs: a chat line for each moderator action, with who did it. Twitch
 * already receives the actions (the moderator-actions PubSub feed, model
 * {@code ModerationActionPubSubEvent$ModeratorAction$Data}, names not
 * obfuscated) but never says who acted. The feed only reaches moderators and
 * the broadcaster. Everyone else gets the IRC side: CLEARCHAT / CLEARMSG from
 * Twitch's IRC event parser ({@code IrcEventPatch}) give "X was timed out /
 * banned / a message from X was deleted" without the moderator. Never throws.
 */
public final class ModLogs {

    private static final String TAG = "RTModLogs";
    private static boolean loggedNoFields;
    /** The moderator-actions feed reached us this session (we are a moderator somewhere). */
    private static volatile boolean modFeedSeen;
    /** IRC command names already logged once (a few dozen at most). */
    private static final java.util.Set<String> SEEN_COMMANDS = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** One action reaches several of Twitch's listeners: each is shown once. */
    private static final Map<String, Boolean> SEEN = new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 64;
        }
    };

    /** action|login -> when its line was shown: IRC and PubSub report the same action, it is shown once. */
    private static final Map<String, Long> SHOWN = new LinkedHashMap<String, Long>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 64;
        }
    };
    private static final long SAME_ACTION_MS = 5000;
    /** With mod logs on the IRC line waits this long for the PubSub line, which names the moderator. */
    private static final long MOD_LOG_WAIT_MS = 2000;

    private static volatile Class<?> ircClass;
    /** {tags, command, content} of {@link #ircClass}; null if it is not the IRC event shape. */
    private static volatile Field[] ircFields;

    private ModLogs() {
    }

    /** Injected before {@code ModeratorAction.getData()} returns ({@code ChatChannelEventsPatch}). */
    public static void onAction(Object data) {
        try {
            if (data == null || !Toggles.on(Toggles.MOD_LOGS)) {
                return;
            }
            modFeedSeen = true;
            String action = get(data, "getAction");
            List<String> args = new ArrayList<>();
            Object rawArgs = data.getClass().getMethod("getArgs").invoke(data);
            if (rawArgs instanceof List) {
                for (Object a : (List<?>) rawArgs) {
                    args.add(a != null ? a.toString() : "");
                }
            }
            String key = action + "|" + get(data, "getCreatedAt") + "|" + get(data, "getModeratorId") + "|"
                    + get(data, "getTargetUserId") + "|" + args;
            Log.i(TAG, "pubsub " + key);
            BanDiag.watch(args.isEmpty() ? null : args.get(0), "pubsub " + key);
            synchronized (SEEN) {
                if (SEEN.put(key, Boolean.TRUE) != null) {
                    return;
                }
            }
            String target = get(data, "getTargetUserLogin");
            if (target.isEmpty() && !args.isEmpty()) {
                target = args.get(0);
            }
            String text = describe(action, get(data, "getModeratorName"), target, args);
            if (text != null && claim(action, target, SystemClock.elapsedRealtime())) {
                Log.i(TAG, "pubsub posting \"" + text + "\"");
                ChatNotices.post(text);
            } else {
                Log.i(TAG, "pubsub " + action + ": " + (text == null ? "no line for this action" : "already shown (dedup)"));
            }
        } catch (Throwable t) {
            Log.w(TAG, "onAction failed", t);
            CrashReporter.report(TAG, "onAction failed", t);
        }
    }

    /**
     * Injected at the entry of Twitch's IRC event parser ({@code IrcEventPatch}, 31.8.0
     * {@code gna.L(IrcEventComponents, ...)}): every IRC event of a joined channel, every user's.
     */
    public static void onIrcEvent(Object event) {
        try {
            // Runs for every IRC line: stay free while both switches are off.
            boolean history = Toggles.on(Toggles.USER_HISTORY);
            boolean senders = tv.retwitch.mod.emotes.CardCosmetics.wanted();
            boolean danmaku = Danmaku.active();
            boolean counting = NewMessages.counting();
            boolean modActions = ChatModActions.wanted();
            boolean backlog = RecentMessages.wanted();
            boolean threads = ReplyThreads.wanted();
            if (event == null || (!Toggles.on(Toggles.MOD_LOGS) && !BanDiag.enabled() && !history && !senders && !danmaku
                    && !counting && !modActions && !backlog && !threads)) {
                return;
            }
            Class<?> c = event.getClass();
            if (c != ircClass) {
                ircFields = ircFields(c);
                ircClass = c;
            }
            Field[] f = ircFields;
            if (f == null) {
                if (!loggedNoFields) {
                    loggedNoFields = true;
                    Log.w(TAG, "IRC fields not found in " + c + ": " + event);
                }
                return;
            }
            Object command = f[1].get(event);
            if (!(command instanceof Enum)) {
                if (SEEN_COMMANDS.add(String.valueOf(command))) {
                    Log.w(TAG, "irc command is not an enum: " + command + " in " + event);
                }
                return;
            }
            String name = ((Enum<?>) command).name();
            if (SEEN_COMMANDS.add(name)) {
                Log.i(TAG, "irc hook alive, first " + name);
            }
            if ("PRIVMSG".equals(name)) {
                Map<?, ?> tags = (Map<?, ?>) f[0].get(event);
                if (senders && tags != null) {
                    tv.retwitch.mod.emotes.CardCosmetics.noteUser(tags.get("display-name"), tags.get("user-id"));
                }
                if (history) {
                    UserHistory.note(tags, (String) f[2].get(event));
                }
                boolean old = RecentMessages.historical(tags); // backlog injected by RecentMessages
                if (backlog && !old) {
                    RecentMessages.onLivePrivmsg(tags);
                }
                if (danmaku && !old) {
                    Danmaku.onMessage(tags, (String) f[2].get(event));
                }
                if (counting && !old) {
                    NewMessages.onPrivmsg(tags);
                }
                if (modActions) {
                    ChatModActions.onPrivmsg(tags);
                }
                if (threads) {
                    ReplyThreads.note(tags, (String) f[2].get(event));
                }
                return;
            }
            if ("USERSTATE".equals(name) || "ROOMSTATE".equals(name)) {
                if (backlog && "ROOMSTATE".equals(name)) {
                    RecentMessages.onRoomState((Map<?, ?>) f[0].get(event));
                }
                if (modActions) {
                    Map<?, ?> tags = (Map<?, ?>) f[0].get(event);
                    String channel = ircChannel(event);
                    if ("USERSTATE".equals(name)) {
                        ChatModActions.onUserState(tags, channel);
                    } else {
                        ChatModActions.onRoomState(tags, channel);
                    }
                }
                return;
            }
            if (!"CLEARCHAT".equals(name) && !"CLEARMSG".equals(name)) {
                return;
            }
            Map<?, ?> tags = (Map<?, ?>) f[0].get(event);
            String content = (String) f[2].get(event);
            Log.i(TAG, "irc " + name + " tags=" + tags + " content=" + content);
            BanDiag.watch("CLEARCHAT".equals(name) ? content : (String) tags.get("login"),
                    "irc " + name + " tags=" + tags + " content=" + content);
            if (!Toggles.on(Toggles.MOD_LOGS)) {
                return;
            }
            final String[] line = describeIrc(name, tags, content);
            if (line == null) {
                Log.i(TAG, "irc " + name + ": no line (general clear or no target)");
                return;
            }
            Object room = tags.get("room-id");
            String channel = ChannelTracker.getCurrentChannelId();
            if (room != null && channel != null && !channel.equals(room)) {
                Log.i(TAG, "irc " + line[0] + ": other room " + room + ", current " + channel);
                return;
            }
            Runnable show = () -> {
                if (claim(line[0], line[1], SystemClock.elapsedRealtime())) {
                    Log.i(TAG, "irc " + line[0] + ": posting \"" + line[2] + "\"");
                    ChatNotices.post(line[2]);
                } else {
                    Log.i(TAG, "irc " + line[0] + " " + line[1] + ": already shown (dedup)");
                }
            };
            // A moderator's PubSub line names who acted: give it the chance to come first.
            if (modFeedSeen) {
                new Handler(Looper.getMainLooper()).postDelayed(show, MOD_LOG_WAIT_MS);
            } else {
                show.run();
            }
        } catch (Throwable t) {
            Log.w(TAG, "onIrcEvent failed", t);
            CrashReporter.report(TAG, "onIrcEvent failed", t);
        }
    }

    /**
     * {tags, command, content} of an {@code IrcEventComponents(messageTags, prefix, command, channel,
     * ctcpEvent, content)} class by field types, or null. Content is the last String: R8 names fields
     * in declaration order and ART lists them in dex (name) order.
     */
    /** The event's channel ("#login") from its toString: it has three String fields (prefix, channel, content). */
    private static String ircChannel(Object event) {
        String text = String.valueOf(event);
        int at = text.indexOf(", channel=");
        if (at < 0) {
            return null;
        }
        int start = at + ", channel=".length();
        int end = text.indexOf(',', start);
        return end < 0 ? null : text.substring(start, end).trim();
    }

    static Field[] ircFields(Class<?> c) {
        Field tags = null;
        Field command = null;
        Field content = null;
        for (Field f : c.getDeclaredFields()) {
            Class<?> t = f.getType();
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (Map.class.isAssignableFrom(t)) {
                tags = f;
            } else if (t == String.class) {
                content = f;
            } else if (command == null && t.isEnum() && hasConstant(t, "CLEARCHAT")) {
                // The first such enum: ctcpEvent (31.8.0 g8c) declares CLEARCHAT too, after command.
                command = f;
            }
        }
        if (tags == null || command == null || content == null) {
            Log.w(TAG, "not an IRC event: " + c);
            return null;
        }
        Field[] fields = {tags, command, content};
        for (Field f : fields) {
            f.setAccessible(true);
        }
        return fields;
    }

    private static boolean hasConstant(Class<?> enumType, String name) {
        for (Object e : enumType.getEnumConstants()) {
            if (((Enum<?>) e).name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {action, login, line} for a CLEARCHAT on one user (content = login, {@code ban-duration} only on
     * a timeout) or a CLEARMSG; null for anything else, including the whole-chat /clear.
     */
    static String[] describeIrc(String command, Map<?, ?> tags, String content) {
        if ("CLEARCHAT".equals(command)) {
            Object target = tags.get("target-user-id");
            // Twitch's own parser treats an empty or "0" target as the whole-chat /clear.
            if (target == null || "".equals(target) || "0".equals(target) || content == null || content.isEmpty()) {
                return null;
            }
            Object seconds = tags.get("ban-duration");
            if (seconds == null) {
                return new String[]{"ban", content, "⚖ " + Strings.pickf("%s забанен навсегда",
                        "%s was permanently banned", content)};
            }
            String d = duration(seconds.toString());
            return new String[]{"timeout", content, "⚖ " + Strings.pickf("%s отстранён на %s",
                    "%s was timed out for %s", content, d)};
        }
        if ("CLEARMSG".equals(command)) {
            Object login = tags.get("login");
            if (login == null || "".equals(login) || tags.get("target-msg-id") == null) {
                return null;
            }
            return new String[]{"delete", login.toString(), "⚖ " + Strings.pickf("Сообщение %s удалено",
                    "A message from %s was deleted", login)};
        }
        return null;
    }

    /** True if no line for {@code action} on {@code login} was shown in the last {@link #SAME_ACTION_MS}. */
    static boolean claim(String action, String login, long now) {
        String key = action + "|" + login.toLowerCase(Locale.ROOT);
        synchronized (SHOWN) {
            Long at = SHOWN.get(key);
            if (at != null && now - at < SAME_ACTION_MS) {
                return false;
            }
            SHOWN.put(key, now);
            return true;
        }
    }

    private static String get(Object data, String getter) throws Exception {
        Object v = data.getClass().getMethod(getter).invoke(data);
        return v != null ? v.toString() : "";
    }

    /** The line for one action, or null for actions not shown. Args as Twitch sends them (target first). */
    static String describe(String action, String mod, String target, List<String> args) {
        String who = mod == null || mod.isEmpty() ? Strings.pick("Модератор", "A moderator") : mod;
        String arg1 = args.size() > 1 ? args.get(1) : "";
        String arg2 = args.size() > 2 ? args.get(2) : "";
        String text;
        switch (action == null ? "" : action) {
            case "timeout":
                text = Strings.pickf("%s отстранил %s на %s%s",
                        "%s timed out %s for %s%s", who, target, duration(arg1), reason(arg2));
                break;
            case "ban":
                text = Strings.pickf("%s забанил %s%s",
                        "%s banned %s%s", who, target, reason(arg1));
                break;
            case "unban":
                text = Strings.pickf("%s разбанил %s", "%s unbanned %s", who, target);
                break;
            case "untimeout":
                text = Strings.pickf("%s снял отстранение с %s", "%s removed the timeout on %s", who, target);
                break;
            case "delete":
                text = Strings.pickf("%s удалил сообщение %s", "%s deleted a message from %s", who, target)
                        + (arg1.trim().isEmpty() ? "" : ": \"" + arg1 + "\"");
                break;
            default:
                return null;
        }
        return "⚖ " + text;
    }

    private static String reason(String reason) {
        return reason == null || reason.trim().isEmpty() ? "" : Strings.pick(", причина: ", ", reason: ") + reason;
    }

    /** "600" -> "10 мин" / "10 min"; unparsable stays as sent. */
    static String duration(String seconds) {
        long s;
        try {
            s = Long.parseLong(seconds.trim());
        } catch (RuntimeException e) {
            return seconds;
        }
        if (s >= 86400 && s % 86400 == 0) {
            return s / 86400 + Strings.pick(" д", " d");
        }
        if (s >= 3600 && s % 3600 == 0) {
            return s / 3600 + Strings.pick(" ч", " h");
        }
        if (s >= 60 && s % 60 == 0) {
            return s / 60 + Strings.pick(" мин", " min");
        }
        return s + Strings.pick(" с", " s");
    }
}
