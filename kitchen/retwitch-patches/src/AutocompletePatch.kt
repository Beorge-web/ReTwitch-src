package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private fun Instruction.isNewLinkedHashMap() = opcode == Opcode.NEW_INSTANCE &&
    ((this as ReferenceInstruction).reference as? TypeReference)?.type == "Ljava/util/LinkedHashMap;"

val retwitchAutocompletePatch = bytecodePatch(
    name = "ReTwitch third-party emote autocomplete",
    description = "Adds cached 7TV/BTTV/FFZ matches to Twitch's own :code chat-input autocomplete dropdown.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        val emoteModelClassName = emoteModelConstructorFingerprint.method.definingClass

        // The chat input's emote suggestion source (31.8.0 `gna.h`): the
        // `(CharSequence): ArrayList` method that strips the leading ":" and
        // casts its results to the emote model base class (the superclass of
        // the "Generic(id=" model). The old "one contains + one sortedWith"
        // shape matches a String filter (commands/mentions) in 31.x.
        val emoteBaseType = classDefBy(emoteModelClassName).superclass
        fun isEmoteFilter(m: com.android.tools.smali.dexlib2.iface.Method): Boolean {
            if (m.returnType != "Ljava/util/ArrayList;" || m.parameterTypes.map { it.toString() } != listOf("Ljava/lang/CharSequence;")) {
                return false
            }
            val insns = m.implementation?.instructions ?: return false
            return insns.any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == ":" } &&
                insns.any { it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as? TypeReference)?.type == emoteBaseType }
        }
        val filterClassDef = mutableClassDefByOrNull { classDef -> classDef.methods.any(::isEmoteFilter) }
        if (filterClassDef == null) {
            println("[ReTwitch] third-party emote autocomplete skipped: emote filter not found")
            return@execute
        }
        val filterMethod = filterClassDef.methods.first(::isEmoteFilter)

        // Its code -> emote model map, for the images in the input: the filter only runs on autocomplete, so an
        // emote from the picker would find nothing. R8 merged the class; the filter's constructor is the one
        // creating a LinkedHashMap, and the map is handed over as it goes into the field.
        val emoteMapConstructor = filterClassDef.methods.singleOrNull { m ->
            m.name == "<init>" && m.instructions.any { it.isNewLinkedHashMap() }
        }
        if (emoteMapConstructor == null) {
            println("[ReTwitch] Twitch emote images in the input skipped: no constructor with the map")
        } else {
            val code = emoteMapConstructor.instructions.toList()
            val stored = (code.indexOfFirst { it.isNewLinkedHashMap() } until code.size)
                .first { code[it].opcode == Opcode.IPUT_OBJECT }
            val map = (code[stored] as TwoRegisterInstruction).registerA
            emoteMapConstructor.addInstructions(
                stored + 1,
                "invoke-static {v$map}, Ltv/retwitch/mod/emotes/EmoteInputImages;->noteEmotes(Ljava/lang/Object;)V",
            )
        }

        // p0 = this, p1 = the raw CharSequence query, valid at method
        // entry before Twitch's own code reassigns it as scratch space
        // later on. Only 10 locals + 2 params here, well under the
        // /range threshold.
        filterMethod.addInstructions(
            0,
            """
                const-string v0, "$emoteModelClassName"
                invoke-static {p1, v0}, Ltv/retwitch/mod/emotes/EmoteAutocompleteBridge;->begin(Ljava/lang/CharSequence;Ljava/lang/String;)V
            """.trimIndent(),
        )

        // The method's single return point, found by opcode rather than
        // a hardcoded index since inserting the block above shifts every
        // instruction after it by two.
        val returnIndex = filterMethod.instructions.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
        check(returnIndex >= 0) { "autocomplete filter method has no return-object instruction" }
        val returnRegister = (filterMethod.instructions[returnIndex] as OneRegisterInstruction).registerA

        filterMethod.addInstructions(
            returnIndex,
            """
                invoke-static {v$returnRegister}, Ltv/retwitch/mod/emotes/EmoteAutocompleteBridge;->finish(Ljava/util/ArrayList;)V
            """.trimIndent(),
        )
    }
}
