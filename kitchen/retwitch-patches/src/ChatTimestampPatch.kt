package tv.retwitch.patches

import app.morphe.patcher.fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/** `toString()` of the chat row's span group (31.8.0 `wa6`): timestamp, badges, sender, header, message. */
val chatLineSpanGroupFingerprint = fingerprint {
    returns("Ljava/lang/String;")
    strings("ChatMessageSpanGroup(timestampSpan=")
}

/** `toString()` of the live chat message model (31.8.0 `a56`). */
val chatLineLiveMessageFingerprint = fingerprint {
    returns("Ljava/lang/String;")
    strings("LiveChatMessage(messageId=")
}

val retwitchChatTimestampPatch = bytecodePatch(
    name = "ReTwitch chat timestamp format",
    description = "Chat timestamps with seconds / 12 h, shown even when Twitch's own are off.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val spanGroupType = chatLineSpanGroupFingerprint.classDef.type

            // 1. The span-group builder (31.8.0 va6.b, the body builder's class) formats the
            // message's epoch seconds with its own `H:mm` SimpleDateFormat field: swap the format
            // right after that field read (followed by DateFormat.format, sequential code).
            val builderClass = mutableClassDefBy(chatMessageBodyFingerprint.method.definingClass)
            val builder = builderClass.methods.singleOrNull { m ->
                m.returnType == spanGroupType && m.implementation?.instructions?.any {
                    it.opcode == Opcode.IGET_OBJECT &&
                        it.getReference<FieldReference>()?.type == "Ljava/text/SimpleDateFormat;"
                } == true
            } ?: throw PatchException("span group builder with a SimpleDateFormat read not found")
            val formatRead = builder.instructions.indexOfFirst {
                it.opcode == Opcode.IGET_OBJECT &&
                    it.getReference<FieldReference>()?.type == "Ljava/text/SimpleDateFormat;"
            }
            val formatReg = (builder.instructions[formatRead] as TwoRegisterInstruction).registerA
            builder.addInstructions(
                formatRead + 1,
                """
                    invoke-static/range {v$formatReg .. v$formatReg}, Ltv/retwitch/mod/ChatTimestamps;->format(Ljava/text/SimpleDateFormat;)Ljava/text/SimpleDateFormat;
                    move-result-object v$formatReg
                """.trimIndent(),
            )

            // 2. The live wrapper (31.8.0 m76.b: static, LiveChatMessage -> span group) passes the
            // message's Long timestampSeconds only `if (showTimestamps)`: route that flag through
            // ChatTimestamps.forceShow right after the Long read (the if-* follows it directly).
            val messageType = chatLineLiveMessageFingerprint.classDef.type
            val candidates = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
            classDefForEach { c ->
                c.methods.forEach { m ->
                    if (m.returnType == spanGroupType && AccessFlags.STATIC.isSet(m.accessFlags) &&
                        m.parameterTypes.any { it.toString() == messageType }
                    ) candidates += c.type to m
                }
            }
            fun gateIndex(code: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>) = code.indices.firstOrNull { i ->
                val ref = code[i].getReference<FieldReference>()
                code[i].opcode == Opcode.IGET_OBJECT && ref?.definingClass == messageType &&
                    ref.type == "Ljava/lang/Long;" &&
                    (code.getOrNull(i + 1)?.opcode == Opcode.IF_EQZ || code.getOrNull(i + 1)?.opcode == Opcode.IF_NEZ) &&
                    (code[i + 1] as OneRegisterInstruction).registerA != (code[i] as TwoRegisterInstruction).registerA
            }
            val (ownerType, found) = candidates.singleOrNull { (_, m) ->
                gateIndex(m.implementation?.instructions?.toList() ?: emptyList()) != null
            } ?: throw PatchException("live message span-group wrapper not found (${candidates.size} candidates)")
            val wrapper = mutableClassDefBy(ownerType).methods.first {
                it.name == found.name && it.parameterTypes.map(Any::toString) == found.parameterTypes.map(Any::toString)
            }
            val code = wrapper.instructions.toList()
            val longRead = gateIndex(code)!!
            val flagReg = (code[longRead + 1] as OneRegisterInstruction).registerA
            wrapper.addInstructions(
                longRead + 1,
                """
                    invoke-static/range {v$flagReg .. v$flagReg}, Ltv/retwitch/mod/ChatTimestamps;->forceShow(Z)Z
                    move-result v$flagReg
                """.trimIndent(),
            )
        } catch (e: Exception) {
            println("[ReTwitch] chat timestamp format skipped: $e")
        }
        try {
            // 3. The live message model is built (31.9.0 ac6.a) from `"ChatMessageInfo(userInfo="`,
            // whose Long send time is null on the local echo of one's own message: no timestamp.
            val messageType = chatLineLiveMessageFingerprint.classDef.type
            var infoType: String? = null
            classDefForEach { c ->
                if (infoType == null && c.methods.any { m ->
                        m.name == "toString" && m.implementation?.instructions?.any {
                            it.getReference<com.android.tools.smali.dexlib2.iface.reference.StringReference>()
                                ?.string == "ChatMessageInfo(userInfo="
                        } == true
                    }
                ) infoType = c.type
            }
            val info = infoType ?: throw PatchException("ChatMessageInfo not found")
            fun timeRead(m: com.android.tools.smali.dexlib2.iface.Method) =
                m.implementation?.instructions?.indexOfFirst {
                    val ref = it.getReference<FieldReference>()
                    it.opcode == Opcode.IGET_OBJECT && ref?.definingClass == info && ref.type == "Ljava/lang/Long;"
                } ?: -1
            val builders = mutableListOf<Pair<String, String>>()
            classDefForEach { c ->
                c.methods.filter { m ->
                    m.returnType == messageType && m.parameterTypes.any { it.toString() == info } && timeRead(m) >= 0
                }.forEach { builders += c.type to it.name }
            }
            val (owner, name) = builders.singleOrNull()
                ?: throw PatchException("live message builder: ${builders.size} candidates")
            val builder = mutableClassDefBy(owner).methods.single {
                it.name == name && it.returnType == messageType && it.parameterTypes.any { p -> p.toString() == info }
            }
            val read = timeRead(builder)
            val reg = (builder.instructions[read] as TwoRegisterInstruction).registerA
            builder.addInstructions(
                read + 1,
                """
                    invoke-static/range {v$reg .. v$reg}, Ltv/retwitch/mod/ChatTimestamps;->orNow(Ljava/lang/Long;)Ljava/lang/Long;
                    move-result-object v$reg
                """.trimIndent(),
            )
            println("[ReTwitch] chat timestamp of own messages: $owner->$name")
        } catch (e: Exception) {
            println("[ReTwitch] chat timestamp of own messages skipped: $e")
        }
    }
}
