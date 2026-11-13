package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.getResourceId
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction

/**
 * Twitch's viewer card (tap on a chatter) opens in a bottom sheet dialog, a window of its own that the
 * activity's UI pass never sees. Its view binding (31.9.0 `mj3.b(LayoutInflater, ViewGroup)`, the only
 * static inflater of `layout/viewer_card`) hands the inflated root to `UserHistory.onViewerCard`, which
 * adds the message history row and our badge/nick paint while the card is shown.
 */
val retwitchViewerCardPatch = bytecodePatch(
    name = "ReTwitch viewer card",
    description = "Adds the message history, the RTE badge and the nick paint to the viewer card.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch, resourceMappingPatch)

    execute {
        try {
            val layout = getResourceId(ResourceType.LAYOUT, "viewer_card")
            fun Method.isInflater() = AccessFlags.STATIC.isSet(accessFlags) &&
                parameterTypes.map(Any::toString) == listOf("Landroid/view/LayoutInflater;", "Landroid/view/ViewGroup;") &&
                implementation?.instructions?.any { (it as? WideLiteralInstruction)?.wideLiteral == layout } == true
            var owner: String? = null
            classDefForEach { c ->
                if (c.methods.any { it.isInflater() }) {
                    require(owner == null) { "two viewer card inflaters: $owner, ${c.type}" }
                    owner = c.type
                }
            }
            val method = mutableClassDefBy(owner ?: throw PatchException("viewer card inflater not found"))
                .methods.single { it.isInflater() }
            val code = method.instructions.toList()
            val literal = code.indexOfFirst { (it as? WideLiteralInstruction)?.wideLiteral == layout }
            val result = (literal until code.size).first { code[it].opcode == Opcode.MOVE_RESULT_OBJECT }
            val root = (code[result] as OneRegisterInstruction).registerA
            method.addInstructions(
                result + 1,
                "invoke-static/range {v$root .. v$root}, Ltv/retwitch/mod/UserHistory;->onViewerCard(Landroid/view/View;)V",
            )
            println("[ReTwitch] viewer card: $owner")
        } catch (throwable: Throwable) {
            println("[ReTwitch] viewer card skipped: $throwable")
        }
    }
}
