package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.io.File

private fun Method.vodStrings() = implementation?.instructions
    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.orEmpty()

private fun BytecodePatchContext.mutableOf(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.first {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
    }

private fun vodPart(label: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        println("[ReTwitch] VOD player: $label skipped: $e")
    }
}

/** After `const vN, <literal>`: `vN = hook(vN)`. Range form, so any register works. */
private fun MutableMethod.filterLiteral(index: Int, hook: String) {
    val reg = (getInstruction(index) as OneRegisterInstruction).registerA
    addInstructions(
        index + 1,
        """
            invoke-static/range {v$reg .. v$reg}, $hook
            move-result v$reg
        """.trimIndent(),
    )
}

/**
 * VOD player options (31.8.0, each part skips on its own):
 *  - chat offset: the chat replay asks the comments fetcher (ChommentsFetcherImpl,
 *    31.8.0 `p67.m(int seconds, String playableId)`, the method naming
 *    "parseChommentResponse") for the comments at the player's second once a
 *    second; `VodChatSync.shift` moves that second by the VOD's own offset;
 *  - seek step: the overlay's seek buttons add -10 / +30 s per tap (touch
 *    listener, 31.8.0 `put.onTouch`), the media session -10000 / +30000 ms right
 *    before `SeekTrigger.BACK_BUTTON/FORWARD_BUTTON` (`rk90`); `VodSeek` swaps
 *    in the chosen steps (Twitch's label sums the same values);
 *  - autoplay: the "Up next" view (31.8.0 `ab2.w`, "Recommended model: ") stores
 *    its 10 s countdown subscription; `PlayerHook.onUpNextCountdown` cancels it.
 */
/**
 * Twitch's seek icons without their drawn-in 10 and 30, for `VodSeek.showStep`. The cast icons use the stock
 * ones, so these are copies. The arrow is the one path starting above the digits. Skipped softly: the
 * buttons keep the stock icons.
 */
private val seekIconsPatch = resourcePatch {
    execute {
        try {
            val res = get("res", false)
            val top = Regex("""pathData="M[\d.]+,([\d.]+)""")
            for ((stock, own) in listOf("ic_forward_30" to "rt_seek_forward", "ic_rewind_10" to "rt_seek_rewind")) {
                val icon = get("res/drawable/$stock.xml").readText()
                val paths = Regex("""<path\b[^>]*/>""").findAll(icon).map { it.value }.toList()
                val digits = paths.filter { top.find(it)!!.groupValues[1].toFloat() >= 5 }
                if (digits.size != paths.size - 1) throw PatchException("$stock: ${paths.size} paths, ${digits.size} digits")
                File(res, "drawable/$own.xml").writeText(digits.fold(icon) { text, path -> text.replace(path, "") })
            }
            println("[ReTwitch] seek icons without digits written")
        } catch (e: Exception) {
            println("[ReTwitch] seek icons skipped: $e")
        }
    }
}

val retwitchVodPlayerPatch = bytecodePatch(
    name = "ReTwitch VOD player",
    description = "Per-VOD chat offset, custom VOD seek steps and no autoplay of the next VOD/clip.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch, seekIconsPatch)

    execute {
        val methods = ArrayList<Method>()
        classDefForEach { classDef -> methods += classDef.methods }

        vodPart("chat offset") {
            // 32.0.0 moved the parser's method reference out of the fetcher into a lambda's constructor.
            fun key(owner: String, name: String, params: List<*>) = "$owner->$name(${params.joinToString("")})"
            val parsers = methods.filter { "parseChommentResponse" in it.vodStrings() }
                .map { key(it.definingClass, it.name, it.parameterTypes) }.toSet()
            val fetch = methods.single { m ->
                m.parameterTypes.map(Any::toString) == listOf("I", "Ljava/lang/String;") && m.returnType != "V" &&
                    ("parseChommentResponse" in m.vodStrings() || m.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? MethodReference)
                            ?.let { ref -> key(ref.definingClass, ref.name, ref.parameterTypes) in parsers } == true
                    } == true)
            }
            // `.locals 18`: p0/p1 are above v15, hence the range form.
            mutableOf(fetch).addInstructions(
                0,
                """
                    invoke-static/range {p0 .. p1}, Ltv/retwitch/mod/VodChatSync;->shift(Ljava/lang/Object;I)I
                    move-result p1
                """.trimIndent(),
            )
        }

        vodPart("seek buttons") {
            // `const vN, -10|30` right before `Integer.valueOf(vN)`: the per-tap seconds.
            fun sites(code: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>) = code.indices.filter { i ->
                val literal = (code[i] as? NarrowLiteralInstruction)?.narrowLiteral
                (literal == -10 || literal == 30) && code[i].opcode in setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST) &&
                    ((code.getOrNull(i + 1) as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                        it.definingClass == "Ljava/lang/Integer;" && it.name == "valueOf"
                    } == true
            }
            val touch = methods.single { m ->
                m.name == "onTouch" && m.parameterTypes.map(Any::toString) ==
                    listOf("Landroid/view/View;", "Landroid/view/MotionEvent;") &&
                    sites(m.implementation?.instructions?.toList().orEmpty()).size == 2
            }
            val target = mutableOf(touch)
            sites(target.instructions.toList()).sortedDescending().forEach {
                target.filterLiteral(it, "Ltv/retwitch/mod/VodSeek;->stepSeconds(I)I")
            }
        }

        vodPart("media session seek") {
            var patched = 0
            for (m in methods) {
                val code = m.implementation?.instructions?.toList() ?: continue
                val sites = code.indices.filter { i ->
                    val field = ((code.getOrNull(i + 1) as? ReferenceInstruction)?.reference as? FieldReference)
                        ?.takeIf { code[i + 1].opcode == Opcode.SGET_OBJECT && it.type == it.definingClass }
                    val literal = (code[i] as? NarrowLiteralInstruction)?.narrowLiteral
                    field != null && (field.name == "FORWARD_BUTTON" && literal == 30000 ||
                        field.name == "BACK_BUTTON" && literal == -10000)
                }
                if (sites.isEmpty()) continue
                val target = mutableOf(m)
                sites.sortedDescending().forEach { target.filterLiteral(it, "Ltv/retwitch/mod/VodSeek;->stepMillis(I)I") }
                patched += sites.size
            }
            if (patched == 0) throw PatchException("no SeekTrigger button literal found")
        }

        vodPart("up next autoplay") {
            val render = methods.single { "Recommended model: " in it.vodStrings() }
            val target = mutableOf(render)
            val code = target.instructions.toList()
            // The countdown's Disposable is the only field this render method stores.
            val store = code.indices.single { i ->
                code[i].opcode == Opcode.IPUT_OBJECT &&
                    ((code[i] as ReferenceInstruction).reference as FieldReference).definingClass == render.definingClass
            }
            val insn = code[store] as TwoRegisterInstruction
            if (insn.registerA > 15 || insn.registerB > 15) throw PatchException("registers out of range")
            target.addInstructions(
                store + 1,
                "invoke-static {v${insn.registerB}, v${insn.registerA}}, " +
                    "Ltv/retwitch/mod/PlayerHook;->onUpNextCountdown(Ljava/lang/Object;Ljava/lang/Object;)V",
            )
        }
    }
}
