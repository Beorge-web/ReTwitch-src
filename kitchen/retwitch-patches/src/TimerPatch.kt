package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/**
 * Adds a sleep/exit-timer button to Twitch's player controls overlay: a small
 * clock next to the settings gear that opens a dialog to pick an hours/minutes
 * countdown, after which the whole app is closed (ReTwitch's port of PurpleTV's
 * timer feature).
 *
 * Like [retwitchDisplayCutoutPatch] / [retwitchTheatreDividerPatch], this needs
 * no fragile fingerprint into Twitch's obfuscated player code: it injects a
 * one-liner into the app's own [APP_CLASS].onCreate that installs an
 * [Application.ActivityLifecycleCallbacks] (see [tv.retwitch.mod.TimerHook]).
 * That hook watches the view tree and drops the button into the overlay when the
 * feature is on; the countdown itself lives in [tv.retwitch.mod.TimerController].
 *
 * The Application class ([APP_CLASS]) is declared in AndroidManifest.xml, so its
 * name is one of the few Twitch identifiers R8 must keep stable across releases;
 * the single()/error() below fails the build loudly instead of silently
 * no-op'ing at runtime if it ever moves. Injecting a third install call at
 * onCreate index 0 (the cutout and divider patches do the same) is safe: each
 * patch prepends its own `invoke-static/range`, they are independent, and order
 * between them does not matter.
 */
private const val APP_CLASS = "Ltv/twitch/android/app/consumer/TwitchApplication;"
private const val HOOK = "Ltv/retwitch/mod/TimerHook;"

val retwitchTimerPatch = bytecodePatch(
    name = "ReTwitch sleep/exit timer",
    description = "Adds a clock button to the player overlay that sets a countdown after which the app closes itself. Toggle in the ReTwitch settings menu.",
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
