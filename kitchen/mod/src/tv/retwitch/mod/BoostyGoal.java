package tv.retwitch.mod;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * The open fundraising goal of the mod's Boosty blog, shown on top of the mod's settings (SettingsScreen.goal). Boosty's public
 * API lists every goal of a blog without signing in ({@code GET api.boosty.to/v1/target/<blog>/}); a goal is still open
 * while its {@code finishTime} is null. Asked again on every opening of the screen once the last answer is 15 minutes
 * old, so when one goal closes and the next opens, the screen shows the new one by itself.
 */
final class BoostyGoal {

    private static final String TAG = "RTBoostyGoal";
    static final String BLOG = "sentryward";
    static final String PAGE = "https://boosty.to/" + BLOG;
    private static final String API = "https://api.boosty.to/v1/target/" + BLOG + "/";
    private static final long FRESH_MS = 15 * 60_000L;

    interface Done {
        /** Main thread; null: no open goal (or Boosty never answered). */
        void got(BoostyGoal goal);
    }

    final long id;
    final String title;
    /** The rest of the description (thanks), may be empty. */
    final String more;
    final double current;
    final double target;
    final String currency;
    final double currentUsd;
    final double targetUsd;

    private static volatile BoostyGoal last;
    private static volatile long fetchedAt;
    private static volatile boolean loading;

    private BoostyGoal(long id, String title, String more, double current, double target, String currency,
                       double currentUsd, double targetUsd) {
        this.id = id;
        this.title = title;
        this.more = more;
        this.current = current;
        this.target = target;
        this.currency = currency;
        this.currentUsd = currentUsd;
        this.targetUsd = targetUsd;
    }

    /** The last answer, for drawing before a new one comes; null if none. */
    static BoostyGoal cached() {
        return last;
    }

    /** Asks Boosty unless the last answer is fresh; {@code done} only gets a call when there is a new answer. */
    static void load(Done done) {
        if (loading || System.currentTimeMillis() - fetchedAt < FRESH_MS) {
            return;
        }
        loading = true;
        new Thread(() -> {
            try {
                BoostyGoal goal = parse(fetch());
                last = goal;
                fetchedAt = System.currentTimeMillis();
                new Handler(Looper.getMainLooper()).post(() -> done.got(goal));
            } catch (Throwable t) {
                Log.w(TAG, "load failed", t); // the last answer stays
            } finally {
                loading = false;
            }
        }, "RTBoostyGoal").start();
    }

    private static String fetch() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (connection.getResponseCode() != 200) {
                throw new java.io.IOException("HTTP " + connection.getResponseCode());
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) > 0; ) {
                    out.write(buffer, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    /** The open goal of the API's answer: highest priority, then the newest; null if every goal is finished. */
    static BoostyGoal parse(String json) throws Exception {
        JSONArray data = new JSONObject(json).optJSONArray("data");
        JSONObject best = null;
        for (int i = 0; data != null && i < data.length(); i++) {
            JSONObject goal = data.optJSONObject(i);
            if (goal == null || !goal.isNull("finishTime") || goal.optDouble("targetSum", 0) <= 0) {
                continue;
            }
            if (best == null || goal.optInt("priority") > best.optInt("priority")
                    || goal.optInt("priority") == best.optInt("priority") && goal.optLong("createdAt") > best.optLong("createdAt")) {
                best = goal;
            }
        }
        if (best == null) {
            return null;
        }
        String description = best.optString("description").trim();
        int cut = description.indexOf('\n');
        String title = (cut < 0 ? description : description.substring(0, cut)).trim();
        String more = cut < 0 ? "" : description.substring(cut + 1).trim();
        JSONObject currentSums = best.optJSONObject("currencyCurrentSums");
        JSONObject targetSums = best.optJSONObject("currencyTargetSums");
        return new BoostyGoal(best.optLong("id"), title.isEmpty() ? "Boosty" : title, more,
                best.optDouble("currentSum", 0), best.optDouble("targetSum", 0), best.optString("bloggerCurrency", "RUB"),
                currentSums != null ? currentSums.optDouble("USD", -1) : -1, targetSums != null ? targetSums.optDouble("USD", -1) : -1);
    }

    /** Raised so far, in % of the target (can pass 100 while the goal stays open). */
    int percent() {
        return (int) Math.round(current * 100 / target);
    }

    /** "11 467 ₽ из 25 000 ₽", or in dollars when the interface isn't Russian and Boosty gives them. */
    String sums(boolean russian) {
        boolean usd = !russian && currentUsd >= 0 && targetUsd > 0;
        String sign = usd ? "USD" : currency;
        Locale locale = russian ? new Locale("ru") : Locale.US;
        return Strings.pickf("%s из %s", "%s of %s", money(usd ? currentUsd : current, sign, locale),
                money(usd ? targetUsd : target, sign, locale));
    }

    private static String money(double value, String currency, Locale locale) {
        String number = NumberFormat.getIntegerInstance(locale).format(Math.floor(value));
        switch (currency) {
            case "RUB":
                return number + " ₽";
            case "USD":
                return "$" + number;
            case "EUR":
                return "€" + number;
            default:
                return number + " " + currency;
        }
    }
}
