package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val HOOK = "Ltv/retwitch/mod/ChatBan;"
private const val TOKEN_RESPONSE = "Ltv/twitch/android/models/AccessTokenResponse;"

/** The IRC login (31.9.0 `ijm.emit`): `PASS oauth:<token>` or `PASS listen`, then `NICK <login>`. */
private val ircLoginFingerprint = fingerprint {
    strings("oauth:", "PASS ", "NICK ")
}

private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

/**
 * `BAN_BYPASS`, see `ChatBan`. The token model parser (31.9.0 `zf80.a`) reads the forbidden reason: Twitch
 * answers a banned account there, before usher, so the reason goes through the hook. The login's token and
 * nick do too, each right where it is read. Skipped softly.
 */
val retwitchBanBypassPatch = bytecodePatch(
    name = "ReTwitch ban bypass",
    description = "Optionally plays a channel that banned the account and reads its chat anonymously.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            // Two GQL models share the toString (31.9.0 `rwt`, `z2r`); the parser reads one of them.
            val authorizations = HashSet<String>()
            classDefForEach { c ->
                if (c.methods.any { m -> m.returnType == "Ljava/lang/String;" && m.implementation?.instructions?.any {
                        it.string() == "Authorization(forbiddenReasonCode="
                    } == true }
                ) authorizations += c.type
            }
            fun Instruction.readsAuthorization() =
                ((this as? ReferenceInstruction)?.reference as? FieldReference)?.definingClass in authorizations
            val parsers = ArrayList<Pair<String, String>>()
            classDefForEach { c ->
                c.methods.filter { m ->
                    m.returnType == TOKEN_RESPONSE && m.implementation?.instructions?.any { it.readsAuthorization() } == true
                }.forEach { parsers += c.type to it.name }
            }
            val (owner, name) = parsers.single()
            val parser = mutableClassDefBy(owner).methods.single { it.name == name && it.returnType == TOKEN_RESPONSE }
            val code = parser.instructions.toList()
            val read = code.indexOfFirst { it.readsAuthorization() }
            val field = (code[read] as ReferenceInstruction).reference as FieldReference
            val reason = (code[read] as TwoRegisterInstruction).registerA
            parser.addInstructions(
                read + 1,
                """
                    invoke-static {v$reason, p0}, $HOOK->onTokenReason(Ljava/lang/Enum;Ljava/lang/Object;)Ljava/lang/Enum;
                    move-result-object v$reason
                    check-cast v$reason, ${field.type}
                """.trimIndent(),
            )
            println("[ReTwitch] ban bypass: token $owner->$name")
        } catch (e: Exception) {
            println("[ReTwitch] ban bypass token skipped: $e")
        }
        try {
            val login = ircLoginFingerprint.method
            val code = login.instructions.toList()
            fun read(before: String) = (code.indexOfFirst { it.string() == before } downTo 0).first {
                code[it].opcode == Opcode.IGET_OBJECT
            }
            val token = read("oauth:")
            val nick = read("NICK ")
            val tokenReg = (code[token] as TwoRegisterInstruction).registerA
            val nickReg = (code[nick] as TwoRegisterInstruction).registerA
            // The later one first: the indexes of the earlier code stay valid.
            login.addInstructions(
                nick + 1,
                """
                    invoke-static/range {v$nickReg .. v$nickReg}, $HOOK->loginNick(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$nickReg
                """.trimIndent(),
            )
            login.addInstructions(
                token + 1,
                """
                    invoke-static/range {v$tokenReg .. v$tokenReg}, $HOOK->loginToken(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$tokenReg
                """.trimIndent(),
            )
            println("[ReTwitch] ban bypass: chat login ${login.definingClass}->${login.name}")
        } catch (e: Exception) {
            println("[ReTwitch] ban bypass chat skipped: $e")
        }
        try {
            // The IRC parser (31.9.0 tna.L): NOTICE msg-id == "msg_banned" parts the channel with
            // TTV_EC_BANNED_USER. The comparison's result goes through ChatBan.banNotice.
            val sites = ArrayList<Pair<String, String>>()
            classDefForEach { c ->
                c.methods.filter { m ->
                    val code = m.implementation?.instructions?.toList() ?: return@filter false
                    code.any { it.string() == "msg_banned" } && code.any {
                        ((it as? ReferenceInstruction)?.reference as? FieldReference)?.name == "TTV_EC_BANNED_USER"
                    }
                }.forEach { sites += c.type to it.name }
            }
            val (owner, name) = sites.single()
            val parser = mutableClassDefBy(owner).methods.single { m ->
                m.name == name && m.implementation?.instructions?.any { it.string() == "msg_banned" } == true
            }
            val code = parser.instructions.toList()
            val literal = code.indexOfFirst { it.string() == "msg_banned" }
            val result = (literal until code.size).first { code[it].opcode == Opcode.MOVE_RESULT }
            require(code[result - 1].opcode == Opcode.INVOKE_STATIC) { "msg_banned is not compared right away" }
            val flag = (code[result] as OneRegisterInstruction).registerA
            parser.addInstructions(
                result + 1,
                """
                    invoke-static/range {v$flag .. v$flag}, $HOOK->banNotice(Z)Z
                    move-result v$flag
                """.trimIndent(),
            )
            println("[ReTwitch] ban bypass: chat ban notice $owner->$name")
        } catch (e: Exception) {
            println("[ReTwitch] ban bypass notice skipped: $e")
        }
    }
}
