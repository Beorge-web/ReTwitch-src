package tv.retwitch.mod.emotes;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;

import tv.retwitch.mod.ChatSettings;
import tv.retwitch.mod.Strings;
import tv.retwitch.mod.UiHider;

/**
 * Our cosmetics on Twitch's viewer card: the RTE badge in the card's badge row (tap: what it is) and the
 * nick paint on the name. The card shows the display name only, so the user id comes from the chat's IRC
 * tags ({@link #noteUser}, from {@code ModLogs.onIrcEvent}). Applied from the UI pass while a card is shown.
 */
public final class CardCosmetics {

    private static final String TAG = "RTCardCosmetics";
    private static final int BADGE_TAG_KEY = 0x7e700d62;
    private static final String[] ACTIONS = {"gift_a_sub_button", "follow_button", "whisper_button"};
    private static final int FRIEND_TAG_KEY = 0x7e700d67;
    private static final String FRIENDS_API = "https://ext.rte.net.ru:8443/api/ext/friends/status/";
    /** Friendship status per viewer id ({@code none}, {@code pending_sent}, {@code pending_received}, {@code accepted}). */
    private static final Map<String, String> FRIEND_STATUS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Lowercased display name -> user id, as the chat saw them. */
    private static final Map<String, String> IDS = new LinkedHashMap<String, String>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 5000;
        }
    };

    private CardCosmetics() {
    }

    /** A PRIVMSG's sender (IRC thread). */
    public static void noteUser(Object displayName, Object userId) {
        if (displayName == null || userId == null) {
            return;
        }
        synchronized (IDS) {
            IDS.put(displayName.toString().toLowerCase(Locale.ROOT), userId.toString());
        }
    }

    /** Whether the IRC hook should collect senders for the card. */
    public static boolean wanted() {
        return ChatSettings.isRteBadgesEnabled() || ChatSettings.isNickPaintsEnabled();
    }

    /** Logins already being looked up (or not found), so a card re-layout doesn't ask again. */
    private static final Set<String> LOOKUPS = new HashSet<>();
    private static final String GQL = "https://gql.twitch.tv/gql";
    private static final String CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko";
    /** How soon an open card tries again after a failed step: nothing else would lay it out again. */
    private static final long RETRY_MS = 2000;

    private static String idOf(String shown) {
        String name = shown.trim().toLowerCase(Locale.ROOT);
        int login = name.indexOf(" (");
        synchronized (IDS) {
            String id = IDS.get(name);
            return id == null && login > 0 ? IDS.get(name.substring(0, login)) : id;
        }
    }

    /**
     * A viewer who hasn't written while the chat was open: their id from anonymous GQL by login (the card shows
     * the display name, the login for ASCII names; "Display (login)" otherwise), then the card is laid out again.
     */
    private static void lookup(String shown, final View root) {
        String name = shown.trim().toLowerCase(Locale.ROOT);
        int open = name.indexOf(" (");
        final String login = open > 0 && name.endsWith(")") ? name.substring(open + 2, name.length() - 1) : name;
        if (!login.matches("[a-z0-9_]{1,25}")) {
            return;
        }
        synchronized (LOOKUPS) {
            if (!LOOKUPS.add(login)) {
                return;
            }
        }
        new Thread(() -> {
            try {
                String body = new JSONObject().put("query", "query{user(login:\"" + login + "\"){id}}").toString();
                Request request = new Request.Builder().url(GQL).header("Client-ID", CLIENT_ID)
                        .post(RequestBody.create(MediaType.parse("application/json"), body)).build();
                try (Response response = EmoteHttp.sharedClient().newCall(request).execute()) {
                    JSONObject user = new JSONObject(response.body().string()).getJSONObject("data").optJSONObject("user");
                    String id = user != null ? user.optString("id", null) : null;
                    Log.i(TAG, "lookup " + login + " -> " + id);
                    if (id != null) {
                        noteUser(name, id);
                        root.post(root::requestLayout);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "lookup " + login + " failed", t);
                synchronized (LOOKUPS) {
                    LOOKUPS.remove(login);
                }
                root.postDelayed(root::requestLayout, RETRY_MS);
            }
        }, "RTCardLookup").start();
    }

    /** UI pass: decorates the viewer card whose name view this is. Never throws. */
    public static void apply(Context context, View root) {
        try {
            fitActionLabels(context, root);
            if (!wanted()) {
                return;
            }
            List<View> names = UiHider.findAll(context, root, "target_username");
            if (names.isEmpty() || !(names.get(0) instanceof TextView)) {
                return;
            }
            TextView name = (TextView) names.get(0);
            String id = idOf(name.getText().toString());
            if (id == null) {
                lookup(name.getText().toString(), root);
                return;
            }
            if (!RteBadgeStore.isLoaded()) {
                root.postDelayed(root::requestLayout, RETRY_MS);
            }
            if (!id.equals(root.getTag(BADGE_TAG_KEY))) {
                root.setTag(BADGE_TAG_KEY, id);
                Log.i(TAG, "card " + name.getText() + " id " + id + " badge " + RteBadgeStore.getBadgeUrl(id)
                        + " paint " + (RteBadgeStore.getPaint(id) != null));
            }
            if (ChatSettings.isNickPaintsEnabled()) {
                paintName(name, id);
            }
            List<View> rows = UiHider.findAll(context, root, "viewer_card_badges");
            LinearLayout row = !rows.isEmpty() && rows.get(0) instanceof LinearLayout ? (LinearLayout) rows.get(0) : null;
            if (row != null && ChatSettings.isRteBadgesEnabled()) {
                syncBadges(row, id);
            }
            if (row != null && tv.retwitch.mod.Toggles.on(tv.retwitch.mod.Toggles.FRIEND_BUTTON)) {
                syncFriendButton(row, id);
            }
        } catch (Throwable t) {
            Log.w(TAG, "apply failed", t);
        }
    }

    /**
     * The RTE friend button at the end of the badge row, as the extension's viewer card has it: the status from
     * {@code /api/ext/friends/status/<me>/<them>}, a tap opens {@code ext.rte.net.ru/friends?add=<id>}.
     */
    private static void syncFriendButton(final LinearLayout row, final String id) {
        String me = tv.retwitch.mod.TwitchAuth.getUserId();
        View old = null;
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i).getTag(FRIEND_TAG_KEY) != null) {
                old = row.getChildAt(i);
            }
        }
        // Kept last: badges land after it while their roster loads.
        if (old != null && id.equals(old.getTag(FRIEND_TAG_KEY)) && row.indexOfChild(old) == row.getChildCount() - 1) {
            return;
        }
        if (old != null) {
            row.removeView(old);
        }
        if (me.isEmpty() || me.equals(id)) {
            return;
        }
        String status = FRIEND_STATUS.get(id);
        if (status == null) {
            FRIEND_STATUS.put(id, "");
            new Thread(() -> {
                String found = "none";
                try (Response response = EmoteHttp.sharedClient().newCall(
                        new Request.Builder().url(FRIENDS_API + me + "/" + id).build()).execute()) {
                    found = new JSONObject(response.body().string()).optString("status", "none");
                } catch (Throwable t) {
                    Log.w(TAG, "friend status " + id + " failed: " + t);
                }
                FRIEND_STATUS.put(id, found);
                row.post(row::requestLayout);
            }, "RTFriendStatus").start();
            return;
        }
        if (status.isEmpty() || "self".equals(status)) {
            return;
        }
        final Context context = row.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        TextView button = new TextView(context);
        button.setText(Strings.friendButton(status));
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        button.setTextColor(0xFFFFFFFF);
        button.setBackground(tv.retwitch.mod.SettingsHook.brandBackground(Math.round(12 * density)));
        button.setPadding(Math.round(10 * density), Math.round(3 * density), Math.round(10 * density), Math.round(3 * density));
        if ("none".equals(status) || "pending_received".equals(status)) {
            button.setOnClickListener(v -> {
                FRIEND_STATUS.remove(id); // the site changes it: asked again on the next card
                tv.retwitch.mod.SettingsHook.openLink(v.getContext(), "https://ext.rte.net.ru/friends?add=" + id);
            });
        }
        LinearLayout box = new LinearLayout(context);
        box.setTag(FRIEND_TAG_KEY, id);
        box.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        box.addView(button);
        row.addView(box, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (row.getVisibility() != View.VISIBLE) {
            row.setVisibility(View.VISIBLE);
        }
    }

    /** Twitch's gift/follow/whisper labels are wider than their thirds of the card and run into each other. */
    private static void fitActionLabels(Context context, View root) {
        for (String action : ACTIONS) {
            List<View> buttons = UiHider.findAll(context, root, action);
            List<View> labels = UiHider.findAll(context, root, action + "_label");
            if (buttons.isEmpty() || labels.isEmpty() || !(labels.get(0) instanceof TextView)) {
                continue;
            }
            TextView label = (TextView) labels.get(0);
            int width = buttons.get(0).getWidth();
            if (width > 0 && label.getMaxWidth() != width) {
                label.setMaxWidth(width);
                label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
                label.setMaxLines(2);
                label.setGravity(android.view.Gravity.CENTER);
            }
        }
    }

    private static void paintName(TextView name, String id) {
        NickPaint paint = RteBadgeStore.getPaint(id);
        CharSequence text = name.getText();
        if (paint == null || text.length() == 0) {
            return;
        }
        if (text instanceof Spanned && ((Spanned) text).getSpans(0, text.length(), NickPaint.Span.class).length > 0) {
            return;
        }
        SpannableString painted = new SpannableString(text);
        painted.setSpan(new NickPaint.Span(paint), 0, painted.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        name.setText(painted);
    }

    /** The row shows this viewer's badges: the card is reused, so another viewer's go first. */
    private static void syncBadges(LinearLayout row, String id) {
        String staff = RteBadgeStore.getStaffBadgeUrl(id);
        String main = RteBadgeStore.getBadgeUrl(id);
        for (int i = row.getChildCount() - 1; i >= 0; i--) {
            Object url = row.getChildAt(i).getTag(BADGE_TAG_KEY);
            if (url != null && !url.equals(staff) && !url.equals(main)) {
                row.removeViewAt(i);
            }
        }
        addBadge(row, staff, "ReYohoho Staff", Strings.rteStaffBadgeDescription());
        addBadge(row, main, "ReYohoho", Strings.rteBadgeDescription());
    }

    private static void addBadge(final LinearLayout row, final String url, final String title, final String description) {
        if (url == null) {
            return;
        }
        for (int i = 0; i < row.getChildCount(); i++) {
            if (url.equals(row.getChildAt(i).getTag(BADGE_TAG_KEY))) {
                return;
            }
        }
        final Context context = row.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        int size = Math.round(20 * density);
        // Same size as Twitch's own badge items when there are some.
        for (int i = 0; i < row.getChildCount(); i++) {
            View badge = row.getChildAt(i).findViewById(UiHider.id(context, "badge_image"));
            if (badge != null && badge.getHeight() > 0) {
                size = badge.getHeight();
                break;
            }
        }
        final ImageView image = new ImageView(context);
        image.setTag(BADGE_TAG_KEY, url);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription(title);
        image.setOnClickListener(v -> new AlertDialog.Builder(v.getContext(), android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setMessage(description)
                .setPositiveButton(android.R.string.ok, null)
                .show());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginEnd(Math.round(4 * density));
        row.addView(image, lp);
        if (row.getVisibility() != View.VISIBLE) {
            row.setVisibility(View.VISIBLE);
        }
        EmoteImageCache.loadLarge(url, size, drawable -> {
            if (drawable == null) {
                // The removal lays the row out, and the next pass adds the badge again.
                row.postDelayed(() -> row.removeView(image), RETRY_MS);
            } else if (url.equals(image.getTag(BADGE_TAG_KEY))) {
                image.setImageDrawable(drawable);
            }
        });
    }
}
