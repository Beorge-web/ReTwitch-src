package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val HOOK = "Ltv/retwitch/mod/LandingTabHook;"

private const val PRESENTER_STATE = "Ltv/twitch/android/core/mvp/presenter/PresenterState;"

/**
 * Makes the main discovery-feed pager land on the "Following" tab instead of
 * "Live" on a normal app launch (toggle in the ReTwitch settings menu,
 * default on).
 *
 * The outer feed pager shows `[Following, Live, Clips]` tabs; which one is
 * selected is decided by the initial *position* the presenter (obfuscated
 * `Lb1d` in 30.2.0) stores in its view-state object (obfuscated `La1d`).
 * That position is `indexOf(requestedPage)` on a normal launch (which is
 * `1`, the Live page) and only falls back to a separate default-getter for
 * pages that aren't in the list -- so patching the getter alone does nothing
 * for the common case. Instead this patches the position value right where
 * it's fed into the state constructor, which covers every code path.
 *
 * Nothing is anchored to an R8 name:
 * - the state class `La1d` is found as the one implementing the (stable)
 *   `PresenterState` interface whose constructor takes the shape
 *   `(int position, boolean, boolean, boolean, List, boolean, <obj>, boolean)`;
 * - its sole construction site (in `Lb1d`) is found by scanning for the
 *   range-form invoke that calls `<state>-><init>`.
 *
 * The position (the 2nd register of that range call, i.e. the first `int`
 * arg) is routed through [LandingTabHook.resolveDefaultPosition] together with
 * the page list, which returns the `FollowingPage`'s index when the toggle is
 * on, or the original value otherwise. A fingerprint matching 0 or >1 targets fails the build
 * loudly rather than silently no-op'ing at runtime.
 */
val retwitchLandingTabPatch = bytecodePatch(
    name = "ReTwitch default to Following tab",
    description = "Opens the Following tab instead of Live on the main discovery feed. Toggle in the ReTwitch settings menu.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        val stateClass = mutableClassDefByOrNull { classDef ->
            classDef.interfaces.any { it == PRESENTER_STATE } &&
                classDef.methods.any { m ->
                    m.name == "<init>" && isFeedStateCtorShape(m.parameterTypes.map { it.toString() })
                }
        } ?: error(
            "retwitchLandingTabPatch: no PresenterState class with the discovery-feed " +
                "state constructor shape (I,Z,Z,Z,List,Z,obj,Z) found",
        )
        val stateType = stateClass.type

        // The single site that constructs that state object (the feed
        // presenter's ctor). Found by the invoke to <state>-><init>, never
        // by the presenter's obfuscated name.
        val presenterClass = mutableClassDefByOrNull { classDef ->
            classDef.methods.any { m -> m.callsInit(stateType) }
        } ?: error("retwitchLandingTabPatch: no class constructs $stateType")

        val method = presenterClass.methods.first { it.callsInit(stateType) }

        val invokeIndex = method.instructions.indexOfFirst { insn ->
            insn is ReferenceInstruction &&
                (insn.reference as? MethodReference)?.let {
                    it.definingClass == stateType && it.name == "<init>"
                } == true
        }
        check(invokeIndex >= 0) { "retwitchLandingTabPatch: state <init> invoke not found in target method" }

        val invoke = method.instructions[invokeIndex]
        val rangeStart = (invoke as? RegisterRangeInstruction)?.startRegister
            ?: error("retwitchLandingTabPatch: state <init> is not a /range invoke (unexpected register layout)")
        // Range is {instance, position:int, z, z, z, pages:List, ...}: hand the
        // position plus the page list (one contiguous range) to the hook, which
        // returns the Following page's index (it moved in 31.x).
        val positionRegister = rangeStart + 1

        method.addInstructions(
            invokeIndex,
            """
                invoke-static/range {v$positionRegister .. v${positionRegister + 4}}, $HOOK->resolveDefaultPosition(IZZZLjava/util/List;)I
                move-result v$positionRegister
            """.trimIndent(),
        )

        // 31.x React Native home (DiscoveryFeedRNFragment, unobfuscated): the
        // starting tab comes from the "tv.twitch.android.rn.feed.subfeedTab"
        // fragment argument, null = RN's default (Live). Route the read value
        // through LandingTabHook.resolveRnTab. Optional: skipped if not found.
        try {
            val rnMethod = mutableClassDefBy(RN_FEED_FRAGMENT).methods.first { it.name == "onCreateView" }
            val code = rnMethod.instructions.toList()
            val keyIndex = code.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == RN_TAB_KEY
            }
            val getIndex = (keyIndex + 1 until code.size).first { i ->
                ((code[i] as? ReferenceInstruction)?.reference as? MethodReference)?.name == "getString"
            }
            check(keyIndex >= 0 && code[getIndex + 1].opcode == Opcode.MOVE_RESULT_OBJECT)
            val tabRegister = (code[getIndex + 1] as OneRegisterInstruction).registerA
            val hook = """
                invoke-static/range {v$tabRegister .. v$tabRegister}, $HOOK->resolveRnTab(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v$tabRegister
            """.trimIndent()
            // `args == null ? null : args.getString(key)`: the null branch
            // (`goto merge; move-object tab, null`) never reaches the
            // move-result, so hook right after its move-object as well
            // (inserted first: higher index).
            val nullMove = code.getOrNull(getIndex + 3)
            if (code[getIndex + 2].opcode == Opcode.GOTO && nullMove?.opcode == Opcode.MOVE_OBJECT &&
                (nullMove as OneRegisterInstruction).registerA == tabRegister
            ) {
                rnMethod.addInstructions(getIndex + 4, hook)
            }
            rnMethod.addInstructions(getIndex + 2, hook)
            // Remember the fragment (entry: p0 is still `this`).
            rnMethod.addInstructions(
                0,
                "invoke-static/range {p0 .. p0}, $HOOK->onRnHomeCreated(Ljava/lang/Object;)V",
            )
            val ready = mutableClassDefBy(RN_CONTENT_READY).methods.filter {
                it.name == "onFirstFeedThumbnailLoaded" || it.name == "onFirstFeedVideoPresented"
            }
            check(ready.isNotEmpty())
            // The launch splash goes as soon as the holder reports content: every read of its flags goes
            // through the hook, which also reports ready once the home is up on Following. 31.9.0 reads them
            // in the holder's getters (`j260.a()`, `b()`); 32.0.0 inlined those into the splash condition.
            fun FieldReference.key() = "$definingClass->$name:$type"
            val flags = ready.flatMap { m -> m.implementation!!.instructions.filter { it.opcode == Opcode.IPUT_BOOLEAN } }
                .map { ((it as ReferenceInstruction).reference as FieldReference).key() }.toSet()
            fun isFlagRead(insn: com.android.tools.smali.dexlib2.iface.instruction.Instruction) =
                insn.opcode == Opcode.IGET_BOOLEAN && ((insn as ReferenceInstruction).reference as FieldReference).key() in flags
            val readers = ArrayList<String>()
            classDefForEach { c ->
                if (c.type != RN_CONTENT_READY && c.methods.any { m -> m.implementation?.instructions?.any(::isFlagRead) == true }) {
                    readers += c.type
                }
            }
            var hooked = 0
            for (type in readers) {
                mutableClassDefBy(type).methods.forEach { m ->
                    val insns = m.implementation?.instructions?.toList() ?: return@forEach
                    insns.indices.filter { isFlagRead(insns[it]) }.reversed().forEach { at ->
                        val value = (insns[at] as OneRegisterInstruction).registerA
                        m.addInstructions(at + 1, """
                            invoke-static/range {v$value .. v$value}, $HOOK->feedReady(Z)Z
                            move-result v$value
                        """.trimIndent())
                        hooked++
                    }
                }
            }
            check(hooked > 0) { "no reads of the feed content flags $flags" }
            println("[ReTwitch] launch cover: $hooked feed content flag read(s) in $readers")
        } catch (e: Exception) {
            println("[ReTwitch] Following tab for the React Native home skipped: $e")
        }
    }
}

private const val RN_FEED_FRAGMENT = "Ltv/twitch/android/feature/discovery/feed/rn/feed/DiscoveryFeedRNFragment;"
private const val RN_TAB_KEY = "tv.twitch.android.rn.feed.subfeedTab"
private const val RN_CONTENT_READY = "Ltv/twitch/android/feature/discovery/feed/rn/bridge/TwitchRNFeedContentReadyModule;"

private fun isFeedStateCtorShape(params: List<String>): Boolean {
    if (params.size != 8) return false
    return params[0] == "I" &&
        params[1] == "Z" &&
        params[2] == "Z" &&
        params[3] == "Z" &&
        params[4] == "Ljava/util/List;" &&
        params[5] == "Z" &&
        params[6].startsWith("L") &&
        params[7] == "Z"
}

private fun com.android.tools.smali.dexlib2.iface.Method.callsInit(stateType: String): Boolean =
    implementation?.instructions?.any { insn ->
        insn is ReferenceInstruction &&
            (insn.reference as? MethodReference)?.let {
                it.definingClass == stateType && it.name == "<init>"
            } == true
    } == true
