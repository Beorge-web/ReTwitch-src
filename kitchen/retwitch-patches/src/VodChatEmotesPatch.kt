package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/** `toString()` of the VOD chat message model (31.9.0 `n67`: body, emoticons, badges, color). */
val chommentMessageModelFingerprint = fingerprint {
    returns("Ljava/lang/String;")
    strings("ChommentMessageModel(body=")
}

/**
 * VOD chat: the server gives emote ranges in UTF-8 bytes, the app cuts chars with them, so an emote after
 * Cyrillic or an emoji stays text. On entry of the model's constructor `VodChatEmotes.fix` turns the ranges into
 * char ranges. The parameter order changes between builds (31.9.0 `(body, ArrayList emoticons, List, color)`,
 * 32.0.0 `(body, color, List emoticons, List)`): body and emoticons are the first two fields `toString()` reads,
 * their parameters the ones the constructor stores there.
 */
val retwitchVodChatEmotesPatch = bytecodePatch(
    name = "ReTwitch VOD chat emote offsets",
    description = "Twitch emotes in VOD chat after non-Latin text show as pictures (bug fix, on by default).",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            fun fieldOf(insn: com.android.tools.smali.dexlib2.iface.instruction.Instruction) =
                (insn as? ReferenceInstruction)?.reference as? FieldReference
            val (body, emoticons) = chommentMessageModelFingerprint.method.instructions
                .filter { it.opcode == Opcode.IGET_OBJECT }.mapNotNull(::fieldOf).take(2)
            if (body.type != "Ljava/lang/String;" || !emoticons.type.startsWith("Ljava/util/")) {
                throw PatchException("toString reads ${body.type}, ${emoticons.type}")
            }
            val ctor = mutableClassDefBy(chommentMessageModelFingerprint.classDef.type).methods.single {
                it.name == "<init>" && it.parameterTypes.size == 4
            }
            val code = ctor.instructions.toList()
            val firstParam = ctor.implementation!!.registerCount - 4 - 1
            fun paramOf(field: FieldReference): Int {
                val reg = code.filter { it.opcode == Opcode.IPUT_OBJECT && fieldOf(it) == field }
                    .map { (it as TwoRegisterInstruction).registerA }.single()
                if (reg <= firstParam) throw PatchException("$field is not stored from a parameter")
                return reg - firstParam
            }
            ctor.addInstructions(
                0,
                "invoke-static {p${paramOf(body)}, p${paramOf(emoticons)}}, " +
                    "Ltv/retwitch/mod/VodChatEmotes;->fix(Ljava/lang/String;Ljava/util/List;)V",
            )
            println("[ReTwitch] VOD chat emotes: ${ctor.definingClass}-><init>")
        } catch (throwable: Throwable) {
            println("[ReTwitch] VOD chat emotes skipped: $throwable")
        }
    }
}
