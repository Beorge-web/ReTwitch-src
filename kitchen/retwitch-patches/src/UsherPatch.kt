package tv.retwitch.patches

import app.morphe.patcher.fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * Matches the lambda that builds the live-stream usher.ttvnw.net playlist
 * request URL. Identified purely by the combination of literal string
 * constants baked into it (query parameter names Twitch's usher API itself
 * expects, plus the host) -- these are protocol data, not obfuscatable
 * identifiers, so they survive R8 renaming untouched. "mobile_cellular" and
 * "force_creative_id" specifically disambiguate this from the separate VOD
 * usher URL builder, which shares some but not all of these strings.
 */
val usherLiveUrlFingerprint = fingerprint {
    strings(
        "usher.ttvnw.net",
        "player_backend",
        "allow_source",
        "mobile_cellular",
        "force_creative_id",
    )
}

val retwitchUsherPatch = bytecodePatch(
    name = "ReTwitch usher source quality unlock",
    description = "Forces allow_source=true on the live usher playlist request, unlocking Source quality.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        val method = usherLiveUrlFingerprint.method

        // 31.x: R8 merges this lambda with unrelated ones behind a
        // packed-switch, so the method's last `return-object` belongs to some
        // other branch (31.8.0 `lt5.invoke`: a Compose lambda returning Unit).
        // Anchor on the Uri.Builder#build() of the usher branch instead (the
        // first one after the "usher.ttvnw.net" host), like VodUsherPatch.
        val code = method.instructions.toList()
        val hostIndex = code.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "usher.ttvnw.net"
        }
        val buildIndex = (hostIndex + 1 until code.size).firstOrNull { i ->
            val ref = (code[i] as? ReferenceInstruction)?.reference as? MethodReference
            code[i].opcode == Opcode.INVOKE_VIRTUAL && ref != null &&
                ref.definingClass == "Landroid/net/Uri\$Builder;" && ref.name == "build"
        }
        check(hostIndex >= 0 && buildIndex != null && code[buildIndex + 1].opcode == Opcode.MOVE_RESULT_OBJECT) {
            "usher URL builder: no Uri.Builder#build() + move-result-object after the usher host"
        }
        val register = (code[buildIndex + 1] as OneRegisterInstruction).registerA

        method.addInstructions(
            buildIndex + 2,
            """
                invoke-static/range {v$register .. v$register}, Ltv/retwitch/mod/UsherHook;->forceSourceQuality(Landroid/net/Uri;)Landroid/net/Uri;
                move-result-object v$register
            """.trimIndent(),
        )
    }
}
