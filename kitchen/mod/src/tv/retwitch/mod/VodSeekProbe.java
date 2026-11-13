package tv.retwitch.mod;

import android.util.Log;

/**
 * Diagnostic-only probe for the VOD "progress jumps back after a while" bug.
 *
 * The patch ({@code VodSeekProbePatch}) splices a call to one of these methods
 * at the head of every place that either seeks the VOD playhead or re-installs
 * the HLS playlist/media source, at both the presenter level (Twitch's own,
 * obfuscated {@code hs40}) and the engine level (ExoPlayer2 wrapper
 * {@code tv/twitch/android/shared/player/core/b}).
 *
 * Every log line carries a {@link Throwable} whose stack trace shows the full
 * call chain that reached the seek/reload -- i.e. *who* triggered it. In a
 * release build the frames are R8-obfuscated, but the class/method letters are
 * stable enough within a single build to tell the paths apart (e.g. a
 * manifest-reload lambda vs. a user seekbar gesture vs. the resume-on-open
 * seek).
 *
 * Depends only on the Android SDK, so it stays valid across app updates.
 * This class does nothing but log; remove the patch to disable it.
 *
 * Filter logcat with:  adb logcat -s RTVodProbe
 */
public final class VodSeekProbe {

    private static final String TAG = "RTVodProbe";

    private VodSeekProbe() {
    }

    /** Presenter programmatic seek entry ({@code hs40.seekTo(int)}, resume/reload). */
    public static void seekTo(int positionMs) {
        Log.i(TAG, "presenter.seekTo pos=" + positionMs + "ms", new Throwable("caller"));
    }

    /**
     * Presenter seek executor ({@code hs40.i(int, SeekTrigger)}). Every seek --
     * user seekbar/skip AND programmatic -- funnels through here carrying its
     * trigger. {@code trigger} is Twitch's obfuscated SeekTrigger enum; its
     * {@code toString()} is the constant name (UNSPECIFIED / SEEKBAR /
     * BACK_BUTTON / FORWARD_BUTTON / SEEK_MODAL).
     */
    public static void seekExec(int positionMs, Object trigger) {
        Log.i(TAG, "presenter.seekExec pos=" + positionMs + "ms trigger=" + trigger,
                new Throwable("caller"));
    }

    /** Presenter manifest reload ({@code hs40.h3(Integer)}). */
    public static void reload(Integer positionMs) {
        Log.i(TAG, "presenter.reload(h3) pos=" + positionMs, new Throwable("caller"));
    }

    /** Engine-level seek ({@code core/b.seekTo(int)}) -- the ultimate funnel for all seeks. */
    public static void engineSeek(int positionMs) {
        Log.i(TAG, "engine.seekTo pos=" + positionMs + "ms", new Throwable("caller"));
    }

    /** Engine-level media-source install ({@code core/b} setMediaSource) -- playlist (re)load. */
    public static void engineSetSource() {
        Log.i(TAG, "engine.setMediaSource (playlist (re)load)", new Throwable("caller"));
    }
}
