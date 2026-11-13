package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/** The player-type enum's static initializer: its wire values are protocol data. */
val playerTypeEnumFingerprint = fingerprint {
    strings("mobile_player", "picture-by-picture", "background_audio")
    custom { method, _ -> method.name == "<clinit>" }
}

private const val LIVE_TOKEN_MARKER = "parseStreamAccessTokenResponse"
private const val LIVE_TOKEN_QUERY = "StreamAccessTokenQuery"

/**
 * VAFT's forced access-token player type: in every live StreamAccessToken
 * request builder (the methods referencing "parseStreamAccessTokenResponse";
 * VOD uses a different query; or creating the `StreamAccessTokenQuery` model), the `playerType.toString()` result is passed
 * through `VaftPlayerType.onTokenRequest` before it goes into the GQL
 * variables. 31.8.0: `bro.a` and `d120.call` (Thream hooks the same spots); 32.0.0: `flo.a`, `sw10.call`.
 */
val retwitchVaftPlayerTypePatch = bytecodePatch(
    name = "ReTwitch VAFT player type",
    description = "Requests live access tokens as another player type (popout by default) to get fewer stitched ads. Configurable in the ad-blocking menu.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        val enumType = playerTypeEnumFingerprint.classDef.type
        var hooked = 0
        fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
        // The live token query model (its operation name). 32.0.0 moved the parser's method reference out of one of
        // the builders into a lambda, so the builders are the methods that create the query (or name the parser).
        val queries = HashSet<String>()
        classDefForEach { classDef ->
            if (classDef.methods.any { m -> m.implementation?.instructions?.any { it.string() == LIVE_TOKEN_QUERY } == true }) {
                queries += classDef.type
            }
        }
        fun Instruction.buildsRequest() = string() == LIVE_TOKEN_MARKER ||
            (opcode == Opcode.NEW_INSTANCE && ((this as ReferenceInstruction).reference as TypeReference).type in queries)
        val owners = ArrayList<String>()
        classDefForEach { classDef ->
            if (classDef.type !in queries && classDef.methods.any { m -> m.implementation?.instructions?.any { it.buildsRequest() } == true }) {
                owners += classDef.type
            }
        }
        for (owner in owners) {
            mutableClassDefBy(owner).methods.forEach { method ->
                val insns = method.implementation?.instructions?.toList() ?: return@forEach
                if (insns.none { it.buildsRequest() }) {
                    return@forEach
                }
                val sites = insns.indices.filter { i ->
                    val ref = (insns[i] as? ReferenceInstruction)?.reference as? MethodReference
                    ref != null && ref.definingClass == enumType && ref.name == "toString" &&
                        i + 1 < insns.size && insns[i + 1].opcode == Opcode.MOVE_RESULT_OBJECT
                }
                for (i in sites.sortedDescending()) {
                    val reg = (insns[i + 1] as OneRegisterInstruction).registerA
                    method.addInstructions(
                        i + 2,
                        """
                            invoke-static/range {v$reg .. v$reg}, Ltv/retwitch/mod/ads/VaftPlayerType;->onTokenRequest(Ljava/lang/String;)Ljava/lang/String;
                            move-result-object v$reg
                        """.trimIndent(),
                    )
                    hooked++
                }
            }
        }
        if (hooked == 0) throw PatchException("no live access-token playerType.toString() sites found")
    }
}
