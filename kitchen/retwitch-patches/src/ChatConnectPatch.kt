package tv.retwitch.patches

import app.morphe.patcher.fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/**
 * Matches the chat connection controller's "set broadcaster" method --
 * `Llb5;->M2(ChannelInfo broadcaster, StreamType): void` in one decode --
 * the single spot where the *foreground* chat (re)binds to a broadcaster.
 * Twitch itself calls it exactly once per channel open/switch and even uses
 * it to diff the old vs new broadcaster id (it reads `broadcaster.getId()`
 * and compares against the previously bound one).
 *
 * This is the "the user is now on channel X" signal for live. The live emote
 * path used to lean entirely on the per-message-batch IRC converter in
 * `EmoteChatPatch.kt`, but that fires once per *message batch*, so a busy
 * previously-watched channel whose chat is still connected in the background
 * keeps re-asserting its own id many times a second and drowns out the
 * channel the user actually switched to -- leaving third-party emotes stuck
 * on the old channel's set. That per-batch tracking was dropped in favour of
 * feeding [tv.retwitch.mod.ChannelTracker] from here, which fires exactly
 * once when the foreground channel is (re)opened.
 *
 * Identified by its two model parameter types (ChannelInfo, StreamType) plus
 * the literal string "broadcaster" -- the Kotlin `checkNotNullParameter`
 * argument name on the broadcaster parameter. A full-APK scan shows that
 * exact combination occurs in this one method only (the sibling
 * `(ChannelInfo, StreamType)` constructor on the broadcaster data holder
 * uses the argument name "channelInfo" instead).
 */
private const val CHAT_CONNECTION_KEY =
    "Ltv/twitch/android/shared/chat/pub/messages/data/ChannelChatConnectionKey;"

val chatSetBroadcasterFingerprint = fingerprint {
    returns("V")
    parameters(
        "Ltv/twitch/android/models/channel/ChannelInfo;",
        "Ltv/twitch/android/models/streams/StreamType;",
    )
    // The only non-constructor with this signature (30.2.0 and 31.8.0); the
    // "broadcaster" parameter-name string is stripped in 31.x.
    custom { method, _ -> method.name != "<init>" }
}

/**
 * The player mode setter (31.9.0 `h3u.e(d3u)`), one per theatre: every expand and
 * minimize of the classic and vertical theatres ends up here.
 */
val playerModeSetFingerprint = fingerprint {
    returns("V")
    strings("video_cc", "video_mode_pref")
    custom { method, _ -> method.parameterTypes.size == 1 }
}

val retwitchChatConnectPatch = bytecodePatch(
    name = "ReTwitch live third-party emote channel tracking",
    description = "Feeds the foreground chat's broadcaster id to ChannelTracker on channel open/switch so 7TV/BTTV/FFZ channel emotes follow the channel the user actually switched to.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        // Instance method `(broadcaster: ChannelInfo, streamType: StreamType)`:
        // p1 is the new broadcaster, valid at method entry. Handed to
        // ChannelTracker as a plain Object -- no scratch register needed (the
        // method may have none to spare), and the id accessor changed in 31.x
        // (getId(): int -> getTuid(): Tuid), which noteChannel resolves.
        chatSetBroadcasterFingerprint.method.addInstructions(
            0,
            "invoke-static {p1}, Ltv/retwitch/mod/ChannelTracker;->noteChannel(Ljava/lang/Object;)V",
        )

        // Every chat connection builds a ChannelChatConnectionKey(channelId: Tuid,
        // name) (public model class, not renamed) -- including the discovery
        // feed's inline chat, which never calls the theatre's setBroadcaster.
        // Thream tracks the channel here too. p1 is valid at constructor entry
        // and passing it out before super() is allowed (`this` isn't used).
        mutableClassDefByOrNull { it.type == CHAT_CONNECTION_KEY }
            ?.methods?.firstOrNull { m ->
                m.name == "<init>" && m.parameterTypes.firstOrNull()?.toString() == "Ltv/twitch/android/models/Tuid;"
            }
            ?.addInstructions(
                0,
                "invoke-static {p1}, Ltv/retwitch/mod/ChannelTracker;->noteChannelTuid(Ljava/lang/Object;)V",
            ) ?: println("[ReTwitch] ChannelChatConnectionKey not found; feed chat channel tracking skipped")

        // Expanding the mini-player shows a chat that is already connected, so neither
        // signal above fires. The old mode is read from this theatre's holder: a static
        // flag would go wrong when a new channel opens over the mini-player.
        try {
            val setter = playerModeSetFingerprint.method
            val modeType = setter.parameterTypes.single().toString()
            val getter = playerModeSetFingerprint.classDef.methods.single { m ->
                m.parameterTypes.isEmpty() && m.returnType == modeType
            }
            setter.addInstructions(
                0,
                """
                    invoke-virtual {p0}, ${getter.definingClass}->${getter.name}()$modeType
                    move-result-object v0
                    invoke-static {v0, p1}, Ltv/retwitch/mod/ChannelTracker;->onPlayerMode(Ljava/lang/Enum;Ljava/lang/Enum;)V
                """.trimIndent(),
            )
        } catch (e: Exception) {
            println("[ReTwitch] player mode setter not found; mini-player channel tracking skipped: $e")
        }
    }
}
