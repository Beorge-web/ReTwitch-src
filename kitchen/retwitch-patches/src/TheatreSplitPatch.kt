package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * Makes ReTwitch the single writer of the landscape theatre's `chat_guideline`
 * so the draggable player/chat divider ([tv.retwitch.mod.TheatreDividerHook])
 * no longer fights Twitch's own layout.
 *
 * ## The conflict this fixes
 *
 * In the refactored landscape theatre, Twitch owns `chat_guideline` and moves
 * it itself whenever the side-by-side chat state changes (notably the
 * swipe-left "50/50 split" gesture, and every tap-controls overlay show/hide).
 * The one obfuscated state applier sets the guideline two different ways:
 *
 *  - **docked** chat: `guidelineEnd = ~279dp` (fixed px) with `guidePercent = -1`;
 *  - **split** (swipe) chat: `guidelineEnd = -1` with `guidePercent = 0.5`.
 *
 * The runtime divider hook also writes `guidePercent` by reflection, so the two
 * controllers fought: in docked mode the guideline ended up with *both* a fixed
 * end and a percent, which renders as "chat over the player / player not on the
 * left". Twitch persists the split state, so it survived restarts (only an app
 * data wipe cleared it) and could not be toggled off from our settings.
 *
 * ## The fix (PurpleTV's approach)
 *
 * Rather than keep fighting at runtime, route every `Guideline.setGuidelineEnd`
 * / `setGuidelinePercent` the applier performs through our bridge
 * ([tv.retwitch.mod.TheatreDividerHook.guideEnd] / [guidePercent][tv.retwitch.mod.TheatreDividerHook.guidePercent]).
 * When the divider is enabled we force `guidePercent = -1` and return our
 * persisted width in px as `guidelineEnd` in *both* branches (a fixed width,
 * like Twitch's own 279dp: a percent of the parent followed the theatre's
 * width as the system bars came and went), so Twitch itself lays the split
 * out at our width in every state and there is nothing left to fight. When it's disabled the bridge returns Twitch's original value
 * unchanged, so native behaviour (including the 50/50 swipe) is preserved.
 *
 * ## Target
 *
 * The applier is an R8-obfuscated synthetic method, so we fingerprint it by
 * shape, not name: it references the (kept) `ConstraintTheatreContainerView`,
 * calls both `Guideline.setGuidelinePercent(F)` and `setGuidelineEnd(I)`, and
 * contains the `0x117` (279dp) docked-width literal. Exactly four guideline
 * setters live in that method (2 percent + 2 end, all on `chat_guideline`); we
 * rewrite each one's argument in place.
 */
private const val HOOK = "Ltv/retwitch/mod/TheatreDividerHook;"
private const val GUIDELINE = "Landroidx/constraintlayout/widget/Guideline;"
private const val CONTAINER_VIEW = "ConstraintTheatreContainerView"
private const val DOCKED_WIDTH_DP = 0x117L // 279dp, Twitch's fixed docked-chat width
private const val DOCKED_WIDTH_DP_FLOAT = 0x438b8000L // 279f bits: 31.x keeps it as a float

val theatreSplitGuidelineFingerprint = fingerprint {
    custom { method, _ ->
        val instructions = method.implementation?.instructions ?: return@custom false
        var referencesContainer = false
        var setsPercent = false
        var setsEnd = false
        var hasDockedWidth = false
        for (instruction in instructions) {
            when (val reference = (instruction as? ReferenceInstruction)?.reference) {
                is FieldReference ->
                    if (reference.type.contains(CONTAINER_VIEW)) referencesContainer = true

                is MethodReference ->
                    if (reference.definingClass == GUIDELINE) {
                        when (reference.name) {
                            "setGuidelinePercent" -> setsPercent = true
                            "setGuidelineEnd" -> setsEnd = true
                        }
                    }

                else -> {}
            }
            if (instruction is WideLiteralInstruction && (instruction.wideLiteral == DOCKED_WIDTH_DP || instruction.wideLiteral == DOCKED_WIDTH_DP_FLOAT)) {
                hasDockedWidth = true
            }
        }
        referencesContainer && setsPercent && setsEnd && hasDockedWidth
    }
}

val retwitchTheatreSplitPatch = bytecodePatch(
    name = "ReTwitch theatre split width",
    description = "Routes Twitch's landscape chat_guideline through the ReTwitch divider so the side-by-side split honours the saved width instead of fighting the native swipe-split. No-op when the divider is off.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        val method = theatreSplitGuidelineFingerprint.method

        // (instructionIndex, isPercentSetter, argumentRegister) for each of the
        // four chat_guideline setters, collected before we mutate the method.
        data class Setter(val index: Int, val percent: Boolean, val register: Int)

        val setters = ArrayList<Setter>()
        method.instructions.toList().forEachIndexed { index, instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                ?: return@forEachIndexed
            if (reference.definingClass != GUIDELINE) return@forEachIndexed
            val percent = when (reference.name) {
                "setGuidelinePercent" -> true
                "setGuidelineEnd" -> false
                else -> return@forEachIndexed
            }
            // invoke-virtual {vGuideline, vValue}: registerD is the argument.
            val register = (instruction as FiveRegisterInstruction).registerD
            check(register < 16) {
                "chat_guideline setter argument v$register too high for inline rewrite"
            }
            setters.add(Setter(index, percent, register))
        }

        check(setters.size == 4) {
            "expected 4 chat_guideline setters in the theatre applier, found ${setters.size}"
        }

        // Rewrite highest-index first so earlier indices stay valid as we insert.
        for (setter in setters.sortedByDescending { it.index }) {
            val call = if (setter.percent) {
                "invoke-static {v${setter.register}}, $HOOK->guidePercent(F)F\n" +
                    "move-result v${setter.register}"
            } else {
                "invoke-static {v${setter.register}}, $HOOK->guideEnd(I)I\n" +
                    "move-result v${setter.register}"
            }
            method.addInstructions(setter.index, call)
        }

        // The theatre state renderer (31.8.0 `gi40.y`) also re-lays the theatre
        // on every state change (the controls showing included): it clones the
        // layout into a ConstraintSet, sets chat_guideline's percent to 0.7/0.8
        // (0.5 split, 1.0 no chat) and applies it -- straight into the
        // LayoutParams, past the setters above, so the chat flashed back to
        // Twitch's width. Its percent writes go through `constraintSetPercent`.
        try {
            var constraintSet: String? = null
            classDefForEach { c ->
                if (constraintSet == null && c.methods.any { m ->
                        m.implementation?.instructions?.any {
                            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                                "All children of ConstraintLayout must have ids to use ConstraintSet"
                        } == true
                    }
                ) constraintSet = c.type
            }
            val setType = constraintSet ?: throw IllegalStateException("ConstraintSet class not found")
            val renderers = ArrayList<Pair<String, String>>()
            classDefForEach { c ->
                for (m in c.methods) {
                    val code = m.implementation?.instructions ?: continue
                    val literals = code.mapNotNull { (it as? WideLiteralInstruction)?.wideLiteral }.toSet()
                    if (CHAT_PERCENT_DOCKED in literals && CHAT_PERCENT_TABLET in literals &&
                        code.any { percentSetter(it, setType) }
                    ) renderers += c.type to m.name + m.parameterTypes.joinToString("", "(", ")") + m.returnType
                }
            }
            val (owner, signature) = renderers.singleOrNull()
                ?: throw IllegalStateException("theatre ConstraintSet renderer: ${renderers.size} candidates")
            val renderer = mutableClassDefBy(owner).methods.first {
                it.name + it.parameterTypes.joinToString("", "(", ")") + it.returnType == signature
            }
            // Calls are replaced in place (one is a branch target: an insertion
            // before it would be jumped over) by a bridge that filters the percent.
            val sites = renderer.instructions.withIndex().filter { percentSetter(it.value, setType) }.toList()
            val setter = (sites.first().value as ReferenceInstruction).reference as MethodReference
            val ownerClass = mutableClassDefBy(owner)
            val bridge = ImmutableMethod(
                owner, "retwitchChatGuidePercent",
                listOf(setType, "F", "I").map { ImmutableMethodParameter(it, null, null) },
                "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value,
                null, null, MutableMethodImplementation(3),
            ).toMutable()
            bridge.addInstructions(
                0,
                """
                    invoke-static {p1}, $HOOK->constraintSetPercent(F)F
                    move-result p1
                    invoke-virtual {p0, p1, p2}, $setter
                    return-void
                """.trimIndent(),
            )
            ownerClass.methods.add(bridge)
            for (site in sites) {
                val regs = site.value as FiveRegisterInstruction
                renderer.replaceInstruction(
                    site.index,
                    "invoke-static {v${regs.registerC}, v${regs.registerD}, v${regs.registerE}}, " +
                        "$owner->retwitchChatGuidePercent($setType" + "FI)V",
                )
            }
            println("[ReTwitch] theatre split: ${sites.size} ConstraintSet guideline write(s) in $owner")
        } catch (e: Exception) {
            println("[ReTwitch] theatre split: ConstraintSet renderer skipped: $e")
        }
    }
}

private const val CHAT_PERCENT_DOCKED = 0x3f333333L // 0.7f
private const val CHAT_PERCENT_TABLET = 0x3f4ccccdL // 0.8f

/** `ConstraintSet.setGuidelinePercent` (R8: `(FI)V`, percent first) on [setType]. */
private fun percentSetter(instruction: com.android.tools.smali.dexlib2.iface.instruction.Instruction, setType: String): Boolean {
    val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return ref.definingClass == setType && ref.returnType == "V" &&
        ref.parameterTypes.map { it.toString() } == listOf("F", "I")
}
