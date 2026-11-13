package tv.retwitch.mod;

/**
 * Tiny branchless helper for {@code VodAdResumeFixPatch}.
 *
 * Twitch's VOD player-tracker resumes playback after an in-stream ad by
 * seeking to a position it stashed when the ad break started. On a spurious
 * {@code AdPlaybackCompleted} (an ad reported complete that never properly
 * started) that stashed value is still its default {@code 0}, so the player
 * jumps to the very beginning.
 *
 * The patch replaces that {@code seekTo} call with {@link #seek(Object, int)},
 * which skips the jump when nothing was stashed. Branch-free on the smali side
 * on purpose: the target method reuses one register as both an object and an
 * int, and an injected branch there tripped the dex verifier
 * ({@code VerifyError}: args to if-ne "(Conflict, Integer)").
 *
 * Depends only on plain Java, so it stays valid across app updates.
 */
public final class VodAdResumeFix {

    private VodAdResumeFix() {
    }

    /**
     * @param stashed the position the tracker wants to resume at (ms); {@code 0}
     *                or negative means "never actually recorded" (spurious ad).
     * @param current the live playhead (ms), from the presenter's {@code y1()}.
     * @return {@code stashed} when it is a real (&gt; 0) position, otherwise the
     *         live playhead so playback stays put instead of jumping to the start.
     */
    /**
     * Replaces the tracker's {@code presenter.seekTo(stashed)}: seeks only to a
     * real stashed position, otherwise stays where playback is (seeking to the
     * current position is a no-op, so no R8-renamed position getter is needed).
     * Injected in place of Twitch's call -- must never throw.
     */
    public static void seek(Object presenter, int stashed) {
        try {
            if (presenter != null && stashed > 0) {
                presenter.getClass().getMethod("seekTo", int.class).invoke(presenter, stashed);
            }
        } catch (Throwable t) {
            android.util.Log.w("RTVodAdResume", "seek failed", t);
            CrashReporter.report("RTVodAdResume", "seek failed", t);
        }
    }
}
