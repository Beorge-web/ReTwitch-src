package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/** `ChannelMessagesCleared.toString()`: the moderator's whole-chat /clear. */
val channelMessagesClearedToStringFingerprint = fingerprint {
    returns("Ljava/lang/String;")
    strings("ChannelMessagesCleared(channelId=")
}

/** `ChannelNoticeEvent(channelId: Tuid, channelNotice)`: what the controller pushes for an IRC NOTICE. */
val channelNoticeEventToStringFingerprint = fingerprint {
    returns("Ljava/lang/String;")
    strings("ChannelNoticeEvent(channelId=")
}

/** `RegularChannelNotice(noticeId: String, message: StringResource)`. */
val regularChannelNoticeToStringFingerprint = fingerprint {
    returns("Ljava/lang/String;")
    strings("RegularChannelNotice(noticeId=")
}

private const val MOD_ACTION =
    "Ltv/twitch/android/shared/moderation/actions/pubsub/ModerationActionPubSubEvent\$ModeratorAction;"
private const val RAID_INFO = "Ltv/twitch/android/shared/raids/pubsub/RaidEventInfo;"

/**
 * Chat channel events, besides the message dispatcher of
 * `ChatEventFilterPatch`: the chat controller's handler for the
 * `ChannelMessagesCleared` / `ChannelNoticeReceived` / ... family (31.8.0
 * merged lambda `b1.d(Object)`, a chain of `instance-of` on each event, its
 * receiver the controller `ir5`). Parts, each skipped on its own:
 *  - the handler's entry asks `ChatFilters.dropChannelEvent(event, controller)`
 *    and returns the method's own Unit on true (keep chat on /clear, hide
 *    room-mode notices);
 *  - `retwitchPostNotice` bridge on the controller class: pushes
 *    `ChannelNoticeEvent(tuid, RegularChannelNotice(id, text))` into the same
 *    stream Twitch uses for IRC NOTICEs (`ChatNotices`: our own chat lines);
 *  - mod logs: `ModeratorAction.getData()` hands its result to `ModLogs`;
 *  - raid line: `RaidEventInfo.getRaidId()` hands the raid to `RaidCard`.
 */
val retwitchChatChannelEventsPatch = bytecodePatch(
    name = "ReTwitch chat channel events",
    description = "Optionally keeps chat on a moderator's /clear, hides room-mode notices, adds mod logs and a raid line.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        val clearedType = channelMessagesClearedToStringFingerprint.classDef.type
        val handlerClass = mutableClassDefByOrNull { c ->
            c.type != clearedType && c.methods.any { isChannelHandler(it, clearedType) }
        } ?: throw PatchException("chat channel event handler (instance-of $clearedType) not found")
        val handler = handlerClass.methods.first { isChannelHandler(it, clearedType) }
        val code = handler.instructions.toList()

        // The controller: `iget-object x, p0, <CallableReference>->receiver` + `check-cast x, <controller>`, the last
        // one before the /clear test. 31.9.0 has the handler in its own method (`b1.d`); 32.0.0 inlined it as one
        // branch of the merged lambda's `invoke` (`c1`), whose other branches have other receivers.
        val clearedAt = code.indexOfFirst {
            it.opcode == Opcode.INSTANCE_OF && ((it as ReferenceInstruction).reference as TypeReference).type == clearedType
        }
        val receiverAt = (clearedAt downTo 0).firstOrNull {
            code[it].opcode == Opcode.IGET_OBJECT && ((code[it] as ReferenceInstruction).reference as FieldReference).name == "receiver" &&
                code[it + 1].opcode == Opcode.CHECK_CAST
        } ?: throw PatchException("${handler.name}: receiver read not found")
        val controllerType = ((code[receiverAt + 1] as ReferenceInstruction).reference as TypeReference).type
        val controllerReg = (code[receiverAt + 1] as OneRegisterInstruction).registerA

        part("notice bridge") {
            val eventType = channelNoticeEventToStringFingerprint.classDef.type
            val noticeClass = regularChannelNoticeToStringFingerprint.classDef
            val eventInit = code.indexOfFirst {
                it.opcode == Opcode.INVOKE_DIRECT &&
                    ((it as ReferenceInstruction).reference as MethodReference).let { m ->
                        m.definingClass == eventType && m.name == "<init>"
                    }
            }
            val eventCtor = (code[eventInit] as ReferenceInstruction).reference as MethodReference
            val push = code[eventInit + 1]
            if (eventCtor.parameterTypes.size != 2 || push.opcode != Opcode.INVOKE_VIRTUAL) {
                throw PatchException("notice push shape changed")
            }
            val onNext = (push as ReferenceInstruction).reference as MethodReference
            val subjectReg = (push as FiveRegisterInstruction).registerC
            val subjectField = (eventInit downTo 0).map { code[it] }.first {
                it.opcode == Opcode.IGET_OBJECT && (it as OneRegisterInstruction).registerA == subjectReg
            }.let { (it as ReferenceInstruction).reference as FieldReference }
            if (subjectField.definingClass != controllerType) throw PatchException("notice stream is not on $controllerType")
            val noticeCtor = noticeClass.methods.single { it.name == "<init>" && it.parameterTypes.size == 2 }
            if (noticeClass.superclass != eventCtor.parameterTypes[1].toString() ||
                noticeCtor.parameterTypes[0].toString() != "Ljava/lang/String;"
            ) {
                throw PatchException("RegularChannelNotice shape changed")
            }
            val noticeInit = "${noticeClass.type}-><init>(${noticeCtor.parameterTypes.joinToString("")})V"

            val bridge = ImmutableMethod(
                controllerType, "retwitchPostNotice",
                listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/String;", "Ljava/lang/Object;")
                    .map { ImmutableMethodParameter(it, null, null) },
                "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value,
                null, null, MutableMethodImplementation(7),
            ).toMutable()
            bridge.addInstructions(
                0,
                """
                    check-cast p0, $controllerType
                    iget-object v0, p0, $subjectField
                    new-instance v1, ${noticeClass.type}
                    check-cast p3, ${noticeCtor.parameterTypes[1]}
                    invoke-direct {v1, p2, p3}, $noticeInit
                    new-instance v2, $eventType
                    check-cast p1, ${eventCtor.parameterTypes[0]}
                    invoke-direct {v2, p1, v1}, $eventCtor
                    invoke-virtual {v0, v2}, $onNext
                    return-void
                """.trimIndent(),
            )
            mutableClassDefBy(controllerType).methods.add(bridge)
        }

        part("channel event gate") {
            // Twitch's own kotlin.Unit (the extension carries another one), as the handler itself returns it.
            val units = HashSet<String>()
            classDefForEach { c ->
                if (c.methods.any { m -> m.name == "toString" && m.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "kotlin.Unit"
                    } == true }
                ) units += c.type
            }
            val unit = code.firstNotNullOfOrNull { insn ->
                ((insn as? ReferenceInstruction)?.reference as? FieldReference)
                    ?.takeIf { insn.opcode == Opcode.SGET_OBJECT && it.type in units && it.definingClass == it.type }
            }
            // Right before the branch's first event test, `instance-of vS, vEvent, ...`: the controller is cast,
            // and vS is about to be written, so it is free for the gate.
            val testAt = (receiverAt + 2..clearedAt).first { code[it].opcode == Opcode.INSTANCE_OF }
            val test = code[testAt] as TwoRegisterInstruction
            val (scratch, event) = test.registerA to test.registerB
            if (scratch == event || scratch == controllerReg || maxOf(event, controllerReg) > 15) {
                throw PatchException("${handler.name}: registers v$scratch, v$event, v$controllerReg")
            }
            handler.addInstructionsWithLabels(
                testAt,
                """
                    invoke-static {v$event, v$controllerReg}, Ltv/retwitch/mod/ChatFilters;->dropChannelEvent(Ljava/lang/Object;Ljava/lang/Object;)Z
                    move-result v$scratch
                    if-eqz v$scratch, :rt_keep_channel_event
                    sget-object v$scratch, ${unit ?: throw PatchException("kotlin.Unit not found")}
                    return-object v$scratch
                """.trimIndent(),
                ExternalLabel("rt_keep_channel_event", handler.getInstruction(testAt)),
            )
            println("[ReTwitch] chat channel events: ${handlerClass.type}->${handler.name}, controller $controllerType")
        }

        part("mod logs") {
            val getData = mutableClassDefBy(MOD_ACTION).methods.single { it.name == "getData" && it.parameterTypes.isEmpty() }
            hookReturns(getData, "Ltv/retwitch/mod/ModLogs;->onAction(Ljava/lang/Object;)V")
        }

        part("raid line") {
            val getRaidId = mutableClassDefBy(RAID_INFO).methods.single { it.name == "getRaidId" && it.parameterTypes.isEmpty() }
            getRaidId.addInstruction(
                0,
                "invoke-static/range {p0 .. p0}, Ltv/retwitch/mod/RaidCard;->onRaidInfo(Ljava/lang/Object;)V",
            )
        }
    }
}

/** `Object -> Object` method with an `instance-of` test for the /clear event. */
private fun isChannelHandler(method: Method, clearedType: String) =
    method.returnType == "Ljava/lang/Object;" && method.parameterTypes.size == 1 &&
        method.parameterTypes[0].toString() == "Ljava/lang/Object;" &&
        method.implementation?.instructions?.any {
            it.opcode == Opcode.INSTANCE_OF && ((it as ReferenceInstruction).reference as TypeReference).type == clearedType
        } == true

/** Hands each returned object to `hook` (static, one Object param) right before its `return-object`. */
private fun hookReturns(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod, hook: String) {
    val returns = method.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
    if (returns.isEmpty()) throw PatchException("${method.name}: no return-object")
    for (index in returns.reversed()) {
        val reg = (method.getInstruction(index) as OneRegisterInstruction).registerA
        method.addInstruction(index, "invoke-static/range {v$reg .. v$reg}, $hook")
    }
}

private fun part(label: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        println("[ReTwitch] chat channel events: $label skipped: $e")
    }
}
