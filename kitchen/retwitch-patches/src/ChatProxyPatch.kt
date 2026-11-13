package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/**
 * Chat through ReYohoho's IRC WebSocket relay (toggle, off by default). The chat socket's
 * connect (31.9.0 `pe2.q()`: OkHttp client with a 20 s ping `"interval"`, then the stored
 * URL -- `iget-object` + `check-cast String` -- into the request builder) runs on every
 * (re)connect: the URL goes through `UsherHook.chatUrl` there, which also falls back to
 * direct while the relay is down. If that site isn't found, the endpoint enum's getter
 * (`p06`, `"wss://irc-ws.chat.twitch.tv"` in its `<clinit>`; read once when the chat
 * component is built) is hooked instead, without the fallback. Skipped softly.
 */
val retwitchChatProxyPatch = bytecodePatch(
    name = "ReTwitch chat proxy",
    description = "Optionally connects chat through the ReYohoho IRC WebSocket proxy.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            var site: Pair<com.android.tools.smali.dexlib2.iface.Method, Int>? = null
            var sites = 0
            classDefForEach { c ->
                for (m in c.methods) {
                    if (m.returnType != "V" || m.parameterTypes.isNotEmpty() || AccessFlags.STATIC.isSet(m.accessFlags)) continue
                    val code = m.implementation?.instructions?.toList() ?: continue
                    if (code.none { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "interval" }) continue
                    for (i in 1 until code.size) {
                        val cast = code[i]
                        val get = code[i - 1]
                        if (cast.opcode == Opcode.CHECK_CAST && get.opcode == Opcode.IGET_OBJECT &&
                            ((cast as ReferenceInstruction).reference as TypeReference).type == "Ljava/lang/String;" &&
                            ((get as ReferenceInstruction).reference as FieldReference).definingClass == c.type
                        ) {
                            site = m to i
                            sites++
                        }
                    }
                }
            }
            if (sites == 1) {
                val (found, index) = site!!
                val method = mutableClassDefBy(found.definingClass).methods.first {
                    it.name == found.name && it.parameterTypes.isEmpty() && it.returnType == "V"
                }
                val reg = (method.instructions[index] as OneRegisterInstruction).registerA
                method.addInstructions(
                    index + 1,
                    """
                        invoke-static {v$reg}, Ltv/retwitch/mod/UsherHook;->chatUrl(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$reg
                    """.trimIndent(),
                )
                println("[ReTwitch] chat proxy: connect ${found.definingClass}->${found.name}")
                return@execute
            }
            println("[ReTwitch] chat proxy: $sites connect sites, using the endpoint getter")
            val found = ArrayList<ClassDef>()
            classDefForEach { c ->
                val strings = c.methods.firstOrNull { it.name == "<clinit>" }?.implementation?.instructions
                    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.orEmpty()
                if ("wss://irc-ws.chat.twitch.tv" in strings && "irc.chat.twitch.tv:6667" in strings) found += c
            }
            val endpoint = found.singleOrNull() ?: throw PatchException("chat endpoint enum: ${found.size} matches")
            val getter = mutableClassDefBy(endpoint.type).methods.single {
                it.returnType == "Ljava/lang/String;" && it.parameterTypes.isEmpty() &&
                    !AccessFlags.STATIC.isSet(it.accessFlags) && it.name != "toString"
            }
            val ret = getter.instructions.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
            val reg = (getter.instructions[ret] as OneRegisterInstruction).registerA
            getter.addInstructions(
                ret,
                """
                    invoke-static {v$reg}, Ltv/retwitch/mod/UsherHook;->chatUrl(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$reg
                """.trimIndent(),
            )
            println("[ReTwitch] chat proxy: ${endpoint.type}->${getter.name}")
        } catch (e: Exception) {
            println("[ReTwitch] chat proxy skipped: $e")
        }
    }
}
