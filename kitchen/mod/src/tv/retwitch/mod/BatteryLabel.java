package tv.retwitch.mod;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * The battery percentage on the player in landscape ({@link Toggles#BATTERY_LABEL}, feedback idea #82): full screen
 * hides the status bar. A badge like the latency one, in the bottom-end corner of the player's centre area, so it
 * shows and hides with the controls; "⚡" while charging. Portrait has the status bar, there it stays hidden.
 */
final class BatteryLabel {

    private static final String TAG = "RTBattery";
    private static final String LABEL_TAG = "retwitch_battery";
    /** The centre areas of both player overlay layouts, as PlayerHook's latency badge. */
    private static final String[] HOSTS = {"play_pause_container", "play_pause_fast_seek_view"};
    private static boolean attached;

    private BatteryLabel() {
    }

    /** From UiHider's layout pass. */
    static void attach(Activity activity, View decor) {
        boolean on = Toggles.on(Toggles.BATTERY_LABEL);
        if (!on && !attached) {
            return;
        }
        attached = on;
        for (String name : HOSTS) {
            for (View host : UiHider.findAll(activity, decor, name)) {
                if (!(host instanceof FrameLayout)) {
                    continue;
                }
                View label = host.findViewWithTag(LABEL_TAG);
                if (on && label == null) {
                    int margin = Math.round(12 * activity.getResources().getDisplayMetrics().density);
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
                    lp.setMargins(margin, margin, margin, margin);
                    ((FrameLayout) host).addView(label(activity, margin / 2), lp);
                } else if (!on && label != null) {
                    ((FrameLayout) host).removeView(label);
                }
            }
        }
    }

    static String text(int percent, boolean charging) {
        return (charging ? "⚡ " : "🔋 ") + percent + "%";
    }

    private static TextView label(Context context, int pad) {
        final TextView label = new TextView(context);
        label.setTag(LABEL_TAG);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setPadding(pad, pad / 2, pad, pad / 2);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99000000);
        bg.setCornerRadius(pad);
        label.setBackground(bg);
        label.setVisibility(View.GONE);
        final BatteryManager battery = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        // Once a minute is plenty for a percentage; the first read is right away.
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                try {
                    boolean landscape = label.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
                    int percent = battery != null ? battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
                    if (!landscape || percent < 0 || percent > 100) {
                        label.setVisibility(View.GONE);
                    } else {
                        String text = text(percent, battery.isCharging());
                        if (!text.contentEquals(label.getText())) {
                            label.setText(text);
                        }
                        label.setVisibility(View.VISIBLE);
                    }
                    label.postDelayed(this, landscape ? 30_000L : 5_000L);
                } catch (Throwable t) {
                    Log.w(TAG, "battery label failed", t);
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
        // Rotating doesn't re-attach the view: check again right away instead of waiting for the next tick.
        label.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) {
                v.removeCallbacks(tick);
                v.post(tick);
            }
        });
        return label;
    }
}
