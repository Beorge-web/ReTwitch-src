package tv.retwitch.patches

import app.morphe.patcher.fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/**
 * Matches the `toString()` of Twitch's `ChatMessageListConfig` Kotlin data
 * class. Identified purely by the header string the Kotlin compiler bakes
 * into every data-class `toString()` -- "ChatMessageListConfig(hasModAccess="
 * -- which is source-derived text, not an obfuscatable identifier, so it
 * survives R8 renaming untouched. A full-APK dump shows it in exactly one
 * class. `hasModAccess` is the first declared property, so it's the flag
 * Twitch checks before wiring up the click-to-reveal treatment for deleted
 * chat messages (see the reveal setup that requires `isOverlay && hasModAccess`).
 */
val chatMessageListConfigFingerprint = fingerprint {
    returns("Ljava/lang/String;")
    strings("ChatMessageListConfig(hasModAccess=")
}

/**
 * Matches the `toString()` of Twitch's `DeletedMessageClickedEvent` Kotlin
 * data class via the source-derived header string the Kotlin compiler bakes
 * into it -- "DeletedMessageClickedEvent(messageId=" -- which survives R8
 * renaming. This event is instantiated in exactly one place: the `onClick`
 * of the deleted-message clickable span. We use that to locate the span
 * class (and its own mod-access flag) without hardcoding an obfuscated name.
 */
val deletedMessageClickedEventFingerprint = fingerprint {
    returns("Ljava/lang/String;")
    strings("DeletedMessageClickedEvent(messageId=")
}

val retwitchDeletedMessagesPatch = bytecodePatch(
    name = "ReTwitch show deleted messages",
    description = "Reveals deleted chat messages behind a tap (spoiler), toggleable in the ReTwitch menu.",
) {
    compatibleWith(twitchCompatibility)

    // Merges tv.retwitch.mod.DeletedMessagesHook / ChatSettings into the app.
    dependsOn(retwitchExtensionPatch)

    execute {
        // Derive the (obfuscated) hasModAccess field without hardcoding an R8
        // field letter: hasModAccess is the first property of the data class
        // (the toString header proves it: "ChatMessageListConfig(hasModAccess="),
        // so the constructor's first `iput-boolean` into this class is that
        // field. (toString itself can't be traced with the usual StringBuilder
        // helper here -- R8 routes it through a String-concat helper instead.)
        val configClass = chatMessageListConfigFingerprint.classDef
        val configType = configClass.type

        val hasModAccessField = configClass.methods
            .filter { it.name == "<init>" }
            .firstNotNullOfOrNull { constructor ->
                constructor.implementation?.instructions?.firstOrNull { instruction ->
                    instruction.opcode == Opcode.IPUT_BOOLEAN &&
                        instruction.getReference<FieldReference>()?.definingClass == configType
                }?.getReference<FieldReference>()
            } ?: throw PatchException("Could not locate hasModAccess field in $configType")

        // The tap-to-reveal is gated a SECOND time, on a boolean the
        // clickable span stores on itself (its `onClick` early-returns when
        // that flag is false). That flag is seeded from the config's
        // hasModAccess at span-creation time -- but Twitch ALSO rewrites it in
        // bulk afterwards (an updateModAccess-style setter that walks every
        // existing span) once the real, non-mod status is known, which
        // silently turns already-shown spoilers back off. Forcing only the
        // config flag above misses that path, so clicks stop revealing
        // intermittently. Route the span flag through the hook too.
        //
        // Locate the span class without hardcoding an R8 name: its
        // `onClick(View)` is the sole place that instantiates
        // DeletedMessageClickedEvent (fingerprinted above by its data-class
        // toString header). The span has exactly one instance boolean field --
        // the mod-access flag.
        val clickedEventType = deletedMessageClickedEventFingerprint.classDef.type
        val spanClass = classDefByOrNull { classDef ->
            classDef.methods.any { method ->
                method.name == "onClick" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].toString() == "Landroid/view/View;" &&
                    method.implementation?.instructions?.any { instruction ->
                        instruction.opcode == Opcode.NEW_INSTANCE &&
                            instruction.getReference<TypeReference>()?.type == clickedEventType
                    } == true
            }
        } ?: throw PatchException(
            "Could not locate DeletedMessageClickableSpan (via $clickedEventType)",
        )

        val spanFlagField = spanClass.instanceFields.singleOrNull { it.type == "Z" }
            ?: throw PatchException("Could not locate mod-access flag on ${spanClass.type}")

        // 31.x builds this span for chat rows with a null EventDispatcher, and its
        // onClick only reveals by dispatching the clicked event -- so taps did
        // nothing. Let the hook reveal the original text in place first.
        val onClick = mutableClassDefBy(spanClass).methods.first {
            it.name == "onClick" && it.parameterTypes.map(Any::toString) == listOf("Landroid/view/View;")
        }
        if ((onClick.implementation?.registerCount ?: 0) <= 2) {
            throw PatchException("${spanClass.type}.onClick has no free register")
        }
        onClick.addInstructionsWithLabels(
            0,
            """
                invoke-static/range {p0 .. p1}, Ltv/retwitch/mod/DeletedMessagesHook;->revealInPlace(Ljava/lang/Object;Landroid/view/View;)Z
                move-result v0
                if-eqz v0, :rt_reveal_original
                return-void
            """.trimIndent(),
            ExternalLabel("rt_reveal_original", onClick.getInstruction(0)),
        )

        // Every mod-access boolean write we route through the toggle hook,
        // keyed by (definingClass, fieldName).
        val targetFields: Set<Pair<String, String>> = setOf(
            hasModAccessField.definingClass to hasModAccessField.name,
            spanClass.type to spanFlagField.name,
        )

        // Gate every write to those flags (constructors and setters alike)
        // through the hook. Routing the setters too is what stops a later
        // setHasModAccess(false)/updateModAccess(false) -- fired once the real,
        // non-mod status is known -- from turning the spoiler back off.
        classDefForEach { classDef ->
            classDef.methods.forEach methods@{ method ->
                val instructions = method.implementation?.instructions ?: return@methods

                val writeIndices = instructions.withIndex().filter { (_, instruction) ->
                    instruction.opcode == Opcode.IPUT_BOOLEAN &&
                        instruction.getReference<FieldReference>()?.let {
                            (it.definingClass to it.name) in targetFields
                        } == true
                }.map { it.index }

                if (writeIndices.isEmpty()) return@methods

                val mutableMethod = mutableClassDefBy(classDef).findMutableMethodOf(method)

                // Iterate last-to-first so earlier indices stay valid as we
                // insert instructions ahead of each write.
                writeIndices.asReversed().forEach { index ->
                    val valueRegister =
                        mutableMethod.getInstruction<TwoRegisterInstruction>(index).registerA
                    val invoke = if (valueRegister < 16) {
                        "invoke-static {v$valueRegister}"
                    } else {
                        "invoke-static/range {v$valueRegister .. v$valueRegister}"
                    }

                    mutableMethod.addInstructions(
                        index,
                        """
                            $invoke, Ltv/retwitch/mod/DeletedMessagesHook;->forceModAccess(Z)Z
                            move-result v$valueRegister
                        """.trimIndent(),
                    )
                }
            }
        }

        // Text / strike / grey modes: put the original text in place of the placeholder.
        // (a) A message that arrived deleted: the span group's message span (31.8.0 wa6.<init>
        // p5) is "<message deleted>" under the deleted span, which holds the body.
        // (b) A line deleted on screen: the static builder that slices the line and adds the
        // placeholder (31.8.0 cqo.k(String, SpannedString, Context, EventDispatcher, Z): Spanned)
        // -- hooked after each call's move-result, since its own returns sit on branch targets.
        try {
            val groupCtor = mutableClassDefBy(chatLineSpanGroupFingerprint.classDef.type).methods.single {
                it.name == "<init>" && it.parameterTypes.size == 6 &&
                    it.parameterTypes.take(5).all { t -> t.toString() == "Ljava/lang/CharSequence;" }
            }
            groupCtor.addInstructions(
                0,
                """
                    invoke-static/range {p5 .. p5}, Ltv/retwitch/mod/DeletedMessagesHook;->styleDeleted(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;
                    move-result-object p5
                """.trimIndent(),
            )

            val lineBuilder = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
            classDefForEach { classDef ->
                classDef.methods.forEach { method ->
                    if (method.returnType == "Landroid/text/Spanned;" &&
                        method.parameterTypes.any { it.toString() == "Landroid/text/SpannedString;" } &&
                        method.implementation?.instructions?.any { instruction ->
                            instruction.opcode == Opcode.NEW_INSTANCE &&
                                instruction.getReference<TypeReference>()?.type == spanClass.type
                        } == true
                    ) lineBuilder += classDef.type to method
                }
            }
            if (lineBuilder.size != 1) throw PatchException("deleted line builder: ${lineBuilder.size} candidates")
            val (builderType, builder) = lineBuilder.single()
            var calls = 0
            classDefForEach { classDef ->
                classDef.methods.forEach methods@{ method ->
                    val code = method.implementation?.instructions?.toList() ?: return@methods
                    val sites = code.indices.filter { i ->
                        val ref = code[i].getReference<MethodReference>()
                        ref != null && ref.definingClass == builderType && ref.name == builder.name &&
                            ref.parameterTypes.map(Any::toString) == builder.parameterTypes.map(Any::toString) &&
                            code.getOrNull(i + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
                    }
                    if (sites.isEmpty()) return@methods
                    val mutable = mutableClassDefBy(classDef).findMutableMethodOf(method)
                    sites.asReversed().forEach { i ->
                        val reg = (code[i + 1] as OneRegisterInstruction).registerA
                        mutable.addInstructions(
                            i + 2,
                            """
                                invoke-static/range {v$reg .. v$reg}, Ltv/retwitch/mod/DeletedMessagesHook;->styleDeletedLine(Landroid/text/Spanned;)Landroid/text/Spanned;
                                move-result-object v$reg
                            """.trimIndent(),
                        )
                        calls++
                    }
                }
            }
            if (calls == 0) throw PatchException("no calls to $builderType.${builder.name}")
        } catch (e: Exception) {
            println("[ReTwitch] deleted message styles skipped: $e")
        }
    }
}

/**
 * Deleted notice-style rows (31.9.0 `v770` generic user notice, `in20` sub goal notice; 32.0.0 `ez60`, `th20`) vanished:
 * their "mark deleted" (`dfd.j()`, 32.0.0 `obd.k()`, the chat row interface over the one with `getUserId()`) nulls
 * the text / hides it, where a plain message row keeps it under "<message deleted>". A message
 * sent with channel points renders as such a row, so a banned user's points message disappeared.
 * At the entry of those `j()` (the ones writing null into a `Spanned` field) `DeletedMessagesHook.keepDeletedNotice`
 * styles the text as deleted instead, unless the deleted mode is Twitch's own.
 */
val retwitchDeletedNoticesPatch = bytecodePatch(
    name = "ReTwitch deleted notice rows",
    description = "Keeps deleted notice-style chat rows (channel points messages) readable instead of blanking them.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            var rowInterface: String? = null
            var markName: String? = null
            classDefForEach { c ->
                if (!com.android.tools.smali.dexlib2.AccessFlags.INTERFACE.isSet(c.accessFlags)) return@classDefForEach
                val shapes = c.methods.map { it.parameterTypes.map { p -> p.toString() } to it.returnType }
                val mark = c.methods.firstOrNull { it.parameterTypes.isEmpty() && it.returnType == "V" } ?: return@classDefForEach
                // The text setter: `(CharSequence)V` in 31.9.0, `(SpannedString)V` in 32.0.0.
                if (shapes.size != 2 || shapes.none { (params, ret) ->
                        ret == "V" && params in listOf(listOf("Ljava/lang/CharSequence;"), listOf("Landroid/text/SpannedString;"))
                    }
                ) return@classDefForEach
                val userIdParent = c.interfaces.any { parent ->
                    classDefByOrNull { it.type == parent }?.methods?.any { m ->
                        m.name == "getUserId" && m.returnType == "Ltv/twitch/android/models/Tuid;"
                    } == true
                }
                if (userIdParent) {
                    rowInterface = c.type
                    markName = mark.name
                }
            }
            val iface = rowInterface ?: throw PatchException("deletable chat row interface not found")
            val targets = mutableListOf<Pair<com.android.tools.smali.dexlib2.iface.ClassDef, com.android.tools.smali.dexlib2.iface.Method>>()
            classDefForEach { c ->
                if (iface !in c.interfaces) return@classDefForEach
                val mark = c.methods.firstOrNull { it.name == markName && it.parameterTypes.isEmpty() && it.returnType == "V" }
                    ?: return@classDefForEach
                // Blanking = writing null (`const/4 vA, 0x0` ... `iput-object vA, p0, <Spanned field>`);
                // a row building its own "<message deleted>" (paid pinned `t0t`) writes a fresh Spanned.
                val insns = mark.implementation?.instructions?.toList() ?: return@classDefForEach
                val blanks = insns.withIndex().any { (i, insn) ->
                    insn.opcode == Opcode.IPUT_OBJECT && insn.getReference<FieldReference>()?.type == "Landroid/text/Spanned;" &&
                        insns.take(i).any { prev ->
                            prev.opcode == Opcode.CONST_4 && (prev as com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction).narrowLiteral == 0 &&
                                (prev as OneRegisterInstruction).registerA == (insn as TwoRegisterInstruction).registerA
                        }
                }
                if (blanks) targets += c to mark
            }
            if (targets.isEmpty()) throw PatchException("no blanking row in $iface")
            for ((c, m) in targets) {
                val method = mutableClassDefBy(c).findMutableMethodOf(m)
                check(method.implementation!!.registerCount > 1) { "${c.type}: no free register" }
                method.addInstructionsWithLabels(
                    0,
                    """
                        invoke-static {p0}, Ltv/retwitch/mod/DeletedMessagesHook;->keepDeletedNotice(Ljava/lang/Object;)Z
                        move-result v0
                        if-eqz v0, :rt_twitch_blank
                        return-void
                    """.trimIndent(),
                    ExternalLabel("rt_twitch_blank", method.getInstruction(0)),
                )
            }
            println("[ReTwitch] deleted notice rows: $iface.$markName in ${targets.joinToString { it.first.type }}")
        } catch (throwable: Throwable) {
            println("[ReTwitch] deleted notice rows skipped: $throwable")
        }
    }
}
