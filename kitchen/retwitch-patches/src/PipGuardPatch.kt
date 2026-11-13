package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Stops Twitch's auto Picture-in-Picture code from crashing the app on
 * Android's PiP aspect-ratio rate limit.
 *
 * ## The crash this fixes
 *
 * Twitch pushes fresh `PictureInPictureParams` at the system whenever the
 * player's bounds change (source-rect hint + aspect ratio). Android throttles
 * aspect-ratio changes -- `ActivityClientController.ensureValidPictureInPictureActivityParams`
 * throws `IllegalStateException: "Too many PiP aspect ratio change requests
 * from <pkg>"` once the app exceeds the allowed rate. When the offending
 * `setPictureInPictureParams` call runs on a plain main-thread `Handler`
 * callback (not inside an RxJava chain, where it merely logs a NON_FATAL) the
 * exception is uncaught and brings the whole process down. Resizing the player
 * with the side-by-side chat divider ([tv.retwitch.mod.TheatreDividerHook])
 * changes the player bounds more often, which makes the limit easier to hit.
 *
 * ## The fix
 *
 * We can't stop Twitch calling the setter, but we can stop it from crashing.
 * Route every `Activity.setPictureInPictureParams(PictureInPictureParams)` call
 * site through [tv.retwitch.mod.PipGuard.setParams], which swallows the throttle
 * `IllegalStateException` (fail-soft: dropping one params update is harmless,
 * the next allowed update corrects it). The bridge takes the receiver as its
 * first argument, so the original `invoke-virtual {recv, params}` becomes an
 * `invoke-static {recv, params}` with the same registers.
 *
 * ## Why a whole-app scan, not a fingerprint
 *
 * `setPictureInPictureParams` is a stable public framework method, so we match
 * its call sites by the (R8-proof) framework `MethodReference` -- name +
 * single `PictureInPictureParams` parameter + `void` return -- rather than by
 * the shape of the obfuscated Twitch method that happens to contain it. There
 * can be several call sites (set aspect / clear / auto-enter), so we rewrite
 * every one. Finding zero fails the build loudly (the method was renamed or the
 * call removed), per the repo's fingerprint-or-die convention.
 */
private const val GUARD = "Ltv/retwitch/mod/PipGuard;"
private const val PIP_PARAMS = "Landroid/app/PictureInPictureParams;"
private const val SETTER_NAME = "setPictureInPictureParams"

private fun MethodReference.isPipSetter(): Boolean =
    name == SETTER_NAME &&
        parameterTypes.size == 1 &&
        parameterTypes.first() == PIP_PARAMS &&
        returnType == "V"

val retwitchPipGuardPatch = bytecodePatch(
    name = "ReTwitch PiP crash guard",
    description = "Routes Activity.setPictureInPictureParams through a fail-soft bridge so Android's PiP aspect-ratio rate limit can't crash the app (aggravated by the chat divider resizing the player).",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        var rewritten = 0
        var skipped = 0

        classDefForEach { classDef ->
            // Never rewrite our own extension classes -- PipGuard.setParams
            // itself contains the real Activity.setPictureInPictureParams call,
            // and rewriting that would make the guard call itself forever
            // (infinite recursion / StackOverflow, spammed to logcat).
            if (classDef.type.startsWith("Ltv/retwitch/")) return@classDefForEach

            // Cheap immutable pre-scan: skip classes with no PiP setter call so
            // we never make an untouched class mutable.
            val touches = classDef.methods.any { method ->
                method.implementation?.instructions?.any { instruction ->
                    ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.isPipSetter() == true
                } == true
            }
            if (!touches) return@classDefForEach

            val mutableClass = mutableClassDefBy(classDef)
            for (method in mutableClass.methods) {
                if (method.implementation == null) continue

                // (index, receiverRegister, paramsRegister) for each call site,
                // collected before we mutate (replaceInstruction is 1:1 so
                // indices stay valid, but a snapshot keeps the scan simple).
                data class Site(val index: Int, val receiver: Int, val params: Int)

                val sites = ArrayList<Site>()
                method.instructions.forEachIndexed { index, instruction ->
                    val reference =
                        (instruction as? ReferenceInstruction)?.reference as? MethodReference
                            ?: return@forEachIndexed
                    if (!reference.isPipSetter()) return@forEachIndexed
                    val invoke = instruction as? FiveRegisterInstruction ?: return@forEachIndexed
                    // invoke-virtual {vReceiver, vParams}: registerC=receiver, registerD=params.
                    sites.add(Site(index, invoke.registerC, invoke.registerD))
                }

                for (site in sites) {
                    if (site.receiver >= 16 || site.params >= 16) {
                        // 35c (invoke-static {v,v}) only addresses v0..v15 and
                        // /range needs consecutive registers we can't assume.
                        // Leave this rare high-register site as-is rather than
                        // emit an unencodable instruction.
                        skipped++
                        continue
                    }
                    method.replaceInstruction(
                        site.index,
                        "invoke-static {v${site.receiver}, v${site.params}}, " +
                            "$GUARD->setParams(Landroid/app/Activity;$PIP_PARAMS)V",
                    )
                    rewritten++
                }
            }
        }

        check(rewritten > 0) {
            "no Activity.setPictureInPictureParams(...) call sites found to guard " +
                "(skipped $skipped high-register sites) -- did the method get renamed/removed?"
        }
    }
}
