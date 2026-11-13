package tv.retwitch.mod;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.WeakHashMap;
import java.util.Set;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;

/**
 * Player tweaks driven by the IVS player's own state changes
 * ({@code PlayerTweaksPatch} hooks {@code CorePlayerImpl}'s state dispatch, so
 * {@code player} is the IVS player itself and all calls below are its public
 * SDK API -- not R8-renamed):
 * <ul>
 *   <li>default quality per network (Wi-Fi / cellular), applied on READY;</li>
 *   <li>auto-reload of a stream stuck in BUFFERING ({@link ReloadHook}).</li>
 * </ul>
 * Only the audible player is touched, which skips Twitch's muted feed
 * previews. shortcut: Twitch's own quality menu keeps showing its previous
 * pick after we set a default; picking a fixed quality in the menu overrides
 * ours, picking "Auto" gives ours again ({@link #onAutoQualityMode}).
 */
public final class PlayerHook {

    private static final String TAG = "RTPlayer";
    private static final int MAX_RELOADS = 3;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static int reloadsInARow;
    /** The last audible player, for chapter seeks. */
    private static WeakReference<Object> lastPlayer;
    private static Runnable pendingStallCheck;

    /** Players loaded in the open theatre, and those of a closed one: both are touched from Twitch's threads. */
    private static final Set<Object> THEATRE_PLAYERS = Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());
    private static final Set<Object> ORPHANS = Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());
    private static volatile boolean theatreOpen;
    /** The mod's recording player ({@link RecordPlayer}): not Twitch's, none of the hooks here apply to it. */
    private static volatile WeakReference<Object> replay = new WeakReference<>(null);

    private PlayerHook() {
    }

    static void setReplay(Object player) {
        replay = new WeakReference<>(player);
    }

    private static boolean isReplay(Object player) {
        return player != null && player == replay.get();
    }

    /**
     * From the UI pass. A stream closed while it loads kept playing with nothing on screen, one more voice
     * per stream, until the app restarted: Twitch's stop only pauses, and a start it put off still comes
     * (31.9.0 {@code ona.w} / {@code B}), maybe with the next theatre already open. So a player belongs to the
     * theatre it was loaded in, and stays paused once that one is gone, until it is loaded again.
     * shortcut: the race itself stays in Twitch, find what starts the player if this ever pauses a wanted one.
     */
    /** A theatre (and with it the chat) is on screen, as the last UiHider pass saw it. */
    static boolean theatreOpen() {
        return theatreOpen;
    }

    static void onTheatre(boolean open) {
        if (theatreOpen && !open) {
            synchronized (THEATRE_PLAYERS) {
                ORPHANS.addAll(THEATRE_PLAYERS);
                for (Object player : THEATRE_PLAYERS) {
                    pauseOrphan(player);
                }
                THEATRE_PLAYERS.clear();
            }
        }
        theatreOpen = open;
    }

    private static void pauseOrphan(Object player) {
        try {
            String state = String.valueOf(call(player, "getState"));
            if ("PLAYING".equals(state) || "BUFFERING".equals(state)) {
                Log.i(TAG, "pausing a player left without a theatre (" + state + ")");
                call(player, "pause");
            }
        } catch (Throwable t) {
            Log.w(TAG, "pauseOrphan failed", t);
        }
    }

    /** Injected into IVS's state dispatch (main thread). Never throws. */
    public static void onStateChanged(Object player, Object state) {
        try {
            if (player == null || state == null || isReplay(player) || !isAudible(player)) {
                return;
            }
            lastPlayer = new WeakReference<>(player);
            String name = state.toString();
            PlayerStats.logState(player, name);
            cancelStallCheck();
            if ("READY".equals(name)) {
                final WeakReference<Object> ref = new WeakReference<>(player);
                // After Twitch's own quality setup for this load has run.
                MAIN.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        applyDefaultQuality(ref.get());
                    }
                }, 500L);
            } else if ("BUFFERING".equals(name)) {
                scheduleStallCheck(player);
            } else if ("PLAYING".equals(name)) {
                reloadsInARow = 0;
                AudioCompressor.onPlaying();
                synchronized (THEATRE_PLAYERS) {
                    if (ORPHANS.contains(player)) {
                        pauseOrphan(player);
                    } else if (theatreOpen) {
                        THEATRE_PLAYERS.add(player);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "onStateChanged failed", t);
            CrashReporter.report(TAG, "onStateChanged failed", t);
        }
    }

    /** Width/height of the current video (IVS {@code getQuality()}), or 16:9 when unknown. */
    /**
     * Injected after Twitch's usher-manifest cache lookup (31.8.0 {@code rap.P}):
     * a hit is reused for 5 minutes from download, so a stream restarted in
     * that window kept playing the previous broadcast's dead playlists. Null =
     * "not cached", Twitch then asks usher again. Never throws.
     */
    public static Object filterCachedManifest(Object cached) {
        return cached != null && Toggles.on(Toggles.NO_MANIFEST_CACHE) ? null : cached;
    }

    static float videoAspect() {
        try {
            Object player = lastPlayer != null ? lastPlayer.get() : null;
            Object quality = player != null ? call(player, "getQuality") : null;
            if (quality != null) {
                int w = (Integer) call(quality, "getWidth");
                int h = (Integer) call(quality, "getHeight");
                if (w > 0 && h > 0) {
                    return w / (float) h;
                }
            }
        } catch (Throwable ignored) {
            // fall through to the common default
        }
        return 16f / 9f;
    }

    /** The audible IVS player, or null. */
    static Object currentPlayer() {
        return lastPlayer != null ? lastPlayer.get() : null;
    }

    /**
     * Injected at the entry of IVS {@code setLiveLowLatencyEnabled(boolean)}: with
     * the "normal latency" option every request turns it off. Never throws.
     */
    public static boolean filterLowLatency(boolean enabled) {
        return enabled && !Toggles.on(Toggles.NORMAL_LATENCY);
    }

    /**
     * Injected after each IVS load: Twitch never sets the latency mode itself
     * (IVS's default is low latency for usher URLs), so turn it off here. Never throws.
     */
    public static void onLoad(Object player) {
        if (isReplay(player)) {
            return;
        }
        try {
            if (player != null) {
                synchronized (THEATRE_PLAYERS) {
                    ORPHANS.remove(player);
                    if (theatreOpen && isAudible(player)) {
                        THEATRE_PLAYERS.add(player);
                    }
                }
            }
            if (player != null && Toggles.on(Toggles.NORMAL_LATENCY)) {
                player.getClass().getMethod("setLiveLowLatencyEnabled", boolean.class).invoke(player, false);
            }
        } catch (Throwable t) {
            Log.w(TAG, "low latency off failed", t);
        }
    }

    /**
     * Injected where the "Up next" card (31.8.0 {@code ab2.w}) stores its 10 s
     * countdown: with autoplay off the countdown is cancelled, so nothing plays
     * by itself; the card and its play/cancel buttons stay. Never throws.
     */
    public static void onUpNextCountdown(Object card, Object countdown) {
        try {
            if (card == null || countdown == null || !Toggles.on(Toggles.NO_AUTOPLAY)) {
                return;
            }
            Method dispose = countdown.getClass().getMethod("dispose");
            dispose.setAccessible(true);
            dispose.invoke(countdown);
            for (Class<?> k = card.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (!View.class.isAssignableFrom(f.getType())) {
                        continue;
                    }
                    f.setAccessible(true);
                    View v = (View) f.get(card);
                    if (v instanceof ProgressBar) {
                        v.setVisibility(View.INVISIBLE);
                    } else if (v instanceof TextView && v.getId() == v.getResources().getIdentifier(
                            "autoplay_time_remaining", "id", v.getContext().getPackageName())) {
                        ((TextView) v).setText(Strings.upNext());
                    }
                }
            }
            Log.i(TAG, "up next countdown cancelled");
        } catch (Throwable t) {
            Log.w(TAG, "onUpNextCountdown failed", t);
            CrashReporter.report(TAG, "onUpNextCountdown failed", t);
        }
    }

    /** Seeks the current (audible) player; IVS public {@code seekTo(long ms)}. */
    static boolean seekCurrent(long positionMs) {
        try {
            Object player = lastPlayer != null ? lastPlayer.get() : null;
            if (player == null) {
                return false;
            }
            player.getClass().getMethod("seekTo", long.class).invoke(player, positionMs);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "seek failed", t);
            CrashReporter.report(TAG, "seek failed", t);
            return false;
        }
    }

    /**
     * Injected at the entry of IVS {@code CorePlayerImpl.setAutoQualityMode}:
     * Twitch re-applies its stored "auto" pick on its own schedule (Rx
     * callbacks after the quality list arrives, resume from pause), which
     * undid the default applied on READY. With a default set, the audible
     * player gets that quality instead -- Twitch's "Auto" menu item included.
     *
     * @return true to skip the original call. Never throws.
     */
    public static boolean onAutoQualityMode(Object player, boolean auto) {
        try {
            return auto && player != null && isAudible(player) && applyDefaultQuality(player);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Injected where the player settings menu reads its selected quality
     * (31.8.0 {@code x720.D2} <- {@code z620.f()}): that is Twitch's own
     * requested quality, which the default set below Twitch never changes, so
     * the menu ticked e.g. 1080p60 over a 720p60 picture. With a default
     * active it shows what the audible player really plays. Never throws.
     */
    public static String menuQuality(String twitchName) {
        try {
            Object player = lastPlayer != null ? lastPlayer.get() : null;
            if (player == null || PlayerSettings.getDefaultQuality(isCellular()) == 0 || !isAudible(player)) {
                return twitchName;
            }
            if ((Boolean) call(player, "isAutoQualityMode")) {
                return "auto";
            }
            Object quality = call(player, "getQuality");
            String name = quality != null ? (String) call(quality, "getName") : null;
            return name != null && !name.isEmpty() ? name : twitchName;
        } catch (Throwable t) {
            return twitchName;
        }
    }

    /** @return whether a default quality was set (false: none chosen, or qualities not known yet). */
    private static boolean applyDefaultQuality(Object player) {
        try {
            if (player == null) {
                return false;
            }
            int target = PlayerSettings.getDefaultQuality(isCellular());
            if (target == 0) {
                return false;
            }
            Object best = pickQuality((Collection<?>) call(player, "getQualities"), target);
            if (best == null) {
                return false;
            }
            Object current = call(player, "getQuality");
            // Background audio and "Audio only" play audio_only (no height): no video default over
            // it, nor anything while the app isn't in the foreground -- Twitch's pick stays.
            android.app.ActivityManager.RunningAppProcessInfo app = new android.app.ActivityManager.RunningAppProcessInfo();
            android.app.ActivityManager.getMyMemoryState(app);
            if ((current != null && (Integer) call(current, "getHeight") <= 0)
                    || app.importance > android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                return false;
            }
            if (current != null && !(Boolean) call(player, "isAutoQualityMode")
                    && String.valueOf(call(best, "getName")).equals(call(current, "getName"))) {
                return true; // already playing it: setQuality again only reloads the rendition
            }
            float rate = ((Number) call(player, "getPlaybackRate")).floatValue();
            for (Method m : player.getClass().getMethods()) {
                if (m.getName().equals("setQuality") && m.getParameterTypes().length == 1) {
                    m.invoke(player, best);
                    if (rate != 1f) {
                        // VOD speed survives the quality switch.
                        player.getClass().getMethod("setPlaybackRate", float.class).invoke(player, rate);
                    }
                    String name = (String) call(best, "getName");
                    Log.i(TAG, "default quality -> " + name);
                    rememberInTwitch(name);
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyDefaultQuality failed", t);
            CrashReporter.report(TAG, "applyDefaultQuality failed", t);
        }
        return false;
    }

    /**
     * Twitch's quality menu and its next stream start read the last pick from
     * SharedPreferences "video_quality", key "video_quality" + "_live"/"_vod"
     * (31.8.0 {@code lo80.q}, read straight from the prefs on every call);
     * without this the menu kept showing Twitch's old pick over ours.
     */
    private static void rememberInTwitch(String name) {
        Context context = AppContext.get();
        if (context == null || name == null) {
            return;
        }
        android.content.SharedPreferences prefs = context.getSharedPreferences("video_quality", Context.MODE_PRIVATE);
        Log.i(TAG, "Twitch's stored quality " + prefs.getString("video_quality_live", null) + " -> " + name);
        prefs.edit().putString("video_quality_live", name).putString("video_quality_vod", name).apply();
    }

    private static final String LATENCY_TAG = "retwitch_latency";
    /** Centre areas of the two player overlay layouts: shown and hidden with the controls. */
    private static final String[] LATENCY_HOSTS = {"play_pause_container", "play_pause_fast_seek_view"};
    private static boolean latencyLabels;

    /** From {@link UiHider}'s layout pass: a live-latency badge in each player overlay while the toggle is on. */
    static void attachLatencyLabels(Activity activity, View decor) {
        boolean on = Toggles.on(Toggles.LATENCY_LABEL);
        if (!on && !latencyLabels) {
            attachLatencyHints(activity, decor);
            return;
        }
        latencyLabels = on;
        attachLatencyHints(activity, decor);
        for (String name : LATENCY_HOSTS) {
            for (View host : UiHider.findAll(activity, decor, name)) {
                if (!(host instanceof FrameLayout)) {
                    continue;
                }
                View label = host.findViewWithTag(LATENCY_TAG);
                if (on && label == null) {
                    int margin = Math.round(12 * activity.getResources().getDisplayMetrics().density);
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
                    lp.setMargins(margin, margin, margin, margin);
                    ((FrameLayout) host).addView(latencyLabel(activity, margin / 2), lp);
                    Log.i(TAG, "latency label added to " + name);
                } else if (!on && label != null) {
                    ((FrameLayout) host).removeView(label);
                }
            }
        }
    }

    /** Tag on a chat input with our hint ticker; holds the hint we last set ("" = none). */
    private static final int HINT_TAG = 0x7e700d45;

    /** Chat inputs show the live latency instead of Twitch's "Send a message" while the toggle is on. */
    private static void attachLatencyHints(Activity activity, View decor) {
        if (!Toggles.on(Toggles.LATENCY_HINT)) {
            return; // installed tickers restore the hint themselves
        }
        for (View v : UiHider.findAll(activity, decor, "message_input")) {
            if (v instanceof TextView && v.getTag(HINT_TAG) == null) {
                v.setTag(HINT_TAG, "");
                v.post(hintTicker((TextView) v));
            }
        }
    }

    /**
     * Once a second: Twitch's default hint (or ours) becomes the latency while
     * the audible player is live; any other hint Twitch sets (slow mode,
     * followers-only...) is left alone. Off live or with the toggle off, our
     * hint goes back to Twitch's.
     */
    private static Runnable hintTicker(final TextView field) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    int res = field.getResources().getIdentifier("chat_input_hint", "string",
                            field.getContext().getPackageName());
                    String send = res != 0 ? field.getResources().getString(res) : null;
                    String ours = String.valueOf(field.getTag(HINT_TAG));
                    String hint = field.getHint() != null ? field.getHint().toString() : null;
                    boolean mine = !ours.isEmpty() && ours.equals(hint);
                    long ms = Toggles.on(Toggles.LATENCY_HINT) ? liveLatencyMs() : -1;
                    if (ms > 0 && send != null && (mine || send.equals(hint))) {
                        String text = Strings.latencyShort(ms);
                        if (!text.equals(hint)) {
                            field.setHint(text);
                        }
                        field.setTag(HINT_TAG, text);
                    } else if (mine && send != null) {
                        field.setHint(send);
                        field.setTag(HINT_TAG, "");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "latency hint failed", t);
                }
                field.postDelayed(this, 1000L);
            }
        };
    }

    /** A badge that re-reads IVS {@code getLiveLatency()} once a second while on screen; hidden off live. */
    private static TextView latencyLabel(Context context, int pad) {
        final TextView label = new TextView(context);
        label.setTag(LATENCY_TAG);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setGravity(Gravity.CENTER);
        label.setPadding(pad, pad / 2, pad, pad / 2);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99000000);
        bg.setCornerRadius(pad);
        label.setBackground(bg);
        label.setVisibility(View.GONE);
        final Runnable tick = new Runnable() {
            private boolean logged;

            @Override
            public void run() {
                try {
                    long ms = liveLatencyMs();
                    if (!logged) {
                        logged = true;
                        Log.i(TAG, "live latency reads " + ms + " ms");
                    }
                    if (ms <= 0) {
                        label.setVisibility(View.GONE);
                    } else if (label.getParent() instanceof View && ((View) label.getParent()).isShown()) {
                        setLatencyText(label, Strings.latencyLabel(ms));
                        label.setVisibility(View.VISIBLE);
                    }
                    label.postDelayed(this, 1000L);
                } catch (Throwable t) {
                    Log.w(TAG, "latency label failed", t);
                }
            }
        };
        label.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                v.removeCallbacks(tick);
                v.post(tick);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                v.removeCallbacks(tick);
            }
        });
        return label;
    }

    /**
     * New text on the badge without a window relayout every second: a WRAP_CONTENT
     * TextView requests layout on any setText, so the width only grows (fixed), and
     * a fixed-width text change just redraws.
     */
    private static void setLatencyText(TextView label, String text) {
        if (text.contentEquals(label.getText())) {
            return;
        }
        int width = (int) Math.ceil(Layout.getDesiredWidth(text, label.getPaint()))
                + label.getCompoundPaddingLeft() + label.getCompoundPaddingRight();
        ViewGroup.LayoutParams lp = label.getLayoutParams();
        if (lp != null && lp.width < width) {
            lp.width = width;
            label.setLayoutParams(lp);
        }
        label.setText(text);
    }

    /** IVS {@code getLiveLatency()} of the audible player in ms, or -1. */
    static long liveLatencyMs() {
        try {
            Object player = lastPlayer != null ? lastPlayer.get() : null;
            return player == null ? -1 : (Long) call(player, "getLiveLatency");
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Highest quality not taller than {@code target} (-1 = highest overall);
     * falls back to the lowest when every rendition is taller.
     */
    static Object pickQuality(Collection<?> qualities, int target) throws Exception {
        Object best = null;
        Object lowest = null;
        int bestHeight = -1;
        int lowestHeight = Integer.MAX_VALUE;
        for (Object q : qualities) {
            int h = (Integer) call(q, "getHeight");
            if (h <= 0) {
                continue; // audio-only
            }
            if (h < lowestHeight) {
                lowestHeight = h;
                lowest = q;
            }
            if ((target < 0 || h <= target) && h > bestHeight) {
                bestHeight = h;
                best = q;
            }
        }
        return best != null ? best : lowest;
    }

    private static void scheduleStallCheck(Object player) {
        if (!Toggles.on(Toggles.AUTO_RELOAD)) {
            return;
        }
        final WeakReference<Object> ref = new WeakReference<>(player);
        final long stallMs = PlayerSettings.getStallSeconds() * 1000L;
        pendingStallCheck = new Runnable() {
            @Override
            public void run() {
                try {
                    Object p = ref.get();
                    if (p == null || !"BUFFERING".equals(String.valueOf(call(p, "getState")))) {
                        return;
                    }
                    if (reloadsInARow >= MAX_RELOADS) {
                        Log.i(TAG, "stalled, but reload attempts exhausted");
                        return;
                    }
                    reloadsInARow++;
                    Log.i(TAG, "stalled " + stallMs + "ms -> reload #" + reloadsInARow);
                    ReloadHook.reloadPlayer();
                } catch (Throwable t) {
                    Log.w(TAG, "stall check failed", t);
                    CrashReporter.report(TAG, "stall check failed", t);
                }
            }
        };
        MAIN.postDelayed(pendingStallCheck, stallMs);
    }

    private static void cancelStallCheck() {
        if (pendingStallCheck != null) {
            MAIN.removeCallbacks(pendingStallCheck);
            pendingStallCheck = null;
        }
    }

    static boolean isAudible(Object player) {
        try {
            return !(Boolean) call(player, "isMuted") && (Float) call(player, "getVolume") > 0f;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isCellular() {
        try {
            Context context = AppContext.get();
            ConnectivityManager cm = context == null ? null
                    : (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object call(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }
}
