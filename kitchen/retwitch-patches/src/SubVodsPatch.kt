package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/**
 * Sub-only VODs, after TwitchNoSub: a VOD list item's click asks to subscribe
 * instead of playing while `VodModel.isRestricted()` (public model, name kept
 * by R8) is true, and the list draws a lock. Its result goes through
 * `UsherHook.vodRestricted` (false with the toggle on); the refused usher
 * master is then rebuilt by `VaftServer`/`SubVods` (see `UsherHook`).
 * The getter is `.locals 0`, so the result register of its `return` is reused.
 */
val retwitchSubVodsPatch = bytecodePatch(
    name = "ReTwitch sub-only VODs",
    description = "Lets sub-only VODs be opened and played without a sub (toggle, off by default).",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val method = mutableClassDefBy("Ltv/twitch/android/models/videos/VodModel;").methods.single {
                it.name == "isRestricted" && it.parameterTypes.isEmpty() && it.returnType == "Z"
            }
            val returnIndex = method.instructions.indexOfFirst { it.opcode == Opcode.RETURN }
            check(returnIndex >= 0) { "VodModel.isRestricted() has no return" }
            val register = (method.instructions[returnIndex] as OneRegisterInstruction).registerA
            method.addInstructions(
                returnIndex,
                """
                    invoke-static {v$register}, Ltv/retwitch/mod/UsherHook;->vodRestricted(Z)Z
                    move-result v$register
                """.trimIndent(),
            )
        } catch (throwable: Throwable) {
            println("[ReTwitch] sub-only VODs skipped: $throwable")
        }
    }
}
