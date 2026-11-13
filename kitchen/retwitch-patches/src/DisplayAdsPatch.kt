package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val GATE = "Ltv/retwitch/mod/AdSettings;->isDisplayAdsBlocked()Z"

private fun Method.strings() = implementation?.instructions?.mapNotNull {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
}.orEmpty()

/**
 * Display ads (banners, sponsored cards) and the Following headliner, ported
 * from twitch-patched's "Hide feed and display ads" (written for 31.8.0):
 *  - the display-ad response parser (`"failed to parse display ad response: "`,
 *    `(Result, boolean)`) returns Twitch's own no-ad singleton;
 *  - the Following headliner request (`"show_headliner_ad"`) completes with
 *    `Single.just(Optional.empty())`, found from its own no-fill branch;
 *  - React Native fetches of Ad Edge (the RN Following headliner, feed and stream display ads)
 *    fail (`AdSettings.rnRequestUrl`); skipped on its own if RN changes.
 * All gated by `AdSettings.isDisplayAdsBlocked()` (off by default).
 */
val retwitchDisplayAdsPatch = bytecodePatch(
    name = "ReTwitch hide display ads",
    description = "Optionally hides display ads, sponsored cards and the Following headliner. Toggle in the ad-blocking menu.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        val methods = ArrayList<Method>()
        classDefForEach { classDef -> methods += classDef.methods }

        // 1) Display-ad parser -> no-ad result.
        val parser = methods.singleOrNull {
            "failed to parse display ad response: " in it.strings() &&
                it.parameterTypes.map(Any::toString) == listOf("Lretrofit2/adapter/rxjava2/Result;", "Z") &&
                !AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: throw PatchException("display-ad response parser not found")
        val noAd = parser.implementation!!.instructions.filter { it.opcode == Opcode.SGET_OBJECT }
            .mapNotNull { (it as ReferenceInstruction).reference as? FieldReference }
            .filter { it.type == it.definingClass && classDefBy(it.type).superclass == parser.returnType }
            .distinctBy { it.toString() }.singleOrNull()
            ?: throw PatchException("display no-ad singleton not found")
        gate(mutable(parser), "sget-object v0, $noAd\nreturn-object v0")

        // 2) Following headliner request -> Single.just(Optional.empty()).
        val request = methods.singleOrNull {
            it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;") &&
                it.returnType == "Ljava/lang/Object;" &&
                it.strings().containsAll(listOf("show_headliner_ad", "ad_request_format_declined"))
        } ?: throw PatchException("Following headliner request not found")
        val code = request.implementation!!.instructions.toList()
        val emptyIndex = code.indices.firstOrNull { i ->
            val ref = (code[i] as? ReferenceInstruction)?.reference as? MethodReference
            code[i].opcode == Opcode.INVOKE_STATIC && ref != null &&
                ref.definingClass == "Ltv/twitch/android/util/Optional\$Companion;" && ref.parameterTypes.isEmpty() &&
                code.getOrNull(i + 2)?.let { next ->
                    ((next as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == "Lio/reactivex/u;"
                } == true
        } ?: throw PatchException("headliner no-fill branch not found")
        val empty = (code[emptyIndex] as ReferenceInstruction).reference as MethodReference
        val single = (code[emptyIndex + 2] as ReferenceInstruction).reference as MethodReference
        // Not at the method's entry: 32.0.0 inlined the request into a merged lambda's `invoke` (`i9j`), whose
        // other branches must run. Right before the request reads its "show_headliner_ad" pref instead; that
        // `const-string`'s register is free there.
        val headliner = mutable(request)
        val prefAt = headliner.instructions.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "show_headliner_ad"
        }
        val scratch = (headliner.getInstruction(prefAt) as OneRegisterInstruction).registerA
        headliner.addInstructionsWithLabels(
            prefAt,
            """
                invoke-static {}, $GATE
                move-result v$scratch
                if-eqz v$scratch, :rt_display_original
                invoke-static {}, $empty
                move-result-object v$scratch
                invoke-static/range {v$scratch .. v$scratch}, $single
                move-result-object v$scratch
                return-object v$scratch
            """.trimIndent(),
            ExternalLabel("rt_display_original", headliner.getInstruction(prefAt)),
        )

        // 3) React Native ad requests: the RN home fetches Ad Edge itself (Following headliner,
        // feed/stream display ads). NetworkingModule is not obfuscated; R8 inlined `sendRequest`
        // straight into `sendRequestInternalReal` (the only sender). p2 = url at entry, a high
        // register there (`.locals 24`), hence /range.
        try {
            mutableClassDefBy("Lcom/facebook/react/modules/network/NetworkingModule;").methods
                .single { it.name == "sendRequestInternalReal" && it.parameterTypes[1].toString() == "Ljava/lang/String;" }
                .addInstructions(0, """
                    invoke-static/range {p2 .. p2}, Ltv/retwitch/mod/AdSettings;->rnRequestUrl(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object p2
                """.trimIndent())
        } catch (e: Exception) {
            println("[ReTwitch] hide display ads: RN ad requests skipped: $e")
        }
    }
}

private fun app.morphe.patcher.patch.BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.first {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
    }

/** At method entry: when display ads are blocked, run [blocked] (which returns); else the original body. */
private fun gate(method: MutableMethod, blocked: String) {
    val impl = method.implementation ?: throw PatchException("${method.name}: no code")
    val ins = 1 + method.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
    if (impl.registerCount <= ins) throw PatchException("${method.name}: no free local register")
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static {}, $GATE
            move-result v0
            if-eqz v0, :rt_display_original
        """.trimIndent() + "\n" + blocked,
        ExternalLabel("rt_display_original", method.getInstruction(0)),
    )
}
