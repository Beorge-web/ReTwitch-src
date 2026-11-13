package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel

/** `AnnouncementNotice.toString()`: its Kotlin header survives R8. */
val announcementNoticeToStringFingerprint = fingerprint {
    returns("Ljava/lang/String;")
    strings("AnnouncementNotice(sender=")
}

/**
 * Chat filters (announcements, sub notices, channel-points redemptions, watch
 * streaks): every chat list event goes through one dispatcher,
 * `(ChatEvent): void` on the abstract event base class (31.8.0 `tb6.a(y56)`,
 * Thream's `xa6.a(e56)` in 31.4.2). Its entry now asks
 * `ChatFilters.filterEvent` and returns early on null.
 */
val retwitchChatEventFilterPatch = bytecodePatch(
    name = "ReTwitch chat filters",
    description = "Optionally hides announcements, sub notices, channel points redemptions and watch streaks in chat.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        // Topmost non-Object superclass of AnnouncementNotice = the event base.
        var baseType = announcementNoticeToStringFingerprint.classDef.type
        while (true) {
            val superType = classDefByOrNull { it.type == baseType }?.superclass ?: break
            if (superType == "Ljava/lang/Object;") break
            baseType = superType
        }
        val dispatcherClass = mutableClassDefByOrNull { classDef ->
            classDef.methods.any { isDispatcher(it, baseType) }
        } ?: throw PatchException("chat event dispatcher ($baseType): void not found")
        val method = dispatcherClass.methods.first { isDispatcher(it, baseType) }

        // Event param register: p1 (instance method, single object param).
        method.addInstructionsWithLabels(
            0,
            """
                invoke-static/range {p1 .. p1}, Ltv/retwitch/mod/ChatFilters;->filterEvent(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object p1
                check-cast p1, $baseType
                if-nez p1, :rt_keep_event
                return-void
            """.trimIndent(),
            ExternalLabel("rt_keep_event", method.getInstruction(0)),
        )
    }
}

private fun isDispatcher(method: com.android.tools.smali.dexlib2.iface.Method, baseType: String) =
    method.name != "<init>" && method.returnType == "V" &&
        method.parameterTypes.size == 1 && method.parameterTypes[0].toString() == baseType &&
        com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(method.accessFlags).not()
