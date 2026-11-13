package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/**
 * DIAGNOSTIC-ONLY patch: overlays a bar of real 7TV/BTTV/FFZ global emotes
 * (plus a zero-width overlay pair) on the login screen so the third-party emote
 * decode/draw path can be exercised without logging in.
 *
 * Twitch blocks username/password login on non-standard clients ("This app
 * version/OS is not currently supported."), so on an emulator we can't reach a
 * real chat. This injects [tv.retwitch.mod.EmoteRenderProbe.attach] at the top
 * of `LoginActivity.onCreate(Bundle)` -- see that class for how it drives the
 * same production pipeline (`EmoteImageCache` decode + `AnimatedEmoteDrawable`
 * draw via `EmoteChatHook.onTextBound`) that live chat uses.
 *
 * `LoginActivity` is targeted by its exact, non-obfuscated type descriptor:
 * activity class names survive R8 because they're referenced from
 * AndroidManifest.xml (confirmed on-device: the foreground activity while the
 * login WebView is up is literally `tv.twitch.android.login.LoginActivity`).
 *
 * DISABLED by default ([default] = `false`): this and [EmoteRenderProbe] are
 * kept in-tree for the upcoming Android 8/9 decode/draw testing. To run the
 * probe again, flip [default] to `true`, rebuild, and reinstall; it must never
 * ship enabled in a release build (it draws a debug bar on the login screen).
 */
private const val PROBE = "Ltv/retwitch/mod/EmoteRenderProbe;"
private const val LOGIN_ACTIVITY = "Ltv/twitch/android/login/LoginActivity;"

val retwitchEmoteRenderProbePatch = bytecodePatch(
    name = "ReTwitch emote render probe (login screen)",
    description = "DIAGNOSTIC: draws 7TV/BTTV/FFZ global emotes (+ zero-width overlay) on the " +
        "login screen to reproduce the emote decode/draw crash without logging in.",
    // Disabled: kept for the upcoming Android 8/9 testing. Flip to true to run
    // the probe again; never ship it enabled (it draws a debug bar on login).
    default = false,
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        val loginActivity = mutableClassDefByOrNull { it.type == LOGIN_ACTIVITY }
            ?: error("login activity $LOGIN_ACTIVITY not found (did Twitch rename/move it?)")

        val onCreate = loginActivity.methods.single { method ->
            method.name == "onCreate" &&
                method.returnType == "V" &&
                method.parameterTypes.map { it.toString() } == listOf("Landroid/os/Bundle;")
        }

        // p0 = this (LoginActivity, an Activity). Deferred UI work happens on the
        // main looper inside attach(), so running before super.onCreate is fine.
        onCreate.addInstructions(
            0,
            "invoke-static {p0}, $PROBE->attach(Landroid/app/Activity;)V",
        )
    }
}
