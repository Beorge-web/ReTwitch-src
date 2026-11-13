package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val RECYCLER_VIEW = "Landroidx/recyclerview/widget/RecyclerView;"
private const val LINEAR_LAYOUT_MANAGER = "Landroidx/recyclerview/widget/LinearLayoutManager;"

/**
 * Finds the (obfuscated) live-chat message-list view delegate structurally,
 * without hardcoding an R8 class name. It is the one class that:
 *   - holds a `RecyclerView` field (the chat message list),
 *   - holds a `LinearLayoutManager` field (its layout manager),
 *   - has a non-final `boolean` field (the "user scrolled up" flag), and
 *   - whose constructor sets the RecyclerView's item animator.
 *
 * That combination matches exactly the chat list delegate on current builds.
 * The animator check accepts both Twitch's original `setItemAnimator` call and
 * the `applyChatAnimator` hook the smooth-messages patch rewrites it into, so
 * the two patches resolve the same class regardless of the order they run in.
 * Returns null when it can't be resolved so callers can no-op gracefully.
 */
private fun BytecodePatchContext.findChatListClass(): ClassDef? =
    classDefByOrNull { classDef ->
        classDef.fields.any { it.type == RECYCLER_VIEW } &&
            classDef.fields.any { it.type == LINEAR_LAYOUT_MANAGER } &&
            classDef.fields.any { it.type == "Z" && !AccessFlags.FINAL.isSet(it.accessFlags) } &&
            classDef.methods.any { it.name == "<init>" && it.setsItemAnimator() }
    }

/** [findScrollListener]'s result: the listener class, its onScrolled method,
 *  and the instruction index of the chat-list-branch anchor within it. */
private class ScrollListener(val classDef: ClassDef, val onScrolled: Method, val anchorIndex: Int)

/**
 * The chat list's scroll listener: a field of [chatListClass] whose class
 * declares onScrolled: (RecyclerView, int, int) -> void *and* whose body reads
 * the chat list's own LinearLayoutManager field (the chat-list branch of the
 * shared listener). That read is required so we don't accidentally pick a
 * framework field (e.g. the RecyclerView itself, which is declared before the
 * listener) that happens to expose a same-shape method. Returns null when the
 * chat list has no LinearLayoutManager field or no matching listener.
 */
private fun BytecodePatchContext.findScrollListener(chatListClass: ClassDef): ScrollListener? {
    val chatListType = chatListClass.type
    val lmFieldName =
        chatListClass.fields.firstOrNull { it.type == LINEAR_LAYOUT_MANAGER }?.name ?: return null
    for (field in chatListClass.fields) {
        if (!field.type.startsWith("L")) continue
        val candidate = classDefByOrNull { it.type == field.type } ?: continue
        for (method in candidate.methods) {
            if (method.returnType != "V") continue
            if (method.parameters.size != 3) continue
            if (method.parameters[0].type != RECYCLER_VIEW) continue
            if (method.parameters[1].type != "I" || method.parameters[2].type != "I") continue
            val instructions = method.implementation?.instructions?.toList() ?: continue
            val anchorIndex = instructions.indexOfFirst { instruction ->
                instruction.opcode == Opcode.IGET_OBJECT &&
                    instruction.getReference<FieldReference>()?.let {
                        it.definingClass == chatListType && it.name == lmFieldName
                    } == true
            }
            if (anchorIndex >= 0) return ScrollListener(candidate, method, anchorIndex)
        }
    }
    return null
}

/** Raw `RecyclerView.setItemAnimator(...)` call (Twitch's original form). */
private fun Method.callsSetItemAnimator(): Boolean =
    implementation?.instructions?.any { instruction ->
        instruction.opcode == Opcode.INVOKE_VIRTUAL &&
            instruction.getReference<MethodReference>()?.name == "setItemAnimator"
    } == true

/**
 * Either Twitch's original `setItemAnimator` call or the `applyChatAnimator`
 * hook the smooth-messages patch rewrites it into -- so the chat list class
 * stays discoverable after that rewrite.
 */
private fun Method.setsItemAnimator(): Boolean =
    implementation?.instructions?.any { instruction ->
        val ref = instruction.getReference<MethodReference>() ?: return@any false
        (instruction.opcode == Opcode.INVOKE_VIRTUAL && ref.name == "setItemAnimator") ||
            (instruction.opcode == Opcode.INVOKE_STATIC && ref.name == "applyChatAnimator")
    } == true

/**
 * Restores "sticky" chat scrolling.
 *
 * Twitch's chat scroll listener decides, for the chat-list branch of its shared
 * `onScrolled(RecyclerView, dx, dy)` handler, whether the list is "scrolled up"
 * with the rule `isScrolling && lastVisible != lastItem`, then pushes the
 * result to the adapter (which drives the "more messages below" pill and pauses
 * autoscroll-to-end). Because it clears the state the moment the list goes
 * idle, a message arriving while you read history re-enables autoscroll and
 * snaps you back down.
 *
 * The branch compiles to:
 * ```
 *   iget-boolean vB, p0, listener->isScrolling      # boolIndex
 *   if-eqz vB, :atBottom                             # +1
 *   if-eq vLastVisible, vLastItem, :atBottom         # +2
 *   move vFlag, vOne                                 # +3  (vFlag = 1)
 *   :atBottom
 *   invoke-virtual {vAdapter, vFlag}, adapter->setScrolledUp   # +4
 * ```
 * We replace the three decision instructions (`if-eqz` / `if-eq` / `move`) with
 * a call to `LiveChatHook.scrolledUp(adapter, isScrolling, lastVisible,
 * lastItem)`, which latches on a real drag and only releases at the bottom (see
 * that method), writing its result into `vFlag`. Everything -- the register
 * numbers and the adapter -- is read out of the existing instructions, and the
 * exact opcode shape is asserted first, so on any deviation the patch logs and
 * no-ops rather than corrupting the method.
 */
val retwitchStickyScrollPatch = bytecodePatch(
    name = "ReTwitch sticky chat scroll",
    description = "Stops chat autoscroll from yanking you back down when scrolled up, toggleable in the ReTwitch menu.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val chatListClass = findChatListClass()
                ?: throw PatchException("chat list delegate not found")

            val listener = findScrollListener(chatListClass)
                ?: throw PatchException("chat scroll listener not found on ${chatListClass.type}")
            val listenerType = listener.classDef.type
            val onScrolled = listener.onScrolled

            val instructions = onScrolled.implementation!!.instructions.toList()

            // The read of the listener's own boolean field (isScrolling) after
            // the chat-list-branch anchor starts the scrolled-up decision.
            val boolIndex = (listener.anchorIndex + 1 until instructions.size).firstOrNull { i ->
                instructions[i].opcode == Opcode.IGET_BOOLEAN &&
                    instructions[i].getReference<FieldReference>()?.definingClass == listenerType
            } ?: throw PatchException("scrolling gate not found in scroll listener")

            // Assert the exact decision shape before touching it. 30.2.0:
            //   if-eqz; if-eq; move vFlag, vOne; invoke
            // 31.8.0 (flag pre-set to 1, cleared on the "not scrolled" path):
            //   if-eqz; if-eq; goto :invoke; move vFlag, vZero; invoke
            val hasGoto = boolIndex + 3 < instructions.size &&
                instructions[boolIndex + 3].opcode == Opcode.GOTO
            val moveIndex = boolIndex + if (hasGoto) 4 else 3
            val invokeIndex = moveIndex + 1
            if (invokeIndex >= instructions.size ||
                instructions[boolIndex + 1].opcode != Opcode.IF_EQZ ||
                instructions[boolIndex + 2].opcode != Opcode.IF_EQ ||
                instructions[moveIndex].opcode != Opcode.MOVE ||
                instructions[invokeIndex].opcode != Opcode.INVOKE_VIRTUAL
            ) {
                throw PatchException("unexpected scrolled-up decision shape at $boolIndex")
            }

            val isScrollingReg = (instructions[boolIndex] as OneRegisterInstruction).registerA
            val positionCompare = instructions[boolIndex + 2] as TwoRegisterInstruction
            val lastVisibleReg = positionCompare.registerA
            val lastItemReg = positionCompare.registerB
            val flagReg = (instructions[moveIndex] as TwoRegisterInstruction).registerA
            val adapterReg = (instructions[invokeIndex] as FiveRegisterInstruction).registerC

            val mutableMethod = mutableClassDefBy(listener.classDef).findMutableMethodOf(onScrolled)

            // Drop `if-eqz` / `if-eq` / [`goto`] / `move`, keep the isScrolling load, and
            // compute the flag via the hook instead.
            mutableMethod.removeInstructions(boolIndex + 1, invokeIndex - boolIndex - 1)
            mutableMethod.addInstructions(
                boolIndex + 1,
                """
                    invoke-static {v$adapterReg, v$isScrollingReg, v$lastVisibleReg, v$lastItemReg}, Ltv/retwitch/mod/LiveChatHook;->scrolledUp(Ljava/lang/Object;ZII)Z
                    move-result v$flagReg
                """.trimIndent(),
            )
        } catch (throwable: Throwable) {
            println("[ReTwitch] sticky-scroll hook skipped: $throwable")
        }
    }
}

/**
 * Restores smooth, one-by-one chat message appearance.
 *
 * The chat list delegate's constructor calls
 * `recyclerView.setItemAnimator(null)`, which disables item animations, so new
 * messages pop in and fast bursts look batched. We replace that single call
 * with `LiveChatHook.applyChatAnimator(recyclerView)`, which keeps the
 * RecyclerView's built-in DefaultItemAnimator when the toggle is on (restoring
 * older builds' per-item add animation) and otherwise re-applies the original
 * null-out (reflectively, so we never touch the obfuscated ItemAnimator type).
 *
 * Best-effort: any resolution failure logs and leaves the app untouched.
 */
val retwitchSmoothMessagesPatch = bytecodePatch(
    name = "ReTwitch smooth chat messages",
    description = "Animates new chat messages in one-by-one instead of popping them in as a batch, toggleable in the ReTwitch menu.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val chatListClass = findChatListClass()
                ?: throw PatchException("chat list delegate not found")

            val constructor = chatListClass.methods.first {
                it.name == "<init>" && it.callsSetItemAnimator()
            }
            val instructions = constructor.implementation!!.instructions.toList()

            val animatorIndex = instructions.indexOfFirst { instruction ->
                instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                    instruction.getReference<MethodReference>()?.name == "setItemAnimator"
            }
            if (animatorIndex < 0) throw PatchException("setItemAnimator call not found")

            val mutableConstructor =
                mutableClassDefBy(chatListClass).findMutableMethodOf(constructor)

            // First register of the invoke is the RecyclerView receiver; it is
            // a View subtype, so passing it to a View-typed hook param verifies.
            val register =
                mutableConstructor.getInstruction<FiveRegisterInstruction>(animatorIndex).registerC

            mutableConstructor.replaceInstruction(
                animatorIndex,
                "invoke-static {v$register}, " +
                    "Ltv/retwitch/mod/LiveChatHook;->applyChatAnimator(Landroid/view/View;)V",
            )
        } catch (throwable: Throwable) {
            println("[ReTwitch] smooth-messages hook skipped: $throwable")
        }
    }
}

/**
 * Sticky scroll for VOD chat (`Toggles.VOD_STICKY_SCROLL`).
 *
 * VOD chat (chomments) uses the same chat list delegate and adapter as live chat, but only
 * live chat sets the delegate's "paused" flag (its non-final boolean) from the scrolled-up
 * state. That flag gates every scroll to the newest message (new messages, list resized), so
 * each batch of replayed VOD messages snapped the list down whatever the scroll position.
 *
 * Each read of that flag right before a `RecyclerView` scroll call goes through
 * `LiveChatHook.holdAutoscroll(flag, list)`, and the VOD presenter (the class with the
 * `"chomment_tab_action"` analytics string) hands its adapter to `LiveChatHook.markVodAdapter`
 * right after binding it to the list.
 */
val retwitchVodStickyScrollPatch = bytecodePatch(
    name = "ReTwitch VOD chat sticky scroll",
    description = "Keeps VOD chat where you scrolled it while new messages replay, toggleable in the ReTwitch menu.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val chatList = findChatListClass() ?: throw PatchException("chat list delegate not found")
            val paused = chatList.fields.single { it.type == "Z" && !AccessFlags.FINAL.isSet(it.accessFlags) }.name

            // (class, method, index of the flag read) of every gate, and the VOD presenter's bind call.
            val gates = mutableListOf<Triple<ClassDef, Method, Int>>()
            var bind: Triple<ClassDef, Method, Int>? = null
            classDefForEach { c ->
                for (m in c.methods) {
                    val insns = m.implementation?.instructions?.toList() ?: continue
                    for (i in 0 until insns.size - 2) {
                        val ref = insns[i].getReference<FieldReference>() ?: continue
                        if (insns[i].opcode != Opcode.IGET_BOOLEAN || ref.definingClass != chatList.type || ref.name != paused) continue
                        if (insns[i + 1].opcode != Opcode.IF_NEZ) continue
                        val scrolls = (i + 2..minOf(i + 3, insns.size - 1)).any { j ->
                            insns[j].opcode == Opcode.INVOKE_VIRTUAL &&
                                insns[j].getReference<MethodReference>()?.let {
                                    it.definingClass == RECYCLER_VIEW && it.parameterTypes.map { p -> p.toString() } == listOf("I")
                                } == true
                        }
                        if (scrolls) gates += Triple(c, m, i)
                    }
                    if (insns.any { it.getReference<StringReference>()?.string == "chomment_tab_action" }) {
                        for (other in c.methods) {
                            val body = other.implementation?.instructions?.toList() ?: continue
                            val at = body.indexOfFirst { insn ->
                                insn.opcode == Opcode.INVOKE_VIRTUAL &&
                                    insn.getReference<MethodReference>()?.let {
                                        it.definingClass == chatList.type && it.parameterTypes.size == 2 && it.returnType == "V"
                                    } == true
                            }
                            if (at >= 0) bind = Triple(c, other, at)
                        }
                    }
                }
            }
            val vod = bind ?: throw PatchException("VOD chat presenter's list binding not found")
            if (gates.isEmpty()) throw PatchException("no autoscroll gate on ${chatList.type}")

            for ((c, m, i) in gates.sortedByDescending { it.third }) {
                val read = m.implementation!!.instructions.toList()[i] as TwoRegisterInstruction
                mutableClassDefBy(c).findMutableMethodOf(m).addInstructions(
                    i + 1,
                    """
                        invoke-static {v${read.registerA}, v${read.registerB}}, Ltv/retwitch/mod/LiveChatHook;->holdAutoscroll(ZLjava/lang/Object;)Z
                        move-result v${read.registerA}
                    """.trimIndent(),
                )
            }
            val (c, m, at) = vod
            val call = m.implementation!!.instructions.toList()[at] as FiveRegisterInstruction
            mutableClassDefBy(c).findMutableMethodOf(m).addInstructions(
                at + 1,
                "invoke-static {v${call.registerD}}, Ltv/retwitch/mod/LiveChatHook;->markVodAdapter(Ljava/lang/Object;)V",
            )
            println("[ReTwitch] VOD sticky scroll: ${gates.size} gates (${gates.joinToString { it.first.type }}), VOD bind ${c.type}->${m.name}")
        } catch (throwable: Throwable) {
            println("[ReTwitch] VOD sticky scroll skipped: $throwable")
        }
    }
}

/**
 * AutoMod lines are tappable (`AutoModChat`). Chat notices (system messages, a bare TextView)
 * are not bound through the message-row hook, so this hooks the chat adapter's base bind
 * (31.9.0 `r4.m(holder, position)`: `holder.u(item)`) and hands every bound holder to
 * `AutoModChat.onRowBound` right after its text is set.
 */
val retwitchAutoModRowsPatch = bytecodePatch(
    name = "ReTwitch AutoMod chat lines",
    description = "Makes AutoMod's held-message chat lines tappable (allow / deny).",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val chatList = findChatListClass() ?: throw PatchException("chat list delegate not found")
            // The list's adapter: first parameter of its (adapter, listener) binder.
            var type = chatList.methods.first {
                it.name != "<init>" && it.parameterTypes.size == 2 && it.returnType == "V"
            }.parameterTypes[0].toString()
            while (true) {
                val adapter = classDefByOrNull { it.type == type } ?: throw PatchException("bind not found up to $type")
                // onBindViewHolder: `check-cast holder` first, `invoke-virtual holder.<bind>(item)V` last.
                val bind = adapter.methods.firstOrNull { m ->
                    val insns = m.implementation?.instructions?.toList() ?: return@firstOrNull false
                    val cast = insns.firstOrNull()?.takeIf { it.opcode == Opcode.CHECK_CAST }?.getReference<TypeReference>()?.type
                    m.returnType == "V" && m.parameterTypes.size == 2 && m.parameterTypes[1].toString() == "I" && cast != null &&
                        insns.lastOrNull { it.opcode == Opcode.INVOKE_VIRTUAL }?.getReference<MethodReference>()
                            ?.let { it.definingClass == cast && it.parameterTypes.size == 1 && it.returnType == "V" } == true
                }
                if (bind != null) {
                    val insns = bind.implementation!!.instructions.toList()
                    val call = insns.indexOfLast { it.opcode == Opcode.INVOKE_VIRTUAL }
                    val holder = (insns[call] as FiveRegisterInstruction).registerC
                    val adapterClass = mutableClassDefBy(adapter)
                    val method = adapterClass.findMutableMethodOf(bind)
                    method.addInstructions(
                        call + 1,
                        "invoke-static {v$holder}, Ltv/retwitch/mod/AutoModChat;->onRowBound(Ljava/lang/Object;)V",
                    )
                    // Alternating rows (`ChatRows`): the position at entry, and the item getter as a bridge
                    // for the neighbours (the bind's first call, `getItem(position)` on the list interface).
                    val getter = insns.first { it.opcode == Opcode.INVOKE_INTERFACE }.getReference<MethodReference>()!!
                    method.addInstructions(
                        0,
                        "invoke-static {p0, p2}, Ltv/retwitch/mod/AutoModChat;->onBindStart(Ljava/lang/Object;I)V",
                    )
                    val bridge = ImmutableMethod(
                        adapter.type, "retwitchItemAt",
                        listOf("Ljava/lang/Object;", "I").map { ImmutableMethodParameter(it, null, null) },
                        "Ljava/lang/Object;", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value,
                        null, null, MutableMethodImplementation(2),
                    ).toMutable()
                    bridge.addInstructions(
                        0,
                        """
                            check-cast p0, ${getter.definingClass}
                            invoke-interface {p0, p1}, $getter
                            move-result-object p0
                            return-object p0
                        """.trimIndent(),
                    )
                    adapterClass.methods.add(bridge)
                    println("[ReTwitch] AutoMod rows: ${adapter.type}->${bind.name}")
                    break
                }
                type = adapter.superclass ?: throw PatchException("bind not found")
            }
        } catch (throwable: Throwable) {
            println("[ReTwitch] AutoMod rows skipped: $throwable")
        }
    }
}

/**
 * Chat badges follow the chat text size. Image sizes live in an enum (31.9.0 `ngp`: `Badge` 18dp,
 * getter names kept); the chat row binder gets the size in px from `kkj.g(Context, drawable)`, the
 * only `I` method reading both `getSizeDp` and `getLandscapeSizeDp`, and sizes the image from it.
 * Each return goes through `EmoteChatHook.imageSizePx` with the enum, still in its register there.
 * 32.0.0 moved the two reads into the drawable (`yn60.b()`, `c()`); the binder (`rej.g`) calls those and
 * keeps the drawable in its parameter, which then goes to the hook instead.
 */
val retwitchChatBadgeSizePatch = bytecodePatch(
    name = "ReTwitch chat badge size",
    description = "Chat badges follow the chat text size.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val sizeGetters = setOf("getSizeDp", "getLandscapeSizeDp")
            fun key(owner: String, name: String, params: List<*>) = "$owner->$name(${params.joinToString("")})"
            val reads = HashMap<String, Set<String>>()
            val all = ArrayList<Method>()
            classDefForEach { c ->
                c.methods.forEach { m ->
                    all += m
                    val calls = m.implementation?.instructions?.mapNotNull { it.getReference<MethodReference>()?.name }.orEmpty()
                    val own = sizeGetters.intersect(calls.toSet())
                    if (own.isNotEmpty()) reads[key(m.definingClass, m.name, m.parameterTypes)] = own
                }
            }
            val direct = all.filter { it.returnType == "I" && reads[key(it.definingClass, it.name, it.parameterTypes)] == sizeGetters }
            val target = direct.singleOrNull() ?: all.single { m ->
                m.returnType == "I" && m.parameterTypes.size == 2 && AccessFlags.STATIC.isSet(m.accessFlags) &&
                    m.implementation?.instructions?.flatMap { insn ->
                        insn.getReference<MethodReference>()?.let { reads[key(it.definingClass, it.name, it.parameterTypes)] }.orEmpty()
                    }?.toSet() == sizeGetters
            }
            val method = mutableClassDefBy(target.definingClass).methods.first {
                it.name == target.name && it.parameterTypes.map(Any::toString) == target.parameterTypes.map(Any::toString)
            }
            val insns = method.implementation!!.instructions.toList()
            val image = if (direct.isNotEmpty()) {
                // The enum, still in the register `getSizeDp` was called on.
                (insns.first { it.getReference<MethodReference>()?.name == "getSizeDp" } as FiveRegisterInstruction).registerC
            } else {
                // The drawable parameter (static: p1), which the method must not overwrite.
                val reg = method.implementation!!.registerCount - 1
                if (insns.any { it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == reg }) {
                    throw PatchException("${target.definingClass}->${target.name} overwrites its drawable")
                }
                reg
            }
            insns.indices.filter { insns[it].opcode == Opcode.RETURN }.reversed().forEach { i ->
                val px = (insns[i] as OneRegisterInstruction).registerA
                method.addInstructions(i, """
                    invoke-static {v$px, v$image}, Ltv/retwitch/mod/emotes/EmoteChatHook;->imageSizePx(ILjava/lang/Object;)I
                    move-result v$px
                """.trimIndent())
            }
            println("[ReTwitch] chat badge size: ${target.definingClass}->${target.name}")
        } catch (e: Exception) {
            println("[ReTwitch] chat badge size skipped: $e")
        }
    }
}

/**
 * The first message highlight matches rows by message id: the row model (`"MessageRecyclerItem(messageId="`,
 * 31.9.0 `vwp`) goes to `FirstMessageHighlight.onRowBind` at the entry of its bind (`e8d.C(vwp, Z)`, the one
 * taking it and calling the `(Context, Spanned, TextView)` text bind). Its text is rebuilt on every bind.
 */
/**
 * Twitch's image span centres chat images on the font's box, so badges hang below the text. Its draw takes
 * the y from [EmoteChatHook.imageTop], its getSize the line height from [EmoteChatHook.imageLine]; both
 * leave emotes alone. The span has no locals: getSize's start parameter, unused until overwritten, is the
 * scratch register.
 */
val retwitchBadgeBaselinePatch = bytecodePatch(
    name = "ReTwitch badges on the baseline",
    description = "Chat badges sit on the text baseline, as on the web.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            fun Method.calls() = implementation?.instructions?.mapNotNull { it.getReference<MethodReference>()?.name }.orEmpty()
            val found = ArrayList<ClassDef>()
            classDefForEach { c ->
                if (c.superclass == "Landroid/text/style/ImageSpan;" && c.methods.any { m ->
                        m.name == "draw" && m.calls().containsAll(listOf("getFontMetricsInt", "translate", "clipRect"))
                    }) {
                    found.add(c)
                }
            }
            val span = mutableClassDefBy(found.single().type)
            val draw = span.methods.first { it.name == "draw" }
            val insns = draw.implementation!!.instructions.toList()
            val translate = insns.indexOfFirst { it.getReference<MethodReference>()?.name == "translate" }
            val y = (insns[translate] as FiveRegisterInstruction).registerE
            val image = (insns.drop(translate).first { it.getReference<MethodReference>()?.name == "draw" } as FiveRegisterInstruction).registerC
            val paint = (insns.first { it.getReference<MethodReference>()?.name == "getFontMetricsInt" } as FiveRegisterInstruction).registerC
            draw.addInstructions(translate, """
                invoke-static {v$y, v$image, v$paint}, Ltv/retwitch/mod/emotes/EmoteChatHook;->imageTop(FLandroid/graphics/drawable/Drawable;Landroid/graphics/Paint;)F
                move-result v$y
            """)
            val size = span.methods.first { it.name == "getSize" }
            val p = size.implementation!!.registerCount - 6
            size.addInstructionsWithLabels(0, """
                invoke-static {v$p, v${p + 1}, v${p + 5}}, Ltv/retwitch/mod/emotes/EmoteChatHook;->imageLine(Landroid/text/style/ImageSpan;Landroid/graphics/Paint;Landroid/graphics/Paint${'$'}FontMetricsInt;)I
                move-result v${p + 3}
                if-ltz v${p + 3}, :rt_native
                return v${p + 3}
            """, ExternalLabel("rt_native", size.getInstruction(0)))
        } catch (e: Exception) {
            println("[ReTwitch] badges on the baseline skipped: $e")
        }
    }
}

/**
 * Notice rows (watch streaks, subs, announcements) load their emotes through `kkj.e`, which keeps nothing
 * of the target (31.9.0 `zjj`): normal rows hold theirs (`yjj`) in the view holder. Glide tracks targets
 * weakly and a GIF only keeps its Drawable.Callback weakly, so on the next GC, ~20 s in a live chat, the
 * target, its listener and that callback go and the animated emote freezes. The image (`ow60`) now holds
 * the target that delivered it, for as long as the row's text lives.
 */
val retwitchNoticeEmotesPatch = bytecodePatch(
    name = "ReTwitch notice emotes keep animating",
    description = "Animated emotes in watch streak, sub and announcement rows don't freeze.",
) {
    compatibleWith(twitchCompatibility)

    execute {
        try {
            val images = ArrayList<ClassDef>()
            val targets = ArrayList<ClassDef>()
            classDefForEach { c ->
                if (c.superclass == "Landroid/graphics/drawable/BitmapDrawable;" &&
                    c.fields.any { it.type == "Landroid/graphics/drawable/Drawable;" } && c.fields.any { it.type == "Ljava/lang/String;" }
                ) {
                    images.add(c)
                }
                if (c.methods.any { it.name == "onResourceReady" } && c.methods.none { it.name == "onLoadCleared" }) {
                    targets.add(c)
                }
            }
            val image = images.single { i -> targets.any { t -> t.fields.any { it.type == i.type } } }
            val target = targets.single { t -> t.fields.any { it.type == image.type } }
            val holder = target.fields.single { it.type == image.type }
            mutableClassDefBy(image.type).instanceFields.add(
                ImmutableField(
                    image.type, "retwitchTarget", "Ljava/lang/Object;", AccessFlags.PUBLIC.value, null, null, null,
                ).toMutable(),
            )
            mutableClassDefBy(target.type).methods.first { it.name == "onResourceReady" }.addInstructions(0, """
                iget-object v0, p0, ${target.type}->${holder.name}:${image.type}
                iput-object p0, v0, ${image.type}->retwitchTarget:Ljava/lang/Object;
            """)
        } catch (e: Exception) {
            println("[ReTwitch] notice emotes skipped: $e")
        }
    }
}

val retwitchNoticeBodyPatch = bytecodePatch(
    name = "ReTwitch notice message emotes",
    description = "Third-party emotes load and animate in the message of watch streak, sub and announcement rows.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val textView = "Landroid/widget/TextView;"
            val spanned = "Landroid/text/Spanned;"
            // Sets one line of a notice row (31.9.0 y770.x, in20.l): (TextView, Spanned, ...)V around Twitch's image
            // binder. A row calls it for the system line, then the message: the hook goes on the last call.
            val rows = ArrayList<Pair<ClassDef, Method>>()
            classDefForEach { c ->
                c.methods.firstOrNull { m ->
                    m.returnType == "V" && m.parameterTypes.take(2).map(Any::toString) == listOf(textView, spanned) &&
                        m.implementation?.instructions?.any {
                            val ref = it.getReference<MethodReference>()
                            ref?.returnType == "V" && ref.parameterTypes.map(Any::toString) ==
                                listOf("Landroid/content/Context;", spanned, textView)
                        } == true
                }?.let { rows.add(c to it) }
            }
            if (rows.isEmpty()) throw PatchException("no notice rows")
            for ((row, setter) in rows) {
                val isSetter = { insn: Instruction ->
                    val ref = insn.getReference<MethodReference>()
                    ref?.definingClass == row.type && ref?.name == setter.name &&
                        ref?.parameterTypes?.map(Any::toString) == setter.parameterTypes.map(Any::toString)
                }
                val method = mutableClassDefBy(row.type).methods.single { m ->
                    (m.implementation?.instructions?.count(isSetter) ?: 0) >= 2
                }
                val code = method.implementation!!.instructions.toList()
                val call = code.indexOfLast(isSetter)
                val args = code[call] as FiveRegisterInstruction
                method.addInstructions(call, """
                    invoke-static {v${args.registerE}, v${args.registerD}}, Ltv/retwitch/mod/emotes/EmoteChatHook;->onNoticeBound(Landroid/text/Spanned;Landroid/widget/TextView;)Landroid/text/Spanned;
                    move-result-object v${args.registerE}
                """)
            }
        } catch (e: Exception) {
            println("[ReTwitch] notice message emotes skipped: $e")
        }
    }
}

/**
 * The message menu's "Reply" (31.9.0 `hq4` case 12) sends `ReplyToMessageEvent(replyTargetInfo=...)` (`o46`) built
 * from the row model's `(Context) -> ReplyTargetInfo` (`vwp.m`) through the row's EventDispatcher; Twitch then opens
 * its thread tray (`ThreadsFragment`). The static `retwitchReplyEvent(row, Context)` on the row class builds that
 * event (null for rows with nothing to reply to), so ReplyThreads opens the same tray from the "Replying to" line.
 */
private fun BytecodePatchContext.replyEventBridge(row: String) {
    try {
        var event: ClassDef? = null
        classDefForEach { c ->
            val strings = c.methods.firstOrNull { it.name == "toString" }?.implementation?.instructions
                ?.mapNotNull { it.getReference<StringReference>()?.string }.orEmpty()
            if ("ReplyToMessageEvent(replyTargetInfo=" in strings) event = c
        }
        val init = event!!.methods.single { it.name == "<init>" && it.parameterTypes.size == 1 }
        val target = init.parameterTypes.single().toString()
        val rowClass = mutableClassDefBy(row)
        val getter = rowClass.methods.single {
            it.returnType == target && it.parameterTypes.map(Any::toString) == listOf("Landroid/content/Context;")
        }
        val bridge = ImmutableMethod(
            row, "retwitchReplyEvent",
            listOf(
                ImmutableMethodParameter("Ljava/lang/Object;", null, null),
                ImmutableMethodParameter("Landroid/content/Context;", null, null),
            ),
            "Ljava/lang/Object;", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value,
            null, null, MutableMethodImplementation(4),
        ).toMutable()
        bridge.addInstructions(
            0,
            """
                check-cast p0, $row
                invoke-virtual {p0, p1}, $row->${getter.name}(Landroid/content/Context;)$target
                move-result-object v0
                if-eqz v0, :none
                new-instance v1, ${event!!.type}
                invoke-direct {v1, v0}, ${event!!.type}-><init>($target)V
                return-object v1
                :none
                return-object v0
            """.trimIndent(),
        )
        rowClass.methods.add(bridge)
        println("[ReTwitch] reply event: $row->${getter.name} -> ${event!!.type}")
    } catch (e: Exception) {
        println("[ReTwitch] reply event skipped: $e")
    }
}

val retwitchFirstMessagePatch = bytecodePatch(
    name = "ReTwitch first message rows",
    description = "Ties the first message highlight to the message id.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            var row: String? = null
            val binds = ArrayList<Method>()
            classDefForEach { c ->
                val strings = c.methods.firstOrNull { it.name == "toString" }?.implementation?.instructions
                    ?.mapNotNull { it.getReference<StringReference>()?.string }.orEmpty()
                if ("MessageRecyclerItem(messageId=" in strings) row = c.type
            }
            classDefForEach { c ->
                c.methods.filterTo(binds) { m ->
                    m.returnType == "V" && m.parameterTypes.map(Any::toString) == listOf(row, "Z") &&
                        m.implementation?.instructions?.any {
                            it.getReference<MethodReference>()?.parameterTypes?.map(Any::toString) ==
                                listOf("Landroid/content/Context;", "Landroid/text/Spanned;", "Landroid/widget/TextView;")
                        } == true
                }
            }
            val bind = binds.single()
            mutableClassDefBy(bind.definingClass).methods.first {
                it.name == bind.name && it.parameterTypes.map(Any::toString) == bind.parameterTypes.map(Any::toString)
            }.addInstructions(0, "invoke-static/range {p1 .. p1}, Ltv/retwitch/mod/FirstMessageHighlight;->onRowBind(Ljava/lang/Object;)V")
            replyEventBridge(row!!)
        } catch (e: Exception) {
            println("[ReTwitch] first message rows skipped: $e")
        }
    }
}
