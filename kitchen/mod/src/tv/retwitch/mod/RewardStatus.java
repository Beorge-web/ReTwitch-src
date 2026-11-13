package tv.retwitch.mod;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * A cooldown timer or a cross over a channel points reward's icon. Twitch's card ignores both, its reward
 * model ({@code CommunityPointsReward$Custom}) is read on tap only. The model's stock is right, but its
 * cooldown comes from PubSub alone: the app's reward query doesn't ask for it, so it's fetched here.
 */
public final class RewardStatus {

    private static final String TAG = "RTRewards";
    private static final int OVERLAY_KEY = 0x7e700d69;
    private static final String GQL = "https://gql.twitch.tv/gql";
    private static final String CLIENT_ID = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp";
    private static final String QUERY = "query($id:ID!){user(id:$id){channel{communityPointsSettings"
            + "{customRewards{id isInStock cooldownExpiresAt}}}}}";
    private static final long REFRESH_MS = 30_000;
    private static final long AVAILABLE = 0;
    private static final long OUT_OF_STOCK = -1;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Open cards, to repaint when the answer comes. Main thread. */
    private static final Set<Tick> TICKS = Collections.newSetFromMap(new WeakHashMap<>());
    /** Reward id: cooldown end in epoch ms, {@link #AVAILABLE} or {@link #OUT_OF_STOCK}. */
    private static Map<String, Long> rewards = Collections.emptyMap();
    private static String channel;
    private static long loadedAt;
    private static boolean loading;

    private RewardStatus() {
    }

    /** Injected at the entry of the reward card's bind (31.9.0 {@code tn9.u}). Never throws. */
    public static void onBind(Object holder, Object row) {
        try {
            RelativeLayout card = (RelativeLayout) value(holder, RelativeLayout.class);
            if (card == null) {
                return;
            }
            Tick tick = (Tick) card.getTag(OVERLAY_KEY);
            Object reward = Toggles.on(Toggles.REWARD_STATUS) ? unavailable(row) : null;
            if (tick == null) {
                if (reward == null) {
                    return;
                }
                tick = new Tick(overlay(card));
                card.setTag(OVERLAY_KEY, tick);
                TICKS.add(tick);
            }
            tick.rewardId = reward == null ? null : (String) reward.getClass().getMethod("getId").invoke(reward);
            String expiry = reward == null ? null
                    : (String) reward.getClass().getMethod("getCoolDownExpiration").invoke(reward);
            tick.cooldown = expiry == null ? AVAILABLE : Instant.parse(expiry).toEpochMilli();
            tick.show();
            if (reward != null) {
                load();
            }
        } catch (Throwable t) {
            Log.w(TAG, "onBind failed", t);
            CrashReporter.report(TAG, "onBind failed", t);
        }
    }

    /** The custom reward of this card if it's out of stock, else null. */
    private static Object unavailable(Object row) throws Exception {
        Object reward = value(row, Object.class);
        boolean custom = reward != null && reward.getClass().getName().endsWith("CommunityPointsReward$Custom");
        return custom && !(boolean) reward.getClass().getMethod("isInStock").invoke(reward) ? reward : null;
    }

    private static void load() {
        String current = ChannelTracker.getCurrentChannelId();
        if (current == null || loading
                || current.equals(channel) && SystemClock.elapsedRealtime() - loadedAt < REFRESH_MS) {
            return;
        }
        loading = true;
        new Thread(() -> {
            Map<String, Long> loaded = null;
            try {
                String body = new JSONObject().put("query", QUERY)
                        .put("variables", new JSONObject().put("id", current)).toString();
                Request request = new Request.Builder().url(GQL).header("Client-ID", CLIENT_ID)
                        .post(RequestBody.create(MediaType.parse("application/json"), body)).build();
                try (Response response = CLIENT.newCall(request).execute()) {
                    loaded = parse(response.body().string());
                }
            } catch (Exception e) {
                Log.w(TAG, "rewards not loaded: " + e);
            }
            final Map<String, Long> result = loaded;
            MAIN.post(() -> {
                loading = false;
                channel = current;
                loadedAt = SystemClock.elapsedRealtime();
                if (result != null) {
                    rewards = result;
                    for (Tick tick : new ArrayList<>(TICKS)) {
                        tick.show();
                    }
                }
            });
        }, TAG).start();
    }

    private static Map<String, Long> parse(String json) throws Exception {
        JSONArray list = new JSONObject(json).getJSONObject("data").getJSONObject("user").getJSONObject("channel")
                .getJSONObject("communityPointsSettings").getJSONArray("customRewards");
        Map<String, Long> result = new HashMap<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject reward = list.getJSONObject(i);
            long status = reward.getBoolean("isInStock") ? AVAILABLE : reward.isNull("cooldownExpiresAt") ? OUT_OF_STOCK
                    : Instant.parse(reward.getString("cooldownExpiresAt")).toEpochMilli();
            result.put(reward.getString("id"), status);
        }
        return result;
    }

    /** The field of exactly this type, the holder's own or an inherited one. */
    private static Object value(Object owner, Class<?> type) throws Exception {
        for (Class<?> k = owner.getClass(); k != null; k = k.getSuperclass()) {
            for (Field field : k.getDeclaredFields()) {
                if (field.getType() == type) {
                    field.setAccessible(true);
                    return field.get(owner);
                }
            }
        }
        return null;
    }

    private static TextView overlay(RelativeLayout card) {
        Context context = card.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        int icon = UiHider.id(context, "reward_icon");
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(0, 0);
        params.addRule(RelativeLayout.ALIGN_LEFT, icon);
        params.addRule(RelativeLayout.ALIGN_TOP, icon);
        params.addRule(RelativeLayout.ALIGN_RIGHT, icon);
        params.addRule(RelativeLayout.ALIGN_BOTTOM, icon);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xA6000000);
        background.setCornerRadius(6 * density);
        TextView label = new TextView(context);
        label.setBackground(background);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(0xFFFFFFFF);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setMaxLines(1);
        label.setAutoSizeTextTypeUniformWithConfiguration(8, 15, 1, TypedValue.COMPLEX_UNIT_SP);
        card.addView(label, params);
        return label;
    }

    /** Whole seconds up: at 0:00 the reward is back. */
    static String format(long ms) {
        long seconds = (ms + 999) / 1000;
        long hours = seconds / 3600;
        if (hours >= 24) {
            return Strings.pickf("%d д", "%d d", hours / 24);
        }
        return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static final class Tick implements Runnable {
        final TextView label;
        String rewardId;
        /** From the model, set when PubSub told about it. */
        long cooldown;
        long until;

        Tick(TextView label) {
            this.label = label;
        }

        /** Nothing until the answer: no cooldown in the model doesn't mean there is none. */
        void show() {
            label.removeCallbacks(this);
            Long known = rewards.get(rewardId);
            until = rewardId == null ? AVAILABLE : cooldown != AVAILABLE ? cooldown : known != null ? known : AVAILABLE;
            if (until == OUT_OF_STOCK) {
                label.setText("❌");
                label.setVisibility(View.VISIBLE);
            } else {
                run();
            }
        }

        @Override
        public void run() {
            long left = until - System.currentTimeMillis();
            if (left <= 0) {
                label.setVisibility(View.GONE);
                return;
            }
            label.setText(format(left));
            label.setVisibility(View.VISIBLE);
            label.postDelayed(this, (left - 1) % 1000 + 1);
        }
    }
}
