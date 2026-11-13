package tv.retwitch.mod;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Delete, timeout, ban and unban from the chat message long-press menu for the channel's moderators ({@code MOD_ACTIONS},
 * from reFrosty). Twitch's own menu can't delete one's own message or another moderator's. One row, "Moderation", is
 * added next to Copy (the same hooks as {@link ChatCopySend}) and opens a dialog of the actions: the menu's width is
 * set by Twitch's rows and it drops down from the message, so four rows got cut off and ellipsized; the message is the menu's row model (31.9.0 {@code vwp},
 * read from its {@code "MessageRecyclerItem(messageId=..., username=..., displayName=..."}). Moderator: the
 * channel's IRC USERSTATE ({@code mod=1}, or the broadcaster / lead moderator badge). The actions are GQL
 * mutations with the app's token, like AutoMod's buttons; Twitch itself decides what is allowed. Unban is offered
 * on a message Twitch marked deleted: that is what a ban or timeout does to the user's rows.
 */
public final class ChatModActions {

    private static final String TAG = "RTModActions";
    private static final int ROW_TAG_KEY = 0x7e700e02;
    private static final String CLIENT_ID = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build();
    private static final Pattern FIELD = Pattern.compile("(messageId|username|displayName|hasBeenDeleted)=([^,)]*)");

    private static final int DELETE = 0;
    private static final int TIMEOUT = 1;
    private static final int BAN = 2;
    private static final int UNBAN = 3;
    /** Seconds, as in reFrosty. */
    private static final int[] TIMEOUTS = {60, 600, 3600, 86400, 604800};

    /** Channel logins whose USERSTATE says we moderate. */
    private static final Set<String> MOD_CHANNELS = ConcurrentHashMap.newKeySet();
    /** room-id -> channel login, from ROOMSTATE. */
    private static final Map<String, String> ROOMS = new ConcurrentHashMap<>();
    /** message id -> room-id, from PRIVMSG: the menu's message may be any joined channel's. */
    private static final Map<String, String> MESSAGE_ROOMS = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, String>(256, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 3000;
                }
            });
    private static volatile Object message;

    private ChatModActions() {
    }

    static boolean wanted() {
        return Toggles.on(Toggles.MOD_ACTIONS);
    }

    /** From the IRC hook: our USERSTATE in {@code channel} ("#login"). */
    static void onUserState(Map<?, ?> tags, String channel) {
        String login = login(channel);
        if (tags == null || login == null) {
            return;
        }
        Object badges = tags.get("badges");
        String b = badges == null ? "" : badges.toString();
        boolean mod = "1".equals(tags.get("mod")) || b.contains("broadcaster/") || b.contains("lead_moderator/");
        if (mod) {
            MOD_CHANNELS.add(login);
        } else {
            MOD_CHANNELS.remove(login);
        }
        Log.i(TAG, "USERSTATE " + login + ": moderator " + mod);
    }

    /** From the IRC hook: the channel's room id. */
    static void onRoomState(Map<?, ?> tags, String channel) {
        String login = login(channel);
        Object room = tags == null ? null : tags.get("room-id");
        if (login != null && room != null) {
            ROOMS.put(room.toString(), login);
        }
    }

    /** From the IRC hook: which channel a message came in. */
    static void onPrivmsg(Map<?, ?> tags) {
        Object id = tags == null ? null : tags.get("id");
        Object room = tags == null ? null : tags.get("room-id");
        if (id != null && room != null) {
            MESSAGE_ROOMS.put(id.toString(), room.toString());
        }
    }

    private static String login(String channel) {
        if (channel == null) {
            return null;
        }
        String login = channel.startsWith("#") ? channel.substring(1) : channel;
        return login.isEmpty() ? null : login.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * In the menu's constructor, after Copy is stored. The popup is measured right there, so a row that turns
     * visible later doesn't fit: which rows show is decided now, from the constructor's message
     * ({@link #noteMessage} at its entry). Never throws.
     */
    public static void addRow(Object copy) {
        try {
            if (!wanted() || !(copy instanceof View) || !(((View) copy).getParent() instanceof ViewGroup)) {
                return;
            }
            View copyRow = (View) copy;
            ViewGroup menu = (ViewGroup) copyRow.getParent();
            Context context = copyRow.getContext();
            // One row: the menu's width and the space under the message fit no more; the actions are a dialog.
            View row = (View) copyRow.getClass().getConstructor(Context.class, AttributeSet.class)
                    .newInstance(context, null);
            copyRow.getClass().getMethod("setText", String.class).invoke(row, Strings.pick("Модерация", "Moderation"));
            Drawable icon = icon(context, "ic_sword");
            if (icon != null) {
                copyRow.getClass().getMethod("setIcon", Drawable.class).invoke(row, icon);
            }
            row.setTag(ROW_TAG_KEY, Boolean.TRUE);
            row.setVisibility(View.GONE);
            menu.addView(row, new LinearLayout.LayoutParams(copyRow.getLayoutParams()));
            wire(menu, copyRow);
        } catch (Throwable t) {
            Log.w(TAG, "addRow failed", t);
            CrashReporter.report(TAG, "addRow failed", t);
        }
    }

    /** The menu's message (31.9.0 {@code vwp}): at the menu constructor's entry and where it reads the text. Never throws. */
    public static void noteMessage(Object row) {
        message = row;
    }

    /** When the menu is shown (the same popup for another message too): our row rewired, the window fitted. Never throws. */
    public static void bind(Object copy) {
        try {
            if (copy instanceof View && ((View) copy).getParent() instanceof ViewGroup) {
                wire((ViewGroup) ((View) copy).getParent(), (View) copy);
                fitPopup((View) copy);
            }
        } catch (Throwable t) {
            Log.w(TAG, "bind failed", t);
            CrashReporter.report(TAG, "bind failed", t);
        }
    }

    /** Our rows for a moderator, wired to {@link #message}. */
    private static void wire(ViewGroup menu, View copyRow) throws Exception {
        {
            Object row = message;
            String[] fields = fields(row);
            final String id = fields[0];
            // The message's own room; history rows (GQL, no PRIVMSG seen) fall back to the open channel.
            String room = id == null ? null : MESSAGE_ROOMS.get(id);
            final String channelId = room != null ? room : ChannelTracker.getCurrentChannelId();
            String channel = channelId == null ? null : ROOMS.get(channelId);
            if (channel == null && room == null) {
                channel = ChannelTracker.getCurrentChannelLogin();
            }
            boolean mod = wanted() && channelId != null && channel != null && MOD_CHANNELS.contains(channel);
            Log.i(TAG, "menu: message " + id + " room " + channelId + " channel " + channel + " moderator " + mod);
            final String login = fields[1];
            final String name = fields[2] != null ? fields[2] : login;
            boolean self = login != null && login.equalsIgnoreCase(TwitchAuth.getUsername());
            boolean deleted = "true".equals(fields[3]);
            final boolean canDelete = id != null && !deleted;
            final boolean canBan = login != null && !self;
            boolean show = mod && (canDelete || canBan);
            for (int i = 0; i < menu.getChildCount(); i++) {
                final View view = menu.getChildAt(i);
                if (view.getTag(ROW_TAG_KEY) == null) {
                    continue;
                }
                view.setVisibility(show ? View.VISIBLE : View.GONE);
                view.setOnClickListener(v -> {
                    Context context = view.getContext();
                    close(view);
                    actions(context, channelId, id, login, name, canDelete, canBan);
                });
            }
        }
    }

    /** The actions for one message: delete, timeout, ban, and unban on a deleted one (a ban or timeout deletes the rows). */
    private static void actions(Context context, String channelId, String id, String login, String name,
            boolean canDelete, boolean canBan) {
        java.util.List<Integer> list = new java.util.ArrayList<>();
        if (canDelete) {
            list.add(DELETE);
        }
        if (canBan) {
            list.add(TIMEOUT);
            list.add(BAN);
            list.add(UNBAN);
        }
        String[] labels = new String[list.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = label(list.get(i), name);
        }
        new AlertDialog.Builder(context)
                .setTitle(name != null ? name : Strings.pick("Модерация", "Moderation"))
                .setItems(labels, (d, which) -> {
                    int action = list.get(which);
                    if (action == DELETE) {
                        run(context, delete(channelId, id), Strings.pick("Сообщение удалено", "Message deleted"));
                    } else if (action == TIMEOUT) {
                        chooseTimeout(context, channelId, login, name);
                    } else if (action == BAN) {
                        confirmBan(context, channelId, login, name);
                    } else {
                        run(context, unban(channelId, login), Strings.pickf("%s разбанен", "%s unbanned", name));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static String label(int action, String name) {
        if (action == DELETE) {
            return Strings.pick("Удалить сообщение", "Delete message");
        }
        if (action == UNBAN) {
            return Strings.pickf("Снять бан или таймаут %s", "Lift the ban or timeout of %s", name);
        }
        return action == TIMEOUT ? Strings.pickf("Таймаут %s", "Timeout %s", name) : Strings.pickf("Бан %s", "Ban %s", name);
    }

    private static String[] timeoutLabels() {
        return new String[]{Strings.pick("1 минута", "1 minute"), Strings.pick("10 минут", "10 minutes"),
                Strings.pick("1 час", "1 hour"), Strings.pick("1 день", "1 day"), Strings.pick("1 неделя", "1 week")};
    }

    private static void chooseTimeout(Context context, String channelId, String login, String name) {
        final String[] labels = timeoutLabels();
        new AlertDialog.Builder(context)
                .setTitle(Strings.pickf("Таймаут %s", "Timeout %s", name))
                .setItems(labels, (d, which) -> run(context, ban(channelId, login, TIMEOUTS[which] + "s"),
                        Strings.pickf("%s в таймауте: %s", "%s timed out for %s", name, labels[which])))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void confirmBan(Context context, String channelId, String login, String name) {
        new AlertDialog.Builder(context)
                .setTitle(Strings.pickf("Бан %s", "Ban %s", name))
                .setMessage(Strings.pickf("Забанить %s на канале навсегда?", "Ban %s from the channel for good?", name))
                .setPositiveButton(Strings.pick("Забанить", "Ban"), (d, w) -> run(context, ban(channelId, login, null),
                        Strings.pickf("%s забанен", "%s banned", name)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * The menu's window height is Twitch's one measure in the constructor ({@code getMeasuredHeight() + 20}), and our
     * row came short in it: the last row was cut. Measured again once shown, the window grows to fit.
     */
    private static void fitPopup(View row) {
        View root = row.getRootView();
        if (!(root.getLayoutParams() instanceof android.view.WindowManager.LayoutParams) || !(root instanceof ViewGroup)
                || ((ViewGroup) root).getChildCount() == 0) {
            return;
        }
        View content = ((ViewGroup) root).getChildAt(0);
        android.view.WindowManager.LayoutParams lp = (android.view.WindowManager.LayoutParams) root.getLayoutParams();
        // Shown but not laid out yet: the width is the one Twitch set on the window.
        int width = lp.width > 0 ? lp.width - root.getPaddingLeft() - root.getPaddingRight() : root.getWidth();
        if (width <= 0) {
            return;
        }
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int wanted = content.getMeasuredHeight() + root.getPaddingTop() + root.getPaddingBottom();
        if (lp.height > 0 && lp.height < wanted) {
            Log.i(TAG, "menu window " + lp.height + " -> " + wanted + " px");
            lp.height = wanted;
            android.view.WindowManager wm = (android.view.WindowManager) row.getContext().getSystemService(Context.WINDOW_SERVICE);
            wm.updateViewLayout(root, lp);
        }
    }

    /** The menu is a popup: Back on its root dismisses it, as the system's Back would. */
    private static void close(View row) {
        View root = row.getRootView();
        root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
    }

    /** {messageId, username, displayName, hasBeenDeleted} from the row model's toString; nulls when unknown. */
    static String[] fields(Object row) {
        String[] out = new String[4];
        if (row == null) {
            return out;
        }
        Matcher m = FIELD.matcher(row.toString());
        while (m.find()) {
            String value = m.group(2).trim();
            String key = m.group(1);
            int index = "messageId".equals(key) ? 0 : "username".equals(key) ? 1 : "displayName".equals(key) ? 2 : 3;
            if (out[index] == null && !value.isEmpty() && !"null".equals(value)) {
                out[index] = value;
            }
        }
        return out;
    }

    private static JSONObject delete(String channelId, String messageId) {
        try {
            return new JSONObject()
                    .put("query", "mutation($c:ID!,$m:ID!){deleteChatMessage(input:{channelID:$c,messageID:$m}){responseCode}}")
                    .put("variables", new JSONObject().put("c", channelId).put("m", messageId));
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    /** {@code expiresIn} null: a permanent ban. */
    private static JSONObject ban(String channelId, String login, String expiresIn) {
        try {
            JSONObject input = new JSONObject().put("channelID", channelId).put("bannedUserLogin", login);
            if (expiresIn != null) {
                input.put("expiresIn", expiresIn);
            }
            return new JSONObject()
                    .put("query", "mutation($i:BanUserFromChatRoomInput!){banUserFromChatRoom(input:$i){ban{isPermanent}error{code}}}")
                    .put("variables", new JSONObject().put("i", input));
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    private static JSONObject unban(String channelId, String login) {
        try {
            return new JSONObject()
                    .put("query", "mutation($c:ID!,$l:String!){unbanUserFromChatRoom(input:{channelID:$c,bannedUserLogin:$l}){ban{isPermanent}error{code}}}")
                    .put("variables", new JSONObject().put("c", channelId).put("l", login));
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    private static void run(final Context context, final JSONObject body, final String done) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            String error;
            try {
                Request request = new Request.Builder().url("https://gql.twitch.tv/gql")
                        .header("Client-ID", CLIENT_ID)
                        .header("Authorization", "OAuth " + TwitchAuth.getAuthToken())
                        .post(RequestBody.create(MediaType.parse("application/json"), String.valueOf(body)))
                        .build();
                try (Response response = CLIENT.newCall(request).execute()) {
                    error = error(new JSONObject(response.body().string()));
                }
            } catch (Throwable t) {
                Log.w(TAG, "action failed", t);
                error = t.getClass().getSimpleName();
            }
            final String shown = error;
            Log.i(TAG, "action: " + (shown == null ? "ok" : shown));
            MAIN.post(() -> Toast.makeText(app, shown == null ? done
                    : Strings.pickf("Не получилось: %s", "Didn't work: %s", shown), Toast.LENGTH_SHORT).show());
        }, "RTModActions").start();
    }

    /** The failure in a GQL answer, null on success. */
    static String error(JSONObject json) {
        if (json.has("errors")) {
            return json.optJSONArray("errors").optJSONObject(0).optString("message");
        }
        JSONObject data = json.optJSONObject("data");
        if (data == null) {
            return "no data";
        }
        JSONObject deleted = data.optJSONObject("deleteChatMessage");
        if (deleted != null) {
            String code = deleted.optString("responseCode", "");
            return code.isEmpty() || "SUCCESS".equals(code) ? null : code;
        }
        JSONObject banned = data.optJSONObject("banUserFromChatRoom");
        if (banned == null) {
            banned = data.optJSONObject("unbanUserFromChatRoom");
        }
        JSONObject err = banned == null ? null : banned.optJSONObject("error");
        return banned == null ? "no result" : err != null ? err.optString("code") : null;
    }

    private static Drawable icon(Context context, String name) {
        int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
        if (id == 0) {
            id = android.R.drawable.ic_menu_delete;
        }
        try {
            return context.getDrawable(id);
        } catch (Throwable t) {
            return null;
        }
    }
}
