package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/**
 * Makes the fullscreen player able to draw under a camera cutout (notch).
 *
 * Twitch's window already uses LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS; the
 * black bar in landscape comes from Twitch's own layout consuming the
 * display-cutout WindowInsets and shifting the player/chat container inward.
 * This patch injects a one-liner into the app's own Application.onCreate that
 * installs an [Application.ActivityLifecycleCallbacks] (see
 * [tv.retwitch.mod.DisplayCutoutHook]) which, when the ReTwitch "draw under
 * cutout" option is on, strips the display-cutout inset before it reaches
 * Twitch's views (and only raises a DEFAULT-mode window to ALWAYS as a safety
 * net -- see the hook's javadoc for the full mechanism).
 *
 * The Application class ([APP_CLASS]) is declared in AndroidManifest.xml, so
 * its name is one of the few Twitch identifiers R8 must keep stable across
 * releases -- same reasoning that lets [VodSeekProbePatch] reference the
 * non-obfuscated player-core class by literal descriptor. If Twitch ever
 * renames/repackages its Application, the single()/error() below fails the
 * build loudly instead of silently no-op'ing at runtime.
 */
private const val APP_CLASS = "Ltv/twitch/android/app/consumer/TwitchApplication;"
private const val HOOK = "Ltv/retwitch/mod/DisplayCutoutHook;"

val retwitchDisplayCutoutPatch = bytecodePatch(
    name = "ReTwitch draw under cutout",
    description = "Lets the fullscreen player extend under the camera cutout (notch). Toggle in the ReTwitch settings menu.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        val application = mutableClassDefByOrNull { it.type == APP_CLASS }
            ?: error("Application class $APP_CLASS not found")

        val onCreate = application.methods.single {
            it.name == "onCreate" &&
                it.returnType == "V" &&
                it.parameterTypes.isEmpty()
        }

        // p0 is `this` (the TwitchApplication, an android.app.Application).
        // onCreate has many locals, so `this` sits above v15 -> the /range
        // form is required (non-range invokes only encode v0-v15).
        onCreate.addInstructions(
            0,
            "invoke-static/range {p0 .. p0}, $HOOK->install(Landroid/app/Application;)V",
        )
    }
}
