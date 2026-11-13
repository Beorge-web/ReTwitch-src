package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.getResourceId
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction

/**
 * The header of "Vertical view" (31.9.0 `d320.h`): avatar, follow, subscribe, leaderboard. Compose, in the
 * same view as the tap area of the player, so it can't be hidden by id: the composable returns at entry,
 * before it opens its group. Skipped softly.
 *
 * The panel under its chat (input, Bits, points, chat settings) is two composables (31.9.0 `ita.e`, `ita.f`)
 * taking the same state, `"UiState(communityPointsButtonState="`, as their second parameter; the chat around
 * them (`ita.g`) has it third, so the messages stay.
 *
 * Under the messages is the row of highlights: Drops, the pinned message, polls, predictions. It has its own
 * view model (31.9.0 `w880`, keys `"PinnedChat_"`, `"Drops_"`) and one composable on top (`r780.m`).
 *
 * With the chat folded away, a button brings it back and counts the unread messages (`mgs.d`, the composable
 * with that button's description taking two ints; another one, `hpi.n`, has the same text for other screens).
 *
 * On a stream with a vertical format the player overlay has a layout picker (31.9.0 `e5t.c`): rows of a label
 * and an icon, each row clickable, and the labels reach over the pause button. The label's text goes through
 * `UiHider.layoutLabel` right before its `Text`; the icon keeps its own description.
 */
private val verticalHeaderFingerprint = fingerprint {
    returns("V")
    strings("collaborators_")
    custom { method, _ ->
        method.parameterTypes.any { it.toString() == "Ltv/twitch/android/models/channel/ChannelModel;" }
    }
}

private val verticalPanelFingerprint = fingerprint {
    returns("V")
    strings("onChannelPointsClicked", "onChannelPointsRedeemed")
    custom { method, _ -> method.parameterTypes.size > 8 }
}

/** The composable returns at entry while [hook] says so. */
private fun skipWhen(hook: String, method: MutableMethod) = method.addInstructionsWithLabels(
    0,
    """
        invoke-static {}, Ltv/retwitch/mod/UiHider;->$hook()Z
        move-result v0
        if-eqz v0, :keep
        return-void
    """.trimIndent(),
    ExternalLabel("keep", method.getInstruction(0)),
)

private val verticalHighlightsFingerprint = fingerprint {
    strings("PinnedChat_", "Drops_")
}

val retwitchVerticalOverlayPatch = bytecodePatch(
    name = "ReTwitch vertical view buttons",
    description = "Optionally hides the row of buttons and the bottom panel in Vertical view.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch, resourceMappingPatch)

    execute {
        try {
            val method = verticalHeaderFingerprint.method
            skipWhen("hideVerticalButtons", method)
            println("[ReTwitch] vertical buttons: ${method.definingClass}->${method.name}")
        } catch (e: Exception) {
            println("[ReTwitch] vertical buttons skipped: $e")
        }
        try {
            val found = verticalPanelFingerprint.method
            val state = found.parameterTypes[1].toString()
            val panels = mutableClassDefBy(found.definingClass).methods.filter {
                it.returnType == "V" && it.parameterTypes.getOrNull(1)?.toString() == state
            }
            panels.forEach { skipWhen("hideVerticalPanel", it) }
            println("[ReTwitch] vertical panel: ${panels.map { it.name }} in ${found.definingClass}")
        } catch (e: Exception) {
            println("[ReTwitch] vertical panel skipped: $e")
        }
        try {
            val model = verticalHighlightsFingerprint.method.definingClass
            val rows = ArrayList<Pair<String, String>>()
            classDefForEach { c ->
                c.methods.filter { m ->
                    m.returnType == "V" && m.parameterTypes.size == 5 && m.parameterTypes[1].toString() == model
                }.forEach { rows += c.type to it.name }
            }
            val (owner, name) = rows.single()
            skipWhen("hideVerticalPanel", mutableClassDefBy(owner).methods.single {
                it.name == name && it.parameterTypes.size == 5 && it.parameterTypes[1].toString() == model
            })
            println("[ReTwitch] vertical highlights: $owner->$name")
        } catch (e: Exception) {
            println("[ReTwitch] vertical highlights skipped: $e")
        }
        try {
            val classic = getResourceId(ResourceType.STRING, "view_mode_classic_split")
            val vertical = getResourceId(ResourceType.STRING, "view_mode_vertical_view")
            fun Method.isPicker(): Boolean {
                val literals = implementation?.instructions?.mapNotNull { (it as? WideLiteralInstruction)?.wideLiteral }
                return returnType == "V" && literals != null && classic in literals && vertical in literals
            }
            val owners = ArrayList<String>()
            classDefForEach { c -> if (c.methods.any { it.isPicker() }) owners += c.type }
            val picker = mutableClassDefBy(owners.single()).methods.single { it.isPicker() }
            val code = picker.instructions.toList()
            // Text(String, Modifier, color, ..., Composer, Int, Int): the one static call taking the label first.
            val text = code.indices.single {
                val ref = (code[it] as? ReferenceInstruction)?.reference as? MethodReference
                code[it].opcode == Opcode.INVOKE_STATIC_RANGE && ref != null && ref.returnType == "V" &&
                    ref.parameterTypes.size >= 8 && ref.parameterTypes[0].toString() == "Ljava/lang/String;"
            }
            val label = (code[text] as RegisterRangeInstruction).startRegister
            val result = (text - 1 downTo 0).first {
                code[it].opcode == Opcode.MOVE_RESULT_OBJECT && (code[it] as OneRegisterInstruction).registerA == label
            }
            picker.addInstructions(
                result + 1,
                """
                    invoke-static/range {v$label .. v$label}, Ltv/retwitch/mod/UiHider;->layoutLabel(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$label
                """.trimIndent(),
            )
            println("[ReTwitch] layout picker labels: ${picker.definingClass}->${picker.name}")
        } catch (e: Exception) {
            println("[ReTwitch] layout picker labels skipped: $e")
        }
        try {
            val description = getResourceId(ResourceType.STRING, "show_chat_content_description")
            fun Method.isChatButton() = returnType == "V" &&
                parameterTypes.take(2).map(Any::toString) == listOf("I", "I") &&
                implementation?.instructions?.any { (it as? WideLiteralInstruction)?.wideLiteral == description } == true
            val owners = ArrayList<String>()
            classDefForEach { c -> if (c.methods.any { it.isChatButton() }) owners += c.type }
            val button = mutableClassDefBy(owners.single()).methods.single { it.isChatButton() }
            skipWhen("hideVerticalPanel", button)
            println("[ReTwitch] vertical chat button: ${button.definingClass}->${button.name}")
        } catch (e: Exception) {
            println("[ReTwitch] vertical chat button skipped: $e")
        }
    }
}
