package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val CUSTOM_REWARD = "Ltv/twitch/android/models/communitypoints/CommunityPointsReward\$Custom;"

/**
 * Channel points reward card (31.9.0 `tn9.u`, layout `reward_item`): the bind reading a custom reward's bits
 * cost and the reward's background colour. Its entry goes to `RewardStatus.onBind`.
 */
val retwitchRewardStatusPatch = bytecodePatch(
    name = "ReTwitch reward status",
    description = "Optionally shows a cooldown timer or a cross over channel points rewards.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val found = ArrayList<Method>()
            classDefForEach { c ->
                c.methods.filterTo(found) { m ->
                    val calls = m.implementation?.instructions?.mapNotNull { it.getReference<MethodReference>() }.orEmpty()
                    m.returnType == "V" && m.parameterTypes.size == 1 &&
                        calls.any { it.definingClass == CUSTOM_REWARD && it.name == "getBitsCost" } &&
                        calls.any { it.name == "getBackgroundColor" }
                }
            }
            val bind = found.single()
            mutableClassDefBy(bind.definingClass).methods.first {
                it.name == bind.name && it.parameterTypes.map(Any::toString) == bind.parameterTypes.map(Any::toString)
            }.addInstructions(0, "invoke-static/range {p0 .. p1}, Ltv/retwitch/mod/RewardStatus;->onBind(Ljava/lang/Object;Ljava/lang/Object;)V")
        } catch (e: Exception) {
            println("[ReTwitch] reward status skipped: $e")
        }
    }
}
