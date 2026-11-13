package tv.retwitch.mod;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Layout;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tv.retwitch.mod.emotes.ReplayChat;

/**
 * Reply threads ({@link Toggles#REPLY_THREADS}, feedback #34): a tap on a reply's "Replying to ..." line opens the whole
 * thread, its first message and every reply to it, as the web does. That is Twitch's own thread tray, the one behind
 * the message menu's "Reply" (feedback #61: it shows the whole thread; it took a long press and a tap): the row's
 * {@code ReplyToMessageEvent}, built by the patch's {@code retwitchReplyEvent} bridge, goes through the row's
 * EventDispatcher. Without the bridge or the dispatcher, the mod's own window opens. Messages of the open channels are kept as they come over IRC ({@code ModLogs}, with Robotty's backlog when that's on), up to
 * {@link #PER_ROOM} per channel; each carries its thread's first message id ({@code reply-thread-parent-msg-id}, else
 * {@code reply-parent-msg-id}). A first message from before the chat opened still shows, from the reply's own tags.
 * Lines are drawn like the recording player's chat ({@link ReplayChat}): badges, colour, Twitch and third-party emotes.
 */
public final class ReplyThreads {

    private static final String TAG = "RTReplyThreads";
    private static final int PER_ROOM = 5000;
    private static final int HEADER_TAG_KEY = 0x7e700d6b;
    /** {row, reply} of a message TextView whose row is a reply, for {@link #pillAt}. */
    private static final int PILL_TAG_KEY = 0x7e700d6d;

    private static final class Msg {
        final long time;
        final String root;
        final JSONObject line;

        Msg(long time, String root, JSONObject line) {
            this.time = time;
            this.root = root;
            this.line = line;
        }
    }

    /** room id -> message id -> message, oldest first. */
    private static final Map<String, LinkedHashMap<String, Msg>> ROOMS = new HashMap<>();
    /** room ids whose badges were asked for. */
    private static final java.util.Set<String> BADGES = new java.util.HashSet<>();
    /** The reply of the row being bound and its model, between {@code MentionHighlighter.onRowBind} and the text bind. */
    private static Object rowReply;
    private static Object rowModel;
    private static Handler main;

    private ReplyThreads() {
    }

    public static boolean wanted() {
        return Toggles.on(Toggles.REPLY_THREADS);
    }

    /** An IRC PRIVMSG of a joined channel (IRC thread). */
    static void note(Map<?, ?> tags, String content) {
        if (tags == null || content == null) {
            return;
        }
        Object room = tags.get("room-id");
        Object id = tags.get("id");
        if (room == null || id == null) {
            return;
        }
        try {
            Object rootTag = tags.get("reply-thread-parent-msg-id");
            if (rootTag == null || rootTag.toString().isEmpty()) {
                rootTag = tags.get("reply-parent-msg-id");
            }
            String root = rootTag == null || rootTag.toString().isEmpty() ? null : rootTag.toString();
            long time = System.currentTimeMillis();
            try {
                time = Long.parseLong(String.valueOf(tags.get("tmi-sent-ts")));
            } catch (NumberFormatException ignored) {
                // the receive time
            }
            String text = content;
            boolean me = text.startsWith("\u0001ACTION ");
            if (me) {
                text = text.substring(8).replace("\u0001", "");
            }
            String name = str(tags.get("display-name"));
            JSONObject line = new JSONObject().put("n", name).put("u", name.matches("[A-Za-z0-9_]+") ? name.toLowerCase() : "");
            put(line, "i", tags.get("user-id"));
            put(line, "c", tags.get("color"));
            put(line, "b", tags.get("badges"));
            if (me) {
                line.put("m", 1);
            }
            line.put("f", ChatTape.fragments(text, str(tags.get("emotes"))));
            synchronized (ROOMS) {
                LinkedHashMap<String, Msg> msgs = ROOMS.get(room.toString());
                if (msgs == null) {
                    msgs = new LinkedHashMap<String, Msg>(256, 0.75f, false) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, Msg> eldest) {
                            return size() > PER_ROOM;
                        }
                    };
                    ROOMS.put(room.toString(), msgs);
                }
                msgs.put(id.toString(), new Msg(time, root, line));
            }
        } catch (Throwable t) {
            Log.w(TAG, "note failed", t);
        }
    }

    /** At the entry of a message row's bind (via MentionHighlighter.onRowBind): its ReplyParentInfo or null. */
    public static void onRowBind(Object row, Object reply) {
        rowModel = row;
        rowReply = reply;
    }

    /** The row's text was bound: its "Replying to" line opens the thread. Main thread, never throws. */
    public static void bindHeader(TextView text) {
        Object reply = rowReply;
        Object row = rowModel;
        rowReply = null;
        rowModel = null;
        try {
            boolean thread = reply != null && wanted();
            text.setTag(PILL_TAG_KEY, thread ? new Object[]{row, reply} : null);
            if (thread) {
                tv.retwitch.mod.emotes.EmoteChatHook.tapTargets(text);
            }
            if (!(text.getParent() instanceof View)) {
                return;
            }
            View header = ((View) text.getParent()).findViewById(UiHider.id(text.getContext(), "chat_message_header"));
            if (header == null) {
                return;
            }
            if (reply == null || !wanted()) {
                if (header.getTag(HEADER_TAG_KEY) != null) {
                    header.setTag(HEADER_TAG_KEY, null);
                    header.setOnClickListener(null);
                    header.setClickable(false);
                }
                return;
            }
            header.setTag(HEADER_TAG_KEY, Boolean.TRUE);
            header.setOnClickListener(v -> open(v.getContext(), row, reply));
        } catch (Throwable t) {
            Log.w(TAG, "bindHeader failed", t);
        }
    }

    private static void open(Context context, Object row, Object reply) {
        try {
            boolean tray = openTray(context, row);
            Log.i(TAG, "open: tray " + tray);
            if (!tray) {
                show(context, reply);
            }
        } catch (Throwable t) {
            Log.w(TAG, "show failed", t);
            CrashReporter.report(TAG, "show failed", t);
        }
    }

    /**
     * In landscape Twitch shows a reply as a pill inside the message ("Reply to: name", a ReplacementSpan, 31.9.0
     * {@code o3m}) instead of the "Replying to" line, and the pill isn't clickable: a tap there opened the sender's
     * profile (feedback #61). The thread's opener if (x, y) is on the pill of a reply row, else null.
     */
    public static Runnable pillAt(TextView view, float x, float y) {
        Object tag = view.getTag(PILL_TAG_KEY);
        Layout layout = view.getLayout();
        CharSequence text = view.getText();
        if (!(tag instanceof Object[]) || layout == null || !(text instanceof Spanned)) {
            return null;
        }
        Object row = ((Object[]) tag)[0];
        Object reply = ((Object[]) tag)[1];
        String name = get(reply, "getParentDisplayName");
        if (name == null || name.isEmpty()) {
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
        StringBuilder seen = new StringBuilder();
        for (ReplacementSpan span : spanned.getSpans(0, spanned.length(), ReplacementSpan.class)) {
            int start = spanned.getSpanStart(span);
            int end = spanned.getSpanEnd(span);
            seen.append(' ').append(span.getClass().getSimpleName()).append('@').append(start).append('-').append(end);
            if (span.getClass().getName().startsWith("tv.retwitch.") || start < 0 || end <= start
                    || layout.getLineForOffset(start) != line
                    || !spanned.subSequence(start, end).toString().contains(name)) {
                continue;
            }
            float left = layout.getPrimaryHorizontal(start);
            float right = layout.getLineForOffset(end) == line ? layout.getPrimaryHorizontal(end) : layout.getLineRight(line);
            if (px >= left - pad && px <= right + pad) {
                Context context = view.getContext();
                return () -> open(context, row, reply);
            }
        }
        Log.i(TAG, "no pill at " + px + "," + py + " line " + line + " of " + name + ":" + seen);
        return null;
    }

    /** Twitch's thread tray for the row, as its message menu's "Reply" opens it. False if it can't be sent. */
    private static boolean openTray(Context context, Object row) {
        if (row == null) {
            return false;
        }
        try {
            Object event = row.getClass().getMethod("retwitchReplyEvent", Object.class, Context.class)
                    .invoke(null, row, context);
            if (event == null) {
                return false;
            }
            for (Class<?> c = row.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (!f.getType().getName().endsWith(".EventDispatcher")) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object dispatcher = f.get(row);
                    if (dispatcher == null) {
                        continue;
                    }
                    for (java.lang.reflect.Method m : dispatcher.getClass().getMethods()) {
                        // Its one (Object)V method pushes the event to the presenters.
                        if (m.getReturnType() == void.class && m.getParameterTypes().length == 1
                                && m.getParameterTypes()[0] == Object.class && m.getDeclaringClass() != Object.class) {
                            m.invoke(dispatcher, event);
                            return true;
                        }
                    }
                }
            }
            Log.w(TAG, "no dispatcher on " + row.getClass().getName());
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "no reply event bridge");
        } catch (Throwable t) {
            Log.w(TAG, "tray failed", t);
            CrashReporter.report(TAG, "tray failed", t);
        }
        return false;
    }

    private static String get(Object reply, String getter) {
        try {
            Object value = reply.getClass().getMethod(getter).invoke(reply);
            return value == null ? null : value.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** The thread of {@code reply} (Twitch's ReplyParentInfo): its first message, then every reply in time order. */
    private static void show(Context context, Object reply) {
        String parent = get(reply, "getParentMessageID");
        String root = get(reply, "getThreadParentMessageID");
        if (root == null || root.isEmpty()) {
            root = parent;
        }
        if (root == null) {
            return;
        }
        String room = ChannelTracker.getCurrentChannelId();
        Msg first = null;
        List<Msg> replies = new ArrayList<>();
        synchronized (ROOMS) {
            for (Map.Entry<String, LinkedHashMap<String, Msg>> e : ROOMS.entrySet()) {
                Msg m = e.getValue().get(root);
                if (m != null) {
                    first = m;
                    room = e.getKey();
                }
                for (Msg msg : e.getValue().values()) {
                    if (root.equals(msg.root)) {
                        replies.add(msg);
                        room = e.getKey();
                    }
                }
            }
        }
        java.util.Collections.sort(replies, (a, b) -> Long.compare(a.time, b.time));
        loadBadges(room);
        AlertDialog.Builder builder = new AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert);
        Context themed = builder.getContext();
        LinearLayout list = new LinearLayout(themed);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(8 * context.getResources().getDisplayMetrics().density);
        float size = 14f * ChatSettings.getFontScale() / 100f;
        int badgePx = Math.round(18 * ChatSettings.getFontScale() / 100f * context.getResources().getDisplayMetrics().density);
        List<Object[]> rows = new ArrayList<>(); // {TextView, Msg or null}
        if (first == null) {
            // From before the chat opened: the reply's own tags carry who wrote it and, for a direct reply, the text.
            String who = root.equals(parent) ? get(reply, "getParentDisplayName") : get(reply, "getThreadParentDisplayName");
            String body = root.equals(parent) ? get(reply, "getParentMessageBody") : null;
            SpannableStringBuilder b = new SpannableStringBuilder();
            b.append(who == null ? "?" : who);
            b.setSpan(new StyleSpan(Typeface.BOLD), 0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.append(": ");
            int at = b.length();
            b.append(body != null ? body : Strings.pick("(сообщение было до того, как вы открыли чат)",
                    "(written before you opened the chat)"));
            if (body == null) {
                b.setSpan(new ForegroundColorSpan(0xFFADADB8), at, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            rows.add(new Object[]{row(themed, list, pad, size, b), null});
        } else {
            rows.add(new Object[]{row(themed, list, pad, size, null), first});
        }
        TextView count = new TextView(themed);
        count.setPadding(pad * 3, pad, pad * 3, pad / 2);
        count.setTextColor(0xFFADADB8);
        count.setText(Strings.pickf("Ответов: %d", "Replies: %d", replies.size()));
        list.addView(count);
        for (Msg m : replies) {
            rows.add(new Object[]{row(themed, list, pad, size, null), m});
        }
        final String channel = room;
        final boolean[] stillMissing = new boolean[1];
        Runnable draw = () -> {
            boolean missing = false;
            DateFormat format = DateFormat.getTimeInstance(DateFormat.SHORT);
            for (Object[] r : rows) {
                if (r[1] == null) {
                    continue;
                }
                Msg m = (Msg) r[1];
                ReplayChat.Line line = ReplayChat.build(m.line, badgePx);
                missing |= line.missing;
                SpannableStringBuilder out = new SpannableStringBuilder(format.format(new Date(m.time)) + "  ");
                out.setSpan(new ForegroundColorSpan(0xFFADADB8), 0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append(line.text);
                ReplayChat.bind((TextView) r[0], out, channel);
            }
            stillMissing[0] = missing;
        };
        draw.run();
        ScrollView scroll = new ScrollView(themed);
        scroll.addView(list);
        AlertDialog dialog = builder.setTitle(Strings.pick("Ветка ответов", "Reply thread"))
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        // Images still downloading: redraw for a while, as the recording player does.
        if (main == null) {
            main = new Handler(Looper.getMainLooper());
        }
        final int[] left = {10};
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!dialog.isShowing() || !stillMissing[0] || --left[0] < 0) {
                    return;
                }
                draw.run();
                main.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private static TextView row(Context context, LinearLayout list, int pad, float size, CharSequence text) {
        TextView row = new TextView(context);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        row.setTextColor(0xFFEFEFF1);
        row.setPadding(pad * 3, pad / 2, pad * 3, pad / 2);
        if (text != null) {
            row.setText(text);
        }
        list.addView(row);
        return row;
    }

    /** The channel's and global badges for {@link ReplayChat}, once per channel. */
    private static void loadBadges(String room) {
        if (room == null) {
            return;
        }
        synchronized (BADGES) {
            if (!BADGES.add(room)) {
                return;
            }
        }
        new Thread(() -> {
            try {
                ReplayChat.setBadges(VodDownload.gql("query($s:ID!){badges{setID version imageURL(size:DOUBLE)} "
                        + "user(id:$s){broadcastBadges{setID version imageURL(size:DOUBLE)}}}", room));
            } catch (Throwable t) {
                Log.w(TAG, "badges failed: " + t);
                synchronized (BADGES) {
                    BADGES.remove(room);
                }
            }
        }, "RTReplyThreads").start();
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private static void put(JSONObject line, String key, Object value) throws org.json.JSONException {
        if (value != null && !value.toString().isEmpty()) {
            line.put(key, value.toString());
        }
    }
}
