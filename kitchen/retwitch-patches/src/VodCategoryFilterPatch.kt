package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val CHANNEL_VIDEOS_FRAGMENT = "Ltv/twitch/android/feature/profile/videos/MainVideoListForChannelFragment;"

/**
 * Category filter for a channel's videos (`Toggles.VOD_CATEGORY_FILTER`).
 *
 * Twitch's channel VOD fetcher (31.9.0 `uo80`) already sends `options: {gameIDs: [id]}` in
 * `ChannelVodsQuery` when it is created with a game id (fragment arguments, unused by the
 * channel profile). Three hooks:
 *  - the fetcher's read of that game id, right before it builds the options, goes through
 *    `VodFilter.gameId(id, fetcher)` (the category picked for the fetcher's channel);
 *  - every presenter holding a fetcher gets `retwitchReload()`, a bridge to its no-arg method
 *    that resets the fetcher and loads again (31.9.0 `dy80.A2`, `bp80`'s twin);
 *  - `MainVideoListForChannelFragment.onViewCreated` (Videos tab, name kept) hands its view to
 *    `VodFilter.attach`, which adds the category chip above the shelves.
 */
val retwitchVodCategoryFilterPatch = bytecodePatch(
    name = "ReTwitch VOD category filter",
    description = "A category filter above a channel's videos (toggle, off by default).",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            var query: String? = null
            classDefForEach { c ->
                if (c.methods.any { m -> m.implementation?.instructions?.any { it.getReference<StringReference>()?.string == "ChannelVodsQuery" } == true }) {
                    query = c.type
                }
            }
            val queryType = query ?: throw PatchException("ChannelVodsQuery not found")

            // The fetcher: builds the query after `iget-object vA, p0, <own String field>; if-eqz vA; new-instance <options>`.
            var gate: Triple<ClassDef, Method, Int>? = null
            classDefForEach { c ->
                for (m in c.methods) {
                    val insns = m.implementation?.instructions?.toList() ?: continue
                    val build = insns.indexOfFirst { it.opcode == Opcode.NEW_INSTANCE && it.getReference<TypeReference>()?.type == queryType }
                    if (build < 0) continue
                    for (i in 0 until build - 2) {
                        val ref = insns[i].getReference<FieldReference>() ?: continue
                        if (insns[i].opcode == Opcode.IGET_OBJECT && ref.definingClass == c.type && ref.type == "Ljava/lang/String;" &&
                            insns[i + 1].opcode == Opcode.IF_EQZ && insns[i + 2].opcode == Opcode.NEW_INSTANCE
                        ) {
                            if (gate != null) throw PatchException("two game id reads: ${gate!!.first.type}, ${c.type}")
                            gate = Triple(c, m, i)
                        }
                    }
                }
            }
            val (fetcher, buildMethod, readIndex) = gate ?: throw PatchException("fetcher's game id read not found")
            val read = buildMethod.implementation!!.instructions.toList()[readIndex] as TwoRegisterInstruction
            mutableClassDefBy(fetcher).findMutableMethodOf(buildMethod).addInstructions(
                readIndex + 1,
                """
                    invoke-static {v${read.registerA}, v${read.registerB}}, Ltv/retwitch/mod/VodFilter;->gameId(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;
                    move-result-object v${read.registerA}
                """.trimIndent(),
            )

            // Presenters: a fetcher field and a ()V method that resets it (`()V`) and fetches (one argument).
            val reloads = mutableListOf<Pair<String, String>>()
            classDefForEach { c ->
                if (c.fields.none { it.type == fetcher.type }) return@classDefForEach
                val reload = c.methods.firstOrNull { m ->
                    if (m.parameterTypes.isNotEmpty() || m.returnType != "V" || AccessFlags.STATIC.isSet(m.accessFlags)) return@firstOrNull false
                    val calls = m.implementation?.instructions?.mapNotNull { insn ->
                        insn.getReference<MethodReference>()?.takeIf { insn.opcode == Opcode.INVOKE_VIRTUAL && it.definingClass == fetcher.type }
                    } ?: return@firstOrNull false
                    calls.any { it.parameterTypes.isEmpty() && it.returnType == "V" } && calls.any { it.parameterTypes.size == 1 }
                } ?: return@classDefForEach
                reloads += c.type to reload.name
            }
            if (reloads.isEmpty()) throw PatchException("no presenter reloads ${fetcher.type}")
            for ((owner, name) in reloads) {
                val bridge = ImmutableMethod(
                    owner, "retwitchReload", emptyList(), "V",
                    AccessFlags.PUBLIC.value or AccessFlags.SYNTHETIC.value,
                    null, null, MutableMethodImplementation(1),
                ).toMutable()
                bridge.addInstructions(
                    0,
                    """
                        invoke-virtual {p0}, $owner->$name()V
                        return-void
                    """.trimIndent(),
                )
                mutableClassDefBy(owner).methods.add(bridge)
            }

            mutableClassDefBy(CHANNEL_VIDEOS_FRAGMENT).methods.single {
                it.name == "onViewCreated" && it.parameterTypes.size == 2
            }.addInstructions(
                0,
                "invoke-static {p1, p0}, Ltv/retwitch/mod/VodFilter;->attach(Landroid/view/View;Ljava/lang/Object;)V",
            )
            println("[ReTwitch] VOD category filter: fetcher ${fetcher.type}->${buildMethod.name}, reloads $reloads")
        } catch (throwable: Throwable) {
            println("[ReTwitch] VOD category filter skipped: $throwable")
        }
    }
}
