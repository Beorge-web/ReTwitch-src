package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val DECISION_KT = "Ltv/twitch/android/feature/discovery/feed/rn/theatre/RNTheatreRouteDecisionKt;"
private const val DECISION = "Ltv/twitch/android/feature/discovery/feed/rn/theatre/RNTheatreRouteDecision;"

/**
 * 31.x opens live channels in a React Native theatre (`TwitchIVSPlayerView`,
 * JS chat) that no native hook reaches: no third-party emotes or badges, no
 * player buttons, no usher proxy/VAFT. Every route into it asks
 * `RNTheatreRouteDecisionKt.rnTheatreRouteDecision(...)` (31.8.0 callers:
 * `TwitchRNTheatreLauncher.routableEntry`, `TwitchRNHostNavigationModule
 * .routeChannelOpen`); on any `Refuse*` answer Twitch falls back to the native
 * theatre. The result is passed through `RnTheatreHook.route` right after each
 * call (the method itself has no spare registers: 7 params, 0 locals).
 * Class names are unobfuscated in 31.8.0.
 */
val retwitchClassicTheatrePatch = bytecodePatch(
    name = "ReTwitch classic theatre",
    description = "Opens channels in the native theatre instead of the React Native one, so ReTwitch chat and player features work there.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        val owners = ArrayList<String>()
        classDefForEach { classDef ->
            if (classDef.methods.any { m ->
                    m.implementation?.instructions?.any { isDecisionCall(it) } == true
                }
            ) owners += classDef.type
        }
        var hooked = 0
        for (owner in owners) {
            mutableClassDefBy(owner).methods.forEach { method ->
                val insns = method.implementation?.instructions?.toList() ?: return@forEach
                val sites = insns.indices.filter { i ->
                    isDecisionCall(insns[i]) && i + 1 < insns.size &&
                        insns[i + 1].opcode == Opcode.MOVE_RESULT_OBJECT
                }
                for (i in sites.sortedDescending()) {
                    val reg = (insns[i + 1] as OneRegisterInstruction).registerA
                    method.addInstructions(
                        i + 2,
                        """
                            invoke-static/range {v$reg .. v$reg}, Ltv/retwitch/mod/RnTheatreHook;->route(Ljava/lang/Object;)Ljava/lang/Object;
                            move-result-object v$reg
                            check-cast v$reg, $DECISION
                        """.trimIndent(),
                    )
                    hooked++
                }
            }
        }
        if (hooked == 0) throw PatchException("no rnTheatreRouteDecision call sites found")
        println("[ReTwitch] classic theatre: $hooked route decision site(s) in $owners")
    }
}

private fun isDecisionCall(insn: com.android.tools.smali.dexlib2.iface.instruction.Instruction): Boolean {
    val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return ref.definingClass == DECISION_KT && ref.name == "rnTheatreRouteDecision"
}
