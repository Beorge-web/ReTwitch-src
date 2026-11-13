package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val SETUP_BEGIN_EVENT = "play_integrity_setup_begin"

/**
 * Login on a re-signed app ("This app version/OS is not currently supported"),
 * after "Fix login" in arandomhooman/hoomans-morphe-patches and bakwudo/uyu:
 * Twitch sets up Play Integrity, requests a token and POSTs it to
 * `/api/v1/android/attestation` (31.8.0 `vtt.a`, run by `vtt.b` two minutes
 * after start behind an experiment flag). A re-signed APK fails that check and
 * the server refuses the device. Login itself sends only credentials, so the
 * setup method returns at once while the (default-on) fix toggle is on, as on
 * a device without Google Play. The method is a suspend function whose only
 * caller compares the result with COROUTINE_SUSPENDED, so null completes it.
 */
val retwitchFixLoginPatch = bytecodePatch(
    name = "ReTwitch fix login",
    description = "Doesn't send the Play Integrity attestation, which a re-signed app fails and which blocks login.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            fun logsSetupBegin(m: com.android.tools.smali.dexlib2.iface.Method) =
                m.implementation?.instructions?.any {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == SETUP_BEGIN_EVENT
                } == true

            var owner: String? = null
            classDefForEach { c ->
                if (c.methods.any(::logsSetupBegin)) {
                    require(owner == null) { "\"$SETUP_BEGIN_EVENT\" in more than one class" }
                    owner = c.type
                }
            }
            val method = mutableClassDefBy(requireNotNull(owner) { "attestation class not found" })
                .methods.single { logsSetupBegin(it) }
            require(method.returnType == "Ljava/lang/Object;") { "setup method is no longer a suspend function" }
            val ins = 1 + method.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
            require(method.implementation!!.registerCount > ins) { "no free register" }

            method.addInstructionsWithLabels(
                0,
                """
                    sget-object v0, Ltv/retwitch/mod/Toggles;->FIX_LOGIN:Ltv/retwitch/mod/Toggles${'$'}Toggle;
                    invoke-static {v0}, Ltv/retwitch/mod/Toggles;->on(Ltv/retwitch/mod/Toggles${'$'}Toggle;)Z
                    move-result v0
                    if-eqz v0, :rt_attest_original
                    const/4 v0, 0x0
                    return-object v0
                """.trimIndent(),
                ExternalLabel("rt_attest_original", method.getInstruction(0)),
            )
        } catch (throwable: Throwable) {
            println("[ReTwitch] fix login skipped: $throwable")
        }
    }
}
