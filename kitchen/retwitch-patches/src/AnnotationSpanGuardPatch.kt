package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/**
 * Stops Twitch's inline-annotation Compose strings from crashing with
 * {@code java.util.NoSuchElementException: Array is empty.} on OEM builds where
 * a styled string resource loses its {@code Annotation} span.
 *
 * ## The crash
 *
 * A handful of Twitch screens render an inline icon from an
 * {@code <annotation image="..."/>}-styled string resource (quick-cheer page
 * title, "insufficient funds", community-points balance title, ...). Twitch's
 * `AnnotationSpanHelper` builds a `SpannableString` from the resource and the
 * caller then does, in effect:
 *
 * ```
 * const-class          Landroid/text/Annotation;
 * invoke-interface ... getSpans(II Landroid/text/Annotation;)[Object   // the styled spans
 * move-result-object   vArr
 * invoke-static    ... ArraysKt.first([Object)Object                   // assumes >= 1 span
 * move-result-object   vSpan
 * invoke-interface ... getSpanStart(vSpan)I                            // where the icon goes
 * move-result          vStart
 * // ... build an inline-content Range at [vStart, vStart + 1] ...
 * ```
 *
 * That `first()` assumes the styled resource always carries an `Annotation`
 * span. On some Huawei/Honor EMUI builds (Android 10/11) `Context.getText(id)`
 * doesn't return the framework `SpannedString` for a styled resource, so the
 * span is missing, the array is empty and `first()` throws -- an uncaught crash
 * inside Twitch's own Compose code (it never reaches our try/catch, and can't be
 * reproduced on stock Android where the span is always present). See
 * [tv.retwitch.mod.AnnotationSpanGuard] for the full analysis.
 *
 * ## The fix
 *
 * This is a whole-app structural scan (not a fingerprint on one obfuscated
 * method): every occurrence of the exact `getSpans(...Annotation).first()`
 * shape above is rewritten so that
 *
 *   * the `ArraysKt.first([Object)Object` call becomes
 *     [tv.retwitch.mod.AnnotationSpanGuard.firstOrReport] -- identical to
 *     `first()` when the array is non-empty, but reports once and returns
 *     {@code null} instead of throwing when it's empty; and
 *   * the following `getSpanStart(...)` result is clamped to `>= 0` via
 *     [tv.retwitch.mod.AnnotationSpanGuard.clampStart], so the `null` span's
 *     `-1` start can't produce an invalid `Range(-1, 0)` downstream.
 *
 * Both are no-ops on a healthy device (real span, non-negative start), so the
 * normal render path is untouched.
 *
 * ## Why match by shape, not name
 *
 * Nothing here is an obfuscatable Twitch identifier: the match keys off the
 * framework `Annotation` class, the framework `Spanned.getSpans`/`getSpanStart`
 * methods, and the stable `([Object)Object` signature of Kotlin's
 * `ArraysKt.first`. R8 renaming the enclosing methods/classes between releases
 * doesn't move this pattern. Finding zero sites fails the build loudly (per the
 * repo's fingerprint-or-die convention), so a real shape change is caught at
 * build time instead of silently no-op'ing.
 */
private const val GUARD = "Ltv/retwitch/mod/AnnotationSpanGuard;"
private const val ANNOTATION = "Landroid/text/Annotation;"

/** `getSpans(int, int, Class)[Object` -- the styled-span query. */
private fun MethodReference.isGetSpans(): Boolean =
    name == "getSpans" &&
        returnType == "[Ljava/lang/Object;" &&
        parameterTypes.map { it.toString() } == listOf("I", "I", "Ljava/lang/Class;")

/** `getSpanStart(Object)I` -- where the found span starts. */
private fun MethodReference.isGetSpanStart(): Boolean =
    name == "getSpanStart" &&
        returnType == "I" &&
        parameterTypes.size == 1

/** Kotlin `ArraysKt.first(T[])T` (obfuscated class/method, but a stable signature). */
private fun MethodReference.isArrayFirst(): Boolean =
    returnType == "Ljava/lang/Object;" &&
        parameterTypes.map { it.toString() } == listOf("[Ljava/lang/Object;")

/** Single-argument invoke encoding that also copes with a high register. */
private fun invokeStatic(register: Int): String =
    if (register < 16) {
        "invoke-static {v$register}"
    } else {
        "invoke-static/range {v$register .. v$register}"
    }

val retwitchAnnotationSpanGuardPatch = bytecodePatch(
    name = "ReTwitch annotation-span crash guard",
    description = "Fail-soft guard for Twitch's inline-annotation Compose strings so a missing " +
        "Annotation span (seen on some Huawei/Honor EMUI builds) can't crash the app with " +
        "NoSuchElementException.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        // A guarded site: the ArraysKt.first(...) call to rewrite plus the
        // getSpanStart result register to clamp, collected before mutating.
        data class Site(val firstIndex: Int, val firstReg: Int, val startMoveIndex: Int, val startReg: Int)

        var guarded = 0

        classDefForEach { classDef ->
            // Never rewrite our own extension classes.
            if (classDef.type.startsWith("Ltv/retwitch/")) return@classDefForEach

            // Cheap immutable pre-scan: only touch classes that actually have a
            // getSpans(...) query, so we never make an untouched class mutable.
            val touches = classDef.methods.any { method ->
                method.implementation?.instructions?.any {
                    (it.getReference<MethodReference>())?.isGetSpans() == true
                } == true
            }
            if (!touches) return@classDefForEach

            val mutableClass = mutableClassDefBy(classDef)
            for (method in mutableClass.methods) {
                if (method.implementation == null) continue

                val insns = method.instructions.toList()
                val sites = ArrayList<Site>()

                insns.forEachIndexed { i, insn ->
                    if (insn.opcode != Opcode.INVOKE_INTERFACE) return@forEachIndexed
                    if ((insn.getReference<MethodReference>())?.isGetSpans() != true) return@forEachIndexed

                    // The Class being queried must be Annotation. It's loaded by
                    // a const-class shortly before the getSpans call.
                    val queriesAnnotation = (i - 1 downTo maxOf(0, i - 6)).any { j ->
                        insns[j].opcode == Opcode.CONST_CLASS &&
                            insns[j].getReference<TypeReference>()?.type == ANNOTATION
                    }
                    if (!queriesAnnotation) return@forEachIndexed

                    // move-result-object vArr ; invoke-static first([Object)Object
                    if (i + 5 >= insns.size) return@forEachIndexed
                    if (insns[i + 1].opcode != Opcode.MOVE_RESULT_OBJECT) return@forEachIndexed

                    val firstInsn = insns[i + 2]
                    if (firstInsn.opcode != Opcode.INVOKE_STATIC) return@forEachIndexed
                    if ((firstInsn.getReference<MethodReference>())?.isArrayFirst() != true) return@forEachIndexed

                    // move-result-object vSpan ; getSpanStart(vSpan) ; move-result vStart
                    if (insns[i + 3].opcode != Opcode.MOVE_RESULT_OBJECT) return@forEachIndexed
                    val spanStart = insns[i + 4]
                    if (spanStart.opcode != Opcode.INVOKE_INTERFACE) return@forEachIndexed
                    if ((spanStart.getReference<MethodReference>())?.isGetSpanStart() != true) return@forEachIndexed
                    if (insns[i + 5].opcode != Opcode.MOVE_RESULT) return@forEachIndexed

                    val firstReg = when (firstInsn) {
                        is FiveRegisterInstruction -> firstInsn.registerC
                        is RegisterRangeInstruction -> firstInsn.startRegister
                        else -> return@forEachIndexed
                    }
                    val startReg = (insns[i + 5] as OneRegisterInstruction).registerA

                    sites.add(Site(i + 2, firstReg, i + 5, startReg))
                }

                if (sites.isEmpty()) continue

                // Descending order: replaceInstruction is 1:1 (no shift) and
                // each addInstructions inserts *after* the site being handled,
                // so processing high indices first keeps the lower, not-yet-
                // handled indices valid.
                for (site in sites.sortedByDescending { it.firstIndex }) {
                    method.replaceInstruction(
                        site.firstIndex,
                        "${invokeStatic(site.firstReg)}, " +
                            "$GUARD->firstOrReport([Ljava/lang/Object;)Ljava/lang/Object;",
                    )
                    method.addInstructions(
                        site.startMoveIndex + 1,
                        """
                            ${invokeStatic(site.startReg)}, $GUARD->clampStart(I)I
                            move-result v${site.startReg}
                        """.trimIndent(),
                    )
                    guarded++
                }
            }
        }

        check(guarded > 0) {
            "no getSpans(...Annotation).first() sites found to guard -- did Twitch change the " +
                "inline-annotation render shape?"
        }
    }
}
