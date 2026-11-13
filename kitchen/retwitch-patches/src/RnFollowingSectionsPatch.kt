package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/**
 * Sections of the React Native Following tab can be hidden (categories, Continue Watching, offline
 * channels). RN's `fetch` reads bodies as blobs: the handler (31.9.0 `p43.a([B)`, the one method
 * from bytes to a `WritableMap`) gets the whole answer, so `RnFollowingHook.onResponse` swaps the
 * bytes at its entry and the blob size follows.
 *
 * The same tab has Twitch's own watch streak tag, off behind flags: `TwitchRNHostPropsBuilder.build`
 * (not obfuscated) goes through `RnFollowingHook.onHostProps`, which adds the `eppoFlagOverrides` prop.
 */
val retwitchRnFollowingSectionsPatch = bytecodePatch(
    name = "ReTwitch Following sections",
    description = "Optionally hides sections below the live channels on the Following tab.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            var owner: String? = null
            classDefForEach { c ->
                if (c.methods.any { it.isBlobHandler() }) {
                    check(owner == null) { "two blob handlers: $owner, ${c.type}" }
                    owner = c.type
                }
            }
            mutableClassDefBy(owner ?: error("blob handler not found")).methods.first { it.isBlobHandler() }
                .addInstructions(0, """
                    invoke-static {p1}, Ltv/retwitch/mod/RnFollowingHook;->onResponse([B)[B
                    move-result-object p1
                """.trimIndent())
        } catch (e: Exception) {
            println("[ReTwitch] Following sections skipped: $e")
        }
        try {
            val build = mutableClassDefBy("Ltv/twitch/android/feature/discovery/feed/rn/runtime/TwitchRNHostPropsBuilder;")
                .methods.single { it.name == "build" && it.returnType == "Landroid/os/Bundle;" && it.parameterTypes.size == 2 }
            val insns = build.implementation!!.instructions.toList()
            val at = insns.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
            val props = (insns[at] as OneRegisterInstruction).registerA
            build.addInstructions(at, "invoke-static {v$props}, Ltv/retwitch/mod/RnFollowingHook;->onHostProps(Landroid/os/Bundle;)V")
        } catch (e: Exception) {
            println("[ReTwitch] Following streak tag skipped: $e")
        }
    }
}

private fun com.android.tools.smali.dexlib2.iface.Method.isBlobHandler() =
    returnType == "Lcom/facebook/react/bridge/WritableMap;" && parameterTypes.map(Any::toString) == listOf("[B")
