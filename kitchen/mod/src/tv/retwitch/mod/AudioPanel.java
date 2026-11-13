package tv.retwitch.mod;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Long press on the player's compressor button (feedback #42): the compressor and the audio filters right over the
 * player, changed while the stream plays. The same settings as Player -> Audio; every change re-creates the effect
 * through {@link AudioCompressor#setFilter} / {@code refresh}.
 */
final class AudioPanel {

    private AudioPanel() {
    }

    static void show(Context context) {
        if (Build.VERSION.SDK_INT < 28) {
            Toast.makeText(context, Strings.compressorUnsupported(), Toast.LENGTH_SHORT).show();
            return;
        }
        int pad = dp(context, 20);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(context, 8), pad, dp(context, 8));

        Switch compressor = new Switch(context);
        compressor.setText(Strings.compressorTalkback());
        compressor.setChecked(AudioCompressor.isActive());
        compressor.setEnabled(Toggles.on(Toggles.COMPRESSOR_BUTTON));
        compressor.setOnCheckedChangeListener((b, on) -> AudioCompressor.setActive(context, on));
        box.addView(compressor, row(context));

        Switch filters = new Switch(context);
        filters.setText(Toggles.AUDIO_FILTERS.label());
        filters.setChecked(Toggles.on(Toggles.AUDIO_FILTERS));
        box.addView(filters, row(context));

        List<View> sliders = new ArrayList<>();
        int[] db = {-12, -9, -6, -3, 0, 3, 6, 9, 12};
        int[] percent = {0, 25, 50, 75, 100};
        slider(context, box, sliders, Strings.audioBoostLabel(), AudioCompressor.BOOST, new int[]{0, 2, 4, 6, 8, 10, 12}, false);
        slider(context, box, sliders, Strings.audioBassLabel(), AudioCompressor.BASS, db, false);
        slider(context, box, sliders, Strings.audioTrebleLabel(), AudioCompressor.TREBLE, db, false);
        slider(context, box, sliders, Strings.audioVoiceLabel(), AudioCompressor.VOICE, percent, true);
        slider(context, box, sliders, Strings.audioLimitLabel(), AudioCompressor.LIMIT, percent, true);
        CompoundButton.OnCheckedChangeListener filtersChanged = (b, on) -> {
            Toggles.set(context, Toggles.AUDIO_FILTERS, on);
            AudioCompressor.refresh();
            for (View v : sliders) {
                v.setEnabled(on);
                v.setAlpha(on ? 1f : 0.4f);
            }
        };
        filters.setOnCheckedChangeListener(filtersChanged);
        filtersChanged.onCheckedChanged(filters, filters.isChecked());

        ScrollView scroll = new ScrollView(context);
        scroll.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(Strings.pick("Звук", "Audio"))
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setDimAmount(0.15f); // the video stays visible while the sound changes
        }
    }

    /** A filter: its name with the current value, and a stepped slider over {@code values}. */
    private static void slider(Context context, LinearLayout box, List<View> sliders, String name, String key,
            int[] values, boolean percent) {
        String[] labels = Strings.audioLevelLabels(values, percent);
        TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 10);
        box.addView(label, lp);
        SeekBar bar = new SeekBar(context);
        bar.setMax(values.length - 1);
        int index = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == AudioCompressor.filter(key)) {
                index = i;
            }
        }
        bar.setProgress(index);
        label.setText(name + ": " + labels[index]);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                label.setText(name + ": " + labels[progress]);
                if (fromUser) {
                    AudioCompressor.setFilter(context, key, values[progress]);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 36)));
        sliders.add(label);
        sliders.add(bar);
    }

    private static LinearLayout.LayoutParams row(Context context) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 48));
        lp.gravity = Gravity.CENTER_VERTICAL;
        return lp;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
