package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/**
 * Watching without signing in, after lurk's "Watch without signing in": Twitch's logged-out experience
 * is an Eppo experiment (31.9.0 `nho`, `<clinit>` with "variant1", "variant7", "no_nudges"). Its getter
 * `a()Z` answers false when signed in, else whether the device got a variant (by default none, so the
 * app routes to the login screen). With the toggle on and nobody signed in it answers true; the signed-in
 * check stays first, so login is untouched. Read when the intent router starts: needs a restart.
 */
val retwitchLoggedOutPatch = bytecodePatch(
    name = "ReTwitch watch without signing in",
    description = "Lets the app be used without an account.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            fun hasString(c: com.android.tools.smali.dexlib2.iface.ClassDef, s: String) = c.methods.any { m ->
                m.name == "<clinit>" && m.implementation?.instructions?.any {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == s
                } == true
            }
            var owner: String? = null
            classDefForEach { c ->
                if (hasString(c, "no_nudges") && hasString(c, "variant1") && hasString(c, "variant7")) {
                    require(owner == null) { "two logged-out experiments: $owner, ${c.type}" }
                    owner = c.type
                }
            }
            val method = mutableClassDefBy(owner ?: throw PatchException("logged-out experiment not found"))
                .methods.single { m ->
                    m.returnType == "Z" && m.parameterTypes.isEmpty() && m.implementation?.instructions?.any {
                        it.opcode == Opcode.INVOKE_INTERFACE &&
                            it.getReference<MethodReference>()?.name == "containsKey"
                    } == true
                }
            val code = method.instructions.toList()
            require(code[0].opcode == Opcode.IGET_OBJECT && code[1].opcode == Opcode.INVOKE_VIRTUAL &&
                code[2].opcode == Opcode.MOVE_RESULT && code[3].opcode == Opcode.IF_EQZ) { "getter shape changed" }
            val signedIn = code[0].getReference<FieldReference>()!!
            val check = code[1].getReference<MethodReference>()!!
            method.addInstructionsWithLabels(
                0,
                """
                    sget-object v0, Ltv/retwitch/mod/Toggles;->WATCH_LOGGED_OUT:Ltv/retwitch/mod/Toggles${'$'}Toggle;
                    invoke-static {v0}, Ltv/retwitch/mod/Toggles;->on(Ltv/retwitch/mod/Toggles${'$'}Toggle;)Z
                    move-result v0
                    if-eqz v0, :rt_logged_out_original
                    iget-object v0, p0, $signedIn
                    invoke-virtual {v0}, $check
                    move-result v0
                    if-nez v0, :rt_logged_out_original
                    const/4 v0, 0x1
                    return v0
                """.trimIndent(),
                ExternalLabel("rt_logged_out_original", method.getInstruction(0)),
            )
            println("[ReTwitch] watch without signing in: $owner")
        } catch (throwable: Throwable) {
            println("[ReTwitch] watch without signing in skipped: $throwable")
        }
    }
}
