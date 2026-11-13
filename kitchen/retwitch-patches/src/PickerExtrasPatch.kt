package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val RECYCLER = "Landroidx/recyclerview/widget/RecyclerView;"
private const val DISPATCHER = "Ltv/twitch/android/core/mvp/viewdelegate/EventDispatcher;"

/**
 * Two optional emote picker hooks, each skipped on its own if its shape changes:
 *
 * - Sticky section headers (PurpleTV DISABLE_STICKY_HEADERS_EP). The picker view
 *   factory (31.8.0 `tlc.k(Context, LayoutParams)`, the only method reading both
 *   `WITH_KEYBOARD` and `EMOTE_PICKER` - enum names survive R8) adds a header
 *   item decoration (`cse(2)`, it draws the current section's header over the
 *   list) with `RecyclerView.addItemDecoration`. That one call is gated by
 *   `PickerInput.stickyHeaders()`.
 * - Frequently Used (ReVive FrequentEmotes). The frequent-emotes repository
 *   (31.8.0 `pxf`: `"SeemsGood"` in `<clinit>`, an `EventDispatcher` field) counts
 *   the emotes clicked since the last send in its only `(String message)` method
 *   (`e`); `FrequentEmotes.beforeSend` runs at its entry.
 */
val retwitchPickerExtrasPatch = bytecodePatch(
    name = "ReTwitch emote picker extras",
    description = "Optional sticky section headers in the emote picker; Twitch emotes sent from autocomplete count towards Frequently Used.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val sites = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
            classDefForEach { c ->
                c.methods.forEach { m ->
                    val code = m.implementation?.instructions?.toList() ?: return@forEach
                    if (stickyHeaderAdd(code) >= 0) sites += c.type to m
                }
            }
            val (owner, found) = sites.singleOrNull() ?: throw PatchException("picker factory: ${sites.map { it.first }}")
            val method = mutableClassDefBy(owner).methods.first {
                it.name == found.name && it.parameterTypes.map(Any::toString) == found.parameterTypes.map(Any::toString)
            }
            val code = method.instructions.toList()
            val add = stickyHeaderAdd(code)
            // The int argument of the decoration's constructor: dead after it, free for the flag.
            val free = (code[add - 1] as FiveRegisterInstruction).registerD
            if (!freeAfter(code, add, free)) throw PatchException("v$free is read after the decoration")
            method.addInstructionsWithLabels(
                add,
                """
                    invoke-static {}, Ltv/retwitch/mod/emotes/PickerInput;->stickyHeaders()Z
                    move-result v$free
                    if-eqz v$free, :rt_no_sticky
                """.trimIndent(),
                ExternalLabel("rt_no_sticky", method.getInstruction(add + 1)),
            )
            println("[ReTwitch] picker sticky headers gate in $owner.${found.name}")
        } catch (throwable: Throwable) {
            println("[ReTwitch] picker sticky headers skipped: $throwable")
        }

        try {
            val events = mutableSetOf<String>()
            val repos = mutableSetOf<String>()
            classDefForEach { c ->
                if (c.methods.any { m -> m.name == "toString" && hasString(m, "EmoteClicked(emoteModel=") }) events += c.type
                if (c.fields.any { it.type == DISPATCHER } && c.methods.any { m -> m.name == "<clinit>" && hasString(m, "SeemsGood") }) {
                    repos += c.type
                }
            }
            val event = events.singleOrNull() ?: throw PatchException("EmoteClicked: $events")
            val repo = repos.singleOrNull() ?: throw PatchException("frequent emotes repository: $repos")
            val send = mutableClassDefBy(repo).methods.filter { m ->
                m.returnType == "V" && m.name != "<init>" && !AccessFlags.STATIC.isSet(m.accessFlags) &&
                    m.parameterTypes.map(Any::toString) == listOf("Ljava/lang/String;")
            }.singleOrNull() ?: throw PatchException("$repo: no single (String)V method")
            val registers = send.implementation?.registerCount ?: throw PatchException("$repo: no code")
            // p0, p1 and the v0 local must all fit the 4-bit invoke encoding.
            if (registers > 16 || registers < 3) throw PatchException("$repo: $registers registers")
            val generic = emoteModelConstructorFingerprint.method.definingClass
            send.addInstructions(
                0,
                """
                    const-string v0, "$event|$generic"
                    invoke-static {p0, p1, v0}, Ltv/retwitch/mod/emotes/FrequentEmotes;->beforeSend(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V
                """.trimIndent(),
            )
            println("[ReTwitch] frequent emotes hook in $repo.${send.name}")
        } catch (throwable: Throwable) {
            println("[ReTwitch] frequent emotes skipped: $throwable")
        }
    }
}

private fun hasString(m: com.android.tools.smali.dexlib2.iface.Method, s: String): Boolean =
    m.implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == s } == true

/**
 * In the picker factory (reads `WITH_KEYBOARD` and `EMOTE_PICKER`), the index of
 * `invoke-virtual {rv, d}, RecyclerView->?(..)V` right after
 * `new-instance d, X; const vN, ..; invoke-direct {d, vN}, X-><init>(I)V`, or -1.
 */
private fun stickyHeaderAdd(code: List<Instruction>): Int {
    val fields = code.mapNotNull { ((it as? ReferenceInstruction)?.reference as? FieldReference)?.name }
    if ("WITH_KEYBOARD" !in fields || "EMOTE_PICKER" !in fields) return -1
    for (i in 0 until code.size - 3) {
        if (code[i].opcode != Opcode.NEW_INSTANCE) continue
        val type = ((code[i] as ReferenceInstruction).reference as TypeReference).type
        val decoration = (code[i] as OneRegisterInstruction).registerA
        val arg = code[i + 1] as? OneRegisterInstruction ?: continue
        val init = code[i + 2] as? FiveRegisterInstruction ?: continue
        val add = code[i + 3] as? FiveRegisterInstruction ?: continue
        val initRef = (init as? ReferenceInstruction)?.reference as? MethodReference ?: continue
        val addRef = (add as? ReferenceInstruction)?.reference as? MethodReference ?: continue
        if (arg.opcode !in setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST)) continue
        if (init.opcode != Opcode.INVOKE_DIRECT || initRef.definingClass != type || initRef.name != "<init>" ||
            initRef.parameterTypes.map(Any::toString) != listOf("I") ||
            init.registerC != decoration || init.registerD != arg.registerA
        ) continue
        if (add.opcode != Opcode.INVOKE_VIRTUAL || addRef.definingClass != RECYCLER || addRef.returnType != "V" ||
            addRef.parameterTypes.size != 1 || add.registerCount != 2 || add.registerD != decoration
        ) continue
        return i + 3
    }
    return -1
}

/**
 * Whether [reg] is written before any read after index [from], scanning straight-line
 * code to the end of the method. Gives up (false) on any branch.
 */
private fun freeAfter(code: List<Instruction>, from: Int, reg: Int): Boolean {
    for (j in from + 1 until code.size) {
        val insn = code[j]
        if (insn is OffsetInstruction) return false
        val reads = mutableListOf<Int>()
        var writes = false
        when (insn) {
            is FiveRegisterInstruction ->
                reads += listOf(insn.registerC, insn.registerD, insn.registerE, insn.registerF, insn.registerG)
                    .take(insn.registerCount)
            is RegisterRangeInstruction -> reads += (insn.startRegister until insn.startRegister + insn.registerCount)
            is OneRegisterInstruction -> {
                if (insn is TwoRegisterInstruction) reads += insn.registerB
                if (insn is ThreeRegisterInstruction) reads += insn.registerC
                val setsA = insn.opcode.setsRegister() && !insn.opcode.name.contains("2ADDR", ignoreCase = true)
                if (!setsA) reads += insn.registerA else if (insn.registerA == reg) writes = true
            }
        }
        if (reg in reads) return false
        if (writes) return true
    }
    return true
}
