package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * Restarted streams play right away: Twitch keeps usher master playlists in an
 * in-memory cache for 5 minutes from download (31.8.0 `kro` LruCache of `iro`,
 * read in `rap.P`, analytics "manifest_cache_hit"), so a stream restarted in
 * that window kept getting the previous broadcast's dead playlists. The cache
 * read right before that hit branch goes through `PlayerHook.filterCachedManifest`,
 * which answers "not cached" while the (default-on) toggle is on.
 */
val retwitchManifestCachePatch = bytecodePatch(
    name = "ReTwitch no stale stream manifests",
    description = "Doesn't reuse cached master playlists, so a restarted stream plays the new broadcast right away.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        var owner: String? = null
        classDefForEach { c ->
            if (c.methods.any { m ->
                    m.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "manifest_cache_hit"
                    } == true
                }
            ) owner = c.type
        }
        val method = mutableClassDefBy(owner ?: throw PatchException("manifest cache reader not found")).methods.first { m ->
            m.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "manifest_cache_hit"
            } == true
        }
        val code = method.instructions.toList()
        val hit = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "manifest_cache_hit" }
        // Walk back to the null check of the cache read guarding the hit branch.
        val check = (hit downTo 0).first { code[it].opcode == Opcode.IF_EQZ }
        val read = check - 1
        val call = code[read - 1]
        val ref = (call as? ReferenceInstruction)?.reference as? MethodReference
        if (code[read].opcode != Opcode.MOVE_RESULT_OBJECT || ref == null || !ref.returnType.startsWith("L")) {
            throw PatchException("manifest cache read shape changed")
        }
        val reg = (code[read] as OneRegisterInstruction).registerA
        method.addInstructions(
            check,
            """
                invoke-static/range {v$reg .. v$reg}, Ltv/retwitch/mod/PlayerHook;->filterCachedManifest(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v$reg
                check-cast v$reg, ${ref.returnType}
            """.trimIndent(),
        )
    }
}
