package tv.retwitch.patches

import app.morphe.patcher.fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/**
 * Matches Fragment.onCreateView() overrides by structure (return type,
 * parameter types, a distinctive string constant baked in by the Kotlin
 * compiler's null-check for the "settingsLatencyTracker" field) instead of
 * by the obfuscated class/method name, which Twitch's R8 build renames on
 * every release.
 */
val settingsOnCreateViewFingerprint = fingerprint {
    returns("Landroid/view/View;")
    parameters(
        "Landroid/view/LayoutInflater;",
        "Landroid/view/ViewGroup;",
        "Landroid/os/Bundle;",
    )
    strings("settingsLatencyTracker")
}

val retwitchSettingsPatch = bytecodePatch(
    name = "ReTwitch settings entry",
    description = "Adds a ReTwitch row to the bottom of the main Settings screen.",
) {
    compatibleWith(twitchCompatibility)

    // Merges tv.retwitch.mod.SettingsHook / DiagProbe (plain javac+d8 dex,
    // no dependency on Twitch's own obfuscated classes) into the patched app.
    dependsOn(retwitchExtensionPatch)

    execute {
        val method = settingsOnCreateViewFingerprint.method

        val returnIndex = method.instructions.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
        check(returnIndex >= 0) { "onCreateView has no return-object instruction" }
        val view = (method.instructions[returnIndex] as OneRegisterInstruction).registerA

        // Re-wrap the view in the register the original `return-object` reads.
        // Only that register is touched: by the return, R8 may have reused
        // `p0` and other registers (31.8.0 puts an unrelated object in p0).
        method.addInstructions(
            returnIndex,
            """
                invoke-static/range {v$view .. v$view}, Ltv/retwitch/mod/SettingsHook;->wrap(Landroid/view/View;)Landroid/view/View;
                move-result-object v$view
            """.trimIndent(),
        )
    }
}
