package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val SORT_KEY = "shared_pref_sort_method"

/**
 * Tablet home (native there): the followed channels column on the left (the following drawer, 31.9.0 view model `kai`,
 * state `"State(drawerItems="`) and the Following list on the right (`d7e`) keep their sort under one preference key,
 * so a change on one side moved the other. Every read and write of that key in the drawer's view model goes through
 * `UiHider.drawerSortKey` (its own key while `SEPARATE_DRAWER_SORT` is on). Skipped softly.
 */
val retwitchDrawerSortPatch = bytecodePatch(
    name = "ReTwitch drawer sort",
    description = "Optionally gives the tablet's followed channels column its own sort.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            fun strings(c: com.android.tools.smali.dexlib2.iface.ClassDef) = c.methods.flatMap { m ->
                m.implementation?.instructions?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.orEmpty()
            }
            val states = HashSet<String>()
            classDefForEach { c -> if ("State(drawerItems=" in strings(c)) states += c.type }
            var owner: String? = null
            classDefForEach { c ->
                if (SORT_KEY in strings(c) && c.methods.any { m ->
                        m.implementation?.instructions?.any {
                            it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type in states
                        } == true
                    }
                ) owner = c.type
            }
            var hooked = 0
            mutableClassDefBy(owner ?: throw PatchException("drawer view model not found")).methods.forEach { m ->
                val code = m.implementation?.instructions?.toList() ?: return@forEach
                code.indices.filter { ((code[it] as? ReferenceInstruction)?.reference as? StringReference)?.string == SORT_KEY }
                    .reversed().forEach { i ->
                        val reg = (code[i] as OneRegisterInstruction).registerA
                        m.addInstructions(i + 1, """
                            invoke-static/range {v$reg .. v$reg}, Ltv/retwitch/mod/UiHider;->drawerSortKey(Ljava/lang/String;)Ljava/lang/String;
                            move-result-object v$reg
                        """.trimIndent())
                        hooked++
                    }
            }
            println("[ReTwitch] drawer sort: $hooked key use(s) in $owner")
        } catch (e: Exception) {
            println("[ReTwitch] drawer sort skipped: $e")
        }
    }
}
