package tv.retwitch.mod;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import tv.retwitch.mod.emotes.EmoteCache;
import tv.retwitch.mod.emotes.EmoteSettings;
import tv.retwitch.mod.emotes.PickerInput;

/**
 * Injects per-provider (7TV/BTTV/FFZ) quick-jump buttons into the bottom
 * button bar of Twitch's native emote picker, next to the native category
 * buttons (keyboard/frequent/channel/all/delete). Tapping one scrolls the
 * picker's RecyclerView to the first ReTwitch section of that provider
 * ("7TV Channel" before "7TV Global", matching {@code EmoteCache}'s group
 * order) -- the sections themselves are appended by
 * {@code EmotePickerBridge}.
 *
 * <p>The picker root is <b>not</b> Compose: it inflates
 * {@code res/layout/emote_picker.xml}, an
 * {@code androidx.constraintlayout.widget.ConstraintLayout} whose bottom bar
 * buttons are direct children constrained to {@code @id/bottom_bar_background}.
 * {@code PickerPatch.kt} injects a call to {@link #onContainerCreated(View)}
 * at the entry of the (switch-free) helper that wires up that
 * freshly-inflated root. We deliberately do <i>not</i> touch the big
 * {@code packed-switch} factory method that does the inflation ({@code
 * bv.a()} in one decode) -- splicing into a switch-payload-bearing method
 * once caused a runtime {@code VerifyError} that blanked the whole picker
 * (see the incident note in {@code EmotePickerBridge}).
 *
 * <p><b>Obfuscation-safety strategy</b> (nothing below is pinned to one
 * build's R8 names):
 * <ul>
 * <li>View lookups go by resource <i>entry name</i> via
 *     {@link Resources#getIdentifier} (entry names survive R8; numeric ids
 *     do not).</li>
 * <li>Placement inside the ConstraintLayout bar can't use
 *     {@code ConstraintLayout.LayoutParams} directly (that class -- {@code
 *     id9} in one decode -- and its constraint fields are renamed).
 *     Instead the last button of the native left-hand chain is found
 *     purely by constraint <i>topology</i> (see {@link #findChainTail}),
 *     its params object is <i>cloned</i> (same runtime class, public
 *     {@code (ViewGroup.LayoutParams)} constructor, every primitive/String
 *     field copied), and the clone's stale START reference is retargeted
 *     to the anchor itself by rewriting int fields matched by <i>value</i>
 *     -- giving a genuine "startToEnd = anchor" constraint that continues
 *     the native chain. No button names, no field names, no coordinate
 *     math.</li>
 * <li>Scrolling can't use {@code smoothScrollToPosition} etc. (renamed).
 *     Instead we resolve, at runtime, the abstract {@code (int) -> void}
 *     method declared on the layout manager's base class (that is
 *     {@code RecyclerView.LayoutManager.scrollToPosition} -- {@code
 *     d.H0(I)V} in one decode) purely by its structural signature.
 *     {@code getAdapter()}/{@code getLayoutManager()} keep their real names
 *     in current builds (verified in smali) and are the only two
 *     name-based reflective calls here.</li>
 * <li>Top-alignment can't use {@code scrollToPositionWithOffset} (renamed to
 *     one of three indistinguishable public {@code (int,int) -> void}
 *     siblings on {@code LinearLayoutManager}). Instead we lean on a side
 *     effect of {@code scrollToPosition} itself: it stamps the pending-offset
 *     field with {@code INVALID_OFFSET} ({@link Integer#MIN_VALUE}) before its
 *     async layout runs, so we overwrite that field -- found by its unique
 *     MIN_VALUE sentinel -- with 0 (see {@link #forceTopAlignment}). The
 *     last sections can't reach the top and stop at the bottom edge.</li>
 * <li>The target adapter position is found by scanning the adapter's
 *     backing item list(s) for an element that (recursively) carries one of
 *     our own section titles ("7TV Channel", "BTTV Global", ...) -- strings
 *     that <i>we</i> injected via {@code EmotePickerBridge}, so they can
 *     never be renamed out from under us.</li>
 * </ul>
 *
 * <p>Everything is guarded and best-effort: this runs inside Twitch's own
 * view-creation path and click handlers, so it must never throw.
 */
public final class PickerHook {

    private static final String TAG = "RTPicker";
    private static final String BAR_TAG = "retwitch_picker_provider_bar";

    /**
     * Recursion budget for the title scan. The observed chain in one decode
     * is section (rje) -> header model (uie subclass) -> string-resource
     * wrapper -> CharSequence, but wrappers can nest one level deeper, so
     * leave headroom.
     */
    private static final int TITLE_SCAN_DEPTH = 5;

    /** How many leading elements of a nested List/Map are inspected by the title scan (headers sit at the front). */
    private static final int TITLE_SCAN_LIST_ELEMENTS = 4;

    private PickerHook() {
    }

    /**
     * @param root the freshly-inflated view handed to Twitch's picker
     *             view-delegate wiring. May be any view delegate's root (the
     *             hooked helper is shared) -- we self-filter to the emote
     *             picker by looking for its {@code @id/emote_picker} content
     *             frame and bail out on anything else.
     */
    public static void onContainerCreated(View root) {
        try {
            addProviderButtons(root);
        } catch (Throwable t) {
            Log.w(TAG, "picker provider buttons add failed", t);
            CrashReporter.report(TAG, "picker provider buttons add failed", t);
        }
        PickerInput.attach(root);
    }

    private static void addProviderButtons(View root) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        Context ctx = root.getContext();
        Resources res = ctx.getResources();
        String pkg = ctx.getPackageName();

        // Presence of the content frame doubles as our "is this actually
        // the emote picker?" filter (other delegates routed through the
        // same shared helper won't have it).
        int contentId = res.getIdentifier("emote_picker", "id", pkg);
        int barId = res.getIdentifier("bottom_bar_background", "id", pkg);
        if (contentId == 0 || barId == 0 || root.findViewById(contentId) == null) {
            return;
        }
        View barBackground = root.findViewById(barId);
        if (barBackground == null) {
            return;
        }

        ViewGroup rootGroup = (ViewGroup) root;
        // Idempotent: the hooked helper can run more than once per picker.
        if (rootGroup.findViewWithTag(BAR_TAG) != null) {
            return;
        }

        // The native left-hand button chain, found by constraint topology
        // rather than hardcoded button names (the set of buttons varies
        // between picker variants and Twitch versions). See findChain().
        BarChain barChain = findChain(rootGroup, barBackground);
        if (barChain == null || barChain.members.isEmpty()) {
            Log.w(TAG, "picker matched but no bar button chain found; skipping provider bar");
            return;
        }

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setTag(BAR_TAG);
        // ConstraintLayout children need a real id for its solver.
        bar.setId(View.generateViewId());

        // One button per enabled provider; whether that provider actually
        // has emotes for the current channel is only known (and re-checked)
        // at click time, since fetching is async and channel-dependent.
        if (EmoteSettings.isSevenTvEnabled()) {
            bar.addView(makeProviderButton(root, "7TV"), buttonParams());
        }
        if (EmoteSettings.isBttvEnabled()) {
            bar.addView(makeProviderButton(root, "BTTV"), buttonParams());
        }
        if (EmoteSettings.isFfzEnabled()) {
            bar.addView(makeProviderButton(root, "FFZ"), buttonParams());
        }
        if (bar.getChildCount() == 0) {
            return;
        }
        // A reset button after the provider jump buttons: clears this
        // channel's emote cache (metadata + images) and re-requests the API.
        bar.addView(makeCacheResetButton(root), buttonParams());

        // Place our bar right after the native left-hand button chain, so the
        // provider buttons read as extra native buttons. We clone the chain's
        // tail (e.g. all_emotes_button) -- inheriting its vertical constraints
        // to the bar background -- then retarget the clone's START reference
        // (which the tail points at its own predecessor) to the tail itself.
        // That anchors our start to the tail's END, i.e. our bar flows
        // immediately after it. The native views are never touched.
        //
        // A tail that goes GONE per-channel simply collapses to a point at the
        // last-visible button's end, and our bar follows it there -- exactly
        // where we want to be. (This only became viable once cloneSiblingParams
        // stopped sharing the tail's ConstraintWidget via the copy
        // constructor; sharing it made both views resolve to identical bounds.)
        View tail = barChain.tail != null ? barChain.tail : barChain.members.get(0);

        List<Integer> tailPredecessors = new ArrayList<>();
        for (Integer value : intFieldValues(tail.getLayoutParams())) {
            if (value != tail.getId() && barChain.memberIds().contains(value)) {
                tailPredecessors.add(value);
            }
        }

        ViewGroup.LayoutParams lp = cloneSiblingParams(tail);
        if (lp == null) {
            Log.w(TAG, "could not clone tail layout params; skipping provider bar");
            return;
        }
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        int retargeted = tailPredecessors.isEmpty()
                ? 0
                : retargetIntFields(lp, tailPredecessors, tail.getId());
        rootGroup.addView(bar, lp);

        Log.i(TAG, "provider bar appended after " + resourceName(res, tail.getId())
                + " (chain=" + barChain.members.size() + " buttons=" + bar.getChildCount()
                + " retargeted=" + retargeted + ")");

        // Safety net for ConstraintLayout's wrap_content quirks: if the bar
        // still resolves to zero width after layout, pin it to the summed
        // measured width of its children (which measure fine on their own).
        final ViewGroup fixBar = bar;
        bar.post(new Runnable() {
            @Override
            public void run() {
                if (fixBar.getWidth() > 0) {
                    return;
                }
                int sum = 0;
                for (int i = 0; i < fixBar.getChildCount(); i++) {
                    View c = fixBar.getChildAt(i);
                    c.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    int w = c.getMeasuredWidth();
                    sum += w > 0 ? w : c.getWidth();
                }
                if (sum > 0) {
                    ViewGroup.LayoutParams p = fixBar.getLayoutParams();
                    p.width = sum;
                    fixBar.setLayoutParams(p);
                }
            }
        });
    }

    /** Resource entry name for logging, or the raw id if unresolvable. */
    private static String resourceName(Resources res, int id) {
        try {
            return res.getResourceEntryName(id);
        } catch (Throwable t) {
            return "0x" + Integer.toHexString(id);
        }
    }

    /** Result of {@link #findChain}: the chain's topological tail plus every button participating in the chain (delete/content frame excluded). */
    private static final class BarChain {
        final View tail;
        final List<View> members;

        BarChain(View tail, List<View> members) {
            this.tail = tail;
            this.members = members;
        }

        List<Integer> memberIds() {
            List<Integer> ids = new ArrayList<>(members.size());
            for (View member : members) {
                ids.add(member.getId());
            }
            return ids;
        }
    }

    /**
     * Finds the picker's native left-hand button chain purely from
     * constraint topology -- no button names, so it survives Twitch adding,
     * removing or renaming bar buttons:
     *
     * <ol>
     * <li><b>Candidates</b> are the root's direct children whose layout
     *     params reference the bar background's id (every bar button is
     *     constrained to it vertically). The content frame also matches
     *     (it's constrained *above* the bar) but is harmless -- it neither
     *     references nor is referenced by any button.</li>
     * <li><b>Chain members</b> are the candidates that reference, or are
     *     referenced by, another candidate (keyboard &lt;- frequent &lt;-
     *     channel &lt;- all...). Isolated candidates are excluded -- which
     *     drops both the content frame <i>and</i> the delete button (it's
     *     anchored only to the bar). Excluding delete is deliberate: Twitch
     *     repositions it at runtime via translationX, so it's neither part
     *     of the visual chain nor a safe anchor.</li>
     * </ol>
     *
     * The caller picks the visually-last member as the anchor (see
     * {@code addProviderButtons}); the {@code tail} returned here is the
     * pure topological tail, kept only for logging/diagnostics.
     *
     * <p>Field values are read via the same name-free int-field scan used
     * for retargeting, so R8 renames of ConstraintLayout.LayoutParams don't
     * matter.
     */
    private static BarChain findChain(ViewGroup root, View barBackground) {
        int barId = barBackground.getId();
        List<View> candidates = new ArrayList<>();
        List<Set<Integer>> candidateRefs = new ArrayList<>();
        Set<Integer> candidateIds = new HashSet<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child == barBackground || child.getId() == View.NO_ID
                    || BAR_TAG.equals(child.getTag())) {
                continue;
            }
            ViewGroup.LayoutParams params = child.getLayoutParams();
            if (params == null) {
                continue;
            }
            Set<Integer> values = intFieldValues(params);
            if (!values.contains(barId)) {
                continue;
            }
            candidates.add(child);
            candidateRefs.add(values);
            candidateIds.add(child.getId());
        }

        // Ids that some *other* candidate points at.
        Set<Integer> referencedIds = new HashSet<>();
        for (int i = 0; i < candidates.size(); i++) {
            int ownId = candidates.get(i).getId();
            for (Integer value : candidateRefs.get(i)) {
                if (value != ownId && candidateIds.contains(value)) {
                    referencedIds.add(value);
                }
            }
        }

        // A member both-directions-connected: references another candidate,
        // or is referenced by one. Isolated candidates (delete, content
        // frame) fail both tests and are dropped.
        List<View> members = new ArrayList<>();
        View tail = null;
        for (int i = 0; i < candidates.size(); i++) {
            View candidate = candidates.get(i);
            int ownId = candidate.getId();
            boolean referencesOther = false;
            for (Integer value : candidateRefs.get(i)) {
                if (value != ownId && candidateIds.contains(value)) {
                    referencesOther = true;
                    break;
                }
            }
            boolean isReferenced = referencedIds.contains(ownId);
            if (!referencesOther && !isReferenced) {
                continue;
            }
            members.add(candidate);
            if (referencesOther && !isReferenced) {
                tail = candidate; // references someone, referenced by no-one
            }
        }
        return members.isEmpty() ? null : new BarChain(tail, members);
    }

    /** Every non-static int field value of {@code params} across its class hierarchy (the name-free dual of {@link #retargetIntFields}). */
    private static Set<Integer> intFieldValues(Object params) {
        Set<Integer> values = new HashSet<>();
        for (Class<?> k = params.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    values.add(f.getInt(params));
                } catch (Throwable ignored) {
                    // best-effort per-field
                }
            }
        }
        return values;
    }

    /**
     * Sets every non-static int field of {@code params} (across its class
     * hierarchy) whose current value is one of {@code fromIds} to
     * {@code toId}. Returns how many fields were rewritten.
     */
    private static int retargetIntFields(Object params, List<Integer> fromIds, int toId) {
        int count = 0;
        for (Class<?> k = params.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    if (fromIds.contains(f.getInt(params))) {
                        f.setInt(params, toId);
                        count++;
                    }
                } catch (Throwable ignored) {
                    // best-effort per-field
                }
            }
        }
        return count;
    }

    private static LinearLayout.LayoutParams buttonParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static View makeProviderButton(View pickerRoot, String provider) {
        Context ctx = pickerRoot.getContext();
        Resources res = ctx.getResources();
        String pkg = ctx.getPackageName();

        TextView button = new TextView(ctx);
        button.setText(provider);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription("Scroll to " + provider + " emotes");
        int pad = dimenPx(res, pkg, "emote_picker_button_padding", dp(ctx, 8));
        button.setPadding(pad, 0, pad, 0);
        button.setTextColor(themedColor(ctx, "text_alt_2", 0xFFADADB8));
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    scrollToProvider(pickerRoot, provider);
                } catch (Throwable t) {
                    Log.w(TAG, "scroll to " + provider + " failed", t);
                    CrashReporter.report(TAG, "scroll to " + provider + " failed", t);
                }
            }
        });
        return button;
    }

    /**
     * A small "↻" button placed after the provider jump buttons. Tapping it
     * asks to reset the emote cache for the channel the picker is open in
     * (metadata + images) and re-request it from the API -- useful when an
     * emote was added or removed upstream but a stale copy is still cached.
     */
    private static View makeCacheResetButton(View pickerRoot) {
        Context ctx = pickerRoot.getContext();
        Resources res = ctx.getResources();
        String pkg = ctx.getPackageName();

        TextView button = new TextView(ctx);
        button.setText("↻");
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(Strings.cacheResetButtonDesc());
        int pad = dimenPx(res, pkg, "emote_picker_button_padding", dp(ctx, 8));
        button.setPadding(pad, 0, pad, 0);
        button.setTextColor(themedColor(ctx, "text_alt_2", 0xFFADADB8));
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    showCacheResetDialog(v.getContext());
                } catch (Throwable t) {
                    Log.w(TAG, "cache reset dialog failed", t);
                    CrashReporter.report(TAG, "cache reset dialog failed", t);
                }
            }
        });
        return button;
    }

    private static void showCacheResetDialog(final Context ctx) {
        final String channelId = ChannelTracker.getCurrentChannelId();
        new AlertDialog.Builder(ctx)
                .setTitle(Strings.cacheResetTitle())
                .setMessage(Strings.cacheResetMessage())
                .setPositiveButton(Strings.reset(), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        EmoteCache.reloadChannel(channelId);
                        Toast.makeText(ctx, Strings.cacheResetToast(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton(Strings.emotesRefresh(), (dialog, which) -> {
                    EmoteCache.refresh(channelId);
                    Toast.makeText(ctx, Strings.emotesRefreshToast(), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(Strings.cancel(), null)
                .show();
    }

    // ------------------------------------------------------------------
    // Placement: clone a native sibling's ConstraintLayout params.
    // ------------------------------------------------------------------

    /**
     * Builds a new instance of {@code sibling}'s runtime LayoutParams class
     * (whatever R8 named it) via its public {@code (ViewGroup.LayoutParams)}
     * constructor, then copies every non-static primitive/String field --
     * which for ConstraintLayout params is exactly the constraint anchors,
     * margins and biases. Object-typed fields (the solver's per-child
     * ConstraintWidget, etc.) are deliberately left at the fresh instance's
     * defaults: sharing those across two views would corrupt layout.
     */
    private static ViewGroup.LayoutParams cloneSiblingParams(View sibling) {
        ViewGroup.LayoutParams src = sibling.getLayoutParams();
        if (src == null) {
            return null;
        }
        // Build via the (int,int) constructor, NOT the copy constructor
        // `newInstance(src)`. ConstraintLayout.LayoutParams' copy constructor
        // carries over the internal ConstraintWidget (the solver node); a clone
        // that shares it drives the SAME node as the sibling, so both views
        // resolve to identical bounds (confirmed on-device: our bar and the
        // cloned-from delete button both landed at 631..949 despite referencing
        // only bottom_bar_background and no cross-reference between them). The
        // (int,int) constructor gives a fresh widget; we then copy just the
        // primitive/String constraint fields (widget is an object field, so the
        // copy loop below never touches it).
        ViewGroup.LayoutParams clone;
        try {
            Constructor<? extends ViewGroup.LayoutParams> ctor =
                    src.getClass().getConstructor(int.class, int.class);
            clone = ctor.newInstance(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        } catch (Throwable t) {
            Log.w(TAG, "no (int,int) ctor on " + src.getClass().getName(), t);
            CrashReporter.report(TAG, "no (int,int) ctor on " + src.getClass().getName(), t);
            return null;
        }
        for (Class<?> k = src.getClass();
                k != null && ViewGroup.LayoutParams.class.isAssignableFrom(k);
                k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Class<?> type = f.getType();
                if (!type.isPrimitive() && type != String.class) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    f.set(clone, f.get(src));
                } catch (Throwable ignored) {
                    // best-effort per-field; missing one constraint is
                    // better than no bar at all
                }
            }
        }
        return clone;
    }

    // ------------------------------------------------------------------
    // Scrolling: structural (name-free) RecyclerView reflection.
    // ------------------------------------------------------------------

    private static void scrollToProvider(View pickerRoot, String provider) {
        Context ctx = pickerRoot.getContext();
        View recycler = findPickerRecycler(pickerRoot);
        if (recycler == null) {
            Log.w(TAG, "picker recycler not found");
            return;
        }

        Object adapter = invokeNoArg(recycler, "getAdapter");
        Object layoutManager = invokeNoArg(recycler, "getLayoutManager");
        if (adapter == null || layoutManager == null) {
            Log.w(TAG, "recycler adapter/layoutManager unavailable");
            return;
        }

        // No runway below the last row: the last sections (all of ours with "channel emotes only", or on a
        // channel with only global ones) stop at the bottom edge instead of the top. A viewport-high bottom
        // padding let them reach the top, but left a screen of empty space under them (bug report).

        // Our own EmoteCache group titles, in EmoteCache's own precedence
        // order (channel sections come before global ones in the list, and
        // first match wins).
        List<String> titles = new ArrayList<>(2);
        titles.add(provider + " Channel");
        titles.add(provider + " Global");

        int position = findSectionPosition(adapter, titles);
        if (position < 0) {
            // A failed or not yet finished load: kick it; refreshSections puts the section in.
            EmoteCache.ensureChannelLoaded(ChannelTracker.getCurrentChannelId());
            Toast.makeText(ctx, Strings.pickf(
                    "ReTwitch: эмоуты %s ещё грузятся, откройте панель заново через пару секунд",
                    "ReTwitch: %s emotes are still loading, reopen the picker in a few seconds", provider),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // We want the target section pinned to the TOP of the viewport.
        // Plain scrollToPosition(position) only makes the row *visible* with
        // minimal movement, so scrolling forward leaves it at the bottom edge
        // with the PREVIOUS section filling the screen -- reading as "scrolled
        // one section short". LinearLayoutManager.scrollToPositionWithOffset
        // would fix that, but its name is obfuscated to one of three
        // indistinguishable public (int,int)->void methods, so we can't pick
        // it reflectively.
        //
        // Instead we exploit what scrollToPosition itself does: it sets the
        // pending-scroll-offset field to INVALID_OFFSET (Integer.MIN_VALUE)
        // and schedules an async layout. If we overwrite that field with 0
        // *before* the layout pass runs, LinearLayoutManager top-aligns the
        // row exactly as scrollToPositionWithOffset(position, 0) would -- and
        // the field is identifiable purely by its MIN_VALUE sentinel, with no
        // reliance on obfuscated names.
        Method scrollToPosition = findScrollToPositionMethod(layoutManager.getClass());
        if (scrollToPosition == null) {
            Log.w(TAG, "no structural scrollToPosition on "
                    + layoutManager.getClass().getName());
            return;
        }
        try {
            scrollToPosition.invoke(layoutManager, position);
            forceTopAlignment(layoutManager);
        } catch (Throwable t) {
            Log.w(TAG, "scrollToPosition(" + position + ") failed", t);
            CrashReporter.report(TAG, "scrollToPosition(" + position + ") failed", t);
        }
    }

    /**
     * Turns the just-issued {@code scrollToPosition(position)} into a
     * top-aligned scroll. {@code LinearLayoutManager.scrollToPosition} sets
     * {@code mPendingScrollPositionOffset = INVALID_OFFSET}
     * ({@link Integer#MIN_VALUE}) and calls {@code requestLayout()}, which is
     * asynchronous -- so at this point the layout has not consumed the pending
     * scroll yet. We locate that offset field by its unmistakable MIN_VALUE
     * value (scoped to the class that also declares the {@code SavedState}
     * field, i.e. LinearLayoutManager, to rule out unrelated int fields) and
     * set it to 0. When the pending layout runs it pins the target row's top
     * to the start padding. Entirely name-agnostic: works regardless of how
     * R8 renamed the method and field.
     */
    private static void forceTopAlignment(Object layoutManager) {
        try {
            Class<?> scanClass = classDeclaringSavedState(layoutManager.getClass());
            if (scanClass == null) {
                scanClass = layoutManager.getClass();
            }
            for (Field f : scanClass.getDeclaredFields()) {
                if (f.getType() != int.class || Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                if (f.getInt(layoutManager) == Integer.MIN_VALUE) {
                    f.setInt(layoutManager, 0);
                    return;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "top-align failed", t);
            CrashReporter.report(TAG, "top-align failed", t);
        }
    }

    /**
     * The nearest class in {@code start}'s hierarchy that declares a field
     * whose type is a {@code *$SavedState} (LinearLayoutManager keeps that
     * nested type's name even under R8), i.e. LinearLayoutManager itself --
     * the class that also owns the pending-scroll offset field.
     */
    private static Class<?> classDeclaringSavedState(Class<?> start) {
        for (Class<?> k = start; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getType().getName().contains("SavedState")) {
                    return k;
                }
            }
        }
        return null;
    }

    /**
     * The picker's emote grid RecyclerView. The obvious route -- {@code
     * findViewById(@id/emote_picker_view_recycler)} -- fails on the live
     * chat-input picker: Twitch's factory ({@code bv.a()} in one decode)
     * assigns that id to the recycler but then immediately <i>overwrites</i>
     * it with {@code @id/emote_palette} via a second {@code setId} call
     * ({@code nj9.D(id)}), so both names are tried. Final fallback walks the
     * picker's content frame for a class literally named {@code
     * *RecyclerView} (the class name survives R8 in Twitch builds --
     * verified in smali -- even though most of its methods don't).
     */
    private static View findPickerRecycler(View pickerRoot) {
        Context ctx = pickerRoot.getContext();
        Resources res = ctx.getResources();
        String pkg = ctx.getPackageName();
        for (String idName : new String[] {"emote_palette", "emote_picker_view_recycler"}) {
            int rvId = res.getIdentifier(idName, "id", pkg);
            View byId = rvId != 0 ? pickerRoot.findViewById(rvId) : null;
            if (byId != null && findRecyclerViewIn(byId) == byId) {
                return byId;
            }
        }
        int contentId = res.getIdentifier("emote_picker", "id", pkg);
        View content = contentId != 0 ? pickerRoot.findViewById(contentId) : null;
        return findRecyclerViewIn(content != null ? content : pickerRoot);
    }

    private static View findRecyclerViewIn(View view) {
        for (Class<?> k = view.getClass(); k != null; k = k.getSuperclass()) {
            if (k.getName().endsWith("RecyclerView")) {
                return view;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findRecyclerViewIn(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }


    /**
     * {@code RecyclerView.LayoutManager.scrollToPosition(int)} without its
     * name: the layout-manager <i>base</i> class (the ancestor directly
     * below {@code Object}) declares exactly one public abstract
     * {@code (int) -> void} method, and that is it ({@code H0} in one
     * decode). Falls back to any abstract {@code (int) -> void} found while
     * walking up, should R8 ever flatten the hierarchy differently.
     */
    private static Method findScrollToPositionMethod(Class<?> layoutManagerClass) {
        Method fallback = null;
        for (Class<?> k = layoutManagerClass; k != null && k != Object.class; k = k.getSuperclass()) {
            Method candidate = null;
            int matches = 0;
            for (Method m : k.getDeclaredMethods()) {
                int mods = m.getModifiers();
                if (!Modifier.isAbstract(mods) || !Modifier.isPublic(mods)) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (m.getReturnType() == void.class && params.length == 1 && params[0] == int.class) {
                    candidate = m;
                    matches++;
                }
            }
            if (candidate != null && matches == 1) {
                // Deeper (closer to Object) declarations win; keep walking.
                fallback = candidate;
            }
        }
        if (fallback != null) {
            fallback.setAccessible(true);
        }
        return fallback;
    }

    /**
     * Finds the flat adapter position of the first item that (recursively)
     * carries one of {@code titles}. Candidate item lists are gathered from
     * the adapter object's own fields (and one level of nested non-platform
     * objects, for adapters that delegate to a section/differ helper).
     *
     * <p>Two shapes are handled:
     * <ul>
     * <li><b>Flat list</b> (header + emote items interleaved): the matched
     *     element is a leaf item -- its index <i>is</i> the adapter
     *     position. Preferred, and of several flat matches the largest list
     *     wins (a flattened list is always far bigger than any per-section
     *     one).</li>
     * <li><b>Section list</b> (each element wraps its own item list, like
     *     Twitch's section adapter): the position of section {@code k} is
     *     the cumulative size of the preceding sections' item lists, plus
     *     one header item per preceding section.</li>
     * </ul>
     */
    private static int findSectionPosition(Object adapter, List<String> titles) {
        List<List<?>> candidates = new ArrayList<>();
        collectLists(adapter, 2, Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()), candidates);

        int bestFlatPosition = -1;
        int bestFlatSize = -1;
        int bestSectionPosition = -1;
        for (List<?> list : candidates) {
            Object[] snapshot;
            try {
                snapshot = list.toArray();
            } catch (Throwable t) {
                continue; // concurrent structure, skip
            }
            // NOTE: no same-class skip heuristic here -- section lists are
            // homogeneous (every element is the same section wrapper class),
            // so skipping repeated classes would skip everything after the
            // first section. carriesTitle() is cheap enough per element.
            for (int i = 0; i < snapshot.length; i++) {
                Object item = snapshot[i];
                if (item == null) {
                    continue;
                }
                // Match the section's own HEADER title only -- do NOT descend
                // into its emote-item list. A frequently-used third-party emote
                // carries its provider's set title ("7TV Channel") in its own
                // model, so descending into the "Frequently used" section's
                // items would false-match it as the 7TV section. The header
                // title sits on the section wrapper / its header model, reached
                // through object fields (never through the emote list).
                boolean matched = carriesTitle(item, titles, TITLE_SCAN_DEPTH,
                        Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()), false);
                if (!matched) {
                    continue;
                }
                List<?> nestedItems = firstListField(item);
                if (nestedItems == null) {
                    if (snapshot.length > bestFlatSize) {
                        bestFlatSize = snapshot.length;
                        bestFlatPosition = i;
                    }
                } else if (bestSectionPosition < 0) {
                    int cumulative = 0;
                    for (int j = 0; j < i; j++) {
                        List<?> priorItems = snapshot[j] != null ? firstListField(snapshot[j]) : null;
                        cumulative += 1 + (priorItems != null ? priorItems.size() : 0);
                    }
                    bestSectionPosition = cumulative;
                }
                break;
            }
        }
        int position = bestFlatPosition >= 0 ? bestFlatPosition : bestSectionPosition;
        int itemCount = adapterItemCount(adapter);
        if (itemCount >= 0 && position >= itemCount) {
            position = itemCount - 1;
        }
        return position;
    }

    /**
     * {@code Adapter.getItemCount()} without its name: the adapter's base
     * class ({@code RecyclerView.Adapter} -- {@code cgt} in one decode)
     * declares exactly one public abstract no-arg {@code int} method, and
     * that is it. Returns -1 if the structural lookup fails; the caller
     * treats the count as advisory only.
     */
    private static int adapterItemCount(Object adapter) {
        Method found = null;
        for (Class<?> k = adapter.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                int mods = m.getModifiers();
                if (Modifier.isAbstract(mods) && Modifier.isPublic(mods)
                        && m.getParameterTypes().length == 0 && m.getReturnType() == int.class) {
                    found = m; // deepest declaration wins
                }
            }
        }
        if (found == null) {
            return -1;
        }
        try {
            found.setAccessible(true);
            Object result = found.invoke(adapter);
            return result instanceof Integer ? (Integer) result : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** The value of {@code item}'s first non-empty {@link List}-typed field, or null -- distinguishes section wrappers from leaf items. */
    private static List<?> firstListField(Object item) {
        for (Class<?> k = item.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !List.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object value = f.get(item);
                    if (value instanceof List && !((List<?>) value).isEmpty()) {
                        return (List<?>) value;
                    }
                } catch (Throwable ignored) {
                    // keep scanning
                }
            }
        }
        return null;
    }

    /** Gathers every {@link List}-typed field value reachable from {@code obj} within {@code depth} hops through non-platform objects. */
    private static void collectLists(Object obj, int depth, Set<Object> visited, List<List<?>> out) {
        if (obj == null || depth <= 0 || !visited.add(obj)) {
            return;
        }
        for (Class<?> k = obj.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                    continue;
                }
                Object value;
                try {
                    f.setAccessible(true);
                    value = f.get(obj);
                } catch (Throwable ignored) {
                    continue;
                }
                if (value == null) {
                    continue;
                }
                if (value instanceof List) {
                    out.add((List<?>) value);
                } else if (!isPlatformClass(value.getClass())) {
                    collectLists(value, depth - 1, visited, out);
                }
            }
        }
    }

    /**
     * Whether {@code obj} is, or (within {@code depth} reflective hops)
     * contains, a CharSequence whose text contains one of {@code titles}
     * ("contains" so wrapped forms like {@code "retwitch:7TV Channel"}
     * still match). Containers are traversed by element (first few only --
     * headers sit at the front and full emote lists are huge); other
     * platform classes are terminal.
     */
    private static boolean carriesTitle(Object obj, List<String> titles, int depth, Set<Object> visited,
            boolean descendLists) {
        if (obj == null) {
            return false;
        }
        if (obj instanceof CharSequence) {
            String text = obj.toString();
            for (String title : titles) {
                if (text.contains(title)) {
                    return true;
                }
            }
            return false;
        }
        if (depth <= 0 || !visited.add(obj)) {
            return false;
        }
        if (obj instanceof List) {
            if (!descendLists) {
                return false;
            }
            List<?> list = (List<?>) obj;
            int limit = Math.min(list.size(), TITLE_SCAN_LIST_ELEMENTS);
            for (int i = 0; i < limit; i++) {
                Object element;
                try {
                    element = list.get(i);
                } catch (Throwable ignored) {
                    break;
                }
                if (carriesTitle(element, titles, depth - 1, visited, descendLists)) {
                    return true;
                }
            }
            return false;
        }
        if (isPlatformClass(obj.getClass())) {
            return false;
        }
        for (Class<?> k = obj.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                    continue;
                }
                Object value;
                try {
                    f.setAccessible(true);
                    value = f.get(obj);
                } catch (Throwable ignored) {
                    continue;
                }
                if (value != null && carriesTitle(value, titles, depth - 1, visited, descendLists)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** JDK/framework/Kotlin-stdlib classes are never Twitch UI models; skipping them keeps the reflective scans small and cycle-free. */
    private static boolean isPlatformClass(Class<?> c) {
        String name = c.getName();
        return name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("android.") || name.startsWith("kotlin.");
    }

    // ------------------------------------------------------------------
    // Small helpers.
    // ------------------------------------------------------------------

    private static Object invokeNoArg(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable t) {
            Log.w(TAG, methodName + "() reflection failed on " + target.getClass().getName());
            return null;
        }
    }

    private static int dimenPx(Resources res, String pkg, String name, int fallbackPx) {
        int id = res.getIdentifier(name, "dimen", pkg);
        if (id == 0) {
            return fallbackPx;
        }
        try {
            return res.getDimensionPixelSize(id);
        } catch (Throwable t) {
            return fallbackPx;
        }
    }

    private static int themedColor(Context ctx, String name, int fallback) {
        int id = ctx.getResources().getIdentifier(name, "color", ctx.getPackageName());
        if (id == 0) {
            return fallback;
        }
        try {
            return ctx.getColor(id);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static int dp(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }
}
