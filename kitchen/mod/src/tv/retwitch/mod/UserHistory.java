package tv.retwitch.mod;

import android.app.AlertDialog;
import android.content.Context;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A viewer's messages in this chat, on their viewer card ({@code USER_HISTORY}). Twitch shows mods the
 * server's log on the card already; this is for everyone and covers what the chat received since it was
 * opened: every IRC PRIVMSG of the channel ({@link ModLogs#onIrcEvent}), kept in memory per channel and
 * sender (display name, as the card shows it). The card's row sits right after the tapped message.
 */
public final class UserHistory {

    private static final String TAG = "RTUserHistory";
    private static final int ROW_TAG_KEY = 0x7e700d61;
    private static final int PER_USER = 100;
    private static final int USERS = 3000;
    private static final int RENDERED = 3000;

    private static final class Line {
        final long time;
        final String text;

        /** The chat row as shown (badges, nick, emotes); null until the row is bound. */
        CharSequence rendered;

        Line(long time, String text) {
            this.time = time;
            this.text = text;
        }
    }

    /** IRC message id -> line. Rendered rows hold spans, so only the newest keep theirs. */
    private static final Map<String, Line> BY_ID = new LinkedHashMap<String, Line>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Line> eldest) {
            if (size() <= RENDERED) {
                return false;
            }
            eldest.getValue().rendered = null;
            return true;
        }
    };
    private static List<Field> rowFields;
    /** The line of the row being bound, between {@link #onRowBind} and {@link #takeBound}. */
    private static Line boundLine;

    /** room id -> lowercased display name -> lines, oldest first. */
    private static final Map<String, Map<String, ArrayDeque<Line>>> ROOMS = new HashMap<>();

    private UserHistory() {
    }

    /** An IRC PRIVMSG (IRC thread). */
    static void note(Map<?, ?> tags, String content) {
        if (tags == null || content == null) {
            return;
        }
        Object room = tags.get("room-id");
        Object name = tags.get("display-name");
        if (room == null || name == null || name.toString().isEmpty()) {
            return;
        }
        long time = System.currentTimeMillis();
        try {
            Object sent = tags.get("tmi-sent-ts");
            if (sent != null) {
                time = Long.parseLong(sent.toString());
            }
        } catch (NumberFormatException ignored) {
            // keep the receive time
        }
        // /me arrives as a CTCP ACTION.
        String text = content.startsWith("\u0001ACTION ") ? content.substring(8).replace("\u0001", "") : content;
        synchronized (ROOMS) {
            Map<String, ArrayDeque<Line>> users = ROOMS.get(room.toString());
            if (users == null) {
                users = new LinkedHashMap<String, ArrayDeque<Line>>(64, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, ArrayDeque<Line>> eldest) {
                        return size() > USERS;
                    }
                };
                ROOMS.put(room.toString(), users);
            }
            String key = name.toString().toLowerCase(Locale.ROOT);
            ArrayDeque<Line> lines = users.get(key);
            if (lines == null) {
                lines = new ArrayDeque<>();
                users.put(key, lines);
            }
            Line line = new Line(time, text);
            if (!lines.isEmpty() && time < lines.peekLast().time) {
                // An older line (RecentMessages): keep the user's lines in time order.
                List<Line> all = new ArrayList<>(lines);
                all.add(line);
                java.util.Collections.sort(all, (a, b) -> Long.compare(a.time, b.time));
                lines.clear();
                lines.addAll(all);
            } else {
                lines.addLast(line);
            }
            while (lines.size() > PER_USER) {
                lines.removeFirst();
            }
            Object id = tags.get("id");
            if (id != null) {
                BY_ID.put(id.toString(), line);
            }
        }
    }

    /** A line from {@link RecentMessages} ({tags, content}): false when it's not a message or already known. */
    static boolean noteHistorical(Object[] parsed) {
        if (parsed == null) {
            return false;
        }
        Map<?, ?> tags = (Map<?, ?>) parsed[0];
        Object id = tags.get("id");
        synchronized (ROOMS) {
            if (id == null || BY_ID.containsKey(id.toString())) {
                return false;
            }
            note(tags, (String) parsed[1]);
            return true;
        }
    }

    /** The chat row model about to be bound (main thread): its String fields hold the message id. Never throws. */
    public static void onRowBind(Object row) {
        boundLine = null;
        try {
            if (row == null || !Toggles.on(Toggles.USER_HISTORY)) {
                return;
            }
            if (rowFields == null) {
                List<Field> fields = new ArrayList<>();
                for (Field f : row.getClass().getDeclaredFields()) {
                    if (f.getType() == String.class) {
                        f.setAccessible(true);
                        fields.add(f);
                    }
                }
                rowFields = fields;
            }
            synchronized (ROOMS) {
                for (Field f : rowFields) {
                    Line line = BY_ID.get(f.get(row));
                    if (line != null) {
                        boundLine = line;
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "onRowBind failed", t);
        }
    }

    /** The bound row's line, read once by the text bind that follows. */
    public static Object takeBound() {
        Line line = boundLine;
        boundLine = null;
        return line;
    }

    /** The row's text is final (emotes injected): keeps it for the history list. */
    public static void rendered(Object line, TextView row) {
        if (line instanceof Line) {
            ((Line) line).rendered = new SpannableString(row.getText());
        }
    }

    private static List<Line> lines(String shownName) {
        String channel = ChannelTracker.getCurrentChannelId();
        if (channel == null || shownName == null) {
            return new ArrayList<>();
        }
        String name = shownName.trim().toLowerCase(Locale.ROOT);
        int login = name.indexOf(" (");
        synchronized (ROOMS) {
            Map<String, ArrayDeque<Line>> users = ROOMS.get(channel);
            ArrayDeque<Line> lines = users == null ? null : users.get(name);
            if (lines == null && users != null && login > 0) {
                lines = users.get(name.substring(0, login)); // "Display (login)" of non-ASCII names
            }
            return lines == null ? new ArrayList<>() : new ArrayList<>(lines);
        }
    }

    private static final int CARD_TAG_KEY = 0x7e700d63;

    /**
     * Injected after Twitch inflates the viewer card (its own dialog window, outside the activity's UI pass):
     * the card fills in asynchronously, so its rows are refreshed on each layout of the card. Never throws.
     */
    public static void onViewerCard(final View root) {
        try {
            if (root == null || root.getTag(CARD_TAG_KEY) != null) {
                return;
            }
            root.setTag(CARD_TAG_KEY, Boolean.TRUE);
            Log.i(TAG, "viewer card inflated; history " + Toggles.on(Toggles.USER_HISTORY));
            root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                try {
                    if (Toggles.on(Toggles.USER_HISTORY)) {
                        List<View> messages = UiHider.findAll(root.getContext(), root, "viewer_card_chat_message");
                        if (!messages.isEmpty()) {
                            attach(root.getContext(), messages.get(0));
                        }
                    }
                    tv.retwitch.mod.emotes.CardCosmetics.apply(root.getContext(), root);
                } catch (Throwable t) {
                    Log.w(TAG, "card layout failed", t);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "onViewerCard failed", t);
        }
    }

    /** Adds or refreshes the history row after the card's tapped-message block. */
    static void attach(Context context, View message) {
        try {
            ViewParent parent = message.getParent();
            if (!(parent instanceof LinearLayout)) {
                return;
            }
            LinearLayout list = (LinearLayout) parent;
            List<View> names = UiHider.findAll(context, list.getRootView(), "target_username");
            if (names.isEmpty() || !(names.get(0) instanceof TextView)) {
                return;
            }
            final String name = ((TextView) names.get(0)).getText().toString();
            TextView row = null;
            for (int i = 0; i < list.getChildCount(); i++) {
                if (list.getChildAt(i).getTag(ROW_TAG_KEY) != null) {
                    row = (TextView) list.getChildAt(i);
                }
            }
            if (row == null) {
                row = new TextView(context);
                row.setTag(ROW_TAG_KEY, Boolean.TRUE);
                row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                // The card's own text colour: the dialog's theme background doesn't match the sheet.
                row.setTextColor(((TextView) names.get(0)).getCurrentTextColor());
                int pad = Math.round(16 * context.getResources().getDisplayMetrics().density);
                row.setPadding(pad, pad / 2, pad, pad / 2);
                TypedValue ripple = new TypedValue();
                if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                    row.setBackgroundResource(ripple.resourceId);
                }
                list.addView(row, list.indexOfChild(message) + 1, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            int count = lines(name).size();
            String label = Strings.userHistoryRow(count);
            if (!label.contentEquals(row.getText())) {
                row.setText(label);
            }
            row.setOnClickListener(v -> show(v.getContext(), name));
            RecentMessages.load(ChannelTracker.getCurrentChannelLogin(), () -> attach(context, message));
        } catch (Throwable t) {
            Log.w(TAG, "attach failed", t);
        }
    }

    /** The viewer's lines as the chat shows them, newest at the bottom. */
    private static void show(Context context, String name) {
        List<Line> lines = lines(name);
        // Theme.Material is dark whatever the system is; the card itself is dark.
        AlertDialog.Builder builder = new AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert);
        Context themed = builder.getContext();
        LinearLayout list = new LinearLayout(themed);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(8 * context.getResources().getDisplayMetrics().density);
        DateFormat format = DateFormat.getTimeInstance(DateFormat.SHORT);
        float size = 14f * ChatSettings.getFontScale() / 100f;
        for (Line line : lines) {
            CharSequence text = line.rendered != null ? line.rendered : line.text;
            SpannableStringBuilder out = new SpannableStringBuilder();
            if (text.length() == 0 || !Character.isDigit(text.charAt(0))) { // else the chat's own timestamp is there
                out.append(format.format(new Date(line.time))).append("  ");
                out.setSpan(new ForegroundColorSpan(0xFFADADB8), 0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            out.append(text);
            TextView row = new TextView(themed);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
            row.setTextColor(0xFFEFEFF1);
            row.setPadding(pad * 3, pad / 2, pad * 3, pad / 2);
            row.setText(out);
            tv.retwitch.mod.emotes.EmoteChatHook.attachHosts(out, row);
            list.addView(row);
        }
        if (lines.isEmpty()) {
            TextView none = new TextView(themed);
            none.setPadding(pad * 3, pad, pad * 3, 0);
            none.setText(Strings.userHistoryEmpty());
            list.addView(none);
        }
        final android.widget.ScrollView scroll = new android.widget.ScrollView(themed);
        scroll.addView(list);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        builder.setTitle(name)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
