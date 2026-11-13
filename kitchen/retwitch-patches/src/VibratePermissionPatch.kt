package tv.retwitch.patches

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

/**
 * Adds `android.permission.VIBRATE` to the manifest so the mention-alert
 * vibration ({@code MentionHighlighter}) actually buzzes -- the stock Twitch
 * APK doesn't request it.
 *
 * Runs in `finalize {}` so it lands after any other manifest edits (e.g. the
 * package rename), and is a no-op if the permission is somehow already there.
 */
val retwitchVibratePermissionPatch = resourcePatch(
    name = "ReTwitch vibrate permission",
    description = "Adds android.permission.VIBRATE so @mention alerts can buzz the device.",
) {
    compatibleWith(twitchCompatibility)

    finalize {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.getElementsByTagName("manifest").item(0) as Element

            val existing = manifest.getElementsByTagName("uses-permission")
            for (i in 0 until existing.length) {
                val element = existing.item(i) as Element
                if (element.getAttribute("android:name") == "android.permission.VIBRATE") {
                    return@use
                }
            }

            val permission = document.createElement("uses-permission")
            permission.setAttribute("android:name", "android.permission.VIBRATE")
            manifest.appendChild(permission)
        }
    }
}
