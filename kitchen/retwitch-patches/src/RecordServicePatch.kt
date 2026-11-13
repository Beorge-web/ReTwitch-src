package tv.retwitch.patches

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

/**
 * Declares the mod's foreground service ({@code tv.retwitch.mod.RecordService}) that keeps live recordings, a VOD
 * download and auto-record's wait running in the background, with
 * `android.permission.FOREGROUND_SERVICE_SPECIAL_USE` for its type. `specialUse`, not `dataSync`: on Android 15
 * with Twitch's target SDK a `dataSync` service is stopped after 6 hours a day. `finalize {}`: after the package
 * rename, like the other manifest additions. Also the recording player's activity ({@code RecordPlayerActivity}).
 */
val retwitchRecordServicePatch = resourcePatch(
    name = "ReTwitch record service",
    description = "Declares the foreground service for stream recording and VOD downloads, and the recording player.",
) {
    compatibleWith(twitchCompatibility)

    finalize {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.getElementsByTagName("manifest").item(0) as Element
            val permission = "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"
            val existing = manifest.getElementsByTagName("uses-permission")
            if ((0 until existing.length).none { (existing.item(it) as Element).getAttribute("android:name") == permission }) {
                manifest.appendChild(document.createElement("uses-permission").apply {
                    setAttribute("android:name", permission)
                })
            }
            val application = document.getElementsByTagName("application").item(0) as Element
            val service = document.createElement("service")
            service.setAttribute("android:name", "tv.retwitch.mod.RecordService")
            service.setAttribute("android:exported", "false")
            service.setAttribute("android:foregroundServiceType", "specialUse")
            service.appendChild(document.createElement("property").apply {
                setAttribute("android:name", "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE")
                setAttribute("android:value", "Records a live stream the user chose to a file")
            })
            application.appendChild(service)
            // The recording player: its own activity, so it can go picture-in-picture (RecordPlayerActivity).
            application.appendChild(document.createElement("activity").apply {
                setAttribute("android:name", "tv.retwitch.mod.RecordPlayerActivity")
                setAttribute("android:exported", "false")
                setAttribute("android:theme", "@android:style/Theme.DeviceDefault.NoActionBar")
                setAttribute("android:supportsPictureInPicture", "true")
                setAttribute("android:resizeableActivity", "true")
                setAttribute("android:configChanges",
                    "keyboard|keyboardHidden|navigation|orientation|screenLayout|screenSize|smallestScreenSize")
            })
        }
    }
}
