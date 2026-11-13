package tv.retwitch.mod;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.util.Log;

/**
 * Fail-soft wrapper around {@link Activity#setPictureInPictureParams(PictureInPictureParams)}.
 *
 * <p><b>Why this exists.</b> Twitch's own auto Picture-in-Picture code pushes
 * fresh {@code PictureInPictureParams} (source-rect hint + aspect ratio) at the
 * system on every player-bounds change. Android rate-limits aspect-ratio
 * changes: {@code ActivityClientController.ensureValidPictureInPictureActivityParams}
 * throws {@code IllegalStateException: "Too many PiP aspect ratio change
 * requests from ..."} once the app exceeds the allowed rate. When Twitch's call
 * happens outside an RxJava chain (a plain {@code Handler} callback on the main
 * thread) that exception is uncaught and takes the whole app down. Resizing the
 * player with our side-by-side chat divider makes the player bounds change more
 * often, which makes hitting that limit more likely.
 *
 * <p>We can't stop Twitch from calling the setter, but we can stop it from
 * crashing: {@code retwitchPipGuardPatch} rewrites every
 * {@code Activity.setPictureInPictureParams(...)} call site to route through
 * here, where the throttle {@code IllegalStateException} (and anything else) is
 * swallowed. Dropping one params update is harmless -- PiP simply keeps the
 * previous params until the next (allowed) update lands.
 *
 * <p>Called potentially many times per second, so it must stay cheap and must
 * not spam: we log every miss to logcat but forward to the crash reporter only once per
 * process (a swallowed platform throttle is worth knowing about once, not on
 * every frame).
 */
public final class PipGuard {

    private static final String TAG = "RTPipGuard";

    /** Forward the swallowed failure to the crash reporter at most once, so we don't flood the dashboard. */
    private static volatile boolean reported;

    private PipGuard() {
    }

    /**
     * Drop-in replacement for {@code activity.setPictureInPictureParams(params)}
     * that never throws. The receiver is passed as the first argument (the
     * original {@code invoke-virtual} receiver register), typed as the base
     * {@link Activity} so a single bridge covers every Twitch activity subclass.
     */
    public static void setParams(Activity activity, PictureInPictureParams params) {
        if (activity == null || params == null) {
            return;
        }
        try {
            activity.setPictureInPictureParams(params);
        } catch (Throwable t) {
            // Almost always the system's "Too many PiP aspect ratio change
            // requests" throttle. Swallow it: the next allowed update corrects
            // the params. Never let it escape into Twitch's main-thread loop.
            Log.w(TAG, "setPictureInPictureParams failed; ignoring", t);
            if (!reported) {
                reported = true;
                CrashReporter.report(TAG, "setPictureInPictureParams failed", t);
            }
        }
    }
}
