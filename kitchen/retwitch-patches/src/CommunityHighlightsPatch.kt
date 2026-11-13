package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/** The highlight presenter's "show" entry, by its analytics event name. */
val communityHighlightShowFingerprint = fingerprint {
    returns("V")
    strings("community_highlight_client_impression_queued")
}

/**
 * Banners above chat (hype train, predictions, raid, shoutout, drops, gift
 * subs, shared chat): Twitch's community-highlight presenter is entered with a
 * highlight event (31.8.0 `n79.D2(h99)`) or a wrapper of it (`n79.M2(w89)`,
 * whose subclasses carry the event). Both get an early return when
 * `ChatFilters.blockHighlight(presenter, event)` says the highlight's type is
 * hidden (Thream hooks the same pair in 31.4.2: `q69.D2`/`M2`).
 * Pinned messages hidden after 30 s go through `retwitchRemoveHighlight`, a
 * bridge on the presenter that feeds Twitch's own
 * `RemoveCommunityHighlight(type, id)` (31.8.0 `u89`) to the wrapper entry.
 */
val retwitchCommunityHighlightsPatch = bytecodePatch(
    name = "ReTwitch hide banners above chat",
    description = "Optionally hides hype train, prediction, raid, shoutout, drops, gift and shared-chat banners above chat.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        val show = communityHighlightShowFingerprint.method
        if (show.parameterTypes.size != 1) throw PatchException("unexpected highlight entry ${show.parameterTypes}")
        val eventType = show.parameterTypes[0].toString()
        val presenter = communityHighlightShowFingerprint.classDef

        // Wrapper entries: single-param void methods whose param has a subclass
        // holding the highlight event.
        val wrapperTypes = HashSet<String>()
        classDefForEach { classDef ->
            // 31.8.0: a subclass of the event itself also holds an event field; that is no wrapper.
            if (classDef.superclass != null && classDef.superclass != "Ljava/lang/Object;" &&
                classDef.superclass != eventType && classDef.fields.any { it.type == eventType }
            ) {
                wrapperTypes += classDef.superclass!!
            }
        }
        val entries = presenter.methods.filter { m ->
            m.returnType == "V" && m.parameterTypes.size == 1 && m.name != "<init>" &&
                (m.parameterTypes[0].toString() == eventType || m.parameterTypes[0].toString() in wrapperTypes)
        }
        if (entries.isEmpty()) throw PatchException("no community highlight entries in ${presenter.type}")
        entries.forEach { gate(it) }

        try {
            // RemoveCommunityHighlight: the wrapper subclass built from (highlight type, String id).
            val wrapperEntry = entries.single { it.parameterTypes[0].toString() in wrapperTypes }
            val wrapperType = wrapperEntry.parameterTypes[0].toString()
            val typeTypes = mutableClassDefBy(eventType).fields.map { it.type }.filter { it != "Ljava/lang/String;" }.toSet()
            val remove = mutableListOf<String>()
            classDefForEach { c ->
                if (c.superclass == wrapperType) {
                    c.methods.filter { m ->
                        m.name == "<init>" && m.parameterTypes.size == 2 &&
                            m.parameterTypes[0].toString() in typeTypes && m.parameterTypes[1].toString() == "Ljava/lang/String;"
                    }.forEach { remove += "${c.type}-><init>(${it.parameterTypes.joinToString("")})V|${it.parameterTypes[0]}|${c.type}" }
                }
            }
            val (removeInit, typeType, removeType) = remove.single().split("|")
            val bridge = ImmutableMethod(
                presenter.type, "retwitchRemoveHighlight",
                listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/String;")
                    .map { ImmutableMethodParameter(it, null, null) },
                "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value,
                null, null, MutableMethodImplementation(4),
            ).toMutable()
            bridge.addInstructions(
                0,
                """
                    new-instance v0, $removeType
                    check-cast p1, $typeType
                    invoke-direct {v0, p1, p2}, $removeInit
                    check-cast p0, ${presenter.type}
                    invoke-virtual {p0, v0}, ${presenter.type}->${wrapperEntry.name}($wrapperType)V
                    return-void
                """.trimIndent(),
            )
            presenter.methods.add(bridge)
        } catch (e: Exception) {
            println("[ReTwitch] hide banners: timed pinned-message removal skipped: $e")
        }
    }
}

private fun gate(method: MutableMethod) {
    val impl = method.implementation ?: return
    // One scratch local at entry (locals are free before the original body runs).
    if (impl.registerCount <= method.parameterTypes.size + 1) {
        throw PatchException("${method.name}: no free local register")
    }
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static/range {p0 .. p1}, Ltv/retwitch/mod/ChatFilters;->blockHighlight(Ljava/lang/Object;Ljava/lang/Object;)Z
            move-result v0
            if-eqz v0, :rt_show_highlight
            return-void
        """.trimIndent(),
        ExternalLabel("rt_show_highlight", method.getInstruction(0)),
    )
}
