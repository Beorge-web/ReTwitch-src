package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val PROMO_GATE = "Ltv/retwitch/mod/PromoHook;->blocked()Z"
private const val LANDING_ACTIVITY = "Ltv/twitch/android/feature/viewer/landing/ViewerLandingActivity;"

private fun Method.refs() = implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference }.orEmpty()
private fun Method.strs() = refs().filterIsInstance<StringReference>().map { it.string }

/**
 * Promotions, ported from twitch-patched ("Hide Turbo promotions", "Hide
 * subscription discount banners"; written for 31.8.0), native parts only:
 *  - the subscription discount banner: its Compose renderer (static, 7 params,
 *    two Strings at 2..3, reads the `CREATOR_LED` enum constant) returns early;
 *  - the Turbo signup prompt: the ViewerLandingActivity action called by the
 *    "maybeShowTurboSignupUpsell" callback returns early;
 *  - the Turbo startup offer: its navigation call in the
 *    "turboAppStartUpsellHelper" coroutine goes through a gated bridge;
 *  - the "Try ad-free" chip by feed section headers: renderers' `instance-of`
 *    test for its `Visible(ingressType=` state goes through the gate.
 * All behind `PromoHook.blocked()` (off by default); each part skips on its own.
 *  - the React Native home's Turbo button: hidden when RN sets its testID.
 * The settings row is not covered.
 * Also the chat header's "Gift a Sub" button, behind its own toggle (`PromoHook.giftButton`).
 * And Twitch's cookie consent dialog at start, behind its own toggle (`PromoHook.consentSubmitted`).
 */
val retwitchPromotionsPatch = bytecodePatch(
    name = "ReTwitch hide promotions",
    description = "Optionally hides the Turbo startup offer and signup prompt and the subscription discount banner.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        val methods = ArrayList<Method>()
        classDefForEach { classDef -> methods += classDef.methods }

        part("subscription discount banner") {
            val renderer = methods.single { m ->
                AccessFlags.STATIC.isSet(m.accessFlags) && m.returnType == "V" && m.parameterTypes.size == 7 &&
                    m.parameterTypes.subList(2, 4).map(Any::toString) == listOf("Ljava/lang/String;", "Ljava/lang/String;") &&
                    m.refs().any { (it as? FieldReference)?.name == "CREATOR_LED" }
            }
            gateEntry(mutable(renderer))
        }

        part("Turbo signup prompt") {
            val callback = methods.filter { it.name == "<init>" && "maybeShowTurboSignupUpsell" in it.strs() }
                .map { it.definingClass }.distinct().single()
            val action = methods.filter { it.definingClass == callback }.flatMap { it.refs() }
                .filterIsInstance<MethodReference>()
                .filter {
                    it.definingClass == LANDING_ACTIVITY && it.name != "<init>" &&
                        it.parameterTypes.isEmpty() && it.returnType == "V"
                }.distinctBy { it.toString() }.single()
            gateEntry(mutableClassDefBy(LANDING_ACTIVITY).methods.first {
                it.name == action.name && it.parameterTypes.isEmpty() && it.returnType == "V"
            })
        }

        part("Turbo ingress chip") {
            // "Try ad-free" chip next to feed section headers: its state model is
            // `Visible(ingressType=...)`; renderers test `instance-of` it, then set
            // the chip VISIBLE, else GONE. The gate turns that test false.
            val visible = methods.filter { it.name == "toString" && "Visible(ingressType=" in it.strs() }
                .map { it.definingClass }.distinct().single()
            var patched = 0
            for (m in methods) {
                if (m.definingClass == visible || m.refs().none { (it as? MethodReference)?.name == "setVisibility" }) continue
                val code = m.implementation?.instructions?.toList() ?: continue
                val sites = code.indices.filter { i ->
                    code[i].opcode == Opcode.INSTANCE_OF && ((code[i] as ReferenceInstruction).reference as TypeReference).type == visible
                }
                if (sites.isEmpty()) continue
                val target = mutable(m)
                for (i in sites.sortedDescending()) {
                    val reg = (code[i] as TwoRegisterInstruction).registerA
                    target.addInstructions(
                        i + 1,
                        """
                            invoke-static {v$reg}, Ltv/retwitch/mod/PromoHook;->turboIngress(Z)Z
                            move-result v$reg
                        """.trimIndent(),
                    )
                    patched++
                }
            }
            if (patched == 0) throw PatchException("no Turbo ingress renderer found")
        }

        part("React Native Turbo button") {
            // The RN home's "Try ad-free" (testID "following-turbo-upsell"): RN views skip
            // native layout passes, so it's hidden right where RN assigns the testID.
            val setTestId = mutableClassDefBy("Lcom/facebook/react/uimanager/BaseViewManager;").methods.single {
                it.name == "setTestId" && it.parameterTypes.map(Any::toString) == listOf("Landroid/view/View;", "Ljava/lang/String;")
            }
            val last = setTestId.instructions.size - 1
            if (setTestId.getInstruction(last).opcode != Opcode.RETURN_VOID) throw PatchException("setTestId shape changed")
            setTestId.addInstructions(
                last,
                "invoke-static {p1, p2}, Ltv/retwitch/mod/PromoHook;->onRnTestId(Landroid/view/View;Ljava/lang/String;)V",
            )
        }

        part("React Native Turbo offers") {
            // A hidden view keeps its Yoga box, so the button must not mount at all: its GQL query
            // gets no offers. The hook also widens the Following tab's categories (own toggle).
            // NetworkingModule isn't obfuscated; p5 = body map at entry (`.locals 24`).
            mutableClassDefBy("Lcom/facebook/react/modules/network/NetworkingModule;").methods
                .single { it.name == "sendRequestInternalReal" && it.parameterTypes[1].toString() == "Ljava/lang/String;" }
                .addInstructions(0, """
                    invoke-static/range {p5 .. p5}, Ltv/retwitch/mod/PromoHook;->onRnRequestData(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object p5
                    check-cast p5, Lcom/facebook/react/bridge/ReadableMap;
                """.trimIndent())
        }

        part("chat header gift button") {
            // Chat header buttons (Compose): builders of `Visible(buttons=...)` states (31.8.0:
            // the chat header and a twin with the notifications bell) add the gift button only
            // if the channel's isGiftingEnabled(); that result goes through PromoHook.giftButton
            // (own toggle), so Subscribe/Follow stay.
            val visible = methods.filter { it.name == "toString" && "Visible(buttons=" in it.strs() }
                .map { it.definingClass }.toSet()
            val builders = methods.filter { m ->
                m.implementation?.instructions?.any {
                    it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type in visible
                } == true && m.refs().any { (it as? MethodReference)?.name == "isGiftingEnabled" }
            }
            if (builders.isEmpty()) throw PatchException("no header button builder found")
            for (builder in builders) {
                val target = mutable(builder)
                val code = target.instructions.toList()
                val call = code.indices.single { i ->
                    val ref = (code[i] as? ReferenceInstruction)?.reference as? MethodReference
                    ref?.name == "isGiftingEnabled" && ref.returnType == "Z" && code[i + 1].opcode == Opcode.MOVE_RESULT
                }
                val reg = (code[call + 1] as OneRegisterInstruction).registerA
                target.addInstructions(
                    call + 2,
                    """
                        invoke-static/range {v$reg .. v$reg}, Ltv/retwitch/mod/PromoHook;->giftButton(Z)Z
                        move-result v$reg
                    """.trimIndent(),
                )
            }
            println("[ReTwitch] hide promotions: gift button gate in ${builders.map { it.definingClass }}")
        }

        part("cookie consent dialog") {
            // The consent presenter's reducer shows "Cookies and ad preferences" on
            // `ConsentDataUpdated(userHasSubmittedConsent=false, ...)`; that flag is read nowhere else.
            val event = methods.filter { it.name == "toString" && "ConsentDataUpdated(userHasSubmittedConsent=" in it.strs() }
                .map { it.definingClass }.distinct().single()
            val init = mutableClassDefBy(event).methods.single { it.name == "<init>" }
            val code = init.instructions.toList()
            val store = code.indices.single { code[it].opcode == Opcode.IPUT_BOOLEAN }
            val reg = (code[store] as TwoRegisterInstruction).registerA
            init.addInstructions(
                store,
                """
                    invoke-static {v$reg}, Ltv/retwitch/mod/PromoHook;->consentSubmitted(Z)Z
                    move-result v$reg
                """.trimIndent(),
            )
        }

        part("Turbo startup offer") {
            val model = methods.filter { it.name == "toString" && "TurboAppStartUpsell" in it.strs() }
                .map { it.definingClass }.distinct().single()
            val coroutine = methods.single { it.name == "invokeSuspend" && "turboAppStartUpsellHelper" in it.strs() }
            val code = coroutine.implementation!!.instructions.toList()
            val read = code.indices.single { i ->
                code[i].opcode == Opcode.SGET_OBJECT && ((code[i] as ReferenceInstruction).reference as FieldReference).type == model
            }
            val call = code[read + 2]
            val original = (call as ReferenceInstruction).reference as MethodReference
            val regs = call as FiveRegisterInstruction
            if (call.opcode != Opcode.INVOKE_VIRTUAL || regs.registerCount != 3 || original.returnType != "V") {
                throw PatchException("navigation call shape changed")
            }
            val owner = mutableClassDefBy(original.definingClass)
            val bridge = ImmutableMethod(
                owner.type, "retwitchTurboStartupOffer",
                (listOf(owner.type) + original.parameterTypes.map { it.toString() })
                    .map { ImmutableMethodParameter(it, null, null) },
                "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value,
                null, null, MutableMethodImplementation(4),
            ).toMutable()
            bridge.addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $PROMO_GATE
                    move-result v0
                    if-nez v0, :done
                    invoke-virtual {p0, p1, p2}, $original
                    :done
                    return-void
                """.trimIndent(),
            )
            owner.methods.add(bridge)
            mutable(coroutine).replaceInstruction(
                read + 2,
                "invoke-static {v${regs.registerC}, v${regs.registerD}, v${regs.registerE}}, " +
                    "${owner.type}->retwitchTurboStartupOffer(${owner.type}${original.parameterTypes.joinToString("")})V",
            )
        }
    }
}

private fun part(label: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        println("[ReTwitch] hide promotions: $label skipped: $e")
    }
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.first {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
    }

/** At entry: return early while promotions are hidden. Needs one free local register. */
private fun gateEntry(method: MutableMethod) {
    val impl = method.implementation ?: throw PatchException("${method.name}: no code")
    val ins = (if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1) +
        method.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
    if (impl.registerCount <= ins || impl.tryBlocks.any { it.startCodeAddress == 0 }) {
        throw PatchException("${method.name}: no free entry register or entry inside a try block")
    }
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static {}, $PROMO_GATE
            move-result v0
            if-eqz v0, :rt_promo_original
            return-void
        """.trimIndent(),
        ExternalLabel("rt_promo_original", method.getInstruction(0)),
    )
}
