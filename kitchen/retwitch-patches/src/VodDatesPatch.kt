package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * VOD dates as date and time. Native "1 week ago" comes from two places: VOD list rows call
 * `VodModel.getRelativeCreatedAtString(Context)` (public model, name kept by R8), the channel's
 * video shelves (past broadcasts, highlights, uploads: the thumbnail overlay) a static
 * `(VodModelBase, Context) -> String` helper (31.9.0 `xl50.e`). On entry of both
 * `VodDates.vodDate` returns the absolute date with the toggle on; null runs Twitch's own.
 */
val retwitchVodDatesPatch = bytecodePatch(
    name = "ReTwitch VOD absolute dates",
    description = "Shows the date and time of VODs instead of \"1 week ago\" (toggle, off by default).",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val targets = mutableListOf<MutableMethod>()
            targets += mutableClassDefBy("Ltv/twitch/android/models/videos/VodModel;").methods.single {
                it.name == "getRelativeCreatedAtString" && it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/content/Context;")
            }
            var helper: String? = null
            classDefForEach { c ->
                if (c.methods.any { it.isShelfDateHelper() }) helper = c.type
            }
            targets += mutableClassDefBy(helper ?: throw IllegalStateException("shelf date helper not found"))
                .methods.single { it.isShelfDateHelper() }
            for (method in targets) {
                check(method.implementation!!.registerCount > method.parameterTypes.size + 1) { "${method.name}: no free local register" }
                method.addInstructionsWithLabels(
                    0,
                    """
                        invoke-static {p0, p1}, Ltv/retwitch/mod/VodDates;->vodDate(Ljava/lang/Object;Landroid/content/Context;)Ljava/lang/String;
                        move-result-object v0
                        if-eqz v0, :rt_twitch_vod_date
                        return-object v0
                    """.trimIndent(),
                    ExternalLabel("rt_twitch_vod_date", method.getInstruction(0)),
                )
                println("[ReTwitch] VOD dates: ${method.definingClass}->${method.name}")
            }
        } catch (throwable: Throwable) {
            println("[ReTwitch] VOD dates skipped: $throwable")
        }
    }
}

private fun com.android.tools.smali.dexlib2.iface.Method.isShelfDateHelper() =
    AccessFlags.STATIC.isSet(accessFlags) && returnType == "Ljava/lang/String;" &&
        parameterTypes.map { it.toString() } == listOf("Ltv/twitch/android/models/base/VodModelBase;", "Landroid/content/Context;")
