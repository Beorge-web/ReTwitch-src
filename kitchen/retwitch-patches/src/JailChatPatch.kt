package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val PONG = "PONG :tmi.twitch.tv"

/**
 * The chat SDK's raw IRC frame collector (31.9.0 `ijm.a(String, Continuation)`): splits a
 * websocket text frame on "\n", parses each line and answers PING with [PONG].
 */
val ircFrameCollectorFingerprint = fingerprint {
    returns("Ljava/lang/Object;")
    strings(PONG)
    custom { method, _ ->
        method.parameterTypes.size == 2 && method.parameterTypes[0].toString() == "Ljava/lang/String;" &&
            !AccessFlags.STATIC.isSet(method.accessFlags)
    }
}

/**
 * RTE jail chat as real chat lines (`JailChat`): the frame collector's entry hands its text
 * to `JailChat.onIrcFrame`, which appends queued synthetic PRIVMSG lines, so Twitch parses and
 * renders them like any message (name color, badges, emotes) on its own coroutine. The
 * `retwitchSend` bridge sends a line on the IRC socket the way the PONG is sent; JailChat
 * sends a PING so a quiet chat still gets a frame to carry the lines. Skipped softly:
 * JailChat then falls back to notice lines.
 */
val retwitchJailChatPatch = bytecodePatch(
    name = "ReTwitch jail chat",
    description = "Shows RTE jail chat messages as regular chat lines.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val collector = ircFrameCollectorFingerprint.method
            val collectorType = collector.definingClass
            val code = collector.instructions.toList()

            // `conn.socket.send("PONG :tmi.twitch.tv")`: the send method, the socket field on
            // the connection, and the collector's field holding the connection.
            val pongAt = code.indexOfFirst {
                it.opcode == Opcode.CONST_STRING &&
                    ((it as ReferenceInstruction).reference as StringReference).string == PONG
            }
            val sendAt = (pongAt + 1 until code.size).first { code[it].opcode == Opcode.INVOKE_VIRTUAL }
            val send = (code[sendAt] as ReferenceInstruction).reference as MethodReference
            if (send.parameterTypes.map { it.toString() } != listOf("Ljava/lang/String;")) {
                throw PatchException("PONG send shape changed: $send")
            }
            val socketReg = (code[sendAt] as FiveRegisterInstruction).registerC
            val socketField = (sendAt downTo 0).map { code[it] }.first {
                it.opcode == Opcode.IGET_OBJECT && (it as OneRegisterInstruction).registerA == socketReg
            }.let { (it as ReferenceInstruction).reference as FieldReference }
            val collectorClass = mutableClassDefBy(collectorType)
            val connField = collectorClass.fields.singleOrNull { it.type == socketField.definingClass }
                ?: throw PatchException("no ${socketField.definingClass} field on $collectorType")

            val bridge = ImmutableMethod(
                collectorType, "retwitchSend",
                listOf("Ljava/lang/Object;", "Ljava/lang/String;").map { ImmutableMethodParameter(it, null, null) },
                "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value,
                null, null, MutableMethodImplementation(3),
            ).toMutable()
            bridge.addInstructions(
                0,
                """
                    check-cast p0, $collectorType
                    iget-object v0, p0, $connField
                    iget-object v0, v0, $socketField
                    invoke-virtual {v0, p1}, $send
                    return-void
                """.trimIndent(),
            )
            collectorClass.methods.add(bridge)
            // The socket itself, for JailChat's watchdog (fails a socket that stopped answering).
            val socketBridge = ImmutableMethod(
                collectorType, "retwitchSocket",
                listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
                "Ljava/lang/Object;", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value,
                null, null, MutableMethodImplementation(2),
            ).toMutable()
            socketBridge.addInstructions(
                0,
                """
                    check-cast p0, $collectorType
                    iget-object v0, p0, $connField
                    iget-object v0, v0, $socketField
                    return-object v0
                """.trimIndent(),
            )
            collectorClass.methods.add(socketBridge)

            // p1 (the frame) is only read on the first call; resumptions pass null and get it back.
            collector.addInstructions(
                0,
                """
                    invoke-static/range {p0 .. p1}, Ltv/retwitch/mod/JailChat;->onIrcFrame(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object p1
                """.trimIndent(),
            )
            println("[ReTwitch] jail chat: $collectorType->${collector.name}, send $send")
        } catch (e: Exception) {
            println("[ReTwitch] jail chat skipped: $e")
        }
    }
}
