package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val CATEGORY_SHELF_KEY = "categoryShelfKey"
private const val RN_PROFILE_FLAG = "Android-Ultralight-Profile"

/** The native channel page's Home tab (31.9.0 `kiv`, a merged lambda): the one place adding the category shelf. */
private val profileShelvesFingerprint = fingerprint {
    strings(CATEGORY_SHELF_KEY)
}

/**
 * Shelves of the native channel page's Home tab. Each goes through one builder call with its key (31.9.0
 * `jq50.u(key, items, ...)`; the static `jq50.v` fills its defaults and calls it): "streamRecapsShelfKey",
 * "categoryShelfKey", "streamerShelfKey" (suggested streamers, or a team's other channels). At the builder's entry
 * the shelves the viewer hid return (`UiHider.profileShelfHidden`). The React Native channel page has shelves of its
 * own, hidden by retwitch-rn.js. Skipped softly.
 */
val retwitchProfileShelvesPatch = bytecodePatch(
    name = "ReTwitch channel page shelves",
    description = "Optionally hides shelves of the channel page's Home tab.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val code = profileShelvesFingerprint.method.instructions.toList()
            val key = code.indexOfFirst {
                it.opcode == Opcode.CONST_STRING &&
                    ((it as ReferenceInstruction).reference as StringReference).string == CATEGORY_SHELF_KEY
            }
            val add = (key + 1 until code.size).firstNotNullOf { i ->
                ((code[i] as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf { ref ->
                    ref.returnType == "V" && ref.parameterTypes.any { it.toString() == "Ljava/lang/String;" }
                }
            }
            val params = add.parameterTypes.map { it.toString() }
            // The static wrapper takes the builder first; the builder's own method takes the rest.
            val own = if (params.firstOrNull() == add.definingClass) params.drop(1) else params
            val builder = mutableClassDefBy(add.definingClass).methods.single {
                !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
                    it.parameterTypes.map { p -> p.toString() } == own
            }
            val keyRegister = 1 + own.indexOf("Ljava/lang/String;")
            builder.addInstructionsWithLabels(
                0,
                """
                    invoke-static {p$keyRegister}, Ltv/retwitch/mod/UiHider;->profileShelfHidden(Ljava/lang/String;)Z
                    move-result v0
                    if-eqz v0, :keep
                    return-void
                """.trimIndent(),
                ExternalLabel("keep", builder.getInstruction(0)),
            )
            println("[ReTwitch] channel page shelves: ${builder.definingClass}->${builder.name}")
        } catch (e: Exception) {
            println("[ReTwitch] channel page shelves skipped: $e")
        }
        // Native or React Native channel page. The flag object (31.9.0 `lfg`) holds the key; its gate (`t260`, built
        // on `t0`, which evaluates outside the shared evaluator ExperimentHook overrides) answers in a ()Z method:
        // false while the React Native shell is off, else the flag's Boolean. That Boolean goes through
        // UiHider.channelPageRn right before its return.
        try {
            val flags = ArrayList<String>()
            classDefForEach { c ->
                if (c.methods.any { m ->
                        m.implementation?.instructions?.any {
                            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == RN_PROFILE_FLAG
                        } == true
                    }) flags += c.type
            }
            val flag = flags.single()
            val gates = ArrayList<String>()
            classDefForEach { c ->
                val init = c.methods.filter { it.name == "<init>" }.any { m ->
                    m.implementation?.instructions?.any {
                        it.opcode == Opcode.SGET_OBJECT &&
                            ((it as ReferenceInstruction).reference as FieldReference).definingClass == flag
                    } == true
                }
                if (init && c.type != flag) gates += c.type
            }
            var hooked = 0
            gates.forEach { gate ->
                mutableClassDefBy(gate).methods.filter { it.returnType == "Z" && it.parameterTypes.isEmpty() }.forEach { m ->
                    val code = m.instructions.toList()
                    val unbox = code.indexOfFirst {
                        val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                        ref?.definingClass == "Ljava/lang/Boolean;" && ref.name == "booleanValue"
                    }
                    if (unbox < 0 || code.getOrNull(unbox + 1)?.opcode != Opcode.MOVE_RESULT) return@forEach
                    val reg = (code[unbox + 1] as OneRegisterInstruction).registerA
                    if (reg > 15) return@forEach
                    m.addInstructions(
                        unbox + 2,
                        """
                            invoke-static {v$reg}, Ltv/retwitch/mod/UiHider;->channelPageRn(Z)Z
                            move-result v$reg
                        """.trimIndent(),
                    )
                    hooked++
                    println("[ReTwitch] channel page type: $gate->${m.name}")
                }
            }
            if (hooked == 0) println("[ReTwitch] channel page type skipped: no gate for $flag in $gates")
        } catch (e: Exception) {
            println("[ReTwitch] channel page type skipped: $e")
        }
    }
}
