package tv.retwitch.mod;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.media.AudioTrack;
import android.media.audiofx.DynamicsProcessing;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Audio compressor for the stream (RTE's "audio compressor": evens out loud
 * and quiet parts). {@code AudioCompressorPatch} reports every IVS
 * {@code AudioTrackRenderer} after it (re)configures its {@link AudioTrack};
 * while the compressor is on, a {@link DynamicsProcessing} effect (Android 9+)
 * is attached to that track's audio session. Toggled from a player button
 * next to reload (see {@link ReloadHook}).
 *
 * <p>The same effect carries the audio filters from the settings page
 * (ReVive's AudioFilters, toggle {@code AUDIO_FILTERS}): volume boost (input
 * gain), bass / treble / voice clarity on a 16-band pre-equaliser, a limiter
 * ceiling. They work with or without the compressor. The effect is re-created
 * whenever the compressor or a filter changes: until 0.3.9 it was kept for the
 * same session, so with a filter set the button did nothing. shortcut: no full
 * 16-slider equaliser, only the three shaped controls; add one if people ask.
 *
 * <p>RTE uses WebAudio's {@code DynamicsCompressorNode} (threshold -50 dB,
 * knee 40, ratio 12, attack 0, release 250 ms). Its knee starts at the
 * threshold and is exponential, it detects peaks and adds make-up gain of 0.6
 * of the reduction at 0 dBFS (+12.4 dB here); DynamicsProcessing's knee is
 * quadratic and centred on the threshold, on the block RMS. The MBC values
 * below are a least-squares fit of Chromium's static curve over -60..0 dBFS
 * (within 0.4 dB), assuming RMS = peak - 3 dB (a sine). shortcut: music has a
 * higher crest factor, so loud parts are squeezed a bit less than in RTE;
 * lower the threshold if users still hear a difference.
 */
public final class AudioCompressor {

    private static final String TAG = "RTCompressor";
    private static final String PREFS_NAME = "retwitch_audio_compressor";
    private static final String KEY_ACTIVE = "active";

    /** Audio filters: preference key, min, max (dB for boost/bass/treble, % for voice/limiter). */
    static final String BOOST = "boost";
    static final String BASS = "bass";
    static final String TREBLE = "treble";
    static final String VOICE = "voice";
    static final String LIMIT = "limit";
    /** Order of {@link #filters()}. */
    private static final String[] FILTERS = {BOOST, BASS, TREBLE, VOICE, LIMIT};

    /** Centre of each pre-equaliser band, Hz (ReVive's bands). */
    private static final int[] EQ_HZ = {30, 40, 60, 100, 200, 300, 500, 1000, 2000, 3000, 4000, 5000, 6000, 8000,
            12000, 16000};

    private static WeakReference<AudioTrack> currentTrack;
    /** Every IVS audio renderer seen (feed previews and preloads have their own). */
    private static final Map<Object, Boolean> renderers = Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    private static Object effect; // DynamicsProcessing; Object so API < 28 never loads the class
    private static int effectSession = -1;
    /** Compressor and filter values {@link #effect} was built with. */
    private static String effectConfig;
    private static WeakReference<ImageView> buttonRef;

    private AudioCompressor() {
    }

    /** Injected at the end of IVS {@code AudioTrackRenderer.configure}. Never throws. */
    public static void onRenderer(Object renderer) {
        try {
            if (renderer == null) {
                return;
            }
            renderers.put(renderer, Boolean.TRUE);
            refresh();
        } catch (Throwable t) {
            Log.w(TAG, "onRenderer failed", t);
            CrashReporter.report(TAG, "onRenderer failed", t);
        }
    }

    /**
     * Called when the audible player starts playing; its AudioTrack may only
     * start a moment later, so check again shortly after. Never throws.
     */
    static void onPlaying() {
        refresh();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        }, 1000L);
    }

    /**
     * Targets the renderer whose AudioTrack is actually playing: the last one
     * configured is often a muted feed preview or a preloaded player, which
     * left the compressor on a silent session until a manual quality change.
     */
    static synchronized void refresh() { // also from IVS render threads
        try {
            Object[] snapshot;
            synchronized (renderers) {
                snapshot = renderers.keySet().toArray();
            }
            // The loudest playing one: muted feed previews play too, at volume 0.
            AudioTrack best = null;
            float bestVolume = -1f;
            for (Object renderer : snapshot) {
                Field field = renderer.getClass().getDeclaredField("track");
                field.setAccessible(true);
                Object track = field.get(renderer);
                if (track instanceof AudioTrack
                        && ((AudioTrack) track).getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    Field volumeField = renderer.getClass().getDeclaredField("volume");
                    volumeField.setAccessible(true);
                    float volume = volumeField.getFloat(renderer);
                    if (volume > bestVolume) {
                        best = (AudioTrack) track;
                        bestVolume = volume;
                    }
                }
            }
            if (best != null) {
                currentTrack = new WeakReference<>(best);
            }
            apply();
        } catch (Throwable t) {
            Log.w(TAG, "refresh failed", t);
            CrashReporter.report(TAG, "refresh failed", t);
        }
    }

    static int min(String key) {
        return BASS.equals(key) || TREBLE.equals(key) ? -12 : 0;
    }

    static int max(String key) {
        return BOOST.equals(key) || BASS.equals(key) || TREBLE.equals(key) ? 12 : 100;
    }

    /** A filter's stored value, clamped to its range; 0 = off. */
    static int filter(String key) {
        try {
            Context context = AppContext.get();
            int v = context == null ? 0 : prefs(context).getInt(key, 0);
            return Math.max(min(key), Math.min(max(key), v));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** From the settings page: store; {@link #apply} re-creates the effect with the new values. */
    static void setFilter(Context context, String key, int value) {
        prefs(context).edit().putInt(key, Math.max(min(key), Math.min(max(key), value))).apply();
        refresh();
    }

    /** Values in {@link #FILTERS} order; all 0 while the filters toggle is off. */
    private static int[] filters() {
        int[] values = new int[FILTERS.length];
        if (Toggles.on(Toggles.AUDIO_FILTERS)) {
            for (int i = 0; i < FILTERS.length; i++) {
                values[i] = filter(FILTERS[i]);
            }
        }
        return values;
    }

    public static boolean isActive() {
        try {
            Context context = AppContext.get();
            // Tied to the button: with the button hidden there'd be no way to turn it off.
            return context != null && Toggles.on(Toggles.COMPRESSOR_BUTTON)
                    && prefs(context).getBoolean(KEY_ACTIVE, false);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void toggle(Context context) {
        if (Build.VERSION.SDK_INT < 28) {
            Toast.makeText(context, Strings.compressorUnsupported(), Toast.LENGTH_SHORT).show();
            return;
        }
        boolean active = !prefs(context).getBoolean(KEY_ACTIVE, false);
        setActive(context, active);
        Toast.makeText(context, active ? Strings.compressorOn() : Strings.compressorOff(), Toast.LENGTH_SHORT).show();
    }

    /** The button's state, also from {@link AudioPanel}. */
    static void setActive(Context context, boolean active) {
        prefs(context).edit().putBoolean(KEY_ACTIVE, active).apply();
        refresh();
        refreshButton();
    }

    /** Attach/detach the effect so it matches the setting and the current track. */
    private static synchronized void apply() {
        if (Build.VERSION.SDK_INT < 28) {
            return;
        }
        AudioTrack track = currentTrack != null ? currentTrack.get() : null;
        int session = track != null ? track.getAudioSessionId() : -1;
        boolean compressor = isActive();
        int[] filters = filters();
        boolean filtered = false;
        for (int v : filters) {
            filtered |= v != 0;
        }
        if (!(compressor || filtered) || session <= 0) {
            release();
            return;
        }
        String config = compressor + java.util.Arrays.toString(filters);
        if (effect != null && effectSession == session && config.equals(effectConfig)) {
            return;
        }
        release();
        effect = create(session, Math.max(1, track.getChannelCount()), compressor, filters);
        effectSession = session;
        effectConfig = config;
        Log.i(TAG, "effect on session " + session + ": compressor " + compressor + ", filters " + config);
    }

    @TargetApi(28)
    private static Object create(int session, int channels, boolean compressor, int[] f) {
        // The pre-equaliser only with filters set: the compressor alone stays as fitted.
        boolean eqInUse = f[1] != 0 || f[2] != 0 || f[3] != 0;
        DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, channels,
                eqInUse, eqInUse ? EQ_HZ.length : 0, true, 1, false, 0, true).build();
        if (eqInUse) {
            float[] gains = eqGains(f[1], f[2], f[3]);
            DynamicsProcessing.Eq eq = new DynamicsProcessing.Eq(true, true, EQ_HZ.length);
            for (int i = 0; i < EQ_HZ.length; i++) {
                // A band ends halfway (log scale) to the next centre; the last at 20 kHz.
                float cutoff = i == EQ_HZ.length - 1 ? 20000f : (float) Math.sqrt((double) EQ_HZ[i] * EQ_HZ[i + 1]);
                eq.setBand(i, new DynamicsProcessing.EqBand(true, cutoff, gains[i]));
            }
            config.setPreEqAllChannelsTo(eq);
        }
        // enabled, cutoff, attack, release, ratio, threshold, knee, noiseGate,
        // expanderRatio, preGain, postGain (make-up): fitted to RTE, see the class comment;
        // ratio 1 = pass-through.
        config.setMbcBandAllChannelsTo(0, compressor
                ? new DynamicsProcessing.MbcBand(true, 20000f, 1f, 250f, 12f, -25f, 28f, -90f, 1f, 0f, 12f)
                : new DynamicsProcessing.MbcBand(true, 20000f, 1f, 250f, 1f, 0f, 0f, -90f, 1f, 0f, 0f));
        // inUse, enabled, linkGroup, attack, release, ratio, threshold, postGain.
        config.setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(
                true, true, 0, 1f, 60f, 10f, ceiling(compressor, f[4]), 0f));
        config.setInputGainAllChannelsTo(f[0]);
        DynamicsProcessing processing = new DynamicsProcessing(0, session, config);
        processing.setEnabled(true);
        return processing;
    }

    /**
     * Bass and treble as gentle shelves over the bands, voice clarity lifting
     * speech (2-5 kHz) and trimming mud (100-300 Hz) -- ReVive's curves, in dB.
     */
    static float[] eqGains(int bass, int treble, int voicePercent) {
        float voice = voicePercent / 100f;
        float[] gains = new float[EQ_HZ.length];
        for (int i = 0; i < EQ_HZ.length; i++) {
            int hz = EQ_HZ[i];
            float b = hz <= 100 ? 1f : hz <= 200 ? 0.6f : hz <= 300 ? 0.25f : 0f;
            float t = hz >= 8000 ? 1f : hz >= 6000 ? 0.8f : hz >= 5000 ? 0.6f : hz >= 4000 ? 0.35f : 0f;
            float g = bass * b + treble * t;
            if (hz >= 100 && hz <= 300) {
                g -= 2.5f * voice;
            }
            if (hz >= 2000 && hz <= 5000) {
                g += 5f * voice;
            } else if (hz >= 6000 && hz <= 8000) {
                g += 1.5f * voice;
            }
            gains[i] = g;
        }
        return gains;
    }

    /**
     * Limiter threshold, dBFS: the chosen ceiling (-1 .. -12 dB), else -1 as the
     * compressor's clip guard (its fitted curve tops out at -8 dBFS), else -0.5 as a
     * guard when the filters add loudness.
     */
    static float ceiling(boolean compressor, int limitPercent) {
        if (limitPercent > 0) {
            return -1f - 11f * limitPercent / 100f;
        }
        return compressor ? -1f : -0.5f;
    }

    private static void release() {
        if (effect != null && Build.VERSION.SDK_INT >= 28) {
            try {
                ((DynamicsProcessing) effect).release();
            } catch (Throwable ignored) {
                // already gone with its session
            }
        }
        effect = null;
        effectSession = -1;
        effectConfig = null;
    }

    /** The player-strip button (see {@link ReloadHook}). */
    static View createButton(final Context context) {
        ImageView button = new ImageView(context);
        int px = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24,
                context.getResources().getDisplayMetrics());
        button.setImageDrawable(new IconDrawable(px));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(Strings.compressorTalkback());
        button.setClickable(true);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    toggle(v.getContext());
                } catch (Throwable t) {
                    Log.w(TAG, "toggle failed", t);
                    CrashReporter.report(TAG, "toggle failed", t);
                }
            }
        });
        button.setOnLongClickListener(v -> {
            try {
                AudioPanel.show(v.getContext());
            } catch (Throwable t) {
                Log.w(TAG, "audio panel failed", t);
                CrashReporter.report(TAG, "audio panel failed", t);
            }
            return true;
        });
        // 48dp: Android's minimum comfortable touch target.
        int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48,
                context.getResources().getDisplayMetrics());
        button.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        buttonRef = new WeakReference<>(button);
        return button;
    }

    private static void refreshButton() {
        ImageView button = buttonRef != null ? buttonRef.get() : null;
        if (button != null) {
            button.invalidate();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Three level bars; Twitch purple when the compressor is on, white when off. */
    private static final class IconDrawable extends Drawable {
        private final int size;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        IconDrawable(int size) {
            this.size = size;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            paint.setColor(isActive() ? 0xFFBF94FF : 0xFFFFFFFF);
            float bar = size / 6f;
            float[] heights = {0.5f, 0.85f, 0.5f};
            float x = b.exactCenterX() - bar * 2.5f;
            for (float h : heights) {
                float half = size * h / 2f;
                canvas.drawRoundRect(x, b.exactCenterY() - half, x + bar, b.exactCenterY() + half,
                        bar / 2f, bar / 2f, paint);
                x += bar * 2f;
            }
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
