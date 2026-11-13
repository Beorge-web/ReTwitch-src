package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/**
 * Adds a draggable player/chat divider to Twitch's landscape side-by-side
 * ("chat next to player") theatre mode.
 *
 * Like [retwitchDisplayCutoutPatch], this needs no fragile fingerprint into
 * Twitch's obfuscated theatre code: it injects a one-liner into the app's own
 * [APP_CLASS].onCreate that installs an [Application.ActivityLifecycleCallbacks]
 * (see [tv.retwitch.mod.TheatreDividerHook]). That hook watches the view tree
 * and, when the side-by-side chat column is on screen and the ReTwitch option
 * is on, drops a draggable handle on the player/chat boundary that live-resizes
 * the split (persisted via [tv.retwitch.mod.TheatreSettings]).
 *
 * The Application class ([APP_CLASS]) is declared in AndroidManifest.xml, so
 * its name is one of the few Twitch identifiers R8 must keep stable across
 * releases; the single()/error() below fails the build loudly instead of
 * silently no-op'ing at runtime if it ever moves.
 *
 * Injecting a second install call at onCreate index 0 (the cutout patch does
 * the same) is safe: each patch prepends its own `invoke-static/range`, they
 * are independent, and order between them does not matter.
 */
private const val APP_CLASS = "Ltv/twitch/android/app/consumer/TwitchApplication;"
private const val HOOK = "Ltv/retwitch/mod/TheatreDividerHook;"

val retwitchTheatreDividerPatch = bytecodePatch(
    name = "ReTwitch player/chat divider",
    description = "Adds a draggable handle between the player and chat in landscape side-by-side mode to resize the split. Toggle in the ReTwitch settings menu.",
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
