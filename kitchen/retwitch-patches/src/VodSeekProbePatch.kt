package tv.retwitch.patches

import app.morphe.patcher.fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * DIAGNOSTIC-ONLY patch for the VOD "progress resets after a while" bug.
 *
 * Logs (with a full caller stack trace, see [VodSeekProbe]) every place that
 * seeks the VOD playhead or re-installs the HLS playlist, at both layers:
 *
 *   Presenter (Twitch's obfuscated VOD player presenter, `hs40`), reached via
 *   its one globally-unique string "Should not be called from VodPlayerPresenter":
 *     - `seekTo(int)`            -> programmatic resume/reload seek
 *     - `i(int, SeekTrigger)`    -> the seek executor every seek funnels through
 *                                   (identified: (int, <enum>)->void carrying the
 *                                   "seekTrigger" checkNotNull string + an
 *                                   invoke-super; disambiguates it from the
 *                                   sibling relative-seek `N(int, SeekTrigger)`
 *                                   which has no super call)
 *     - `h3(Integer)`            -> manifest reload ((Integer)->void carrying the
 *                                   "video_playlist_request" string)
 *
 *   Engine (ExoPlayer2 wrapper `tv/twitch/android/shared/player/core/b`, whose
 *   class name is NOT obfuscated), reached by its literal type descriptor:
 *     - `seekTo(int)`            -> the ultimate funnel for ALL seeks
 *     - the setMediaSource method (the one carrying the "mediaSource" string)
 *                                -> every playlist/media-source (re)install
 *
 * Nothing here changes behavior; it only emits logcat under tag "RTVodProbe".
 */
private const val PROBE = "Ltv/retwitch/mod/VodSeekProbe;"
private const val PLAYER_CORE = "Ltv/twitch/android/shared/player/core/b;"

val vodPresenterProbeFingerprint = fingerprint {
    strings("Should not be called from VodPlayerPresenter")
}

val retwitchVodSeekProbePatch = bytecodePatch(
    name = "ReTwitch VOD seek/reload diagnostics",
    description = "Logs every VOD seek and HLS playlist reload with a caller stack trace (logcat tag RTVodProbe). Diagnostic only.",
    // Diagnostic only: emits a stack-trace log line on every seek/reload (a
    // hot player path). Off by default so it never ships in a release build;
    // enable explicitly (morphe-cli -i / options) when reproducing a bug.
    // (`use` was renamed to `default` in morphe-patcher.)
    default = false,
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        // ---- Presenter (hs40) ----
        val presenter = vodPresenterProbeFingerprint.classDef

        val seekTo = presenter.methods.single {
            it.name == "seekTo" &&
                it.returnType == "V" &&
                it.parameterTypes.map { p -> p.toString() } == listOf("I")
        }
        seekTo.addInstructions(
            0,
            "invoke-static {p1}, $PROBE->seekTo(I)V",
        )

        // Seek executor: (int, <SeekTrigger enum>)->void, has "seekTrigger" and
        // an invoke-super (the sibling relative-seek N(...) has neither super).
        val seekExec = presenter.methods.single { m ->
            m.returnType == "V" &&
                m.parameterTypes.size == 2 &&
                m.parameterTypes[0].toString() == "I" &&
                m.instructions.any { insn ->
                    insn.opcode == Opcode.CONST_STRING &&
                        ((insn as ReferenceInstruction).reference as? StringReference)?.string == "seekTrigger"
                } &&
                m.instructions.any { it.opcode == Opcode.INVOKE_SUPER }
        }
        seekExec.addInstructions(
            0,
            "invoke-static {p1, p2}, $PROBE->seekExec(ILjava/lang/Object;)V",
        )

        // Manifest reload: (Integer)->void carrying "video_playlist_request".
        // .locals is high (params past v15) so the /range form is required.
        val reload = presenter.methods.single { m ->
            m.returnType == "V" &&
                m.parameterTypes.map { p -> p.toString() } == listOf("Ljava/lang/Integer;") &&
                m.instructions.any { insn ->
                    insn.opcode == Opcode.CONST_STRING &&
                        ((insn as ReferenceInstruction).reference as? StringReference)?.string == "video_playlist_request"
                }
        }
        reload.addInstructions(
            0,
            "invoke-static/range {p1 .. p1}, $PROBE->reload(Ljava/lang/Integer;)V",
        )

        // ---- Engine (ExoPlayer2 wrapper, non-obfuscated class name) ----
        val engine = mutableClassDefByOrNull { it.type == PLAYER_CORE }
            ?: error("player core class $PLAYER_CORE not found")

        val engineSeek = engine.methods.single {
            it.name == "seekTo" &&
                it.returnType == "V" &&
                it.parameterTypes.map { p -> p.toString() } == listOf("I")
        }
        engineSeek.addInstructions(
            0,
            "invoke-static {p1}, $PROBE->engineSeek(I)V",
        )

        // setMediaSource: the single method carrying the "mediaSource" string.
        val engineSetSource = engine.methods.single { m ->
            m.instructions.any { insn ->
                insn.opcode == Opcode.CONST_STRING &&
                    ((insn as ReferenceInstruction).reference as? StringReference)?.string == "mediaSource"
            }
        }
        engineSetSource.addInstructions(
            0,
            "invoke-static {}, $PROBE->engineSetSource()V",
        )
    }
}
