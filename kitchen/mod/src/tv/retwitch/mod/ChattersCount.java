package tv.retwitch.mod;

import android.app.Activity;
import android.icu.text.CompactDecimalFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;

/**
 * "(N)" after the viewer count on the player: how many are in chat ({@code SHOW_CHATTERS_COUNT}, as the RTE
 * extension does). The count is GQL {@code channel.chatters.count}, which only the Android client id gets
 * anonymously (the web one fails its integrity check). Twitch writes the viewer count into
 * {@code stream_stats_text}; a watcher there adds ours after every write. Refreshed once a minute while shown.
 */
final class ChattersCount {

    private static final String TAG = "RTChatters";
    private static final String GQL = "https://gql.twitch.tv/gql";
    private static final String CLIENT_ID = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp";
    private static final long REFRESH_MS = 60_000;
    private static final int WATCHED_TAG_KEY = 0x7e700d66;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build();

    /** The appended part, so the next write of Twitch's text and our refresh can find it. */
    private static final class Suffix {
    }

    private static volatile String login;
    private static volatile int count;
    private static volatile long fetchedAt;
    private static volatile boolean fetching;

    private ChattersCount() {
    }

    /** From the UI pass: the viewer count of the open theatre. */
    static void attach(Activity activity, View decor) {
        boolean on = Toggles.on(Toggles.SHOW_CHATTERS_COUNT);
        for (View theatre : UiHider.findAll(activity, decor, "draggable_container")) {
            for (View view : UiHider.findAll(activity, theatre, "stream_stats_text")) {
                if (!(view instanceof TextView)) {
                    continue;
                }
                TextView label = (TextView) view;
                if (on && label.getTag(WATCHED_TAG_KEY) == null) {
                    label.setTag(WATCHED_TAG_KEY, Boolean.TRUE);
                    label.addTextChangedListener(new Watcher());
                    label.setText(label.getText());
                }
                if (on && label.isShown()) {
                    refresh(label);
                }
            }
        }
    }

    private static void refresh(final TextView label) {
        String channel = ChannelTracker.getCurrentChannelLogin();
        if (channel == null || fetching || channel.equals(login)
                && SystemClock.elapsedRealtime() - fetchedAt < REFRESH_MS) {
            return;
        }
        fetching = true;
        new Thread(() -> {
            int found = 0;
            try {
                String body = new JSONObject().put("query",
                        "query{channel(name:\"" + channel + "\"){chatters{count}}}").toString();
                Request request = new Request.Builder().url(GQL).header("Client-ID", CLIENT_ID)
                        .post(RequestBody.create(MediaType.parse("application/json"), body)).build();
                try (Response response = CLIENT.newCall(request).execute()) {
                    JSONObject channelJson = new JSONObject(response.body().string()).getJSONObject("data")
                            .optJSONObject("channel");
                    JSONObject chatters = channelJson != null ? channelJson.optJSONObject("chatters") : null;
                    found = chatters != null ? chatters.optInt("count") : 0;
                }
            } catch (Throwable t) {
                // Twitch can close this to anonymous callers any time: the count just goes away.
                Log.w(TAG, "chatters of " + channel + " failed: " + t);
            }
            final int result = found;
            MAIN.post(() -> {
                login = channel;
                count = result;
                fetchedAt = SystemClock.elapsedRealtime();
                fetching = false;
                label.setText(label.getText());
            });
        }, "RTChatters").start();
    }

    private static final class Watcher implements TextWatcher {
        private boolean busy;

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable text) {
            if (busy) {
                return;
            }
            busy = true;
            try {
                for (Suffix old : text.getSpans(0, text.length(), Suffix.class)) {
                    int start = text.getSpanStart(old);
                    int end = text.getSpanEnd(old);
                    text.removeSpan(old);
                    if (start >= 0) {
                        text.delete(start, end);
                    }
                }
                // 0 is also what an offline channel gets: nothing rather than a wrong zero.
                boolean show = Toggles.on(Toggles.SHOW_CHATTERS_COUNT) && count > 0
                        && login != null && login.equals(ChannelTracker.getCurrentChannelLogin());
                if (show && text.length() > 0) {
                    String suffix = " (" + CompactDecimalFormat.getInstance(Locale.getDefault(),
                            CompactDecimalFormat.CompactStyle.SHORT).format(count) + ")";
                    int start = text.length();
                    text.append(suffix);
                    text.setSpan(new Suffix(), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } catch (Throwable t) {
                Log.w(TAG, "suffix failed", t);
            } finally {
                busy = false;
            }
        }
    }
}
