package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * Chat messages one by one, as they arrive (`Toggles.CHAT_REALTIME`, off by default).
 * Twitch's chat SDK batches incoming messages: the controller's message handler (31.9.0
 * `zs5.p(pim)`) asks its "batching enabled" flag (`zs5.c()`, true whenever the SDK has its
 * config) and, for a message list event, queues it in the batcher (`fd6.a(channel, g55)`)
 * instead of dispatching it. A loop flushes the queue every `MessageBatchingConfig(interval=)`
 * (1 s, built in DI), so the UI gets every message of the last second at once, up to a second
 * late. Only that first flag read goes through `LiveChatHook.batchChatMessages`; the flush
 * loop and the batcher's removal of deleted messages stay as they are, so the toggle can flip
 * mid-session (anything already queued goes out on the next tick). Skipped softly.
 */
val retwitchChatBatchingPatch = bytecodePatch(
    name = "ReTwitch real-time chat",
    description = "Optionally shows chat messages one by one as they arrive instead of Twitch's once-a-second batches.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            var config: String? = null
            classDefForEach { c ->
                if (c.methods.any { m ->
                        m.name == "toString" && m.implementation?.instructions?.any {
                            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "MessageBatchingConfig(interval="
                        } == true
                    }
                ) config = c.type
            }
            val configType = config ?: throw PatchException("MessageBatchingConfig not found")

            // The batcher: built from the config, queues with `(channel, messages, continuation): Object`.
            val batchers = HashSet<String>()
            classDefForEach { c ->
                if (c.methods.any { it.name == "<init>" && it.parameterTypes.size <= 5 && it.parameterTypes.any { p -> p.toString() == configType } }) {
                    batchers += c.type
                }
            }

            var site: Pair<String, Int>? = null
            var sites = 0
            classDefForEach { c ->
                for (m in c.methods) {
                    val code = m.implementation?.instructions?.toList() ?: continue
                    val enqueue = code.indexOfFirst {
                        val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                        it.opcode == Opcode.INVOKE_VIRTUAL && ref != null && ref.definingClass in batchers &&
                            ref.returnType == "Ljava/lang/Object;" && ref.parameterTypes.size == 3 &&
                            ref.parameterTypes[0].toString() == "Ljava/lang/String;"
                    }
                    if (enqueue < 0) continue
                    // The flag read guarding it: `invoke-virtual {p0}, Self->x()Z` + `move-result`.
                    val flag = code.subList(0, enqueue).indexOfLast {
                        val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                        it.opcode == Opcode.INVOKE_VIRTUAL && ref != null && ref.definingClass == c.type &&
                            ref.returnType == "Z" && ref.parameterTypes.isEmpty()
                    }
                    if (flag >= 0 && code[flag + 1].opcode == Opcode.MOVE_RESULT) {
                        site = "${c.type}->${m.name}" to flag + 1
                        sites++
                    }
                }
            }
            if (sites != 1) throw PatchException("batching gate: $sites sites")
            val (name, index) = site!!
            val owner = name.substringBefore("->")
            val method = mutableClassDefBy(owner).methods.single { m ->
                m.name == name.substringAfter("->") && m.implementation?.instructions?.any {
                    val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                    ref != null && ref.definingClass in batchers
                } == true
            }
            val reg = (method.instructions[index] as OneRegisterInstruction).registerA
            method.addInstructions(
                index + 1,
                """
                    invoke-static/range {v$reg .. v$reg}, Ltv/retwitch/mod/LiveChatHook;->batchChatMessages(Z)Z
                    move-result v$reg
                """.trimIndent(),
            )
            println("[ReTwitch] real-time chat: batching gate $name #$index ($configType)")
        } catch (e: Exception) {
            println("[ReTwitch] real-time chat skipped: $e")
        }
    }
}
