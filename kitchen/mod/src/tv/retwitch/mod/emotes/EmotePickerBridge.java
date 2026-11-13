package tv.retwitch.mod.emotes;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import tv.retwitch.mod.CrashReporter;
import tv.retwitch.mod.ChannelTracker;

/**
 * Injected into the small delegate method that forwards Twitch's own
 * emote-set -> emote-picker-UI-section transform lambda's result
 * (historically {@code Ls5e.f(Object,Object,Object): Object}, which just
 * does {@code return (Pair) zn7Instance.n(p1, p2, p3)}) -- see
 * {@code EmotePickerPatch.kt}. Builds one titled
 * {@code OwnerEmoteSet}-equivalent (see {@link EmoteModelReflect}) per
 * non-empty cached third-party (provider, scope) group for the current
 * channel, feeds each through the exact same UI-model-building call
 * ({@code Lqke.X2(EmoteSet, Integer, ...): PickerUiModel}) Twitch's own
 * code already uses for its native sections, and appends every result to
 * the same mutable section list Twitch's own code returns.
 *
 * <p><b>Why here and not inside the transform itself:</b> that transform
 * (historically {@code Lzn7.n()}) is a single huge method R8 merges dozens
 * of unrelated Compose UI lambdas into behind one packed-switch. Splicing
 * bytecode into the middle of it once caused
 * {@code VerifyError: target dex pc ... is not at instruction start} --
 * some packed-switch-payload alignment invariant broke -- which blanked
 * the *entire* picker screen, not just this section. The tiny wrapper method
 * this class is actually injected into has no switch of its own: it just
 * calls {@code n(...)}, gets back a {@code Pair}, and returns it
 * unmodified, so there is nothing switch-payload-shaped nearby to corrupt.
 *
 * <p><b>Why reflection instead of a direct smali call to X2, here:</b>
 * this method doesn't have Twitch's presenter instance, the selected-id
 * {@code Integer}, or the {@code Lske}-equivalent argument sitting in any
 * of its own registers -- those are all captured *inside* the {@code zn7}
 * lambda instance's own closure fields (set once, at its construction, by
 * the presenter). Rather than re-deriving three exact smali type
 * descriptors and hand-writing a virtual call with matching register
 * pressure in an already fragile spot, this class is just handed the raw
 * {@code zn7} instance and the raw {@code Pair} result as opaque
 * {@code Object}s (two registers already alive at the injection point,
 * nothing new needed) and does the field-reads + method dispatch itself,
 * entirely in ordinary, easy-to-get-right Java reflection.
 *
 * <p>Not query-filtered (unlike {@link EmoteAutocompleteBridge}) -- the
 * picker section lists every cached third-party emote at once, same as
 * Twitch's own "Twitch Emotes"/"Unlocked" sections.
 */
public final class EmotePickerBridge {

    // Not a real cap on how many emotes can be shown -- a single 7TV
    // channel alone can have 1000+ emotes, plus BTTV/FFZ/global sets on
    // top of that, all legitimately shown at once (same as Twitch's own
    // "Twitch Emotes" section, which isn't capped either). This is only
    // a sanity ceiling against a pathological/malformed provider
    // response, comfortably above any real-world combined total.
    private static final int MAX_PICKER_EMOTES = 10_000;
    private static final String SET_ID = "retwitch";
    private static final String TAG = "RTEmotes";

    private EmotePickerBridge() {
    }

    /**
     * Builds one picker section per non-empty (provider, scope) group (see
     * {@link EmoteCache#groupedMatches}) -- "7TV Channel", "BTTV Global", ... --
     * as a fake {@code OwnerEmoteSet} (its owner's plain-ASCII display name is
     * shown verbatim as the header) run through the picker presenter's own
     * "emote set -> section UI model" method, so the sections look and behave
     * like Twitch's.
     *
     * <p>Twitch 31.x hook ({@code EmotePickerPatch}): after every
     * set-to-section call of the transform loop the presenter and its
     * selected-channel / config arguments are {@link #capture}d, and right after
     * the loop's result {@code Pair} is built, {@link #appendToList} adds our
     * sections to the same list. Class/method names come from
     * {@link #configure} (patch-time constants, injected into
     * {@code Application.onCreate}).
     */
    private static volatile String[] config;
    private static volatile Object[] context;

    /** {@code "<EmoteOwner>|<OwnerEmoteSet>|<EmoteModel>|<setToSectionMethod>"}. */
    public static void configure(String packed) {
        String[] parts = packed != null ? packed.split("\\|", -1) : null;
        config = parts != null && parts.length == 4 ? parts : null;
    }

    /** The Favorites section of the last build, and what's needed to rebuild it. */
    private static volatile Object favoritesModel;
    private static volatile Object[] lastContext;
    private static volatile Method lastX2;
    /** Our section list (the transform's) and the shown list built from it (see {@link #favoritesFirst}). */
    private static volatile List<Object> lastSections;
    private static volatile List<Object> lastShown;
    /** Our provider sections (not Favorites) in those lists, swapped by {@link #refreshSections}. */
    private static volatile List<Object> lastGroupModels = new ArrayList<>();

    /** Injected after each set-to-section call; just remembers its arguments. */
    public static void capture(Object presenter, Object selectedId, Object ske) {
        context = new Object[]{presenter, selectedId, ske};
    }

    // Called from injected bytecode inside Twitch's picker transform with no
    // try/catch around it: an exception here would blank the whole picker.
    public static void appendToList(Object list) {
        Object[] ctx = context;
        context = null; // never reuse a previous picker's presenter
        try {
            String[] cfg = config;
            if (ctx == null || cfg == null || !(list instanceof List) || !EmoteSettings.isAnyProviderEnabled()) {
                return;
            }
            appendGroups(ctx[0], ctx[1], ctx[2], (List<Object>) list, cfg[0], cfg[1], cfg[2], cfg[3]);
        } catch (Throwable t) {
            Log.w(TAG, "picker appendToList() failed", t);
            CrashReporter.report(TAG, "picker appendToList() failed", t);
        }
    }

    @SuppressWarnings("unchecked")
    private static void appendGroups(Object presenter, Object selectedId, Object ske, List<Object> sections,
            String ownerClassName, String ownerSetClassName, String emoteModelClassName, String x2MethodName)
            throws ReflectiveOperationException {
        if (presenter == null) {
            return;
        }
        String channelId = ChannelTracker.getCurrentChannelId();
        List<EmoteCache.EmoteGroup> groups = EmoteCache.groupedMatches(channelId, MAX_PICKER_EMOTES);
        EmoteCache.EmoteGroup favorites = EmoteFavorites.group(channelId);
        favoritesModel = null;
        if (favorites != null) {
            groups.add(0, favorites);
        }
        Method x2 = null;
        List<Object> groupModels = new ArrayList<>();
        for (EmoteCache.EmoteGroup group : groups) {
            Object emoteSet = buildOwnerEmoteSetFor(ownerClassName, ownerSetClassName, emoteModelClassName, group);
            if (emoteSet == null) {
                continue;
            }
            if (x2 == null) {
                x2 = findX2Method(presenter.getClass(), x2MethodName, emoteSet, selectedId);
                if (x2 == null) {
                    return;
                }
            }
            Object uiModel = x2.invoke(presenter, emoteSet, selectedId, ske);
            if (uiModel != null && group == favorites) {
                sections.add(0, uiModel);
                favoritesModel = uiModel;
            } else if (uiModel != null) {
                sections.add(uiModel);
                groupModels.add(uiModel);
            }
        }
        if (x2 != null) {
            lastGroupModels = groupModels;
            lastContext = new Object[]{presenter, selectedId, ske};
            lastX2 = x2;
            lastSections = sections;
        }
    }

    /**
     * Injected where the picker puts "frequently used" and the channel's own section
     * ahead of the transform's list (31.8.0 {@code ked}): moves Favorites to the very top.
     * Never throws.
     */
    @SuppressWarnings("unchecked")
    public static void favoritesFirst(Object list) {
        try {
            if (!(list instanceof List)) {
                return;
            }
            List<Object> shown = (List<Object>) list;
            lastShown = shown;
            Object favorites = favoritesModel;
            if (favorites != null && remove(shown, favorites)) {
                shown.add(0, favorites);
            }
        } catch (Throwable t) {
            Log.w(TAG, "picker favoritesFirst() failed", t);
            CrashReporter.report(TAG, "picker favoritesFirst() failed", t);
        }
    }

    /**
     * After a favourite was toggled: rebuilds the Favorites section and swaps it into
     * the last built lists in place, so the picker shows it the next time it renders.
     */
    static void refreshFavorites() {
        try {
            Object[] ctx = lastContext;
            Method x2 = lastX2;
            String[] cfg = config;
            if (ctx == null || x2 == null || cfg == null) {
                return;
            }
            EmoteCache.EmoteGroup group = EmoteFavorites.group(ChannelTracker.getCurrentChannelId());
            Object set = group == null ? null : buildOwnerEmoteSetFor(cfg[0], cfg[1], cfg[2], group);
            Object model = set == null ? null : x2.invoke(ctx[0], set, ctx[1], ctx[2]);
            Object old = favoritesModel;
            favoritesModel = model;
            for (List<Object> list : new List[]{lastSections, lastShown}) {
                if (list == null) {
                    continue;
                }
                if (old != null) {
                    remove(list, old);
                }
                if (model != null) {
                    list.add(0, model);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "picker refreshFavorites() failed", t);
            CrashReporter.report(TAG, "picker refreshFavorites() failed", t);
        }
    }

    /**
     * A set finished loading (EmoteCache executor thread): the picker transform is cached per
     * session, so a picker built before that kept no 7TV/BTTV/FFZ sections until the next
     * channel ("emotes not loaded yet"). Rebuilds our sections here and swaps them into the
     * last built lists on the main thread; shown the next time the picker renders.
     */
    static void refreshSections() {
        try {
            Object[] ctx = lastContext;
            Method x2 = lastX2;
            String[] cfg = config;
            if (ctx == null || x2 == null || cfg == null) {
                return;
            }
            final List<Object> models = new ArrayList<>();
            for (EmoteCache.EmoteGroup group
                    : EmoteCache.groupedMatches(ChannelTracker.getCurrentChannelId(), MAX_PICKER_EMOTES)) {
                Object set = buildOwnerEmoteSetFor(cfg[0], cfg[1], cfg[2], group);
                Object model = set == null ? null : x2.invoke(ctx[0], set, ctx[1], ctx[2]);
                if (model != null) {
                    models.add(model);
                }
            }
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        List<Object> old = lastGroupModels;
                        lastGroupModels = models;
                        for (List<Object> list : new List[]{lastSections, lastShown}) {
                            if (list == null) {
                                continue;
                            }
                            for (Object m : old) {
                                remove(list, m);
                            }
                            list.addAll(models);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "picker refreshSections() swap failed", t);
                        CrashReporter.report(TAG, "picker refreshSections() swap failed", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "picker refreshSections() failed", t);
            CrashReporter.report(TAG, "picker refreshSections() failed", t);
        }
    }

    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Removes {@code item} by identity (section models are data classes). */
    private static boolean remove(List<Object> list, Object item) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == item) {
                list.remove(i);
                return true;
            }
        }
        return false;
    }

    private static Object buildOwnerEmoteSetFor(String ownerClassName, String ownerSetClassName,
            String emoteModelClassName, EmoteCache.EmoteGroup group) {
        List<Object> models = new ArrayList<>(group.emotes.size());
        for (Emote emote : group.emotes) {
            Object model = EmoteModelReflect.buildEmoteModel(emoteModelClassName, emote);
            if (model != null) {
                models.add(model);
            }
        }
        if (models.isEmpty()) {
            return null;
        }
        return EmoteModelReflect.buildOwnerEmoteSet(ownerClassName, ownerSetClassName, SET_ID + ":" + group.title, group.title, models);
    }

    /** The public 3-arg method named {@code name} whose first two declared parameter types accept {@code emoteSet}/{@code selectedId} -- disambiguates same-named overloads on heavily R8-merged presenter classes. */
    private static Method findX2Method(Class<?> presenterClass, String name, Object emoteSet, Object selectedId) {
        for (Method m : presenterClass.getMethods()) {
            if (!m.getName().equals(name)) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 3 && p[0].isInstance(emoteSet) && (selectedId == null || p[1].isInstance(selectedId))) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }
}
