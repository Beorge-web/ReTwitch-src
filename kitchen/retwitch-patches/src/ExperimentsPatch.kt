package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Overrides for Twitch's native Eppo feature flags. Every flag is a Kotlin object extending one
 * abstract base (31.9.0 `qgg(String key, Object default, Object fallback, c20, Z)`), and every read
 * goes through one static evaluator (`ghg.b(qgg, q8g, p8g, c20, lm1): Object`, also behind the
 * StateFlow variants). Its entry asks `ExperimentHook.enter(flag)` for an override and returns it
 * when there is one; its return reports the evaluated value to `ExperimentHook.exit` for the
 * settings page. React Native flags go through the `eppoFlagOverrides` host prop instead
 * (`RnFollowingHook.onHostProps`).
 */
val retwitchExperimentsPatch = bytecodePatch(
    name = "ReTwitch experiment overrides",
    description = "Lets the mod settings override Twitch's feature flags.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val bases = HashSet<String>()
            classDefForEach { c ->
                if (AccessFlags.ABSTRACT.isSet(c.accessFlags) && c.superclass == "Ljava/lang/Object;" &&
                    c.methods.any { m ->
                        m.name == "<init>" && m.parameterTypes.map(Any::toString).let { p ->
                            p.size == 5 && p[0] == "Ljava/lang/String;" && p[1] == "Ljava/lang/Object;" &&
                                p[2] == "Ljava/lang/Object;" && p[3].startsWith("L") && p[4] == "Z"
                        }
                    }
                ) bases += c.type
            }

            fun Method.isEvaluator(): Boolean {
                if (!AccessFlags.STATIC.isSet(accessFlags) || returnType != "Ljava/lang/Object;") return false
                val first = parameterTypes.firstOrNull()?.toString() ?: return false
                if (parameterTypes.size != 5 || first !in bases) return false
                // It runs the flag's own evaluation: a 5-argument virtual on the flag type returning Object.
                return implementation?.instructions?.any { insn ->
                    val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
                    insn.opcode == Opcode.INVOKE_VIRTUAL_RANGE && ref != null && ref.definingClass == first &&
                        ref.parameterTypes.size == 5 && ref.returnType == "Ljava/lang/Object;"
                } == true
            }

            var owner: String? = null
            classDefForEach { c ->
                if (c.methods.any { it.isEvaluator() }) {
                    require(owner == null) { "two flag evaluators: $owner, ${c.type}" }
                    owner = c.type
                }
            }
            val method = mutableClassDefBy(requireNotNull(owner) { "flag evaluator not found" })
                .methods.single { it.isEvaluator() }
            val registers = method.implementation!!.registerCount
            val flag = registers - 5
            require(flag > 0) { "no free register" }

            val returns = method.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
            require(returns.isNotEmpty()) { "evaluator has no return" }
            for (at in returns.reversed()) {
                val reg = (method.getInstruction(at) as OneRegisterInstruction).registerA
                method.addInstructions(
                    at,
                    "invoke-static/range {v$reg .. v$reg}, Ltv/retwitch/mod/ExperimentHook;->exit(Ljava/lang/Object;)V",
                )
            }
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static/range {v$flag .. v$flag}, Ltv/retwitch/mod/ExperimentHook;->enter(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v0
                    if-eqz v0, :rt_experiment_original
                    return-object v0
                """.trimIndent(),
                ExternalLabel("rt_experiment_original", method.getInstruction(0)),
            )
        } catch (throwable: Throwable) {
            println("[ReTwitch] experiment overrides skipped: $throwable")
        }
    }
}
