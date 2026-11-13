package tv.retwitch.patches

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch

/**
 * UI translations beyond Russian and English (kitchen/i18n.py pack), read by Strings.translate, and the list of
 * all UI strings the in-app translation files are built from (TranslationFiles).
 */
private val i18nAssetPatch = resourcePatch {
    execute {
        for (name in listOf("retwitch-i18n.json", "retwitch-i18n-catalog.json")) {
            val json = object {}.javaClass.classLoader.getResourceAsStream(name)!!.use { it.readBytes() }
            get("assets/$name", false).apply { parentFile.mkdirs() }.writeBytes(json)
        }
    }
}

/**
 * Merges retwitch-extension.dex into the app once. Every patch that needs the
 * extension depends on this instead of calling extendWith itself: morphe
 * re-reads and re-merges the whole extension (~3300 classes, ~3 s) for each
 * patch that declares it, which was most of the patching time.
 */
val retwitchExtensionPatch = bytecodePatch {
    dependsOn(i18nAssetPatch)
    extendWith("retwitch-extension.dex")
}
