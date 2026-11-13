package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch

private val rnScriptAssetPatch = resourcePatch {
    execute {
        val script = object {}.javaClass.classLoader.getResourceAsStream("retwitch-rn.js")!!.use { it.readBytes() }
        get("assets/retwitch-rn.js", false).apply { parentFile.mkdirs() }.writeBytes(script)
    }
}

/**
 * Native hooks don't reach React Native screens, so what changes them lives in our own script, which
 * `RnScript` evaluates right before Twitch's bundle (`ReactInstance.loadJSBundle`, not obfuscated).
 */
val retwitchRnScriptPatch = bytecodePatch(
    name = "ReTwitch RN script",
    description = "Runs the mod's script in React Native: compact Live tab, Live tab shelves.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch, rnScriptAssetPatch)

    execute {
        try {
            mutableClassDefBy("Lcom/facebook/react/runtime/ReactInstance;").methods
                .single { it.name == "loadJSBundle" && it.parameterTypes.map(Any::toString) == listOf("Lcom/facebook/react/bridge/JSBundleLoader;") }
                .addInstructions(0, "invoke-static {p0}, Ltv/retwitch/mod/RnScript;->load(Ljava/lang/Object;)V")
        } catch (e: Exception) {
            println("[ReTwitch] RN script skipped: $e")
        }
    }
}
