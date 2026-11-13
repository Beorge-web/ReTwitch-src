package tv.retwitch.patches

import app.morphe.patcher.patch.resourcePatch

/**
 * The app's versionCode never goes below [MIN_VERSION_CODE]. A 32.0.0 test build (3200006) went onto the phone and the
 * mod then went back to 31.9.0 (3109006): Android refuses a lower versionCode over an installed non-debuggable app.
 * Equal codes install over each other, so builds on either base keep installing. Twitch tells its servers the version
 * from its own constants ("31.9.0"); only libraries (GMS, Branch, Play Core) read the code from the system.
 * shortcut: a fixed floor; a base newer than it keeps its own code, raise the floor if a base ever goes back again.
 */
private const val MIN_VERSION_CODE = 3200007

val retwitchVersionCodePatch = resourcePatch(
    name = "ReTwitch version code",
    description = "Keeps the versionCode at or above the last test base, so older Twitch bases install over it.",
) {
    compatibleWith(twitchCompatibility)

    execute {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.documentElement
            val code = manifest.getAttribute("android:versionCode").toIntOrNull()
                ?: throw IllegalStateException("no android:versionCode in the manifest")
            if (code < MIN_VERSION_CODE) {
                manifest.setAttribute("android:versionCode", MIN_VERSION_CODE.toString())
            }
            println("[ReTwitch] version code ${maxOf(code, MIN_VERSION_CODE)} (Twitch $code)")
        }
    }
}
