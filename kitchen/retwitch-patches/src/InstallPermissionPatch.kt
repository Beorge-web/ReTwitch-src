package tv.retwitch.patches

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

/**
 * Adds `android.permission.REQUEST_INSTALL_PACKAGES` so the mod can open the system
 * installer on an update it downloaded ({@code ModUpdate}). Android still asks the user
 * to allow it, once, and confirms every install. `finalize {}`: after the package rename.
 */
val retwitchInstallPermissionPatch = resourcePatch(
    name = "ReTwitch install permission",
    description = "Adds android.permission.REQUEST_INSTALL_PACKAGES for the mod's own updates.",
) {
    compatibleWith(twitchCompatibility)

    finalize {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.getElementsByTagName("manifest").item(0) as Element
            val permission = document.createElement("uses-permission")
            permission.setAttribute("android:name", "android.permission.REQUEST_INSTALL_PACKAGES")
            manifest.appendChild(permission)
        }
    }
}
