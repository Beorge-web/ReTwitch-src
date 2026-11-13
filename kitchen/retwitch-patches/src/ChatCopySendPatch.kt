package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val HOOK = "Ltv/retwitch/mod/ChatCopySend;"
private const val MOD = "Ltv/retwitch/mod/ChatModActions;"

/**
 * "Copy and send" in the chat message long-press menu (`ChatCopySend`).
 * Anchor: the method that shows the menu (31.8.0 `k7d.B`, lateinit error string
 * "messageContextMenu"). It reads the message text (`msg.l(Context): String`),
 * then sets Copy's click listener on the row kept in a field of the menu object.
 *  - right after that setOnClickListener: `ChatCopySend.bind(copyRow, text)`;
 *  - in the menu's `(View, msg)` constructor, right after the Copy row is stored
 *    in that field (before the popup is measured): `ChatCopySend.addRow(copyRow)`.
 * Skips softly if the shape changes.
 */
val retwitchChatCopySendPatch = bytecodePatch(
    name = "ReTwitch copy and send",
    description = "Adds \"Copy and send\" to the chat message long-press menu.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val anchors = mutableListOf<Method>()
            classDefForEach { c ->
                c.methods.forEach { m ->
                    val refs = m.implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference }.orEmpty()
                    if (refs.any { (it as? StringReference)?.string == "messageContextMenu" } &&
                        refs.any { (it as? MethodReference)?.name == "setOnClickListener" }
                    ) anchors += m
                }
            }
            val found = anchors.singleOrNull() ?: throw PatchException("menu anchor: ${anchors.size} candidates")
            val show = mutableClassDefBy(found.definingClass).methods.first {
                it.name == found.name && it.parameterTypes.map(Any::toString) == found.parameterTypes.map(Any::toString)
            }
            val code = show.instructions.toList()
            val textCall = code.indices.first { i ->
                val ref = (code[i] as? ReferenceInstruction)?.reference as? MethodReference
                ref?.returnType == "Ljava/lang/String;" &&
                    ref.parameterTypes.map(Any::toString) == listOf("Landroid/content/Context;") &&
                    code.getOrNull(i + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
            }
            val messageType = ((code[textCall] as ReferenceInstruction).reference as MethodReference).definingClass
            val textReg = (code[textCall + 1] as OneRegisterInstruction).registerA
            val click = (textCall + 2 until code.size).first { i ->
                ((code[i] as? ReferenceInstruction)?.reference as? MethodReference)?.name == "setOnClickListener"
            }
            val rowReg = (code[click] as FiveRegisterInstruction).registerC
            val copyField = (textCall + 2 until click).map { code[it] }.last {
                it.opcode == Opcode.IGET_OBJECT && (it as OneRegisterInstruction).registerA == rowReg
            }.let { (it as ReferenceInstruction).reference as FieldReference }
            val clobbered = (textCall + 2 until click).any {
                code[it].opcode.setsRegister() && (code[it] as? OneRegisterInstruction)?.registerA == textReg
            }
            if (clobbered || rowReg > 15 || textReg > 15) throw PatchException("menu registers changed")

            val ctor = mutableClassDefBy(copyField.definingClass).methods.single {
                it.name == "<init>" &&
                    it.parameterTypes.map(Any::toString) == listOf("Landroid/view/View;", messageType)
            }
            val ctorCode = ctor.instructions.toList()
            val store = ctorCode.indices.single { i ->
                val ref = (ctorCode[i] as? ReferenceInstruction)?.reference as? FieldReference
                ctorCode[i].opcode == Opcode.IPUT_OBJECT && ref?.definingClass == copyField.definingClass &&
                    ref.name == copyField.name && ref.type == copyField.type
            }
            val storedReg = (ctorCode[store] as OneRegisterInstruction).registerA

            // ChatModActions reads the row model the text came from: the receiver of that call.
            val messageReg = (code[textCall] as FiveRegisterInstruction).registerC
            show.addInstructions(
                click + 1,
                """
                    invoke-static {v$rowReg, v$textReg}, $HOOK->bind(Ljava/lang/Object;Ljava/lang/String;)V
                    invoke-static {v$rowReg}, $MOD->bind(Ljava/lang/Object;)V
                """.trimIndent(),
            )
            if (messageReg <= 15) {
                // Before the call: its result is moved into the same register.
                show.addInstructions(
                    textCall,
                    "invoke-static {v$messageReg}, $MOD->noteMessage(Ljava/lang/Object;)V",
                )
            }
            // The constructor's message: the popup is measured in there (ChatModActions.addRow).
            ctor.addInstructions(
                0,
                "invoke-static/range {p2 .. p2}, $MOD->noteMessage(Ljava/lang/Object;)V",
            )
            ctor.addInstructions(
                store + 2,
                """
                    invoke-static/range {v$storedReg .. v$storedReg}, $HOOK->addRow(Ljava/lang/Object;)V
                    invoke-static/range {v$storedReg .. v$storedReg}, $MOD->addRow(Ljava/lang/Object;)V
                """.trimIndent(),
            )
            println("[ReTwitch] copy and send: menu ${copyField.definingClass}, shown in ${show.definingClass}")
        } catch (throwable: Throwable) {
            println("[ReTwitch] copy and send skipped: $throwable")
        }
    }
}
