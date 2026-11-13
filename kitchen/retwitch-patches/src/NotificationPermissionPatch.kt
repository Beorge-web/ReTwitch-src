package tv.retwitch.patches

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

/**
 * Adds `android.permission.POST_NOTIFICATIONS` to the manifest so the
 * sleep/exit timer ({@code TimerController}) can show its ongoing countdown
 * notification (with Cancel / +10m / +1h actions) on Android 13+.
 *
 * The timer works without it (the notification is best-effort and silently
 * dropped when the runtime grant is missing), but declaring the permission is
 * what lets the app request it, so the countdown is visible/controllable from
 * the shade like PurpleTV's foreground-service notification.
 *
 * Runs in `finalize {}` (after the package rename and other manifest edits) and
 * is a no-op if the permission is somehow already present -- same shape as
 * [retwitchVibratePermissionPatch].
 */
val retwitchNotificationPermissionPatch = resourcePatch(
    name = "ReTwitch notification permission",
    description = "Adds android.permission.POST_NOTIFICATIONS so the sleep/exit timer can show its countdown notification.",
) {
    compatibleWith(twitchCompatibility)

    finalize {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.getElementsByTagName("manifest").item(0) as Element

            val existing = manifest.getElementsByTagName("uses-permission")
            for (i in 0 until existing.length) {
                val element = existing.item(i) as Element
                if (element.getAttribute("android:name") == "android.permission.POST_NOTIFICATIONS") {
                    return@use
                }
            }

            val permission = document.createElement("uses-permission")
            permission.setAttribute("android:name", "android.permission.POST_NOTIFICATIONS")
            manifest.appendChild(permission)
        }
    }
}
