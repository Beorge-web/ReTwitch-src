package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val BRIDGE = "Ltv/retwitch/mod/emotes/EmotePickerBridge;"

/**
 * Adds 7TV/BTTV/FFZ sections to Twitch's native emote picker (Twitch 31.x).
 *
 * The picker transform loops over the user's emote sets and turns each into a
 * section via the presenter's `(EmoteSet, Tuid, config): SectionUiModel`
 * method, adding it to a list that ends up in a `kotlin.Pair` (31.8.0:
 * `t81.invoke` calling `fwf.G2`). Two plain insertions, no branch targets:
 *  - right after each section call: `EmotePickerBridge.capture(presenter,
 *    selectedId, config)` (registers read off that call);
 *  - right after the result `Pair` is constructed: `appendToList(list)`, which
 *    builds fake `OwnerEmoteSet`s for our providers through the same presenter
 *    method and adds their sections to that list.
 * The class names it needs are handed over once from `TwitchApplication.onCreate`.
 * Skips softly (no picker sections, nothing else affected) if the shape changes.
 */
val retwitchEmotePickerPatch = bytecodePatch(
    name = "ReTwitch third-party emotes in the emote picker",
    description = "Adds a section of cached 7TV/BTTV/FFZ emotes to Twitch's native emote picker.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val ownerSetType = emoteOwnerSetToStringFingerprint.classDef.type
            val setBase = classDefBy(ownerSetType).superclass
                ?: throw PatchException("OwnerEmoteSet has no superclass")

            fun sectionCall(insns: List<Instruction>, i: Int): Boolean {
                val ref = (insns[i] as? ReferenceInstruction)?.reference as? MethodReference ?: return false
                if (insns[i].opcode != Opcode.INVOKE_VIRTUAL || ref.parameterTypes.size != 3 ||
                    ref.parameterTypes[0].toString() != setBase || i + 2 >= insns.size ||
                    insns[i + 1].opcode != Opcode.MOVE_RESULT_OBJECT
                ) return false
                val add = (insns[i + 2] as? ReferenceInstruction)?.reference as? MethodReference ?: return false
                return add.name == "add" && (insns[i + 2] as FiveRegisterInstruction).registerD ==
                    (insns[i + 1] as OneRegisterInstruction).registerA
            }

            var owner: String? = null
            classDefForEach { classDef ->
                if (owner == null && classDef.methods.any { m ->
                        val insns = m.implementation?.instructions?.toList() ?: return@any false
                        insns.indices.any { sectionCall(insns, it) }
                    }
                ) owner = classDef.type
            }
            val method = mutableClassDefBy(owner ?: throw PatchException("picker section loop not found"))
                .methods.first { m ->
                    val insns = m.implementation?.instructions?.toList() ?: return@first false
                    insns.indices.any { sectionCall(insns, it) }
                }
            val insns = method.instructions.toList()
            val callIndex = insns.indices.first { sectionCall(insns, it) }
            val call = insns[callIndex] as FiveRegisterInstruction
            val sectionMethod = ((call as ReferenceInstruction).reference as MethodReference).name
            val listReg = (insns[callIndex + 2] as FiveRegisterInstruction).registerC
            val pairInit = (callIndex until insns.size).firstOrNull { j ->
                val ref = (insns[j] as? ReferenceInstruction)?.reference as? MethodReference
                insns[j].opcode == Opcode.INVOKE_DIRECT && ref?.definingClass == "Lkotlin/Pair;" &&
                    ref.name == "<init>" && (insns[j] as FiveRegisterInstruction).registerE == listReg
            } ?: throw PatchException("result Pair(.., list) not found after the section loop")
            if (listOf(call.registerC, call.registerE, call.registerF, listReg).any { it > 15 }) {
                throw PatchException("picker registers out of 4-bit range")
            }

            // Later index first so the earlier one stays valid.
            method.addInstructions(pairInit + 1, "invoke-static {v$listReg}, $BRIDGE->appendToList(Ljava/lang/Object;)V")
            method.addInstructions(
                callIndex + 2,
                "invoke-static {v${call.registerC}, v${call.registerE}, v${call.registerF}}, " +
                    "$BRIDGE->capture(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
            )

            // Favorites on the very top: the picker later puts "frequently used" and the
            // channel's own section (Pair.first) ahead of this list (31.8.0 `ked`):
            // `ArrayList(recent) + Pair.first + addAll(Pair.second)`. Right after that
            // addAll, EmotePickerBridge.favoritesFirst(list) moves our section to index 0.
            try {
                val sectionType = ((call as ReferenceInstruction).reference as MethodReference).returnType
                val sites = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
                classDefForEach { c ->
                    c.methods.forEach { m ->
                        val code = m.implementation?.instructions?.toList() ?: return@forEach
                        if (orderSite(code, sectionType) >= 0) sites += c.type to m
                    }
                }
                if (sites.isEmpty()) throw PatchException("section order site not found")
                for ((type, m) in sites) {
                    val target = mutableClassDefBy(type).methods.first {
                        it.name == m.name && it.parameterTypes.map(Any::toString) == m.parameterTypes.map(Any::toString)
                    }
                    val addAll = orderSite(target.instructions.toList(), sectionType)
                    val listReg = (target.getInstruction(addAll) as FiveRegisterInstruction).registerC
                    target.addInstructions(
                        addAll + 1,
                        "invoke-static/range {v$listReg .. v$listReg}, $BRIDGE->favoritesFirst(Ljava/lang/Object;)V",
                    )
                }
                println("[ReTwitch] emote favorites on top: ${sites.map { it.first }}")
            } catch (throwable: Throwable) {
                println("[ReTwitch] emote favorites on top skipped: $throwable")
            }

            val packed = listOf(
                emoteOwnerToStringFingerprint.method.definingClass,
                ownerSetType,
                emoteModelConstructorFingerprint.method.definingClass,
                sectionMethod,
            ).joinToString("|")
            val app = mutableClassDefBy("Ltv/twitch/android/app/consumer/TwitchApplication;")
            app.methods.single { it.name == "onCreate" && it.parameterTypes.isEmpty() }.addInstructions(
                0,
                """
                    const-string v0, "$packed"
                    invoke-static {v0}, $BRIDGE->configure(Ljava/lang/String;)V
                """.trimIndent(),
            )
        } catch (throwable: Throwable) {
            println("[ReTwitch] emote picker section skipped: $throwable")
        }

        // Favourites (EmoteFavorites): the picker cell's onLongClick builds
        // `LongClick(emoteView=, clickedEmote=)` and dispatches it. Right after the
        // event is constructed (a plain sequential spot inside R8's merged
        // onLongClick switch): if EmoteFavorites handled it, return the branch's own
        // `true` without dispatching; else fall through to Twitch.
        try {
            val events = mutableSetOf<String>()
            classDefForEach { c ->
                if (c.methods.any { m ->
                        m.name == "toString" && m.implementation?.instructions?.any {
                            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "LongClick(emoteView="
                        } == true
                    }
                ) events += c.type
            }
            val owners = mutableSetOf<String>()
            classDefForEach { c ->
                if (c.methods.any { m -> m.name == "onLongClick" && longClickInit(m.implementation?.instructions?.toList(), events) >= 0 }) {
                    owners += c.type
                }
            }
            if (owners.isEmpty()) throw PatchException("no onLongClick building ${events}")
            for (owner in owners) {
                val method = mutableClassDefBy(owner).methods.first { m ->
                    m.name == "onLongClick" && longClickInit(m.instructions.toList(), events) >= 0
                }
                val code = method.instructions.toList()
                val init = longClickInit(code, events)
                val event = (code[init] as FiveRegisterInstruction).registerC
                val next = code[init + 1]
                if (next.opcode != Opcode.IGET_OBJECT) throw PatchException("$owner: unexpected ${next.opcode} after the event")
                val scratch = (next as TwoRegisterInstruction).registerA // overwritten by that iget anyway
                val ret = code.subList(init, code.size).first { it.opcode == Opcode.RETURN } as OneRegisterInstruction
                if (scratch == event || scratch == ret.registerA) throw PatchException("$owner: register clash")
                method.addInstructionsWithLabels(
                    init + 1,
                    """
                        invoke-static/range {v$event .. v$event}, Ltv/retwitch/mod/emotes/EmoteFavorites;->onLongClick(Ljava/lang/Object;)Z
                        move-result v$scratch
                        if-eqz v$scratch, :rt_fav_dispatch
                        return v${ret.registerA}
                    """.trimIndent(),
                    ExternalLabel("rt_fav_dispatch", method.getInstruction(init + 1)),
                )
            }
            println("[ReTwitch] emote favorites: long press hook in $owners")
        } catch (throwable: Throwable) {
            println("[ReTwitch] emote favorites skipped: $throwable")
        }
    }
}

/** Index of `invoke-direct <LongClick event>.<init>` in [code], or -1. */
private fun longClickInit(code: List<Instruction>?, events: Set<String>): Int =
    code?.indexOfFirst { insn ->
        val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
        insn.opcode == Opcode.INVOKE_DIRECT && ref?.name == "<init>" && ref.definingClass in events
    } ?: -1

/**
 * Index of the `ArrayList.addAll(...)` right after a `Pair` component was cast to the
 * picker section type (`Pair.first`, then `addAll(Pair.second)`), or -1.
 */
private fun orderSite(code: List<Instruction>, sectionType: String): Int {
    for (i in 0 until code.size - 1) {
        val first = (code[i] as? ReferenceInstruction)?.reference as? FieldReference
        if (code[i].opcode != Opcode.IGET_OBJECT || first?.definingClass != "Lkotlin/Pair;") continue
        if (code[i + 1].opcode != Opcode.CHECK_CAST ||
            ((code[i + 1] as ReferenceInstruction).reference as TypeReference).type != sectionType
        ) continue
        for (j in i + 2 until minOf(code.size, i + 14)) {
            val ref = (code[j] as? ReferenceInstruction)?.reference as? MethodReference
            if (ref?.definingClass == "Ljava/util/ArrayList;" && ref.name == "addAll") return j
        }
    }
    return -1
}
