package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * Chat history from Robotty (`RecentMessages`, feedback #39). Twitch's GQL `ChatHistory` answer is parsed into a
 * channel model (31.9.0 `vz5(String id, ArrayList recentChatMessages)`, toString `", recentChatMessages="`) by its
 * adapter (`g06.fromJson`, strings `"recentChatMessages"`). Before that constructor the list goes through
 * `RecentMessages.onTwitchHistory(id, list)`, which empties it when Robotty's lines replace it. The theatre's
 * `TheatrePortalPreloadQuery` carries the same `Channel(id, recentChatMessages)` (31.9.0 `oe40`, adapter `rf40`) and
 * gets the same hook; `RecentMessages` puts one backlog in per open. Skipped softly.
 */
val retwitchRobottyHistoryPatch = bytecodePatch(
    name = "ReTwitch Robotty chat history",
    description = "Optionally fills the chat's history from Robotty's recent-messages service.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            fun strings(c: ClassDef) = c.methods.flatMap { m ->
                m.implementation?.instructions?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.orEmpty()
            }
            val models = HashSet<String>()
            classDefForEach { c ->
                if (", recentChatMessages=" in strings(c) && c.methods.any {
                        it.name == "<init>" && it.parameterTypes.map { p -> p.toString() } == listOf("Ljava/lang/String;", "Ljava/util/ArrayList;")
                    }
                ) models += c.type
            }
            if (models.isEmpty()) throw PatchException("history model not found")
            val owners = ArrayList<String>()
            classDefForEach { c ->
                if ("recentChatMessages" in strings(c) && c.methods.any { m ->
                        m.implementation?.instructions?.any { isModelInit(it, models) } == true
                    }
                ) owners += c.type
            }
            var hooked = 0
            owners.forEach { owner ->
                mutableClassDefBy(owner).methods.forEach { m ->
                    val code = m.implementation?.instructions?.toList() ?: return@forEach
                    code.indices.filter { isModelInit(code[it], models) }.reversed().forEach { i ->
                        val call = code[i] as FiveRegisterInstruction
                        val id = call.registerD
                        val list = call.registerE
                        if (id > 15 || list > 15) {
                            println("[ReTwitch] Robotty history: registers v$id/v$list too high in $owner, skipped")
                            return@forEach
                        }
                        m.addInstructions(i, """
                            invoke-static {v$id, v$list}, Ltv/retwitch/mod/RecentMessages;->onTwitchHistory(Ljava/lang/String;Ljava/util/ArrayList;)Ljava/util/ArrayList;
                            move-result-object v$list
                        """.trimIndent())
                        hooked++
                    }
                }
            }
            println("[ReTwitch] Robotty history: $hooked hook(s) in $owners for $models")
        } catch (e: Exception) {
            println("[ReTwitch] Robotty history skipped: $e")
        }
    }
}

private fun isModelInit(insn: com.android.tools.smali.dexlib2.iface.instruction.Instruction, models: Set<String>): Boolean {
    if (insn.opcode != Opcode.INVOKE_DIRECT) return false
    val ref = (insn as ReferenceInstruction).reference as MethodReference
    return ref.name == "<init>" && ref.definingClass in models && ref.parameterTypes.size == 2
}
