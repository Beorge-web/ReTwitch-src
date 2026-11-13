package tv.retwitch.mod;

import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Bridge invoked from smali injected at the entry of Twitch's Community Points
 * button "render state" method (obfuscated {@code CommunityPointsButtonPresenter},
 * see {@code AutoClaimPatch}). That method runs on every state emission, and its
 * state object carries the currently-available claim (or null).
 *
 * <p>When a claim is present, the injected code asks {@link #shouldClaim} whether
 * to fire the claim; if so, it invokes the app's own claim API with the claim id.
 * Because the render method can emit many times for the same available claim,
 * {@link #shouldClaim} returns {@code true} at most once per claim id (dedup),
 * which prevents spamming the claim mutation.
 *
 * <p>Disabled by default; opt-in via the ReTwitch menu.
 */
public final class AutoClaimHook {

    private static final String TAG = "RTautoclaim";

    private static final Set<String> attemptedClaimIds =
            Collections.synchronizedSet(new HashSet<String>());

    private AutoClaimHook() {
    }

    /**
     * @param claimId the id of the currently-available Community Points claim
     * @return {@code true} exactly once per claim id while auto-claim is enabled
     */
    public static boolean shouldClaim(String claimId) {
        try {
            if (claimId == null || claimId.isEmpty()) {
                return false;
            }
            if (!ChatSettings.isAutoClaimEnabled()) {
                return false;
            }
            // add() is false if the id was already seen -> claim only once.
            if (!attemptedClaimIds.add(claimId)) {
                return false;
            }
            Log.i(TAG, "auto-claiming community points, claimId=" + claimId);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "shouldClaim failed", t);
            CrashReporter.report(TAG, "shouldClaim failed", t);
            return false;
        }
    }
}
