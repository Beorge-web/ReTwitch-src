package tv.retwitch.patches

import app.morphe.patcher.fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/**
 * Matches Twitch's own emote-id-to-CDN-URL resolver,
 * `(Context, String emoticonId, Float): String`. Identified by its two
 * `checkNotNullParameter` debug names ("context", "emoticonId") -- each
 * individually far too common to trust alone -- combined with its exact
 * 3-parameter shape and return type, which together are unique in the
 * whole APK.
 *
 * This is what Twitch's native autocomplete/picker UI calls to turn
 * whatever "id" an `EmoteModel` carries into the URL its own
 * already-working image-loading stack fetches. `EmoteModelReflect`
 * encodes a synthetic id (a recognizable prefix + the real CDN URL) for
 * every third-party emote model it builds, so intercepting *here* is
 * enough to make third-party emote thumbnails render correctly in both
 * the autocomplete dropdown and the emote picker without needing our own
 * image-loading pipeline at all in either place.
 */
val emoteImageUrlFingerprint = fingerprint {
    returns("Ljava/lang/String;")
    // Signature alone is unique (30.2.0 and 31.8.0); 31.x strips the
    // "context"/"emoticonId" parameter-name strings.
    parameters("Landroid/content/Context;", "Ljava/lang/String;", "Ljava/lang/Float;")
}

val retwitchEmoteImageUrlPatch = bytecodePatch(
    name = "ReTwitch third-party emote image URLs",
    description = "Redirects our own synthetic emote ids (autocomplete/picker) to their real, optionally-proxied CDN URL.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        // Instance method `o(Context, String, Float): String` -- p0 is
        // `this`, p1 the Context, p2 the emoticonId String we care about.
        // Only 1 local (v0) declared; safe to use as scratch since
        // Twitch's own first real instruction overwrites it immediately
        // after (its own "context"/"emoticonId" null-checks).
        val method = emoteImageUrlFingerprint.method
        method.addInstructions(
            0,
            """
                invoke-static {p2}, Ltv/retwitch/mod/emotes/EmoteImageUrlBridge;->resolve(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                if-eqz v0, :retwitch_not_ours
                return-object v0
                :retwitch_not_ours
            """.trimIndent(),
        )
    }
}
