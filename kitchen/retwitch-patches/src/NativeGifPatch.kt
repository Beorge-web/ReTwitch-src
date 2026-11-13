package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val GIFS_KEY = "is_gifs_enabled"

/**
 * "Hide native GIFs": every read of Twitch's own `is_gifs_enabled` setting
 * (`const-string "is_gifs_enabled"` then a `(String, boolean): boolean`
 * getter) goes through `ChatFilters.gifsEnabled`. With it off, the chat body
 * builder already falls back to the GIF's caption text.
 */
val retwitchNativeGifPatch = bytecodePatch(
    name = "ReTwitch hide native GIFs",
    description = "Optionally shows the caption instead of Tier 2+ chat GIFs. Toggle in the ReTwitch chat filters menu.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        val owners = ArrayList<String>()
        classDefForEach { classDef ->
            if (classDef.methods.any { m ->
                    m.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == GIFS_KEY
                    } == true
                }
            ) owners += classDef.type
        }
        var hooked = 0
        for (owner in owners) {
            mutableClassDefBy(owner).methods.forEach { method ->
                val insns = method.implementation?.instructions?.toList() ?: return@forEach
                val sites = ArrayList<Int>()
                insns.forEachIndexed { i, insn ->
                    if (((insn as? ReferenceInstruction)?.reference as? StringReference)?.string != GIFS_KEY) return@forEachIndexed
                    val keyReg = (insn as OneRegisterInstruction).registerA
                    // The getter call taking that key, within the next few instructions
                    // (a default-value getter may sit in between).
                    for (j in i + 1 until minOf(i + 6, insns.size - 1)) {
                        val ref = (insns[j] as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                        val call = insns[j] as? FiveRegisterInstruction ?: continue
                        val usesKey = (0 until call.registerCount).any { r ->
                            when (r) { 0 -> call.registerC; 1 -> call.registerD; 2 -> call.registerE; 3 -> call.registerF; else -> call.registerG } == keyReg
                        }
                        if (!usesKey) continue
                        if (ref.returnType == "Z" &&
                            ref.parameterTypes.map { it.toString() } == listOf("Ljava/lang/String;", "Z") &&
                            insns[j + 1].opcode == Opcode.MOVE_RESULT
                        ) {
                            sites += j + 1
                        }
                        break
                    }
                }
                for (moveIndex in sites.sortedDescending()) {
                    val reg = (insns[moveIndex] as OneRegisterInstruction).registerA
                    method.addInstructions(
                        moveIndex + 1,
                        """
                            invoke-static/range {v$reg .. v$reg}, Ltv/retwitch/mod/ChatFilters;->gifsEnabled(Z)Z
                            move-result v$reg
                        """.trimIndent(),
                    )
                    hooked++
                }
            }
        }
        if (hooked == 0) throw PatchException("no $GIFS_KEY reads found")
    }
}
