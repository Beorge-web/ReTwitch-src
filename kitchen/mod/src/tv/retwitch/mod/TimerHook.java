package tv.retwitch.mod;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Drops a small clock button into Twitch's player controls overlay; tapping it
 * opens a dialog to pick an hours/minutes countdown after which the whole app
 * is closed. This is ReTwitch's port of PurpleTV's timer feature (see
 * {@link TimerController} for the countdown/exit engine and {@link TimerSettings}
 * for persistence).
 *
 * <p><b>Why a view-tree hook, not a bytecode fingerprint.</b> Exactly like
 * {@link TheatreDividerHook}, rather than splice into the obfuscated player
 * overlay we install an {@link Application.ActivityLifecycleCallbacks} and watch
 * the view tree via an {@link ViewTreeObserver.OnGlobalLayoutListener}. The
 * button is anchored off the overlay's settings gear (found by its resource
 * <i>entry name</i>, which survives R8) and everything else is public Android
 * SDK -- nothing references Twitch's renamed classes.
 *
 * <p><b>Placement.</b> The overlay button row is a packed {@code ConstraintLayout}
 * chain (...clip -> cast -> share -> gear, gear pinned to the parent's end), so
 * there is no free slot and a purely visual {@code translationX} shift would land
 * us on top of a neighbour. Instead we splice in as a real chain member right
 * before the gear: clone the left neighbour's runtime {@code LayoutParams} (same
 * technique as {@link PickerHook}) so our button inherits the chain's vertical
 * anchors, then re-point that neighbour's <i>end</i> anchor from the gear to us.
 * The whole cluster reflows left to make room -- no overlap, and it looks native.
 *
 * <p>{@code ConstraintLayout.LayoutParams} is R8-renamed, so we can't name the
 * {@code endToStart} field. We identify it the same way {@link TheatreDividerHook}
 * finds {@code guidePercent}: by <i>runtime value</i> -- the anchor int field(s)
 * on the neighbour whose value equals the gear's (large, aapt-range) view id.
 * Everything fails soft if the anchor moves across Twitch releases.
 */
public final class TimerHook {

    private static final String TAG = "RTTimer";

    /**
     * Resource entry names of the settings gear in the player controls overlay,
     * tried in order. Our clock button is placed just left of whichever one is
     * present.
     */
    private static final String[] ANCHOR_NAMES = {
            "settings_button",
            "player_settings_button",
            "player_overlay_settings_button",
    };

    /**
     * Resource entry names of the player controls overlay container. We scope the
     * gear lookup to this subtree so we never match a same-named button that
     * lives elsewhere in the tree -- notably the channel profile's own
     * {@code settings_button}, which is present alongside the VOD player and
     * (being earlier in the tree) would otherwise win {@code findViewById}.
     */
    private static final String[] OVERLAY_NAMES = {
            "player_overlay",
            "player_overlay_container",
    };

    private static final String BUTTON_TAG = "retwitch_timer_button";
    private static final int LISTENER_TAG_KEY = 0x7e700d20;
    private static final int BUTTON_TAG_KEY = 0x7e700d21;
    /** Stores the sibling whose end-anchor we re-pointed at the clock, so disabling can restore it. */
    private static final int NEIGHBOUR_TAG_KEY = 0x7e700d22;

    private static volatile boolean installed;
    private static volatile boolean toastedFailure;

    private TimerHook() {
    }

    /** Called once from the injected {@code TwitchApplication.onCreate} ({@code p0}). */
    public static void install(Application application) {
        try {
            if (installed || application == null) {
                return;
            }
            installed = true;
            application.registerActivityLifecycleCallbacks(new Callbacks());
        } catch (Throwable t) {
            Log.w(TAG, "install failed", t);
            CrashReporter.report(TAG, "install failed", t);
        }
    }

    private static void attachWatcher(Activity activity) {
        try {
            if (activity == null || !activity.getClass().getName().startsWith("tv.twitch")) {
                return;
            }
            Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            final View root = window.findViewById(android.R.id.content);
            // Guard on the decor: AppCompat hands android.R.id.content to its own frame
            // after onActivityCreated, so a tag on the content view let onResume add a second listener.
            View decor = window.getDecorView();
            if (root == null || decor.getTag(LISTENER_TAG_KEY) != null) {
                return;
            }
            root.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            long t0 = UiStallLog.start();
                            try {
                                update(root);
                            } catch (Throwable t) {
                                onFailure(root.getContext(), t);
                            }
                            UiStallLog.end("Timer", t0);
                        }
                    });
            decor.setTag(LISTENER_TAG_KEY, Boolean.TRUE);
        } catch (Throwable t) {
            Log.w(TAG, "attachWatcher failed", t);
            CrashReporter.report(TAG, "attachWatcher failed", t);
        }
    }

    /** One layout pass: ensure the clock button exists, is spliced in and in sync iff enabled. */
    private static void update(View root) {
        View gear = findAnchor(root);
        if (gear == null || !(gear.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup parent = (ViewGroup) gear.getParent();
        ImageView existing = findOurButton(parent);

        if (!TimerSettings.isButtonEnabled()) {
            if (existing != null) {
                removeFromChain(parent, gear, existing);
            }
            return;
        }

        if (existing != null) {
            // Already spliced in; just keep it hiding/showing with the controls.
            existing.setVisibility(gear.getVisibility());
            return;
        }
        if (gear.getWidth() <= 0) {
            return; // overlay not laid out yet; try again next pass
        }
        insertIntoChain(parent, gear);
    }

    /**
     * Splices the clock into the button chain immediately to the left of the
     * gear so the row reflows to make room (see class javadoc). Aborts cleanly
     * (adds nothing) if the gear's left neighbour isn't actually end-anchored to
     * the gear, so we never leave an overlapping orphan.
     */
    private static void insertIntoChain(ViewGroup parent, View gear) {
        try {
            int gearId = gear.getId();
            if (gearId == View.NO_ID) {
                return;
            }
            View neighbour = adjacentLeft(parent, gear, null);
            // We can only reflow if the neighbour's end anchor points at the gear.
            if (neighbour == null || countAnchorMatches(neighbour, gearId) == 0) {
                return;
            }
            int neighbourId = neighbour.getId();

            ImageView clock = createButton(parent.getContext());
            clock.setId(View.generateViewId());
            int clockId = clock.getId();
            ViewGroup.LayoutParams lp = cloneSiblingParams(neighbour);
            if (lp == null) {
                return;
            }
            clock.setLayoutParams(lp);
            parent.addView(clock);

            // The clone kept the neighbour's anchors: end-to-start of the gear
            // (which we want) and start-to-end of the neighbour's own left
            // sibling. Re-point that start anchor at the neighbour itself, so the
            // clock links neighbour <-> clock rather than stealing its slot.
            View leftOfNeighbour = adjacentLeft(parent, neighbour, clock);
            if (leftOfNeighbour != null && leftOfNeighbour.getId() != View.NO_ID
                    && neighbourId != View.NO_ID) {
                retargetAnchors(clock, leftOfNeighbour.getId(), neighbourId);
                clock.setLayoutParams(clock.getLayoutParams());
            }

            // Splice into the (bidirectional) chain: neighbour <-> clock <-> gear.
            //  - neighbour's END anchor:  gear -> clock
            //  - gear's START anchor:     neighbour -> clock   (two-way chains only)
            // Skipping the gear side leaves it a stale start link to the
            // neighbour, unbalancing the chain and leaving a gap on the gear's
            // right; retargeting a large aapt view id is a no-op when absent.
            int changed = retargetAnchors(neighbour, gearId, clockId);
            if (changed == 0) {
                parent.removeView(clock);
                return;
            }
            if (neighbourId != View.NO_ID) {
                retargetAnchors(gear, neighbourId, clockId);
                gear.setLayoutParams(gear.getLayoutParams());
            }
            neighbour.setLayoutParams(neighbour.getLayoutParams());
            clock.setTag(NEIGHBOUR_TAG_KEY, neighbour);
            clock.setVisibility(gear.getVisibility());
            parent.requestLayout();
        } catch (Throwable t) {
            onFailure(gear.getContext(), t);
        }
    }

    /** Restores both sides of the chain link (neighbour <-> gear) and removes the clock. */
    private static void removeFromChain(ViewGroup parent, View gear, ImageView clock) {
        try {
            int clockId = clock.getId();
            Object nb = clock.getTag(NEIGHBOUR_TAG_KEY);
            if (nb instanceof View) {
                View neighbour = (View) nb;
                if (gear.getId() != View.NO_ID) {
                    retargetAnchors(neighbour, clockId, gear.getId());
                    neighbour.setLayoutParams(neighbour.getLayoutParams());
                }
                if (neighbour.getId() != View.NO_ID) {
                    retargetAnchors(gear, clockId, neighbour.getId());
                    gear.setLayoutParams(gear.getLayoutParams());
                }
            }
            parent.removeView(clock);
            parent.requestLayout();
        } catch (Throwable t) {
            onFailure(parent.getContext(), t);
        }
    }

    /**
     * Counts (without mutating) the non-static {@code int} LayoutParams fields on
     * {@code v} whose value equals {@code id} -- i.e. its constraint anchors that
     * point at that view. Used both to gate insertion and, via
     * {@link #retargetAnchors}, to rewrite those anchors name-agnostically.
     */
    private static int countAnchorMatches(View v, int id) {
        return forEachAnchor(v, id, id, false);
    }

    /** Rewrites every anchor int field on {@code v} equal to {@code fromId} to {@code toId}. */
    private static int retargetAnchors(View v, int fromId, int toId) {
        return forEachAnchor(v, fromId, toId, true);
    }

    private static int forEachAnchor(View v, int fromId, int toId, boolean write) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) {
            return 0;
        }
        int count = 0;
        for (Class<?> k = lp.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    if (f.getInt(lp) == fromId) {
                        if (write) {
                            f.setInt(lp, toId);
                        }
                        count++;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return count;
    }

    private static ImageView createButton(Context context) {
        ImageView button = new ImageView(context);
        button.setTag(BUTTON_TAG);
        button.setTag(BUTTON_TAG_KEY, Boolean.TRUE);
        // Fixed 24dp glyph drawn CENTER (not FIT_CENTER, which stretched it to
        // ~2x the native icons inside the ~48dp button box).
        button.setImageDrawable(new ClockDrawable(dp(context, 24)));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(Strings.timerButtonTalkback());
        button.setClickable(true);
        button.setFocusable(true);
        button.setBackground(borderlessBackground(context));
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTimerDialog(v.getContext());
            }
        });
        return button;
    }

    /**
     * Finds the laid-out sibling on {@code ref}'s row whose right edge touches
     * {@code ref}'s left edge (contiguous, immediately to the left). Our own
     * button ({@code exclude}) and zero-size children are skipped.
     */
    private static View adjacentLeft(ViewGroup parent, View ref, View exclude) {
        int refLeft = ref.getLeft();
        int refCy = (ref.getTop() + ref.getBottom()) / 2;
        int refH = Math.max(1, ref.getHeight());
        int tolerance = dp(parent.getContext(), 12);
        View best = null;
        int bestRight = Integer.MIN_VALUE;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child == ref || child == exclude
                    || child.getWidth() <= 0 || child.getHeight() <= 0) {
                continue;
            }
            int cy = (child.getTop() + child.getBottom()) / 2;
            if (Math.abs(cy - refCy) > refH / 2) {
                continue; // not on the same row
            }
            int right = child.getRight();
            if (child.getLeft() < refLeft
                    && right <= refLeft + tolerance && right >= refLeft - tolerance
                    && right > bestRight) {
                best = child;
                bestRight = right;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Timer dialog
    // ------------------------------------------------------------------

    /**
     * Shows the hours/minutes picker; on OK starts (or restarts) the countdown.
     * Public so the ReTwitch settings menu can open it too.
     */
    public static void showTimerDialog(final Context context) {
        try {
            if (context == null) {
                return;
            }
            int pad = dp(context, 20);
            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(pad, dp(context, 8), pad, 0);

            LinearLayout pickers = new LinearLayout(context);
            pickers.setOrientation(LinearLayout.HORIZONTAL);
            pickers.setGravity(Gravity.CENTER);

            final NumberPicker hours = new NumberPicker(context);
            hours.setMinValue(0);
            hours.setMaxValue(23);
            hours.setValue(TimerSettings.getLastHours());
            final NumberPicker minutes = new NumberPicker(context);
            minutes.setMinValue(0);
            minutes.setMaxValue(59);
            minutes.setValue(TimerSettings.getLastMinutes());

            pickers.addView(labeledPicker(context, hours, Strings.timerHoursSuffix()));
            pickers.addView(labeledPicker(context, minutes, Strings.timerMinutesSuffix()));
            content.addView(pickers);

            if (TimerController.isRunning()) {
                TextView hint = new TextView(context);
                hint.setText(Strings.timerRunningHint(TimerController.remaining()));
                hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                hint.setPadding(0, dp(context, 12), 0, 0);
                hint.setAlpha(0.7f);
                content.addView(hint);
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(context)
                    .setTitle(Strings.timerDialogTitle())
                    .setView(content)
                    .setPositiveButton(Strings.timerStart(), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            int h = hours.getValue();
                            int m = minutes.getValue();
                            int totalSeconds = h * 3600 + m * 60;
                            if (totalSeconds <= 0) {
                                TimerController.toast(context, Strings.timerCantSetZero());
                                return;
                            }
                            TimerSettings.setLastDuration(context, h, m);
                            TimerController.start(context, totalSeconds);
                            TimerController.toast(context,
                                    Strings.timerStartedToast(TimerController.formatTime(totalSeconds)));
                        }
                    })
                    .setNegativeButton(Strings.cancel(), null);

            if (TimerController.isRunning()) {
                builder.setNeutralButton(Strings.timerStop(), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        TimerController.cancel();
                        TimerController.toast(context, Strings.timerCancelledToast());
                    }
                });
            }
            builder.show();
        } catch (Throwable t) {
            onFailure(context, t);
        }
    }

    private static LinearLayout labeledPicker(Context context, NumberPicker picker, String suffix) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        int margin = dp(context, 8);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(margin, margin, margin, margin);
        column.setLayoutParams(lp);
        column.addView(picker);
        TextView label = new TextView(context);
        label.setText(suffix);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(context, 8), 0, 0);
        column.addView(label);
        return column;
    }

    // ------------------------------------------------------------------
    // View-tree helpers
    // ------------------------------------------------------------------

    private static View findAnchor(View root) {
        Context ctx = root.getContext();
        // Scope to the player controls overlay first; otherwise on a VOD/channel
        // page findViewById would return the profile header's settings_button
        // (same id, earlier in the tree) instead of the player's gear.
        View scope = root;
        for (String name : OVERLAY_NAMES) {
            View overlay = findByName(root, ctx, name);
            if (overlay != null) {
                scope = overlay;
                break;
            }
        }
        for (String name : ANCHOR_NAMES) {
            View v = findByName(scope, ctx, name);
            // A laid-out gear (width > 0) rules out the detached 0x0 profile one.
            if (v != null && v.getWidth() > 0 && v.getParent() instanceof ViewGroup) {
                return v;
            }
        }
        return null;
    }

    private static View findByName(View parent, Context ctx, String name) {
        try {
            int id = UiHider.id(ctx, name);
            return id != 0 ? parent.findViewById(id) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static ImageView findOurButton(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ImageView && Boolean.TRUE.equals(child.getTag(BUTTON_TAG_KEY))) {
                return (ImageView) child;
            }
        }
        return null;
    }

    /**
     * Clones {@code sibling}'s runtime LayoutParams (whatever R8 named the
     * ConstraintLayout params class) via its {@code (int,int)} ctor + copying
     * every primitive/String field -- the constraint anchors/margins/biases.
     * Same approach as {@link PickerHook}'s {@code cloneSiblingParams}.
     */
    private static ViewGroup.LayoutParams cloneSiblingParams(View sibling) {
        ViewGroup.LayoutParams src = sibling.getLayoutParams();
        if (src == null) {
            return null;
        }
        ViewGroup.LayoutParams clone;
        try {
            Constructor<? extends ViewGroup.LayoutParams> ctor =
                    src.getClass().getConstructor(int.class, int.class);
            clone = ctor.newInstance(
                    src.width > 0 ? src.width : ViewGroup.LayoutParams.WRAP_CONTENT,
                    src.height > 0 ? src.height : ViewGroup.LayoutParams.WRAP_CONTENT);
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
                }
            }
        }
        return clone;
    }

    private static Drawable borderlessBackground(Context context) {
        try {
            android.util.TypedValue outValue = new android.util.TypedValue();
            context.getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, outValue, true);
            if (outValue.resourceId != 0) {
                return context.getResources().getDrawable(outValue.resourceId, context.getTheme());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    private static void onFailure(Context context, Throwable t) {
        Log.w(TAG, "timer hook failure", t);
        CrashReporter.report(TAG, "timer hook failure", t);
        if (toastedFailure) {
            return;
        }
        toastedFailure = true;
        try {
            Context c = context != null ? context : AppContext.get();
            if (c != null) {
                Toast.makeText(c, Strings.timerFailedToast(), Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    private static final class Callbacks implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            attachWatcher(activity);
        }

        @Override
        public void onActivityResumed(Activity activity) {
            attachWatcher(activity);
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }

    /**
     * A simple white clock face so we don't have to inject a drawable resource
     * (the extension dex is code-only): a ring with an hour and a minute hand.
     */
    private static final class ClockDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int sizePx;

        ClockDrawable(int sizePx) {
            this.sizePx = sizePx;
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        public int getIntrinsicWidth() {
            return sizePx;
        }

        @Override
        public int getIntrinsicHeight() {
            return sizePx;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float radius = Math.min(b.width(), b.height()) / 2f;
            if (radius <= 0f) {
                return;
            }
            float stroke = Math.max(2f, radius * 0.12f);
            paint.setStrokeWidth(stroke);
            float ring = radius - stroke;
            canvas.drawCircle(cx, cy, ring, paint);
            // Minute hand (up), hour hand (right) -- roughly "10:10"-ish is
            // hard without trig; keep it readable at "12 and 3".
            canvas.drawLine(cx, cy, cx, cy - ring * 0.62f, paint);
            canvas.drawLine(cx, cy, cx + ring * 0.45f, cy, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
