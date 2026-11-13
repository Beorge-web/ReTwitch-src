package tv.retwitch.mod;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;


/**
 * ⏮ ⏭ beside the live player's pause ({@link Toggles#STREAM_ARROWS}, feedback idea #31): the previous or next stream,
 * like YouTube's. Live puts its pause alone in {@code play_pause_view} (a full-size ConstraintLayout, whose params are
 * R8-renamed) inside the overlay's FrameLayout {@code play_pause_container}; the arrows go in that frame, centred and
 * shifted to the pause's sides, so they fade with the overlay. A VOD's pause sits in {@code center_player_control_views}
 * between the seek buttons and gets none. Recent mode walks {@link StreamHistory} (the channels the live player played);
 * followed mode walks the followed channels live now, in Twitch's own order. A channel opens by its link, as the
 * app's deep links do.
 */
public final class StreamSwitch {

    private static final String TAG = "RTStreamSwitch";
    /** History of opened channels: state, not a setting (SettingsBackup.EXCLUDED). */
    static final String STATE = "retwitch_stream_history";
    private static final String KEY_LOGINS = "logins";
    /** Recent channels whose liveness one tap checks. */
    private static final int CHECKED = 20;

    private static final StreamHistory HISTORY = new StreamHistory();
    private static final Map<FrameLayout, View[]> ARROWS = new WeakHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean restored;
    private static String lastState;
    private static volatile boolean busy;
    /** A channel opened from fullscreen, to put back in fullscreen once it plays (feedback #31). */
    private static String fullscreenFor;
    private static long fullscreenUntil;

    private StreamSwitch() {
    }

    /** From UiHider's pass: notes the live channel and keeps the arrows beside every shown pause. */
    static void sync(Activity activity, View decor) {
        try {
            int vodCenter = UiHider.id(activity, "center_player_control_views");
            List<View> pauses = new ArrayList<>();
            for (View pause : UiHider.findAll(activity, decor, "play_pause_button")) {
                if (pause.getParent() instanceof View && ((View) pause.getParent()).getId() != vodCenter
                        && pause.getParent().getParent() instanceof FrameLayout) {
                    pauses.add(pause);
                }
            }
            // The live pause's layout is up, so a live channel: the player's usher URL, else the chat's channel
            // (a stream proxy's own URL carries no login).
            String login = pauses.isEmpty() ? null : LiveRecord.currentLogin();
            noteOpened(activity, login);
            keepFullscreen(activity, decor, login);
            boolean on = Toggles.on(Toggles.STREAM_ARROWS);
            String state = login + " " + pauses.size() + " " + HISTORY.cursor + "/" + HISTORY.logins.size();
            if (on && !state.equals(lastState)) {
                lastState = state;
                Log.i(TAG, "state: " + state);
            }
            for (View pause : pauses) {
                FrameLayout parent = (FrameLayout) pause.getParent().getParent();
                View[] pair = ARROWS.get(parent);
                if (!on || login == null) {
                    if (pair != null) {
                        setShown(pair[0], false);
                        setShown(pair[1], false);
                    }
                    continue;
                }
                if (pair == null) {
                    pair = new View[]{arrow(activity, parent, false), arrow(activity, parent, true)};
                    ARROWS.put(parent, pair);
                }
                place(pair[0], pause, false);
                place(pair[1], pause, true);
                boolean followedMode = PlayerSettings.getStreamArrowsMode() == PlayerSettings.ARROWS_FOLLOWED;
                setShown(pair[0], followedMode || !HISTORY.back().isEmpty());
                setShown(pair[1], followedMode || !HISTORY.forward().isEmpty());
            }
        } catch (Throwable t) {
            Log.w(TAG, "sync failed", t);
        }
    }


    private static void noteOpened(Context context, String login) {
        if (!restored) {
            restored = true;
            List<String> saved = new ArrayList<>();
            try {
                JSONArray array = new JSONArray(state(context).getString(KEY_LOGINS, "[]"));
                for (int i = 0; i < array.length(); i++) {
                    saved.add(array.optString(i));
                }
            } catch (Throwable t) {
                Log.w(TAG, "history unreadable", t);
            }
            HISTORY.restore(saved);
        }
        if (HISTORY.opened(login)) {
            state(context).edit().putString(KEY_LOGINS, new JSONArray(HISTORY.logins).toString()).apply();
        }
    }

    /** Centred in the frame like the pause, moved off to its side by half of both plus a gap. */
    private static void place(View arrow, View pause, boolean next) {
        float gap = 24 * arrow.getResources().getDisplayMetrics().density;
        float x = (pause.getWidth() + arrow.getLayoutParams().width) / 2f + gap;
        float tx = next ? x : -x;
        if (arrow.getTranslationX() != tx) {
            arrow.setTranslationX(tx);
        }
    }

    private static View arrow(Activity activity, FrameLayout parent, boolean next) {
        ImageView view = new ImageView(activity);
        int icon = UiHider.res(activity, next ? "quantum_ic_skip_next_white_36" : "quantum_ic_skip_previous_white_36", "drawable");
        if (icon != 0) {
            view.setImageResource(icon);
        }
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = Math.round(8 * activity.getResources().getDisplayMetrics().density);
        view.setPadding(pad, pad, pad, pad);
        view.setContentDescription(next ? Strings.pick("Следующий стрим", "Next stream") : Strings.pick("Предыдущий стрим", "Previous stream"));
        int size = Math.round(56 * activity.getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, Gravity.CENTER);
        view.setOnClickListener(v -> go(activity, next));
        parent.addView(view, params);
        return view;
    }

    private static void setShown(View view, boolean shown) {
        int visibility = shown ? View.VISIBLE : View.GONE;
        if (view.getVisibility() != visibility) {
            view.setVisibility(visibility);
        }
    }

    private static void go(Activity activity, boolean next) {
        if (busy) {
            return;
        }
        busy = true;
        final String current = LiveRecord.playerLogin();
        final boolean followedMode = PlayerSettings.getStreamArrowsMode() == PlayerSettings.ARROWS_FOLLOWED;
        final List<String> recent = next ? HISTORY.forward() : HISTORY.back();
        new Thread(() -> {
            String target = null;
            String failure = null;
            try {
                if (followedMode) {
                    List<String> list = StreamHistory.following;
                    Log.i(TAG, "followed: " + (list == null ? "none yet" : list.size() + " live"));
                    target = list == null ? null : firstLive(StreamHistory.round(list, current, next));
                    if (target == null) {
                        failure = list == null ? Strings.pick("Сначала откройте «Отслеживаемое» на главной", "Open Following on Home first")
                                : Strings.pick("Других отслеживаемых в эфире нет", "No other followed channels are live");
                    }
                } else {
                    target = firstLive(recent);
                    if (target == null) {
                        failure = next ? Strings.pick("Дальше в истории никого в эфире", "Nobody further in the history is live")
                                : Strings.pick("Раньше в истории никого в эфире", "Nobody earlier in the history is live");
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "lookup failed", t);
                failure = Strings.pick("Не удалось получить список каналов", "Couldn't get the channel list");
            }
            final String open = target;
            final String error = failure;
            MAIN.post(() -> {
                busy = false;
                if (open == null) {
                    Toast.makeText(activity, error, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!followedMode) {
                    HISTORY.pending = open;
                }
                Log.i(TAG, "open " + open);
                boolean landscape = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
                fullscreenFor = landscape ? open : null;
                fullscreenUntil = SystemClock.elapsedRealtime() + 20_000;
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.twitch.tv/" + open))
                            .setPackage(activity.getPackageName()));
                } catch (Throwable t) {
                    Log.w(TAG, "open failed", t);
                }
            });
        }, "RTStreamSwitch").start();
    }

    /** The new theatre opens in portrait; once the channel opened from fullscreen plays, its rotate button turns it. */
    private static void keepFullscreen(Activity activity, View decor, String login) {
        if (fullscreenFor == null || login == null) {
            return;
        }
        if (SystemClock.elapsedRealtime() > fullscreenUntil) {
            fullscreenFor = null;
            return;
        }
        if (!fullscreenFor.equalsIgnoreCase(login)) {
            return;
        }
        fullscreenFor = null;
        if (activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            return;
        }
        List<View> buttons = UiHider.findAll(activity, decor, "rotate_button");
        if (!buttons.isEmpty()) {
            Log.i(TAG, "back to fullscreen: " + login);
            buttons.get(0).performClick();
        }
    }

    /** The first of {@code logins} live now, in their order. Network. */
    private static String firstLive(List<String> logins) throws Exception {
        List<String> checked = logins.subList(0, Math.min(CHECKED, logins.size()));
        if (checked.isEmpty()) {
            return null;
        }
        JSONObject data = VodDownload.gql("query($l:[String!]){users(logins:$l){login stream{id}}}",
                new JSONObject().put("l", new JSONArray(checked)));
        JSONArray users = data.optJSONArray("users");
        List<String> live = new ArrayList<>();
        for (int i = 0; users != null && i < users.length(); i++) {
            JSONObject user = users.optJSONObject(i);
            if (user != null && user.optJSONObject("stream") != null) {
                live.add(user.optString("login"));
            }
        }
        for (String login : checked) {
            if (live.contains(login)) {
                return login;
            }
        }
        return null;
    }

    private static SharedPreferences state(Context context) {
        return context.getSharedPreferences(STATE, Context.MODE_PRIVATE);
    }
}
