package tv.retwitch.patches

import app.morphe.patcher.fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/**
 * Matches the message-input presenter's setup method -- the one spot that
 * wires up the chat input's `MultiAutoCompleteTextView` (creates its two
 * trigger matchers, its `Tokenizer`, and the `TextWatcher` that decides
 * which autocomplete provider -- emote / mention / command -- lights up as
 * the user types). Identified by the two `checkNotNullParameter` argument
 * names R8 can't rename ("emoteAutoCompleteMapProvider" then
 * "animatedEmotesUrlUtil"), which as an ordered pair occur in exactly this
 * one method.
 */
/**
 * Matches the chat input's TextWatcher `onTextChanged(CharSequence, int,
 * int, int)`: the only one in the APK with two `(CharSequence): Boolean`
 * trigger checks ("@mention?", then "':' emote?"). Unique in 30.2.0 and
 * 31.8.0; replaces the old setup-method anchor whose
 * "emoteAutoCompleteMapProvider"/"animatedEmotesUrlUtil" parameter-name
 * strings 31.x strips.
 */
val chatInputWatcherFingerprint = fingerprint {
    returns("V")
    parameters("Ljava/lang/CharSequence;", "I", "I", "I")
    custom { method, _ ->
        method.name == "onTextChanged" &&
            (method.implementation?.instructions?.count { insn ->
                (insn.opcode == Opcode.INVOKE_VIRTUAL || insn.opcode == Opcode.INVOKE_INTERFACE) &&
                    ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.let { ref ->
                        ref.parameterTypes.size == 1 &&
                            ref.parameterTypes[0].toString() == "Ljava/lang/CharSequence;" &&
                            ref.returnType == "Z"
                    } == true
            } ?: 0) >= 2
    }
}

/**
 * Makes the chat-input emote autocomplete dropdown pop up for plain words,
 * not only after a leading `:`.
 *
 * Twitch's own emote *filter* already matches colon-less queries (it strips
 * a leading `:` if present, else matches the whole word by prefix), but the
 * input `TextWatcher` only ever *routes* to the emote provider when the
 * current word starts with `:` (a per-word `startsWith(':')` check). We
 * splice a call in right after that check so a plain word also lights up the
 * emote provider -- reusing Twitch's own filter + dropdown untouched.
 *
 * Neither the TextWatcher class nor its trigger-matcher class carry any
 * fingerprintable strings of their own (both are synthetic R8-merged
 * classes), so we reach the TextWatcher this way:
 * fingerprint its constructing method by strings, then read the watcher's
 * *current* obfuscated class name off the `new-instance` feeding the
 * `addTextChangedListener(...)` call.
 */
val retwitchAutocompleteNoColonPatch = bytecodePatch(
    name = "ReTwitch colon-less emote autocomplete",
    description = "Pops up the chat-input emote autocomplete for plain words too, not only after a ':'.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        // The message-input setup: the one method that both creates the input's
        // TextWatcher and installs its Tokenizer.
        val watcherClassType = chatInputWatcherFingerprint.classDef.type
        var foundSetup: List<Instruction>? = null
        var setupMethod: com.android.tools.smali.dexlib2.iface.Method? = null
        classDefForEach { classDef ->
            classDef.methods.forEach { m ->
                val insns = m.implementation?.instructions?.toList() ?: return@forEach
                val createsWatcher = insns.any { insn ->
                    insn.opcode == Opcode.NEW_INSTANCE &&
                        ((insn as ReferenceInstruction).reference as TypeReference).type == watcherClassType
                }
                val setsTokenizer = insns.any { insn ->
                    insn.opcode == Opcode.INVOKE_VIRTUAL &&
                        ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.name == "setTokenizer"
                }
                if (createsWatcher && setsTokenizer) {
                    foundSetup = insns
                    setupMethod = m
                }
            }
        }
        val setupInsns = foundSetup
            ?: throw PatchException("message-input setup (creates $watcherClassType + setTokenizer) not found")

        // The watcher is `addTextChangedListener(<watcher>)`-ed onto the
        // input; its arg register (D of the 35c invoke) is the watcher.
        val addIdx = setupInsns.indexOfFirst { insn ->
            insn.opcode == Opcode.INVOKE_VIRTUAL &&
                ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.name == "addTextChangedListener"
        }
        if (addIdx < 0) throw PatchException("no addTextChangedListener call in message-input setup")
        val watcherReg = (setupInsns[addIdx] as FiveRegisterInstruction).registerD

        // Emote images in the input draft: hand the input view (C of that
        // call) to EmoteInputImages right after Twitch adds its watcher.
        try {
            val inputReg = (setupInsns[addIdx] as FiveRegisterInstruction).registerC
            val sm = setupMethod!!
            mutableClassDefBy(sm.definingClass).methods.first {
                it.name == sm.name && it.parameterTypes.map(Any::toString) == sm.parameterTypes.map(Any::toString)
            }.addInstructions(
                addIdx + 1,
                "invoke-static/range {v$inputReg .. v$inputReg}, Ltv/retwitch/mod/emotes/EmoteInputImages;->attach(Ljava/lang/Object;)V",
            )
        } catch (e: Exception) {
            println("[ReTwitch] emote images in the chat input skipped: $e")
        }

        // Walk back to the new-instance that put the watcher in that register
        // and read its (per-build-renamed) class name off the type reference.
        var watcherType: String? = null
        for (i in addIdx - 1 downTo 0) {
            val insn = setupInsns[i]
            if (insn.opcode == Opcode.NEW_INSTANCE &&
                (insn as OneRegisterInstruction).registerA == watcherReg
            ) {
                watcherType = ((insn as ReferenceInstruction).reference as TypeReference).toString()
                break
            }
        }
        watcherType ?: throw PatchException("could not resolve the message-input TextWatcher class")

        val watcherClass = mutableClassDefByOrNull { it.type == watcherType }
            ?: throw PatchException("message-input TextWatcher class $watcherType not found")
        val onTextChanged = watcherClass.methods.first { m ->
            m.name == "onTextChanged" &&
                m.parameterTypes.size == 4 &&
                m.parameterTypes[0].toString() == "Ljava/lang/CharSequence;"
        }

        // Inside onTextChanged the two per-word trigger checks are the only
        // calls shaped `(CharSequence): boolean` -- the first is "@mention?",
        // the second is "starts with ':'? (emote)". We hook after the second.
        val checkIndices = onTextChanged.instructions.withIndex().filter { (_, insn) ->
            (insn.opcode == Opcode.INVOKE_VIRTUAL || insn.opcode == Opcode.INVOKE_INTERFACE) &&
                ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.let { ref ->
                    ref.parameterTypes.size == 1 &&
                        ref.parameterTypes[0].toString() == "Ljava/lang/CharSequence;" &&
                        ref.returnType == "Z"
                } == true
        }.map { it.index }
        if (checkIndices.size < 2) {
            throw PatchException("expected >=2 (CharSequence)Z trigger checks in onTextChanged, found ${checkIndices.size}")
        }
        val emoteCheckIdx = checkIndices[1]

        // arg register of that invoke = the CharSequence (text up to cursor).
        val csReg = (onTextChanged.instructions[emoteCheckIdx] as FiveRegisterInstruction).registerD

        // the following move-result holds the boolean we want to override.
        val moveResultIdx = emoteCheckIdx + 1
        val moveResult = onTextChanged.instructions[moveResultIdx]
        if (moveResult.opcode != Opcode.MOVE_RESULT) {
            throw PatchException("emote trigger check not followed by move-result")
        }
        val boolReg = (moveResult as OneRegisterInstruction).registerA

        if (csReg > 15 || boolReg > 15) {
            throw PatchException("trigger registers out of 4-bit range (cs=$csReg bool=$boolReg)")
        }

        onTextChanged.addInstructions(
            moveResultIdx + 1,
            """
                invoke-static {v$csReg, v$boolReg}, Ltv/retwitch/mod/emotes/EmoteAutocompleteBridge;->shouldTriggerEmoteAutocomplete(Ljava/lang/CharSequence;Z)Z
                move-result v$boolReg
            """.trimIndent(),
        )

        // --- Part 2: the tokenizer -------------------------------------------
        //
        // Routing the emote provider on (part 1) isn't enough: the
        // MultiAutoCompleteTextView still asks the Tokenizer what the current
        // token is, and Twitch's findTokenStart returns max(@pos, cmdPos,
        // :pos), which is 0 for a colon-less word -> the "token" becomes the
        // whole line since the message start ("word1 word2"), which matches no
        // emote and never pops the dropdown for anything but the first word.
        // We raise the start to just after the last whitespace so a plain word
        // is its own token, exactly like the ':' path already is.
        val setTokenizerIdx = setupInsns.indexOfFirst { insn ->
            insn.opcode == Opcode.INVOKE_VIRTUAL &&
                ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.name == "setTokenizer"
        }
        if (setTokenizerIdx < 0) throw PatchException("no setTokenizer call in message-input setup")
        val tokenizerReg = (setupInsns[setTokenizerIdx] as FiveRegisterInstruction).registerD

        var tokenizerType: String? = null
        for (i in setTokenizerIdx - 1 downTo 0) {
            val insn = setupInsns[i]
            if (insn.opcode == Opcode.NEW_INSTANCE &&
                (insn as OneRegisterInstruction).registerA == tokenizerReg
            ) {
                tokenizerType = ((insn as ReferenceInstruction).reference as TypeReference).toString()
                break
            }
        }
        tokenizerType ?: throw PatchException("could not resolve the message-input Tokenizer class")

        val tokenizerClass = mutableClassDefByOrNull { it.type == tokenizerType }
            ?: throw PatchException("message-input Tokenizer class $tokenizerType not found")
        val findTokenStart = tokenizerClass.methods.first { m ->
            m.name == "findTokenStart" &&
                m.parameterTypes.size == 2 &&
                m.parameterTypes[0].toString() == "Ljava/lang/CharSequence;"
        }

        // The two `(CharSequence): int` calls in findTokenStart are the '@' and
        // ':' trigger matchers (in that order). We splice in just before the
        // second (':') one, where the truncated text-up-to-cursor is still live
        // in that call's argument register and the running token-start
        // accumulator is still in the register the following max() reads.
        val startBody = findTokenStart.instructions
        val matcherCalls = startBody.withIndex().filter { (_, insn) ->
            (insn.opcode == Opcode.INVOKE_VIRTUAL || insn.opcode == Opcode.INVOKE_INTERFACE) &&
                ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.let { ref ->
                    ref.parameterTypes.size == 1 &&
                        ref.parameterTypes[0].toString() == "Ljava/lang/CharSequence;" &&
                        ref.returnType == "I"
                } == true
        }.map { it.index }
        if (matcherCalls.size < 2) {
            throw PatchException("expected >=2 (CharSequence)I matcher calls in findTokenStart, found ${matcherCalls.size}")
        }
        val secondMatcherIdx = matcherCalls[1]
        val textReg = (startBody[secondMatcherIdx] as FiveRegisterInstruction).registerD

        // Accumulator = the operand of the final max() that isn't the matcher's
        // own result. Right after the matcher: move-result <matcherRes>;
        // invoke-static {a, b} Math.max; the accumulator is whichever of a/b
        // isn't <matcherRes>.
        val matcherResultReg = (startBody[secondMatcherIdx + 1] as OneRegisterInstruction).registerA
        val finalMax = startBody[secondMatcherIdx + 2]
        val maxA = (finalMax as FiveRegisterInstruction).registerC
        val maxB = finalMax.registerD
        val accReg = if (maxA == matcherResultReg) maxB else maxA

        if (textReg > 15 || accReg > 15) {
            throw PatchException("tokenizer registers out of 4-bit range (text=$textReg acc=$accReg)")
        }

        findTokenStart.addInstructions(
            secondMatcherIdx,
            """
                invoke-static {v$textReg, v$accReg}, Ltv/retwitch/mod/emotes/EmoteAutocompleteBridge;->adjustTokenStart(Ljava/lang/CharSequence;I)I
                move-result v$accReg
            """.trimIndent(),
        )
    }
}
