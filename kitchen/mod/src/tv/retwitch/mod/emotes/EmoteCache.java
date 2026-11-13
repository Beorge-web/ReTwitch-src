package tv.retwitch.mod.emotes;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import tv.retwitch.mod.CrashReporter;
import tv.retwitch.mod.Toggles;

/**
 * In-memory cache of third-party emote sets, keyed by Twitch's numeric
 * broadcaster user id for channel-specific sets, plus one shared global
 * set. All network I/O happens on a single background thread, kicked off
 * by {@link #ensureChannelLoaded}; {@link #lookup} itself is a plain
 * synchronous map read so it is safe to call from the chat render path,
 * which runs frequently (once per message) and must never block on
 * network I/O.
 *
 * All three providers are always fetched together and cached as separate
 * per-provider maps -- {@link EmoteSettings} on/off toggles are applied
 * only at {@link #lookup} time, so flipping a toggle takes effect
 * immediately without needing to invalidate/refetch anything.
 */
public final class EmoteCache {

    private static final String TAG = "RTEmotes";

    private static final long CHANNEL_TTL_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long GLOBAL_TTL_MS = TimeUnit.MINUTES.toMillis(30);
    /** A provider failed (timeout, 5xx): try again this soon instead of waiting out the TTL. */
    private static final long RETRY_MS = TimeUnit.SECONDS.toMillis(30);
    private static final int MAX_CACHED_CHANNELS = 8;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "ReTwitch-Emotes");
            thread.setDaemon(true);
            return thread;
        }
    });

    private static final Handler RETRY_HANDLER = new Handler(Looper.getMainLooper());

    private static volatile ProviderSets global = ProviderSets.EMPTY;
    private static final AtomicBoolean globalLoading = new AtomicBoolean(false);

    private static final Map<String, ProviderSets> channelCache = Collections.synchronizedMap(
            new LinkedHashMap<String, ProviderSets>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ProviderSets> eldest) {
                    return size() > MAX_CACHED_CHANNELS;
                }
            });
    private static final Map<String, Boolean> channelLoading = new ConcurrentHashMap<>();

    private EmoteCache() {
    }

    /**
     * Kicks off a background fetch for this channel if its cached data is
     * missing or stale. Cheap and idempotent -- safe to call on every
     * chat message rather than only on channel-join.
     */
    public static void ensureChannelLoaded(String twitchUserId) {
        if (twitchUserId == null || twitchUserId.isEmpty() || !EmoteSettings.isAnyProviderEnabled()) {
            return;
        }
        ensureGlobalLoaded();

        ProviderSets existing = channelCache.get(twitchUserId);
        if (existing != null && !existing.isStale(CHANNEL_TTL_MS)) {
            return;
        }
        if (channelLoading.putIfAbsent(twitchUserId, Boolean.TRUE) != null) {
            return;
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ProviderSets sets = fetchAll(twitchUserId, existing != null ? existing : ProviderSets.EMPTY);
                    channelCache.put(twitchUserId, sets);
                    if (sets.partial) {
                        retryLater();
                    }
                    SevenTvEvents.refresh();
                    // Chat history for this channel was very likely rendered
                    // (as plain text) before this fetch finished -- nudge a
                    // re-injection pass now that lookups can actually match.
                    EmoteChatHook.requestRefresh();
                    EmotePickerBridge.refreshSections();
                } catch (Throwable t) {
                    // Daemon background thread: an escaped throwable here would
                    // crash the whole app, so swallow anything fetchAll or the
                    // refresh nudge might still let through.
                    Log.w(TAG, "channel emote load crashed (channel=" + twitchUserId + ")", t);
                    CrashReporter.report(TAG, "channel emote load crashed (channel=" + twitchUserId + ")", t);
                } finally {
                    channelLoading.remove(twitchUserId);
                }
            }
        });
    }

    /** The current channel changed with its sets already cached, so no load will refresh the rows and the picker. */
    public static void refreshViews() {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                EmoteChatHook.requestRefresh();
                EmotePickerBridge.refreshSections();
            }
        });
    }

    /** A live 7TV change of the channel's set ({@link SevenTvEvents}): lookups see it from the next message. */
    static void updateChannelSevenTv(final String twitchUserId, final List<String> removed, final Map<String, Emote> added) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ProviderSets sets = channelCache.get(twitchUserId);
                    if (sets == null) {
                        return;
                    }
                    Map<String, Emote> stv = new LinkedHashMap<>(sets.stv);
                    stv.keySet().removeAll(removed);
                    stv.putAll(added);
                    channelCache.put(twitchUserId, new ProviderSets(sets.bttv, sets.ffz, stv, sets.fetchedAt, sets.partial));
                    EmoteChatHook.requestRefresh();
                    EmotePickerBridge.refreshSections();
                } catch (Throwable t) {
                    Log.w(TAG, "7TV update failed", t);
                }
            }
        });
    }

    /**
     * Size (bytes) of the on-disk HTTP cache of emote image responses, which
     * (unlike the in-memory maps) survives app restarts, or {@code -1} if
     * unavailable. For the cache-stats UI.
     */
    public static long diskCacheBytes() {
        return EmoteHttp.diskCacheBytes();
    }

    /**
     * Full reset: drops all cached emote metadata (global + every channel),
     * every decoded image in memory, and the on-disk image cache, then
     * immediately re-requests the global set and (if known) the current
     * channel's set. Because {@link EmoteHttp#getJson} cache-busts every list
     * request, this genuinely re-pulls the current sets from the origin rather
     * than a stale cached copy. Finishes by nudging a chat re-injection pass.
     */
    public static void resetAll(String currentChannelId) {
        global = ProviderSets.EMPTY;
        channelCache.clear();
        EmoteImageCache.clear();
        EmoteHttp.clearDiskCache();
        // Re-pick the fastest proxy host too -- the current one may be exactly
        // what the user is resetting because it went slow/unreachable.
        EmoteProxy.reset();
        Log.i(TAG, "emote cache full reset (manual)");
        ensureGlobalLoaded();
        if (currentChannelId != null && !currentChannelId.isEmpty()) {
            ensureChannelLoaded(currentChannelId);
        }
        EmoteChatHook.requestRefresh();
    }

    /**
     * Fetches the sets again, the images stay: an emote added or removed since. Lookups keep the old sets
     * until the new ones land.
     */
    public static void refresh(String twitchUserId) {
        ProviderSets g = global;
        global = new ProviderSets(g.bttv, g.ffz, g.stv, -1, g.partial);
        if (twitchUserId != null) {
            ProviderSets sets = channelCache.get(twitchUserId);
            if (sets != null) {
                channelCache.put(twitchUserId, new ProviderSets(sets.bttv, sets.ffz, sets.stv, -1, sets.partial));
            }
        }
        Log.i(TAG, "emote sets refresh for channel=" + twitchUserId + " (manual)");
        ensureGlobalLoaded();
        ensureChannelLoaded(twitchUserId);
    }

    /**
     * Per-channel reset for the emote picker's reset button: drops the given
     * channel's cached metadata and re-requests it (plus global). Emote images
     * are keyed by URL, not by channel, so both image layers (memory + disk)
     * are cleared wholesale to guarantee this channel's emotes actually
     * re-download. Ends with a chat re-injection pass.
     */
    public static void reloadChannel(String twitchUserId) {
        if (twitchUserId != null && !twitchUserId.isEmpty()) {
            channelCache.remove(twitchUserId);
        }
        EmoteImageCache.clear();
        EmoteHttp.clearDiskCache();
        EmoteProxy.reset();
        Log.i(TAG, "emote cache reset for channel=" + twitchUserId + " (manual)");
        ensureGlobalLoaded();
        if (twitchUserId != null && !twitchUserId.isEmpty()) {
            ensureChannelLoaded(twitchUserId);
        }
        EmoteChatHook.requestRefresh();
    }

    /**
     * Diagnostics/tests only: kick a background load of the global emote sets
     * without needing a channel id (the normal trigger,
     * {@link #ensureChannelLoaded}, requires one). Idempotent -- a no-op if the
     * global set is already loaded and fresh. Used by
     * {@code tv.retwitch.mod.EmoteRenderProbe} to exercise the emote
     * decode/draw path on a login-reachable screen.
     */
    public static void preloadGlobal() {
        ensureGlobalLoaded();
    }

    private static void ensureGlobalLoaded() {
        if (!global.isStale(GLOBAL_TTL_MS)) {
            return;
        }
        if (!globalLoading.compareAndSet(false, true)) {
            return;
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    global = fetchAll(null, global);
                    if (global.partial) {
                        retryLater();
                    }
                    EmoteChatHook.requestRefresh();
                    EmotePickerBridge.refreshSections();
                } catch (Throwable t) {
                    // Daemon background thread -- never let a throwable escape
                    // (see ensureChannelLoaded).
                    Log.w(TAG, "global emote load crashed", t);
                    CrashReporter.report(TAG, "global emote load crashed", t);
                } finally {
                    globalLoading.set(false);
                }
            }
        });
    }

    /**
     * Loads are only kicked on chat connect, so a failed provider would otherwise wait for the
     * next channel join. Retries the current channel (and with it the global sets).
     * shortcut: no backoff, offline it polls every ~30 s while a channel is current; add one if it shows in battery.
     */
    private static void retryLater() {
        RETRY_HANDLER.postDelayed(new Runnable() {
            @Override
            public void run() {
                ensureChannelLoaded(tv.retwitch.mod.ChannelTracker.getCurrentChannelId());
            }
        }, RETRY_MS + 1000);
    }

    /**
     * @param word         a single chat word (already split on
     *                     whitespace by the caller)
     * @param twitchUserId the current channel's broadcaster id, or
     *                     {@code null} to only consult global sets
     * @return the matching emote respecting current provider toggles, or
     *         {@code null} if there's no match (including while the
     *         relevant set is still loading for the first time)
     */
    public static Emote lookup(String word, String twitchUserId) {
        if (word == null || word.isEmpty()) {
            return null;
        }
        if (twitchUserId != null) {
            ProviderSets channelSets = channelCache.get(twitchUserId);
            if (channelSets != null) {
                Emote emote = channelSets.find(word);
                if (emote != null) {
                    return emote;
                }
            }
        }
        return globalSets().find(word);
    }

    /** The global sets, or none with {@link Toggles#NO_GLOBAL_EMOTES}. */
    private static ProviderSets globalSets() {
        return Toggles.on(Toggles.NO_GLOBAL_EMOTES) ? ProviderSets.EMPTY : global;
    }

    /**
     * Enumerates cached third-party emotes (channel-specific + global,
     * respecting live provider toggles) whose code matches {@code query},
     * for use by the native autocomplete/picker bridges (see
     * {@code EmoteAutocompleteBridge}/{@code EmotePickerBridge}). Never
     * triggers a fetch itself -- only reads whatever
     * {@link #ensureChannelLoaded} has already populated, exactly like
     * {@link #lookup}.
     *
     * <p>Mirrors Twitch's own two-pass ranking in {@code Ldca.g()}: exact
     * prefix matches first (case-insensitive), then substring matches,
     * each in insertion order. Channel-specific emotes are listed ahead of
     * global ones with the same rank, and a token already added from the
     * channel set is never duplicated from the global one.
     *
     * @param query        raw autocomplete text already stripped of the
     *                     leading ':' by the caller; empty matches everything
     * @param twitchUserId current channel's broadcaster id, or {@code null}
     * @param limit        stop collecting once this many matches are found
     */
    public static List<Emote> findMatches(String query, String twitchUserId, int limit) {
        if (!EmoteSettings.isAnyProviderEnabled() || limit <= 0) {
            return Collections.emptyList();
        }
        String needle = (query == null ? "" : query).toLowerCase(Locale.ROOT);

        LinkedHashMap<String, Emote> merged = new LinkedHashMap<>();
        if (twitchUserId != null) {
            ProviderSets channelSets = channelCache.get(twitchUserId);
            if (channelSets != null) {
                channelSets.collectEnabledInto(merged);
            }
        }
        globalSets().collectEnabledInto(merged);

        List<Emote> startsWith = new ArrayList<>();
        List<Emote> contains = new ArrayList<>();
        for (Map.Entry<String, Emote> entry : merged.entrySet()) {
            String lower = entry.getKey().toLowerCase(Locale.ROOT);
            if (lower.startsWith(needle)) {
                startsWith.add(entry.getValue());
            } else if (!needle.isEmpty() && lower.contains(needle)) {
                contains.add(entry.getValue());
            }
            if (startsWith.size() >= limit) {
                break;
            }
        }

        List<Emote> result = new ArrayList<>(limit);
        for (Emote e : startsWith) {
            if (result.size() >= limit) return result;
            result.add(e);
        }
        for (Emote e : contains) {
            if (result.size() >= limit) return result;
            result.add(e);
        }
        return result;
    }

    /** One (provider, scope) group of cached emotes with a human-readable title, e.g. "7TV Channel". */
    public static final class EmoteGroup {
        public final String title;
        public final List<Emote> emotes;

        EmoteGroup(String title, List<Emote> emotes) {
            this.title = title;
            this.emotes = emotes;
        }
    }

    /**
     * Splits cached third-party emotes for {@code twitchUserId} into one
     * group per (provider, scope) pair that is both enabled and
     * non-empty -- e.g. "7TV Channel", "BTTV Global" -- so the emote
     * picker can give each its own titled section (see
     * {@code EmotePickerBridge}) instead of lumping everything into one
     * combined, genericly-titled set. Channel groups are listed ahead of
     * global ones, matching {@link #lookup}'s precedence. Titles are
     * plain ASCII on purpose -- see {@code EmoteModelReflect.buildOwnerEmoteSet}'s
     * doc for why. Never triggers a fetch itself, same as
     * {@link #findMatches}/{@link #lookup}.
     */
    public static List<EmoteGroup> groupedMatches(String twitchUserId, int limitPerGroup) {
        List<EmoteGroup> groups = new ArrayList<>(6);
        if (!EmoteSettings.isAnyProviderEnabled() || limitPerGroup <= 0) {
            return groups;
        }
        ProviderSets channelSets = twitchUserId != null ? channelCache.get(twitchUserId) : null;
        if (channelSets != null) {
            addGroup(groups, "7TV Channel", channelSets.stv, limitPerGroup, EmoteSettings.isSevenTvEnabled());
            addGroup(groups, "BTTV Channel", channelSets.bttv, limitPerGroup, EmoteSettings.isBttvEnabled());
            addGroup(groups, "FFZ Channel", channelSets.ffz, limitPerGroup, EmoteSettings.isFfzEnabled());
        }
        ProviderSets globalSets = globalSets();
        addGroup(groups, "7TV Global", globalSets.stv, limitPerGroup, EmoteSettings.isSevenTvEnabled());
        addGroup(groups, "BTTV Global", globalSets.bttv, limitPerGroup, EmoteSettings.isBttvEnabled());
        addGroup(groups, "FFZ Global", globalSets.ffz, limitPerGroup, EmoteSettings.isFfzEnabled());
        return groups;
    }

    private static void addGroup(List<EmoteGroup> out, String title, Map<String, Emote> source, int limit, boolean enabled) {
        if (!enabled || source.isEmpty()) {
            return;
        }
        List<Emote> emotes = new ArrayList<>(Math.min(source.size(), limit));
        for (Emote e : source.values()) {
            if (emotes.size() >= limit) {
                break;
            }
            emotes.add(e);
        }
        if (!emotes.isEmpty()) {
            out.add(new EmoteGroup(title, emotes));
        }
    }

    /** @param prev the previous result for this scope: a provider that fails now keeps its old set. */
    private static ProviderSets fetchAll(String twitchUserIdOrNull, ProviderSets prev) {
        // Pick the fastest reachable ReYohoho proxy host before any request
        // goes out (no-op when proxying is off, cheap when already resolved).
        // Runs here on the emote executor thread, so the blocking probe never
        // touches the main thread.
        EmoteProxy.ensureResolved();
        String scope = twitchUserIdOrNull == null ? "global" : ("channel=" + twitchUserIdOrNull);
        Map<String, Emote> bttv;
        Map<String, Emote> ffz;
        Map<String, Emote> stv;
        if (twitchUserIdOrNull == null) {
            bttv = safeFetch("BTTV", scope, new Fetch() {
                public Map<String, Emote> run() throws Exception { return BttvClient.fetchGlobal(); }
            });
            ffz = safeFetch("FFZ", scope, new Fetch() {
                public Map<String, Emote> run() throws Exception { return BttvClient.fetchFfzGlobal(); }
            });
            stv = safeFetch("7TV", scope, new Fetch() {
                public Map<String, Emote> run() throws Exception { return SevenTvClient.fetchGlobal(); }
            });
        } else {
            final String channelId = twitchUserIdOrNull;
            bttv = safeFetch("BTTV", scope, new Fetch() {
                public Map<String, Emote> run() throws Exception { return BttvClient.fetchChannel(channelId); }
            });
            ffz = safeFetch("FFZ", scope, new Fetch() {
                public Map<String, Emote> run() throws Exception { return BttvClient.fetchFfzChannel(channelId); }
            });
            stv = safeFetch("7TV", scope, new Fetch() {
                public Map<String, Emote> run() throws Exception { return SevenTvClient.fetchChannel(channelId); }
            });
        }
        boolean failed = bttv == null || ffz == null || stv == null;
        if (bttv == null) bttv = prev.bttv;
        if (ffz == null) ffz = prev.ffz;
        if (stv == null) stv = prev.stv;
        if (EmoteSettings.isHomiesEnabled()) {
            final String channelId = twitchUserIdOrNull;
            Map<String, Emote> homies = safeFetch("Homies", scope, new Fetch() {
                public Map<String, Emote> run() throws Exception { return SevenTvClient.fetchHomies(channelId); }
            });
            // 7TV-hosted, so they ride in the 7TV sets; real 7TV emotes win name clashes.
            Map<String, Emote> merged = new LinkedHashMap<>(stv);
            for (Map.Entry<String, Emote> e : (homies != null ? homies : Collections.<String, Emote>emptyMap()).entrySet()) {
                if (!merged.containsKey(e.getKey())) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
            stv = merged;
        }
        if (twitchUserIdOrNull == null) {
            for (Map<String, Emote> set : Arrays.asList(bttv, ffz, stv)) {
                for (Emote e : set.values()) {
                    e.global = true;
                }
            }
        }
        Log.i(TAG, "fetched emotes (" + scope + "): bttv=" + bttv.size() + " ffz=" + ffz.size() + " 7tv=" + stv.size()
                + (failed ? " (partial, retry in " + RETRY_MS / 1000 + " s)" : ""));
        return new ProviderSets(bttv, ffz, stv, System.currentTimeMillis(), failed);
    }

    private interface Fetch {
        Map<String, Emote> run() throws Exception;
    }

    private static Map<String, Emote> safeFetch(String providerName, String scope, Fetch fetch) {
        int switches = EmoteProxy.switches();
        try {
            return fetch.run();
        } catch (Throwable e) {
            // Catch Throwable, not just Exception: a malformed/blocked-network
            // response can surface as an Error (e.g. OOM while inflating a huge
            // or bogus body), and this runs on a background thread whose
            // uncaught throwables would otherwise crash the whole app.
            Log.w(TAG, providerName + " fetch failed (" + scope + "): " + e);
        }
        if (EmoteProxy.switches() == switches) {
            return null; // not the proxy host's fault (or direct): the 30 s retry handles it
        }
        // The host was dropped: retry right away through a freshly probed one.
        EmoteProxy.ensureResolved();
        try {
            return fetch.run();
        } catch (Throwable e) {
            Log.w(TAG, providerName + " fetch failed again (" + scope + "): " + e);
            return null;
        }
    }

    /** One channel's (or the global) fetch result, split by provider so {@link #find} can respect live toggles. */
    private static final class ProviderSets {
        static final ProviderSets EMPTY =
                new ProviderSets(Collections.<String, Emote>emptyMap(), Collections.<String, Emote>emptyMap(),
                        Collections.<String, Emote>emptyMap(), -1, false);

        final Map<String, Emote> bttv;
        final Map<String, Emote> ffz;
        final Map<String, Emote> stv;
        final long fetchedAt;
        /** Some provider failed this round (its set is the previous one or empty). */
        final boolean partial;

        ProviderSets(Map<String, Emote> bttv, Map<String, Emote> ffz, Map<String, Emote> stv, long fetchedAt,
                boolean partial) {
            this.bttv = bttv;
            this.ffz = ffz;
            this.stv = stv;
            this.fetchedAt = fetchedAt;
            this.partial = partial;
        }

        boolean isStale(long ttlMs) {
            return fetchedAt < 0 || System.currentTimeMillis() - fetchedAt > (partial ? Math.min(ttlMs, RETRY_MS) : ttlMs);
        }

        Emote find(String word) {
            // 7TV/BTTV/FFZ codes can collide; precedence here is arbitrary
            // but fixed, matching common client behavior (7TV wins ties).
            if (EmoteSettings.isSevenTvEnabled()) {
                Emote e = stv.get(word);
                if (e != null) {
                    return e;
                }
            }
            if (EmoteSettings.isBttvEnabled()) {
                Emote e = bttv.get(word);
                if (e != null) {
                    return e;
                }
            }
            if (EmoteSettings.isFfzEnabled()) {
                Emote e = ffz.get(word);
                if (e != null) {
                    return e;
                }
            }
            return null;
        }

        /** Adds every enabled-provider entry not already present in {@code out} (first writer wins). */
        void collectEnabledInto(Map<String, Emote> out) {
            if (EmoteSettings.isSevenTvEnabled()) {
                for (Map.Entry<String, Emote> e : stv.entrySet()) {
                    if (!out.containsKey(e.getKey())) out.put(e.getKey(), e.getValue());
                }
            }
            if (EmoteSettings.isBttvEnabled()) {
                for (Map.Entry<String, Emote> e : bttv.entrySet()) {
                    if (!out.containsKey(e.getKey())) out.put(e.getKey(), e.getValue());
                }
            }
            if (EmoteSettings.isFfzEnabled()) {
                for (Map.Entry<String, Emote> e : ffz.entrySet()) {
                    if (!out.containsKey(e.getKey())) out.put(e.getKey(), e.getValue());
                }
            }
        }
    }
}
