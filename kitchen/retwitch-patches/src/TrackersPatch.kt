package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel

/** (class, method, smali that returns the "do nothing" value from v0). */
private val TRACKER_ENTRIES = listOf(
    Triple("Lcom/comscore/Analytics;", "start", "return-void"),
    Triple("Lcom/google/firebase/crashlytics/FirebaseCrashlytics;", "sendUnsentReports", "return-void"),
    // A provider that "loaded" fine, just without Sentry.
    Triple("Lio/sentry/android/core/SentryInitProvider;", "onCreate", "const/4 v0, 0x1\nreturn v0"),
    // Open Measurement (ad verification) in the React Native player.
    Triple("Lcom/twitchrn/player/TwitchOMSDKModule;", "activateIfNeeded", "const/4 v0, 0x0\nreturn v0"),
    Triple("Lcom/twitchrn/player/TwitchOMSDKModule;", "fetchServiceScript", "const/4 v0, 0x0\nreturn-object v0"),
    Triple("Lcom/twitchrn/player/TwitchOMSDKModule;", "prefetchServiceScript", "return-void"),
)

/**
 * Optional tracker blocking (Thream's "Block third-party trackers", reduced to
 * SDKs whose names R8 keeps): the start-up entry points of Comscore, Sentry,
 * Crashlytics report upload and Open Measurement return early when
 * `AdSettings.isTrackersBlocked()`. Entries missing in a build are skipped.
 */
val retwitchTrackersPatch = bytecodePatch(
    name = "ReTwitch block trackers",
    description = "Optionally stops Comscore, Sentry, Crashlytics uploads and Open Measurement. Toggle in the ad-blocking menu (restart).",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        for ((type, name, blocked) in TRACKER_ENTRIES) {
            val method = mutableClassDefByOrNull { it.type == type }?.methods?.firstOrNull { it.name == name }
            val impl = method?.implementation
            if (method == null || impl == null) {
                println("[ReTwitch] tracker entry $type->$name not found, skipped")
                continue
            }
            val ins = 1 + method.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
            if (impl.registerCount <= ins) {
                println("[ReTwitch] tracker entry $type->$name has no free register, skipped")
                continue
            }
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, Ltv/retwitch/mod/AdSettings;->isTrackersBlocked()Z
                    move-result v0
                    if-eqz v0, :rt_tracker_original
                """.trimIndent() + "\n" + blocked,
                ExternalLabel("rt_tracker_original", method.getInstruction(0)),
            )
        }
    }
}
