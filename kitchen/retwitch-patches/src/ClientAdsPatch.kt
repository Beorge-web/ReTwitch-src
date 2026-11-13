package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Blocks client-side (VAST) video/audio ads -- the ones the app requests and
 * plays through its OWN ad pipeline (equivalent to ReVanced/De-Vanced "Block
 * video/audio ads"). Gated behind the "client-side ads" switch in the ReTwitch
 * settings menu ([AdSettings.isBlockClientAdsEnabled], default on). This is a
 * separate mechanism from the SSAI (stitched, in-stream) ad stripper -- see
 * [AdSettings] for why.
 *
 * ## Where it hooks (30.2.0)
 *
 * Twitch drives its ad player through an MVP state-machine reducer: a
 * `Function2<State, Event, StateAndAction>` (`invoke(Object,Object)Object`).
 * R8 merges several reducers into one synthetic class (`w39` in this decode)
 * that multiplexes them with a `packed-switch` on an int discriminator field;
 * the **default** (fall-through) branch is the interruptive video/audio ad
 * reducer -- it is the only branch that references the ad enum constant
 * `INTERRUPTIVE_AD_ALREADY_RUNNING` and calls `StateMachineKt.noAction(state)`.
 *
 * The default branch is reached ONLY for the ad reducer's own invocations (the
 * very first instruction there is a `check-cast` of the state param to the ad
 * state type, which would `ClassCastException` for any other reducer) -- so
 * short-circuiting the default branch touches nothing but the ad reducer, even
 * though the class is shared.
 *
 * ## What it does
 *
 * At the start of that default branch we inject a gate:
 *
 * ```
 *   if (AdSettings.isBlockClientAdsEnabled())
 *       return StateMachineKt.noAction(state);   // no transition, no action
 *   // else fall through to Twitch's original ad reducer
 * ```
 *
 * `noAction(currentState)` keeps the presenter in its current state and emits
 * no action, so the ad state machine never advances into "request/show ad" --
 * ads are never played -- while every non-ad reducer multiplexed into the same
 * class is left completely untouched. Flipping the toggle off restores stock
 * behaviour on the next stream.
 *
 * ## R8 resilience
 *
 * Nothing obfuscated is hardcoded. The reducer is found structurally (an
 * `invoke(Object,Object)Object` that has a `packed-switch`, references a field
 * literally named `INTERRUPTIVE_AD_ALREADY_RUNNING`, and calls
 * `.../presenter/StateMachineKt;->...(PresenterState)StateAndAction`). The
 * obfuscated `noAction` method name is read back off that very call site, and
 * `PresenterState`/`StateAndAction`/`StateMachineKt` live in the un-obfuscated
 * `tv.twitch.android.core.mvp.presenter` package. Zero or multiple matches fail
 * the build loudly rather than silently no-op'ing at runtime.
 */
private const val AD_SETTINGS = "Ltv/retwitch/mod/AdSettings;"
private const val PRESENTER_STATE = "Ltv/twitch/android/core/mvp/presenter/PresenterState;"
private const val STATE_MACHINE_KT = "Ltv/twitch/android/core/mvp/presenter/StateMachineKt;"
private const val STATE_AND_ACTION = "Ltv/twitch/android/core/mvp/presenter/StateAndAction;"
private const val AD_ENUM_CONSTANT = "INTERRUPTIVE_AD_ALREADY_RUNNING"

private const val OBJECT = "Ljava/lang/Object;"

/** The reducer's `Function2.invoke(state, event): StateAndAction` shape. */
private fun com.android.tools.smali.dexlib2.iface.Method.isReducerInvoke(): Boolean =
    name == "invoke" &&
        returnType == OBJECT &&
        parameterTypes.map { it.toString() } == listOf(OBJECT, OBJECT)

/** `StateMachineKt.noAction(PresenterState): StateAndAction` -- the 1-arg static. */
private fun MethodReference.isNoAction(): Boolean =
    definingClass == STATE_MACHINE_KT &&
        returnType == STATE_AND_ACTION &&
        parameterTypes.map { it.toString() } == listOf(PRESENTER_STATE)

val retwitchClientAdsPatch = bytecodePatch(
    name = "ReTwitch block client-side ads",
    description = "Blocks client-side (VAST) video/audio ads by short-circuiting the ad state-machine reducer. Toggle in the ReTwitch settings menu.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        // The merged ad reducer: the class with an invoke(Object,Object)Object
        // that (a) references the INTERRUPTIVE_AD_ALREADY_RUNNING enum constant,
        // (b) has a packed-switch (the multiplexer), and (c) calls noAction.
        val reducerClass = mutableClassDefByOrNull { classDef ->
            classDef.methods.any { m ->
                m.isReducerInvoke() && run {
                    val insns = m.implementation?.instructions?.toList() ?: return@run false
                    val refsAdEnum = insns.any { insn ->
                        (insn as? ReferenceInstruction)?.reference
                            .let { it as? FieldReference }?.name == AD_ENUM_CONSTANT
                    }
                    val hasSwitch = insns.any { it.opcode == Opcode.PACKED_SWITCH }
                    val callsNoAction = insns.any { insn ->
                        (insn as? ReferenceInstruction)?.reference
                            .let { it as? MethodReference }?.isNoAction() == true
                    }
                    refsAdEnum && hasSwitch && callsNoAction
                }
            }
        } ?: error(
            "retwitchClientAdsPatch: no ad state-machine reducer found " +
                "(invoke(Object,Object)Object referencing $AD_ENUM_CONSTANT with a packed-switch and noAction)",
        )

        val method = reducerClass.methods.first { m ->
            m.isReducerInvoke() && run {
                val insns = m.implementation?.instructions?.toList() ?: return@run false
                insns.any {
                    (it as? ReferenceInstruction)?.reference
                        .let { r -> r as? FieldReference }?.name == AD_ENUM_CONSTANT
                } && insns.any { it.opcode == Opcode.PACKED_SWITCH }
            }
        }

        val insns = method.instructions.toList()

        // Read the (obfuscated) noAction method name off its own call site so
        // the injected invoke is always correct for the build being patched.
        val noActionRef = insns.asSequence()
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .first { it.isNoAction() }
        val noActionDescriptor =
            "$STATE_MACHINE_KT->${noActionRef.name}($PRESENTER_STATE)$STATE_AND_ACTION"

        // The default (ad-reducer) branch begins at the instruction right after
        // the packed-switch. Inject the gate there so only the ad reducer is
        // affected; every other multiplexed reducer keeps its original path.
        val switchIndex = insns.indexOfFirst { it.opcode == Opcode.PACKED_SWITCH }
        val defaultBranchIndex = switchIndex + 1
        val fallThrough = method.getInstruction(defaultBranchIndex)

        // v0 is dead here: it held the packed-switch discriminant (already
        // consumed) and the default branch's first real write overwrites it.
        method.addInstructionsWithLabels(
            defaultBranchIndex,
            """
                invoke-static {}, $AD_SETTINGS->isBlockClientAdsEnabled()Z
                move-result v0
                if-eqz v0, :rt_client_ads_skip
                check-cast p1, $PRESENTER_STATE
                invoke-static {p1}, $noActionDescriptor
                move-result-object p1
                return-object p1
            """.trimIndent(),
            ExternalLabel("rt_client_ads_skip", fallThrough),
        )
    }
}
