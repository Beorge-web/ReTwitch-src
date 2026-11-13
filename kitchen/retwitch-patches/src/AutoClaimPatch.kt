package tv.retwitch.patches

import app.morphe.patcher.fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.findMutableMethodOf

private const val CHAT_MODE_METADATA = "Ltv/twitch/android/shared/one/chat/pub/ChatModeMetadata;"
private const val COMMUNITY_POINTS_MODEL =
    "Ltv/twitch/android/models/communitypoints/CommunityPointsModel;"

/**
 * Anchors the (obfuscated) Community Points button presenter via the Kotlin
 * method-reference signature string R8 keeps for reflection. It embeds the
 * source type name `CommunityPointsButtonStateProvider$State`, so it survives
 * R8 renaming and matches exactly one class (the presenter's constructor).
 */
val communityPointsButtonPresenterFingerprint = fingerprint {
    strings(
        "processStateUpdate(Ltv/twitch/android/shared/community/points/presenters/" +
            "CommunityPointsButtonStateProvider\$State;Ltv/twitch/android/shared/community/" +
            "points/presenters/CommunityPointsButtonStateProvider\$UpdateEvent;)" +
            "Ltv/twitch/android/core/mvp/presenter/StateAndAction;",
    )
}

/**
 * Automatically claims Community Points bonuses.
 *
 * The presenter's render method (state -> view-state) runs on every state
 * emission and its state object holds the currently-available claim (or null).
 * We inject at its entry: if a claim is present and auto-claim is enabled (and
 * this claim id wasn't already attempted -- dedup lives in the extension), we
 * invoke the app's own claim API with the claim id. `ChatModeMetadata` is only
 * an analytics enum, so passing a default value is safe for the claim itself.
 *
 * All obfuscated types/fields are derived from stable anchors
 * (`CommunityPointsModel.setClaim`, `ChatModeMetadata`, the presenter's single
 * non-constructor method), never hardcoded R8 letters. The whole body is
 * best-effort: if any anchor can't be resolved on a future Twitch build, the
 * patch logs and no-ops rather than breaking the rest of the bundle.
 */
val retwitchAutoClaimPatch = bytecodePatch(
    name = "ReTwitch auto claim channel points",
    description = "Automatically claims Community Points bonuses, toggleable in the ReTwitch menu.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val presenterClass = communityPointsButtonPresenterFingerprint.classDef
            val presenterType = presenterClass.type

            // The presenter has exactly one non-constructor method: the render
            // (state -> button view-state) mapper. Its parameter is the state.
            val renderMethod = presenterClass.methods.first { method ->
                method.name != "<init>" &&
                    method.parameters.size == 1 &&
                    method.returnType.startsWith("L")
            }
            val stateType = renderMethod.parameters.first().type

            // The claim model type = parameter of CommunityPointsModel.setClaim.
            val claimType = classDefBy(COMMUNITY_POINTS_MODEL).methods
                .first { it.name == "setClaim" }
                .parameters.first().type

            // The claim field in the state = the field of the claim-model type.
            val claimField = classDefBy(stateType).fields.first { it.type == claimType }

            // The claim id inside the claim model = its sole String field.
            val claimIdField = classDefBy(claimType).fields
                .first { it.type == "Ljava/lang/String;" }

            // The claim API is the presenter field whose (interface) type declares
            // a `(String, ChatModeMetadata) -> void` claim method.
            var apiType: String? = null
            var apiFieldName: String? = null
            var claimMethodName: String? = null
            for (field in presenterClass.fields) {
                val fieldType = field.type
                if (!fieldType.startsWith("L")) continue
                val fieldClass = classDefByOrNull { it.type == fieldType } ?: continue
                val claimMethod = fieldClass.methods.firstOrNull { method ->
                    method.returnType == "V" &&
                        method.parameters.size == 2 &&
                        method.parameters[0].type == "Ljava/lang/String;" &&
                        method.parameters[1].type == CHAT_MODE_METADATA
                } ?: continue
                apiType = fieldType
                apiFieldName = field.name
                claimMethodName = claimMethod.name
                break
            }

            requireNotNull(apiType) { "claim API field not found on $presenterType" }

            val mutableRender = mutableClassDefBy(presenterClass).findMutableMethodOf(renderMethod)
            val fallThrough = mutableRender.getInstruction(0)

            mutableRender.addInstructionsWithLabels(
                0,
                """
                    iget-object v0, p1, $stateType->${claimField.name}:$claimType
                    if-eqz v0, :rt_skip
                    iget-object v0, v0, $claimType->${claimIdField.name}:Ljava/lang/String;
                    invoke-static {v0}, Ltv/retwitch/mod/AutoClaimHook;->shouldClaim(Ljava/lang/String;)Z
                    move-result v1
                    if-eqz v1, :rt_skip
                    iget-object v1, p0, $presenterType->$apiFieldName:$apiType
                    sget-object v2, $CHAT_MODE_METADATA->NoChat:$CHAT_MODE_METADATA
                    invoke-interface {v1, v0, v2}, $apiType->$claimMethodName(Ljava/lang/String;$CHAT_MODE_METADATA)V
                """.trimIndent(),
                ExternalLabel("rt_skip", fallThrough),
            )
        } catch (throwable: Throwable) {
            println("[ReTwitch] auto-claim hook skipped: $throwable")
        }
    }
}
