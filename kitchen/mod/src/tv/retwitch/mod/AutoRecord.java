package tv.retwitch.mod;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Auto-record: chosen channels are recorded from the start of each stream ({@link LiveRecord}, the archive when the
 * channel has one). {@link RecordService} looks at them once a minute (anonymous GQL {@code users(logins:)}) while
 * the switch is on. A stream is recorded once: one that ended, failed or was stopped by hand is in {@code handled};
 * one cut short by a dead process is not, so it continues in the same file ({@link LiveRecord#resume}).
 */
final class AutoRecord {

    private static final String TAG = "RTAutoRecord";
    /** Settings, backed up with the rest. */
    private static final String PREFS = "retwitch_record";
    private static final String KEY_ON = "auto_on";
    private static final String KEY_WIFI_ONLY = "wifi_only";
    /** {login: {"q": rendition label, "on": bool}}. */
    private static final String KEY_CHANNELS = "channels";
    private static final String KEY_HANDLED = "handled";
    private static final int MAX_HANDLED = 50;
    /** Channels live at the last look, for the settings page. */
    private static final Set<String> LIVE = Collections.synchronizedSet(new HashSet<String>());

    private AutoRecord() {
    }

    private static SharedPreferences prefs() {
        return AppContext.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isOn() {
        return AppContext.get() != null && prefs().getBoolean(KEY_ON, false);
    }

    static void setOn(Context context, boolean on) {
        prefs().edit().putBoolean(KEY_ON, on).apply();
        RecordService.refresh(context);
    }

    static boolean wifiOnly() {
        return AppContext.get() == null || prefs().getBoolean(KEY_WIFI_ONLY, true);
    }

    static void setWifiOnly(boolean on) {
        prefs().edit().putBoolean(KEY_WIFI_ONLY, on).apply();
    }

    /** Whether the service should keep watching the channels. */
    static boolean waiting() {
        return Toggles.on(Toggles.LIVE_RECORD) && isOn() && enabledCount() > 0;
    }

    private static JSONObject channelsJson() {
        try {
            return new JSONObject(prefs().getString(KEY_CHANNELS, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** {login, quality, "1"/"0" enabled}, in the order they were added. */
    static List<String[]> channels() {
        List<String[]> out = new ArrayList<>();
        if (AppContext.get() == null) {
            return out;
        }
        JSONObject all = channelsJson();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String login = it.next();
            JSONObject c = all.optJSONObject(login);
            out.add(new String[]{login, c == null ? "" : c.optString("q"), c != null && c.optBoolean("on") ? "1" : "0"});
        }
        return out;
    }

    static boolean has(String login) {
        return login != null && channelsJson().has(login);
    }

    static int enabledCount() {
        int n = 0;
        for (String[] c : channels()) {
            n += "1".equals(c[2]) ? 1 : 0;
        }
        return n;
    }

    /** Adds or updates a channel (enabled), or removes it with {@code quality} null. */
    static void put(Context context, String login, String quality) {
        try {
            JSONObject all = channelsJson();
            if (quality == null) {
                all.remove(login);
            } else {
                all.put(login.toLowerCase(java.util.Locale.ROOT), new JSONObject().put("q", quality).put("on", true));
            }
            prefs().edit().putString(KEY_CHANNELS, all.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "channel list not saved", e);
        }
        RecordService.refresh(context);
    }

    static void setEnabled(Context context, String login, boolean on) {
        update(context, login, "on", on);
    }

    /** Auto-record qualities: "" the best, a height (missing: the nearest not taller, {@link LiveRecord#pick}), audio. */
    static final String[] QUALITIES = {"", "1080p", "720p", "480p", "360p", "160p", "audio_only"};

    /** Where a saved quality sits in {@link #QUALITIES}; the record sheet saves labels like "720p60 · 1280x720". */
    static int qualityIndex(String quality) {
        if (quality == null || quality.isEmpty()) {
            return 0;
        }
        int height = LiveRecord.height(quality);
        if (height == 0) {
            return QUALITIES.length - 1;
        }
        if (!Character.isDigit(quality.charAt(0))) {
            return 0; // "Source (1080p60) · ...": the best there is
        }
        for (int i = 1; i < QUALITIES.length - 1; i++) {
            if (LiveRecord.height(QUALITIES[i]) <= height) {
                return i;
            }
        }
        return QUALITIES.length - 2;
    }

    static void setQuality(Context context, String login, String quality) {
        update(context, login, "q", quality);
    }

    private static void update(Context context, String login, String key, Object value) {
        try {
            JSONObject all = channelsJson();
            JSONObject c = all.optJSONObject(login);
            if (c != null) {
                all.put(login, c.put(key, value));
                prefs().edit().putString(KEY_CHANNELS, all.toString()).apply();
            }
        } catch (Exception e) {
            Log.w(TAG, "channel not saved", e);
        }
        RecordService.refresh(context);
    }

    /** "в эфире, пишется" / "ждёт эфира" / "выключено", for the settings row. */
    static String state(String[] channel) {
        if (!"1".equals(channel[2])) {
            return Strings.pick("выключено", "off");
        }
        if (LiveRecord.find(channel[0]) != null) {
            return Strings.pick("в эфире, пишется", "live, recording");
        }
        return LIVE.contains(channel[0]) ? Strings.pick("в эфире, уже записан", "live, already recorded")
                : Strings.pick("ждёт эфира", "waiting for a stream");
    }

    static String waitingLine(Context context, int channels) {
        return Strings.pickf("Каналов: %d · проверка раз в минуту", "Channels: %d · checked every minute", channels);
    }

    /** One look at the channels; starts a recording for each new stream. Network: not on the main thread. */
    static void check(Context context) throws Exception {
        if (!waiting() || wifiOnly() && LiveRecord.metered(context)) {
            return;
        }
        JSONArray logins = new JSONArray();
        for (String[] c : channels()) {
            if ("1".equals(c[2])) {
                logins.put(c[0]);
            }
        }
        JSONArray users = VodDownload.gql("query($l:[String!]){users(logins:$l){login stream{id}}}",
                new JSONObject().put("l", logins)).optJSONArray("users");
        Set<String> handled = handled(context);
        Set<String> live = new HashSet<>();
        for (int i = 0; users != null && i < users.length(); i++) {
            JSONObject user = users.optJSONObject(i);
            JSONObject stream = user == null ? null : user.optJSONObject("stream");
            if (stream == null) {
                continue;
            }
            String login = user.optString("login");
            live.add(login);
            if (!handled.contains(stream.optString("id")) && LiveRecord.find(login) == null) {
                JSONObject cut = LiveRecord.cutFor(context, stream.optString("id"));
                Log.i(TAG, login + " is live, " + (cut != null ? "continuing" : "recording") + " stream "
                        + stream.optString("id"));
                if (cut != null) {
                    LiveRecord.resume(context, cut);
                } else {
                    LiveRecord.start(context, new LiveRecord.Rec(login, quality(login), true, 0, true));
                }
            }
        }
        LIVE.retainAll(live);
        LIVE.addAll(live);
    }

    private static String quality(String login) {
        JSONObject c = channelsJson().optJSONObject(login);
        return c == null ? "" : c.optString("q");
    }

    private static Set<String> handled(Context context) {
        Set<String> out = new HashSet<>();
        try {
            JSONArray ids = new JSONArray(context.getSharedPreferences(LiveRecord.STATE, Context.MODE_PRIVATE)
                    .getString(KEY_HANDLED, "[]"));
            for (int i = 0; i < ids.length(); i++) {
                out.add(ids.getString(i));
            }
        } catch (Exception e) {
            Log.w(TAG, "handled list unreadable", e);
        }
        return out;
    }

    /** A stream recorded (or given up on): auto-record leaves it alone from now on. */
    static void handled(Context context, String streamId) {
        if (streamId == null || streamId.isEmpty()) {
            return;
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(LiveRecord.STATE, Context.MODE_PRIVATE);
            JSONArray ids = new JSONArray(prefs.getString(KEY_HANDLED, "[]"));
            JSONArray kept = new JSONArray().put(streamId);
            for (int i = 0; i < ids.length() && kept.length() < MAX_HANDLED; i++) {
                if (!streamId.equals(ids.getString(i))) {
                    kept.put(ids.getString(i));
                }
            }
            prefs.edit().putString(KEY_HANDLED, kept.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "handled list not saved", e);
        }
    }
}
