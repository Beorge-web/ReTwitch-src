package tv.retwitch.mod.emotes;

import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import tv.retwitch.mod.ChannelTracker;
import tv.retwitch.mod.CrashReporter;
import tv.retwitch.mod.Strings;
import tv.retwitch.mod.Toggles;
import tv.retwitch.mod.TwitchAuth;

/**
 * RTE friends, as the extension's {@code friends} module: with {@link Toggles#RTE_FRIENDS} the
 * logged-in user's presence goes to the backend ({@code POST /api/ext/friends/activity} after
 * 5 minutes on a channel, then every 10, the extension's {@code activity.js}), and the settings
 * list shows friends and where they watch ({@code GET /api/ext/friends/<id>}). Friends are added
 * on ext.rte.net.ru/friends. Also starts the RTE roster, whose self-registration
 * ({@code /api/badge-users}) the extension sends on every start.
 */
public final class RteFriends {

    private static final String TAG = "RTFriends";
    private static final String API = "https://ext.rte.net.ru:8443";
    static final String MANAGE_URL = "https://ext.rte.net.ru/friends";
    private static final long FIRST_REPORT_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long REPORT_EVERY_MS = TimeUnit.MINUTES.toMillis(10);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String GQL = "https://gql.twitch.tv/gql";
    private static final String CLIENT_ID = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp";

    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "RTFriends");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static boolean installed;
    private static String reportedChannel;
    private static long reportedAt;

    private RteFriends() {
    }

    /** From app start. Never throws. */
    public static void install(Application application) {
        try {
            if (installed || application == null) {
                return;
            }
            installed = true;
            RteBadgeStore.ensureStarted();
            EXECUTOR.scheduleWithFixedDelay(RteFriends::tick, 1, 1, TimeUnit.MINUTES);
        } catch (Throwable t) {
            Log.w(TAG, "install failed", t);
        }
    }

    private static void tick() {
        try {
            if (!Toggles.on(Toggles.RTE_FRIENDS)) {
                return;
            }
            String userId = TwitchAuth.getUserId();
            String channel = ChannelTracker.getCurrentChannelLogin();
            if (userId == null || userId.isEmpty() || channel == null
                    || ChannelTracker.getMillisSinceChannelOpen() < FIRST_REPORT_MS) {
                return;
            }
            long now = System.currentTimeMillis();
            if (channel.equals(reportedChannel) && now - reportedAt < REPORT_EVERY_MS) {
                return;
            }
            JSONObject body = new JSONObject()
                    .put("twitch_id", userId)
                    .put("channel_login", channel)
                    .put("channel_display_name", channel)
                    .put("stream_category", streamCategory(channel));
            Request request = new Request.Builder().url(API + "/api/ext/friends/activity")
                    .post(RequestBody.create(JSON, body.toString())).build();
            try (Response response = EmoteHttp.sharedClient().newCall(request).execute()) {
                if (response.isSuccessful()) {
                    reportedChannel = channel;
                    reportedAt = now;
                }
                Log.i(TAG, "activity " + channel + ": HTTP " + response.code());
            }
        } catch (Throwable t) {
            Log.w(TAG, "activity report failed: " + t);
        }
    }

    /** As the extension: the backend hides a channel without a category from friends. Null leaves the field out. */
    private static String streamCategory(String channel) {
        try {
            String body = new JSONObject().put("query", "query{user(login:\"" + channel + "\"){stream{game{name}}}}").toString();
            Request request = new Request.Builder().url(GQL).header("Client-ID", CLIENT_ID)
                    .post(RequestBody.create(JSON, body)).build();
            try (Response response = EmoteHttp.sharedClient().newCall(request).execute()) {
                JSONObject user = new JSONObject(response.body().string()).getJSONObject("data").optJSONObject("user");
                JSONObject stream = user != null ? user.optJSONObject("stream") : null;
                JSONObject game = stream != null ? stream.optJSONObject("game") : null;
                return game != null ? game.optString("name", null) : null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "category of " + channel + " failed: " + t);
            return null;
        }
    }

    /** The friends dialog from the settings. */
    public static void showDialog(final Context context) {
        final String userId = TwitchAuth.getUserId();
        if (userId == null || userId.isEmpty()) {
            Toast.makeText(context, Strings.friendsNeedLogin(), Toast.LENGTH_SHORT).show();
            return;
        }
        EXECUTOR.execute(() -> {
            JSONArray friends = null;
            int requests = 0;
            try {
                friends = new JSONArray(get("/api/ext/friends/" + Uri.encode(userId)));
                requests = new JSONObject(get("/api/ext/friends/requests/count/" + Uri.encode(userId)))
                        .optInt("count");
            } catch (Throwable t) {
                Log.w(TAG, "friends load failed: " + t);
            }
            final JSONArray list = friends;
            final int pending = requests;
            MAIN.post(() -> show(context, list, pending));
        });
    }

    private static void show(final Context context, JSONArray friends, int requests) {
        try {
            final List<String> logins = new ArrayList<>();
            List<String> rows = new ArrayList<>();
            if (friends != null) {
                // Online first, as the extension's sidebar.
                for (int pass = 0; pass < 2; pass++) {
                    for (int i = 0; i < friends.length(); i++) {
                        JSONObject f = friends.optJSONObject(i);
                        if (f == null || f.optBoolean("is_online") != (pass == 0)) {
                            continue;
                        }
                        String name = f.optString("display_name", f.optString("login"));
                        JSONArray channels = f.optJSONArray("channels");
                        JSONObject ch = channels != null && channels.length() > 0 ? channels.optJSONObject(0) : null;
                        if (ch != null && f.optBoolean("is_online")) {
                            String where = ch.optString("channel_display_name", ch.optString("channel_login"));
                            String category = ch.optString("stream_category", "");
                            rows.add("● " + name + " > " + where + (category.isEmpty() ? "" : " · " + category));
                            logins.add(ch.optString("channel_login"));
                        } else {
                            rows.add("○ " + name);
                            logins.add(f.optString("login"));
                        }
                    }
                }
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(context)
                    .setTitle(Strings.friendsTitle() + (requests > 0 ? " · " + Strings.friendsRequests(requests) : ""))
                    .setPositiveButton(Strings.friendsManage(), (d, w) -> open(context, MANAGE_URL, false))
                    .setNegativeButton(android.R.string.cancel, null);
            if (friends == null) {
                builder.setMessage(Strings.friendsLoadFailed());
            } else if (rows.isEmpty()) {
                builder.setMessage(Strings.friendsEmpty());
            } else {
                builder.setItems(rows.toArray(new String[0]), (d, which) -> {
                    String login = logins.get(which);
                    if (!login.isEmpty()) {
                        open(context, "https://www.twitch.tv/" + login, true);
                    }
                });
            }
            builder.show();
        } catch (Throwable t) {
            Log.w(TAG, "friends dialog failed", t);
            CrashReporter.report(TAG, "friends dialog failed", t);
        }
    }

    /** RTE subscription status ({@code GET /api/subscription/check/<id>}, the extension's SubscriptionStatus). */
    public static void showSubscription(final Context context) {
        final String userId = TwitchAuth.getUserId();
        if (userId == null || userId.isEmpty()) {
            Toast.makeText(context, Strings.friendsNeedLogin(), Toast.LENGTH_SHORT).show();
            return;
        }
        EXECUTOR.execute(() -> {
            JSONObject status = null;
            try {
                status = new JSONObject(get("/api/subscription/check/" + Uri.encode(userId)));
            } catch (Throwable t) {
                Log.w(TAG, "subscription check failed: " + t);
            }
            final JSONObject result = status;
            MAIN.post(() -> {
                try {
                    String text;
                    if (result == null) {
                        text = Strings.friendsLoadFailed();
                    } else if (result.optBoolean("has_subscription")) {
                        String end = result.optString("current_period_end", "");
                        text = Strings.rteSubscriptionActive(result.optInt("tier"),
                                end.length() >= 10 ? end.substring(0, 10) : end);
                    } else {
                        text = Strings.rteSubscriptionNone();
                    }
                    new AlertDialog.Builder(context)
                            .setTitle(Strings.rteSubscriptionTitle())
                            .setMessage(text)
                            .setPositiveButton(Strings.rteSite(), (d, w) -> open(context, "https://ext.rte.net.ru", false))
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                } catch (Throwable t) {
                    Log.w(TAG, "subscription dialog failed", t);
                }
            });
        });
    }

    /** A channel opens in this app (its twitch.tv deep link), the manage page in the browser. */
    private static void open(Context context, String url, boolean here) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            if (here) {
                intent.setPackage(context.getPackageName());
            }
            context.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "open failed: " + url, t);
        }
    }

    private static String get(String path) throws java.io.IOException {
        Request request = new Request.Builder().url(API + path).header("Accept", "application/json").build();
        try (Response response = EmoteHttp.sharedClient().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new java.io.IOException("HTTP " + response.code());
            }
            return response.body().string();
        }
    }
}
