package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * `CHAT_SYNC`, see `ChatSync`. The channel's chat delay lives in the chat connection model (31.9.0 `js5`,
 * `"ChannelConnection(chatChannel="`), its only `long` field; the one read of it (`kp.invoke`, a merged lambda)
 * hands it to the coroutine that holds each message back. The value goes through `ChatSync.delayMs` right there.
 * Skipped softly.
 */
val retwitchChatSyncPatch = bytecodePatch(
    name = "ReTwitch chat sync",
    description = "Optionally holds chat back by the stream's latency.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            var model: String? = null
            classDefForEach { c ->
                val strings = c.methods.firstOrNull { it.name == "toString" }?.implementation?.instructions
                    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.orEmpty()
                if ("ChannelConnection(chatChannel=" in strings) model = c.type
            }
            val type = model ?: throw IllegalStateException("no chat connection model")
            val delay = classDefBy(type).fields.single { it.type == "J" }
            val readers = ArrayList<Pair<String, String>>()
            classDefForEach { c ->
                c.methods.filter { m ->
                    m.implementation?.instructions?.any { i ->
                        i.opcode == Opcode.IGET_WIDE && ((i as ReferenceInstruction).reference as FieldReference)
                            .let { it.definingClass == type && it.name == delay.name }
                    } == true
                }.forEach { readers += c.type to it.name + it.parameterTypes.joinToString("") }
            }
            val (owner, signature) = readers.single()
            val method = mutableClassDefBy(owner).methods.single { it.name + it.parameterTypes.joinToString("") == signature }
            val code = method.instructions.toList()
            val read = code.indexOfFirst { i ->
                i.opcode == Opcode.IGET_WIDE && ((i as ReferenceInstruction).reference as FieldReference)
                    .let { it.definingClass == type && it.name == delay.name }
            }
            val reg = (code[read] as TwoRegisterInstruction).registerA
            method.addInstructions(
                read + 1,
                """
                    invoke-static/range {v$reg .. v${reg + 1}}, Ltv/retwitch/mod/ChatSync;->delayMs(J)J
                    move-result-wide v$reg
                """.trimIndent(),
            )
            println("[ReTwitch] chat sync: $owner->${method.name}")
        } catch (e: Exception) {
            println("[ReTwitch] chat sync skipped: $e")
        }
    }
}
