package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * Links open without Twitch's "This link will take you to ..." dialog (toggle, off by
 * default). The link router (31.8.0 `qz3`: `d(activity, Uri, Z, Function0, Z)` and its
 * suspend twin `f(activity, String, Z, Z, Continuation)`) builds the dialog with the
 * "targetUrl" annotation key after the "twitch.a2z.com" host check, and only while its
 * third parameter is true; at entry that flag goes through `UiHider.linkDisclaimer`.
 * Without the dialog the link takes the same path as a twitch.tv one. Skipped softly.
 */
val retwitchLinkDisclaimerPatch = bytecodePatch(
    name = "ReTwitch no link disclaimer",
    description = "Optionally opens links without the \"This link will take you to\" dialog.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val routers = ArrayList<Method>()
            classDefForEach { c ->
                c.methods.filterTo(routers) { m ->
                    val strings = m.implementation?.instructions
                        ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.orEmpty()
                    m.parameterTypes.size > 2 && m.parameterTypes[2].toString() == "Z" &&
                        "targetUrl" in strings && "twitch.a2z.com" in strings
                }
            }
            if (routers.isEmpty()) throw PatchException("link router not found")
            for (router in routers) {
                val method = mutableClassDefBy(router.definingClass).methods.first {
                    it.name == router.name && it.returnType == router.returnType &&
                        it.parameterTypes.map(Any::toString) == router.parameterTypes.map(Any::toString)
                }
                val first = (if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1) +
                    method.parameterTypes.take(2).sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
                method.addInstructions(
                    0,
                    """
                        invoke-static/range {p$first .. p$first}, Ltv/retwitch/mod/UiHider;->linkDisclaimer(Z)Z
                        move-result p$first
                    """.trimIndent(),
                )
            }
            println("[ReTwitch] no link disclaimer: ${routers.map { "${it.definingClass}->${it.name}" }}")
        } catch (e: Exception) {
            println("[ReTwitch] no link disclaimer skipped: $e")
        }
    }
}
