package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method

/** `IrcEventComponents(messageTags, prefix, command, channel, ctcpEvent, content).toString()`: a parsed IRC line. */
val ircEventComponentsToStringFingerprint = fingerprint {
    returns("Ljava/lang/String;")
    strings("IrcEventComponents(messageTags=")
}

/**
 * Raw IRC events of the open chat for `ModLogs.onIrcEvent` (ban / timeout / deleted message
 * lines for every viewer). The chat SDK collects each parsed line of a joined channel
 * (31.8.0 `ds5.a`) into a method whose first parameter is the `IrcEventComponents` model
 * and that switches on its command (31.8.0 `gna.L`, CLEARCHAT and CLEARMSG branches); it
 * keeps only ids and drops `ban-duration`. The hook gets the model at its entry. Skipped softly.
 */
val retwitchIrcEventPatch = bytecodePatch(
    name = "ReTwitch IRC events",
    description = "Optionally adds chat lines for bans, timeouts and deleted messages for every viewer.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val ircType = ircEventComponentsToStringFingerprint.classDef.type
            val parserClass = mutableClassDefByOrNull { c -> c.methods.any { isIrcParser(it, ircType) } }
                ?: throw PatchException("IRC command switch on $ircType not found")
            val parser = parserClass.methods.single { isIrcParser(it, ircType) }
            val first = if (AccessFlags.STATIC.isSet(parser.accessFlags)) 0 else 1
            parser.addInstruction(
                0,
                "invoke-static/range {p$first .. p$first}, Ltv/retwitch/mod/ModLogs;->onIrcEvent(Ljava/lang/Object;)V",
            )
            println("[ReTwitch] IRC events: ${parserClass.type}->${parser.name}")
        } catch (e: Exception) {
            println("[ReTwitch] IRC events skipped: $e")
        }
    }
}

/** First parameter is the IRC model and the body has a switch (on the command). */
private fun isIrcParser(method: Method, ircType: String) =
    method.parameterTypes.firstOrNull()?.toString() == ircType &&
        method.implementation?.instructions?.any {
            it.opcode == Opcode.PACKED_SWITCH || it.opcode == Opcode.SPARSE_SWITCH
        } == true
