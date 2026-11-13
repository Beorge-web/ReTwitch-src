package tv.retwitch.patches

import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

/**
 * The stock Twitch application id. Every component, provider authority and
 * custom permission in the decoded manifest is written as a fully-qualified
 * name rooted at this string, so renaming the app means rewriting each of
 * those occurrences too -- not just the top-level `package` attribute.
 */
private const val OLD_PACKAGE_NAME = "tv.twitch.android.app"

/**
 * What every ReTwitch patch is `compatibleWith`: Twitch, any version.
 * morphe-patcher 2.x removed `compatibleWith(String)`; note that the one-arg
 * `Compatibility(String)` constructor is a *universal* patch description, not
 * a package name.
 */
val twitchCompatibility = Compatibility(packageName = OLD_PACKAGE_NAME, name = "Twitch")

/**
 * ReTwitch's own application id. Picking a distinct package lets ReTwitch
 * install side-by-side with the official Twitch app instead of clobbering it
 * on `adb install -r`.
 *
 * NOTE: this does *not* touch the Firebase App ID / GCM sender id baked into
 * the resources, so FCM push notifications keep working -- the app still
 * identifies to Google as the same Firebase app. See the notification
 * discussion in the ReTwitch build notes.
 */
private const val NEW_PACKAGE_NAME = "ru.reyohoho.ReTwitch"

/**
 * The visible launcher/app-info label ReTwitch should show instead of the
 * stock "Twitch". Everything in the manifest (the `<application>`, the
 * launcher activity, etc.) points its `android:label` at `@string/app_name`,
 * so rewriting that one string resource renames the app consistently
 * everywhere, in every locale.
 */
private const val NEW_APP_NAME = "ReTwitch"

/**
 * Renames the patched APK's package (application id) to [NEW_PACKAGE_NAME].
 *
 * Modelled on Morphe/ReVanced's "Change package name" patch and PurpleTV's
 * `CloneApk` step, but hardcoded to ReTwitch's package and always enabled so
 * it runs unattended in `retwitch-patches/build.sh` with no CLI options.
 *
 * It rewrites, in AndroidManifest.xml:
 *   - the top-level `<manifest package="...">` attribute;
 *   - the `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` custom permission
 *     (declared + used) so its name stays unique per install;
 *   - every `<provider android:authorities="...">` that embeds the old
 *     package (firebaseinitprovider, androidx-startup, the app FileProvider,
 *     and the Amazon `MapInfoProvider.<pkg>` authority) so two installed
 *     copies don't fight over the same ContentProvider authority.
 *
 * Push/media action strings (`tv.twitch.android.push.*`,
 * `tv.twitch.android.media.action.*`) are intentionally left untouched: they
 * are only delivered via explicit intents / PendingIntents within the app, so
 * they don't need per-install uniqueness, and leaving them avoids having to
 * re-fix the R8-precomputed String.hashCode() switch constants that reference
 * the media actions.
 */
val retwitchPackageNamePatch = resourcePatch(
    name = "ReTwitch package name",
    description = "Renames the app package to $NEW_PACKAGE_NAME so ReTwitch " +
        "installs alongside the official Twitch app.",
) {
    compatibleWith(twitchCompatibility)

    finalize {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.getElementsByTagName("manifest").item(0) as Element
            val currentPackageName = manifest.getAttribute("package")

            manifest.setAttribute("package", NEW_PACKAGE_NAME)

            val receiverPermission = "$currentPackageName.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
            val newReceiverPermission = "$NEW_PACKAGE_NAME.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
            for (tag in arrayOf("permission", "uses-permission")) {
                val nodes = manifest.getElementsByTagName(tag)
                for (i in 0 until nodes.length) {
                    val element = nodes.item(i) as Element
                    if (element.getAttribute("android:name") == receiverPermission) {
                        element.setAttribute("android:name", newReceiverPermission)
                    }
                }
            }

            val providers = manifest.getElementsByTagName("provider")
            for (i in 0 until providers.length) {
                val provider = providers.item(i) as Element
                val authorities = provider.getAttribute("android:authorities")
                if (authorities.contains(currentPackageName)) {
                    provider.setAttribute(
                        "android:authorities",
                        authorities.replace(currentPackageName, NEW_PACKAGE_NAME),
                    )
                }
            }

            // The Amazon "Login with Amazon" deep link routes back to the app
            // via `amzn://<package>`, so its intent-filter host is the package
            // name too. Left stale it would send the OAuth redirect to the
            // official Twitch app if that's installed.
            val dataNodes = manifest.getElementsByTagName("data")
            for (i in 0 until dataNodes.length) {
                val data = dataNodes.item(i) as Element
                if (data.getAttribute("android:host") == currentPackageName) {
                    data.setAttribute("android:host", NEW_PACKAGE_NAME)
                }
            }
        }
    }
}

/**
 * Renames the visible app label from "Twitch" to [NEW_APP_NAME] by rewriting
 * the `app_name` string resource in every values `strings.xml` under `res`
 * (the default plus any localized override).
 *
 * This is preferred over hardcoding a literal `android:label` on the manifest
 * elements: `<application>`, the launcher activity and the settings shortcuts
 * all already reference `@string/app_name`, so a single string change renames
 * them all at once and keeps working across Twitch releases.
 */
val retwitchAppNamePatch = resourcePatch(
    name = "ReTwitch app name",
    description = "Renames the visible app label from \"Twitch\" to $NEW_APP_NAME.",
) {
    compatibleWith(twitchCompatibility)

    execute {
        var renamedAny = false
        // decode(), not get("res").listFiles: with on-request decoding (patcher 2.x)
        // a directory only holds what has been decoded so far.
        for (path in decode("res/values*/strings.xml")) {
            document(path).use { document ->
                val strings = document.getElementsByTagName("string")
                for (i in 0 until strings.length) {
                    val string = strings.item(i) as Element
                    if (string.getAttribute("name") == "app_name") {
                        string.textContent = NEW_APP_NAME
                        renamedAny = true
                    }
                }
            }
        }

        check(renamedAny) { "Could not find an `app_name` string resource to rename" }
    }
}
