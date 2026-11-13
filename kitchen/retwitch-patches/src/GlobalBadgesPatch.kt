package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val AFTER_APP_LAUNCH = "Ltv/twitch/android/util/AfterAppLaunchObserver;"

/**
 * Global chat badges (VIP, moderator, broadcaster, Prime...) load on a cold start again.
 * Twitch loads them with one `GlobalBadges` query from the app lifecycle task (31.9.0
 * `ze1.c`, called from `onActivityStarted`; no retry). 31.8.0 ran it right away; 31.9.0
 * hands it to a gate (`kei.b(Z defer, condition, task)`, the class built from the
 * `AfterAppLaunchObserver`) that, on a cold start into the landing screen, waits for the
 * `app_launch` trace to end. With the mod's React Native home that end may never come,
 * so every global badge was dropped from chat for the whole session. The gate defers only
 * while its own flag (read and cleared) is true; that read goes through
 * `ChatCosmetics.deferLaunchTasks` (false while the default-on fix toggle is on), which runs
 * the task at once, as on a warm start. 32.0.0 inlined the gate into the lifecycle task.
 */
val retwitchGlobalBadgesPatch = bytecodePatch(
    name = "ReTwitch global chat badges",
    description = "Loads global chat badges (VIP, moderator, Prime...) right after launch instead of waiting for Twitch's launch trace.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            // The gate: built from the observer, with one boolean field ("not deferred yet").
            var flag: String? = null
            classDefForEach { c ->
                if (c.methods.any { it.name == "<init>" && it.parameterTypes.map { p -> p.toString() } == listOf(AFTER_APP_LAUNCH) }) {
                    c.fields.singleOrNull { it.type == "Z" }?.let { flag = "${c.type}->${it.name}:Z" }
                }
            }
            val field = flag ?: throw PatchException("launch task gate not found")
            // Its read-and-clear (`iget-boolean`, then `iput-boolean` of the same field), where the task is deferred
            // only when the read is true: 31.9.0 inside the gate's own `kei.b(Z, ze, bq0)`, 32.0.0 inlined into the
            // lifecycle task (`ye1`).
            val sites = ArrayList<Pair<String, Int>>()
            classDefForEach { c ->
                c.methods.forEach { m ->
                    val code = m.implementation?.instructions?.toList() ?: return@forEach
                    code.indices.filter { i ->
                        code[i].opcode == Opcode.IGET_BOOLEAN && code[i].fieldRef() == field &&
                            code.subList(i + 1, minOf(i + 3, code.size)).any { it.opcode == Opcode.IPUT_BOOLEAN && it.fieldRef() == field }
                    }.forEach { sites += "${c.type}->${m.name}${m.parameterTypes.joinToString("", "(", ")")}${m.returnType}" to it }
                }
            }
            val (where, index) = sites.single()
            val method = mutableClassDefBy(where.substringBefore("->")).methods.single {
                "${it.definingClass}->${it.name}${it.parameterTypes.joinToString("", "(", ")")}${it.returnType}" == where
            }
            val reg = (method.getInstruction(index) as TwoRegisterInstruction).registerA
            method.addInstructions(
                index + 1,
                """
                    invoke-static/range {v$reg .. v$reg}, Ltv/retwitch/mod/emotes/ChatCosmetics;->deferLaunchTasks(Z)Z
                    move-result v$reg
                """.trimIndent(),
            )
            println("[ReTwitch] global badges: launch gate $where")
        } catch (e: Exception) {
            println("[ReTwitch] global badges skipped: $e")
        }
    }
}

private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.fieldRef() =
    ((this as? ReferenceInstruction)?.reference as? FieldReference)?.let { "${it.definingClass}->${it.name}:${it.type}" }
