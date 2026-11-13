package tv.retwitch.patches

import app.morphe.patcher.fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Matches the lambda that builds the VOD usher.ttvnw.net playlist request
 * URL. "nauth"/"nauthsig" are the query parameter names this endpoint uses
 * for the playback token/signature (the live endpoint uses "token"/"sig"
 * instead), so together with "acmb" they uniquely disambiguate this from
 * [usherLiveUrlFingerprint] and from every other method in the app.
 *
 * Unlike the live builder, this lambda's *class* is shared/multiplexed by
 * R8 across dozens of unrelated call sites with the same captured-value
 * shape (Kotlin merges structurally-identical lambdas), all funneled
 * through one `invoke` method and a `packed-switch` on a discriminator
 * field. That means this one method contains many unrelated
 * `return-object` instructions belonging to other lambdas entirely, so we
 * can't just patch "the last return" like the live patch does -- see
 * where this fingerprint's method is used below.
 */
val usherVodUrlFingerprint = fingerprint {
    strings(
        "nauth",
        "nauthsig",
        "acmb",
        "allow_source",
        "cache_buster",
    )
}

val retwitchVodUsherPatch = bytecodePatch(
    name = "ReTwitch VOD usher source quality unlock",
    description = "Forces allow_source=true / applies the selected usher proxy on VOD playlist requests too.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        val method = usherVodUrlFingerprint.method

        // This method's class is shared by R8 across many unrelated
        // lambdas, so it has many unrelated `return-object` instructions.
        // Anchor on the *only* `Uri.Builder#build()` call in the whole
        // method instead -- building the final playlist Uri via
        // Uri.Builder only happens in the VOD-manifest branch we want.
        val buildCallIndex = method.instructions.indexOfFirst { instruction ->
            instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                (instruction as ReferenceInstruction).reference.let {
                    it is MethodReference &&
                        it.definingClass == "Landroid/net/Uri\$Builder;" &&
                        it.name == "build"
                }
        }
        check(buildCallIndex >= 0) { "VOD usher URL builder has no Uri.Builder#build() call" }

        val moveResultIndex = buildCallIndex + 1
        val moveResultInstruction = method.instructions[moveResultIndex]
        check(moveResultInstruction.opcode == Opcode.MOVE_RESULT_OBJECT) {
            "expected move-result-object right after Uri.Builder#build()"
        }
        val register = (moveResultInstruction as OneRegisterInstruction).registerA

        // Rewrap the freshly-built Uri sitting in that register in place;
        // whatever the original code does with it next (return it, stash
        // it, etc.) then sees the rewritten Uri.
        method.addInstructions(
            moveResultIndex + 1,
            """
                invoke-static {v$register}, Ltv/retwitch/mod/UsherHook;->forceVodSourceQuality(Landroid/net/Uri;)Landroid/net/Uri;
                move-result-object v$register
            """.trimIndent(),
        )
    }
}
