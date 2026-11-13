package tv.retwitch.patches

import app.morphe.patcher.fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * Matches the method that turns one message's token list into the final
 * `SpannedString` handed to the chat TextView. Identified purely by
 * literal string constants baked into it: the bits/cheermote CDN host
 * check and the pair of bidi override characters used only when rendering
 * an "@mention" token -- none of these are obfuscatable identifiers.
 */
val chatMessageBodyFingerprint = fingerprint {
    returns("Landroid/text/SpannedString;")
    strings(
        "https://images-na.curseapp.net/",
        "\u202d",
        "\u202c",
    )
}

/**
 * Matches the utility method that wires up Twitch's own remote-image
 * ImageSpans (animated cheermote/badge images backed by Twitch's Glide)
 * inside a chat body `Spanned` to the `TextView` about to display it. Called
 * exactly once per chat row bind, right before `TextView.setText(...)` --
 * the only point in the chat pipeline with a real TextView, which is what
 * makes our animated emotes (`AnimatedEmoteDrawable`) actually play.
 *
 * Identified by its signature alone: `(Context, Spanned, TextView): List`
 * is unique in the APK (30.2.0 and 31.8.0). Its void sibling overload has the
 * same parameters. Twitch 31.x builds strip the `checkNotNullParameter`
 * argument-name strings this used to be matched by.
 */
val chatMessageTextBindFingerprint = fingerprint {
    returns("Ljava/util/List;")
    parameters("Landroid/content/Context;", "Landroid/text/Spanned;", "Landroid/widget/TextView;")
}

val retwitchEmoteChatPatch = bytecodePatch(
    name = "ReTwitch third-party emotes (7TV/BTTV/FFZ)",
    description = "Renders cached 7TV/BTTV/FFZ emotes inline in chat, on top of Twitch's own tokens/spans.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        // The current channel id is fed to ChannelTracker from the chat
        // connection controller's setBroadcaster hook (ChatConnectPatch) for
        // live and the "prepare for VOD" hook (VodChannelTrackPatch) for
        // replays -- both authoritative, once-per-channel-open signals. We
        // deliberately do NOT track it from this batch converter: it fires
        // once per message batch, so a busy previously-watched channel whose
        // chat is still connected in the background would keep dragging the
        // tracker back to the old channel.
        val bodyMethod = chatMessageBodyFingerprint.method
        // The real exit returns `new SpannedString(builder)`. In 31.x the last
        // return-object sits behind a Kotlin throw helper and never runs (the
        // hook there left mention detection dead), so anchor on the constructor.
        val returnIndex = bodyMethod.instructions.withIndex().indexOfLast { (i, insn) ->
            val prev = bodyMethod.instructions.getOrNull(i - 1)
            val ctor = (prev as? ReferenceInstruction)?.reference as? MethodReference
            insn.opcode == Opcode.RETURN_OBJECT && prev?.opcode == Opcode.INVOKE_DIRECT &&
                ctor?.definingClass == "Landroid/text/SpannedString;" && ctor.name == "<init>" &&
                (prev as com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction).registerC ==
                (insn as OneRegisterInstruction).registerA
        }
        check(returnIndex >= 0) { "chat message body builder has no `return new SpannedString(...)`" }

        val returnRegister = (bodyMethod.instructions[returnIndex] as OneRegisterInstruction).registerA

        bodyMethod.addInstructions(
            returnIndex,
            """
                invoke-static/range {v$returnRegister .. v$returnRegister}, Ltv/retwitch/mod/emotes/EmoteChatHook;->injectThirdPartyEmotes(Landroid/text/SpannedString;)Landroid/text/SpannedString;
                move-result-object v$returnRegister
            """.trimIndent(),
        )

        // Token-based @mention detection (see MentionHighlighter). The SAME
        // body builder walks the message's token list; for a mention token it
        // reads the target login off the token and prepends '@'. We bracket
        // that loop:
        //   * beginMessage() at method entry resets the per-build "mentions
        //     me" flag;
        //   * noteMention(login) at the mention branch flips it on for us;
        //   * MentionHighlighter.onMessageBody(...), already called from the
        //     injectThirdPartyEmotes hook above (at the return), consumes it.
        // The login is whatever String field the token exposes -- derived
        // structurally as the last `iget-object` of a String field right
        // before the single const-string "@" the builder uses to render the
        // mention -- so no obfuscated class/field name is hardcoded.
        val atIndex = bodyMethod.instructions.indexOfFirst {
            (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO) &&
                it.getReference<StringReference>()?.string == "@"
        }
        check(atIndex >= 0) { "chat body builder has no '@' mention marker" }

        var loginIgetIndex = -1
        for (i in atIndex - 1 downTo 0) {
            val insn = bodyMethod.instructions[i]
            if (insn.opcode == Opcode.IGET_OBJECT &&
                insn.getReference<FieldReference>()?.type == "Ljava/lang/String;"
            ) {
                loginIgetIndex = i
                break
            }
        }
        check(loginIgetIndex >= 0) { "could not locate mention login field read before '@'" }

        val loginRegister =
            bodyMethod.getInstruction<OneRegisterInstruction>(loginIgetIndex).registerA
        val noteInvoke = if (loginRegister < 16) {
            "invoke-static {v$loginRegister}"
        } else {
            "invoke-static/range {v$loginRegister .. v$loginRegister}"
        }

        // Insert AFTER the iget (login register still live). loginIgetIndex is
        // below the return injection above, so it's still valid; do it before
        // the entry insertion so index 0 stays valid.
        bodyMethod.addInstructions(
            loginIgetIndex + 1,
            """
                $noteInvoke, Ltv/retwitch/mod/emotes/MentionHighlighter;->noteMention(Ljava/lang/String;)V
            """.trimIndent(),
        )

        bodyMethod.addInstructions(
            0,
            """
                invoke-static {}, Ltv/retwitch/mod/emotes/MentionHighlighter;->beginMessage()V
            """.trimIndent(),
        )

        // RTE chat badges: hand Twitch our own badge model so it renders the
        // badge natively (its own image loader, native size) instead of us
        // overlaying a hand-sized ImageSpan. createChatMessageSpanGroup takes
        // the badge list and sender model as adjacent params (p2 = badges,
        // p3 = sender) and renders each badge from a URL on the model. We
        // append our badge at the method's entry and write the augmented list
        // back into the badges register.
        // createChatMessageSpanGroup lives in the body builder's class: the
        // static (owner, Context, badges List, sender, ...) method whose sender
        // param is one of the body builder's own parameter types. Found
        // structurally -- 31.x strips the "badges"/"sender" name strings.
        val bodyParams = bodyMethod.parameterTypes.map { it.toString() }
        val bodyClass = mutableClassDefBy(bodyMethod.definingClass)
        val spanGroupMethod = bodyClass.methods.singleOrNull { m ->
            val p = m.parameterTypes.map { it.toString() }
            p.size > 3 && p[0] == bodyClass.type && p[1] == "Landroid/content/Context;" &&
                p[2] == "Ljava/util/List;" && p[3] in bodyParams && p[3] != "Ljava/util/List;"
        } ?: throw PatchException("createChatMessageSpanGroup not found in ${bodyClass.type}")

        spanGroupMethod.addInstructions(
            0,
            """
                invoke-static/range {p2 .. p3}, Ltv/retwitch/mod/emotes/ChatCosmetics;->injectBadges(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;
                move-result-object p2
            """.trimIndent(),
        )

        // RTE nick paints: the span group's constructor (31.8.0 wa6.<init>, p3 = sender span
        // "name: ") draws the name with the sender's paint; the sender id was handed over by
        // injectBadges at the entry of the same build. Optional.
        try {
            val groupCtor = mutableClassDefBy(chatLineSpanGroupFingerprint.classDef.type).methods.single {
                it.name == "<init>" && it.parameterTypes.size == 6 &&
                    it.parameterTypes.take(5).all { t -> t.toString() == "Ljava/lang/CharSequence;" }
            }
            groupCtor.addInstructions(
                0,
                """
                    invoke-static/range {p3 .. p3}, Ltv/retwitch/mod/emotes/ChatCosmetics;->paintName(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;
                    move-result-object p3
                    invoke-static/range {p3 .. p5}, Ltv/retwitch/mod/emotes/ChatDecor;->onSpanGroup(Ljava/lang/CharSequence;Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/CharSequence;
                    move-result-object p5
                """.trimIndent(),
            )
        } catch (e: Exception) {
            println("[ReTwitch] nick paints skipped: $e")
        }

        // Static method `f(Context, Spanned, TextView): List` -- p0 is the
        // Context, p1 the Spanned, p2 the TextView, all valid from method
        // entry. Only 8 locals plus 3 params here, well under the /range
        // threshold, so the plain 4-bit `invoke-static {p1, p2}` encoding
        // is fine.
        val bindMethod = chatMessageTextBindFingerprint.method
        bindMethod.addInstructions(
            0,
            """
                invoke-static {p1, p2}, Ltv/retwitch/mod/emotes/EmoteChatHook;->onTextBound(Landroid/text/Spanned;Landroid/widget/TextView;)V
            """.trimIndent(),
        )

        // Channel-points reward rows (31.8.0 je2.z(Context, LiveChatMessage, ...,
        // StringResource, ..., RewardPricingType, Long)) build their body through
        // the span-group builder directly, skipping the body hook above: route
        // the body (wa6.a() -> SpannedString) and the message through
        // EmoteChatHook.injectRewardMessage. Optional: skipped if not found.
        try {
            val rewardRow = run {
                var found: Pair<String, com.android.tools.smali.dexlib2.iface.Method>? = null
                classDefForEach { c ->
                    c.methods.forEach { m ->
                        val p = m.parameterTypes.map { it.toString() }
                        if (p.size >= 4 && p[0] == "Landroid/content/Context;" &&
                            "Ltv/twitch/android/core/strings/StringResource;" in p &&
                            "Ltv/twitch/android/models/communitypoints/RewardPricingType;" in p
                        ) found = c.type to m
                    }
                }
                found ?: throw PatchException("reward row builder not found")
            }
            val messageType = rewardRow.second.parameterTypes[1].toString()
            val method = mutableClassDefBy(rewardRow.first).methods.first {
                it.name == rewardRow.second.name &&
                    it.parameterTypes.map(Any::toString) == rewardRow.second.parameterTypes.map(Any::toString)
            }
            val code = method.instructions.toList()
            // The call that takes the message model (span-group wrapper) -> its message register.
            val groupCall = code.indexOfFirst { insn ->
                ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.parameterTypes
                    ?.map { it.toString() }?.contains(messageType) == true
            }
            val groupRef = (code[groupCall] as ReferenceInstruction).reference as MethodReference
            val messageReg = (code[groupCall] as com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction).let { r ->
                val regs = listOf(r.registerC, r.registerD, r.registerE, r.registerF, r.registerG)
                regs[groupRef.parameterTypes.map { it.toString() }.indexOf(messageType) + 1]
            }
            val bodyRead = (groupCall + 1 until code.size).first { i ->
                val ref = (code[i] as? ReferenceInstruction)?.reference as? MethodReference
                ref != null && ref.returnType == "Landroid/text/SpannedString;" && ref.parameterTypes.isEmpty() &&
                    code[i + 1].opcode == Opcode.MOVE_RESULT_OBJECT
            }
            val bodyReg = (code[bodyRead + 1] as OneRegisterInstruction).registerA
            method.addInstructions(
                bodyRead + 2,
                """
                    invoke-static {v$bodyReg, v$messageReg}, Ltv/retwitch/mod/emotes/EmoteChatHook;->injectRewardMessage(Landroid/text/SpannedString;Ljava/lang/Object;)Landroid/text/SpannedString;
                    move-result-object v$bodyReg
                """.trimIndent(),
            )
        } catch (e: Exception) {
            println("[ReTwitch] emotes in reward rows skipped: $e")
        }
    }
}
