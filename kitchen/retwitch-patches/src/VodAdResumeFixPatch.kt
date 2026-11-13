package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Fixes VOD playback jumping back to the very start after an in-stream ad is
 * (sometimes spuriously) reported as completed.
 *
 * Root cause (found via [VodSeekProbe] logging on a live repro): Twitch's VOD
 * player-tracker (`w70` in one decode) reacts to player events. When an ad
 * *break starts* it stashes the current playhead into an int field (`l`) so it
 * can resume there afterwards; its ad-resume method (`L2`) then does
 * `presenter.seekTo(l)`. But the `AdPlaybackCompleted` event path calls `L2`
 * WITHOUT ever stashing a position first -- so `l` is still its default `0`,
 * and the player seeks to the very beginning. (The sibling `AdsNotAvailable`
 * path does it right: it writes `y1()` -- the live playhead -- into `l` before
 * calling `L2`.) This reproduces on the stock app too, so it is not a ReTwitch
 * regression.
 *
 * Fix: right before that `seekTo`, replace the argument with
 * `VodAdResumeFix.pick(stashed, presenter.y1())` -- keep the stashed position
 * when it is a real (> 0) resume point, otherwise use the live playhead so
 * playback stays put instead of seeking to 0.
 *
 * The choice is made in a Java helper (a single `invoke-static`) rather than as
 * injected branching smali ON PURPOSE: `L2` reuses one register as both an
 * object (`Ldt`) and an int, and adding an `if`-branch there made the dex
 * verifier reject the method (`VerifyError`: args to if-ne "(Conflict,Integer)").
 * A branch-free injection sidesteps register-type merge conflicts entirely.
 *
 * Located structurally (no obfuscated names hardcoded):
 *   - the tracker class via its constructor's unique checkNotNull parameter
 *     strings "vodPlayerPresenter" + "adsEventFlowable";
 *   - `L2` as the seek whose argument is loaded from an instance int field
 *     (`iget`), distinguishing it from the class's plain `seekTo(int)` delegate
 *     whose argument is the method parameter;
 *   - `y1()` / `seekTo(int)` / the presenter type read live off the matched
 *     `seekTo` instruction, so they are always correct for the build patched.
 */
private const val FIX = "Ltv/retwitch/mod/VodAdResumeFix;"


val retwitchVodAdResumeFixPatch = bytecodePatch(
    name = "ReTwitch VOD ad-completion resume fix",
    description = "Stops VOD progress from jumping to the start when an in-stream ad is (spuriously) reported completed.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        // The VOD ads tracker's post-ad resume: the only place in the APK that
        // reads a stashed int field straight into `presenter.seekTo(int)`
        // (`iget vX; invoke-virtual {presenter, vX}, ...->seekTo(I)V`). Unique
        // in 30.2.0 and 31.8.0; replaces the "vodPlayerPresenter" /
        // "adsEventFlowable" parameter-name anchor 31.x strips.
        var site: Triple<app.morphe.patcher.util.proxy.mutableTypes.MutableMethod, Int, FiveRegisterInstruction>? = null
        classDefForEach { classDef ->
            if (site != null) return@classDefForEach
            classDef.methods.forEach { m ->
                val insns = m.implementation?.instructions?.toList() ?: return@forEach
                for (i in 1 until insns.size) {
                    val ref = (insns[i] as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                    if (insns[i].opcode != Opcode.INVOKE_VIRTUAL || ref.name != "seekTo" ||
                        ref.returnType != "V" || ref.parameterTypes.map { it.toString() } != listOf("I")
                    ) continue
                    val call = insns[i] as FiveRegisterInstruction
                    val prev = insns[i - 1]
                    if (prev.opcode == Opcode.IGET && (prev as OneRegisterInstruction).registerA == call.registerD) {
                        val mutable = mutableClassDefBy(classDef).methods.first {
                            it.name == m.name && it.returnType == m.returnType &&
                                it.parameterTypes.map(Any::toString) == m.parameterTypes.map(Any::toString)
                        }
                        site = Triple(mutable, i, call)
                    }
                }
            }
        }
        // 31.9.0 fixed it upstream: the stash is a nullable Integer and the seek is skipped when unset.
        val (method, index, call) = site ?: return@execute println("[ReTwitch] VOD ad-resume fix skipped: no iget -> seekTo(I)")

        // Swap the virtual call for our static guard: same registers, the
        // presenter as a plain Object. Only skips a seek to an unset (<= 0)
        // stashed position.
        method.replaceInstruction(
            index,
            "invoke-static {v${call.registerC}, v${call.registerD}}, $FIX->seek(Ljava/lang/Object;I)V",
        )
    }
}
