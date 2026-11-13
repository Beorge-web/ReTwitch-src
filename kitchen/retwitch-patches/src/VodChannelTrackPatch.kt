package tv.retwitch.patches

import app.morphe.patcher.fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/**
 * Matches the chomments (VOD replay chat) fetcher's "prepare for VOD"
 * method -- `Lxm6;->l(VodModel, ChannelModel): void` in one decode -- the
 * spot where the VOD's broadcaster [ChannelModel] first becomes known and
 * is stashed for the whole replay session.
 *
 * The live chat path feeds [tv.retwitch.mod.ChannelTracker] from the chat
 * connection controller's setBroadcaster hook (see `ChatConnectPatch.kt`),
 * but VOD chomments never go through that controller, so on a VOD the tracker
 * would keep whatever channel id the last *live* stream left behind -- which
 * is why only global (and stale-channel) third-party emotes rendered on VODs,
 * never the VOD channel's own set. This is the VOD-side equivalent of that
 * live channel signal.
 *
 * Identified by its two model parameter types plus the literal string
 * constants baked into it: "vod"/"channelModel" (its two
 * `checkNotNullParameter` argument names), "vodId" (the null-check name on
 * the id it pulls off the VodModel) and "content_type" (the chat-connect
 * request key it sets to the CHAT_REPLAY content type). A full-APK scan
 * shows that exact combination occurs in this one method only.
 */
val chommentsPrepareForVodFingerprint = fingerprint {
    returns("V")
    parameters(
        "Ltv/twitch/android/models/videos/VodModel;",
        "Ltv/twitch/android/models/channel/ChannelModel;",
    )
    // Spade event name, survives R8 (the "vod"/"channelModel" parameter-name
    // strings are stripped in 31.x).
    strings("chat_connectedfor_vod")
}

val retwitchVodChannelTrackPatch = bytecodePatch(
    name = "ReTwitch VOD third-party emote channel tracking",
    description = "Feeds the VOD replay's broadcaster id to ChannelTracker so 7TV/BTTV/FFZ channel emotes load on VODs, not just global ones.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        // Instance method `(vod: VodModel, channelModel: ChannelModel)`: p2 is
        // the ChannelModel, p1 the VodModel, both valid at method entry. Passed
        // as plain Objects so no scratch register is needed; ChannelTracker
        // reads getTuid()/getId() and the VOD id (for the chapters list).
        chommentsPrepareForVodFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p2}, Ltv/retwitch/mod/ChannelTracker;->noteChannel(Ljava/lang/Object;)V
                invoke-static {p1}, Ltv/retwitch/mod/ChannelTracker;->noteVod(Ljava/lang/Object;)V
            """.trimIndent(),
        )
    }
}
