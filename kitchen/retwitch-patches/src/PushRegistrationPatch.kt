package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val FIS_URL = "https://firebaseinstallations.googleapis.com/v1/"

private fun Method.hasString(value: String) =
    implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == value
    } == true

/**
 * Restores push notifications in the re-signed, renamed app: the Firebase
 * Installations request builder (the method of the FIS client class that sets
 * the `X-Android-Package` / `X-Android-Cert` / `x-goog-api-key` headers) gets
 * every `URLConnection.addRequestProperty` routed through
 * `PushRegistration.addRequestProperty`, which presents Twitch's own package
 * and certificate SHA-1. Same registers, so nothing else in the method moves.
 */
val retwitchPushRegistrationPatch = bytecodePatch(
    name = "ReTwitch push notifications",
    description = "Lets Firebase push registration pass in the re-signed app by presenting Twitch's original package and certificate.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        var found: Pair<ClassDef, Method>? = null
        classDefForEach { classDef ->
            if (classDef.methods.none { it.hasString(FIS_URL) }) return@classDefForEach
            classDef.methods.firstOrNull { it.hasString("X-Android-Cert") && it.hasString("x-goog-api-key") }
                ?.let { found = classDef to it }
        }
        val (classDef, source) = found ?: throw PatchException("Firebase Installations request builder not found")
        val method = mutableClassDefBy(classDef).methods.first {
            it.name == source.name && it.returnType == source.returnType &&
                it.parameterTypes.map(Any::toString) == source.parameterTypes.map(Any::toString)
        }

        val calls = method.instructions.withIndex().filter { (_, insn) ->
            insn.opcode == Opcode.INVOKE_VIRTUAL &&
                ((insn as ReferenceInstruction).reference as? MethodReference)?.let {
                    it.name == "addRequestProperty" && it.parameterTypes.size == 2
                } == true
        }
        if (calls.isEmpty()) throw PatchException("no addRequestProperty calls in ${classDef.type}")
        for ((index, insn) in calls) {
            val call = insn as FiveRegisterInstruction
            method.replaceInstruction(
                index,
                "invoke-static {v${call.registerC}, v${call.registerD}, v${call.registerE}}, " +
                    "Ltv/retwitch/mod/PushRegistration;->addRequestProperty(Ljava/net/URLConnection;Ljava/lang/String;Ljava/lang/String;)V",
            )
        }
    }
}
