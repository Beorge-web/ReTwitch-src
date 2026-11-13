package tv.retwitch.mod;

import android.util.Log;

import java.util.NoSuchElementException;

/**
 * Fail-soft guard for Twitch's "styled-string annotation" Compose helper.
 *
 * <p><b>The crash this fixes.</b> Twitch renders a handful of inline-icon
 * strings (the quick-cheer page title, "insufficient funds", the community
 * points balance title, ...) from a {@code <annotation image="..."/>}-styled
 * string resource. Its {@code AnnotationSpanHelper} turns the resource into a
 * {@link android.text.SpannableString} and the caller then does, in effect:
 *
 * <pre>{@code
 *   Annotation a = (Annotation) s.getSpans(0, s.length(), Annotation.class).first();
 *   int start = s.getSpanStart(a);
 *   // ... place the inline image at [start, start+1] ...
 * }</pre>
 *
 * <p>That {@code first()} (Kotlin {@code ArraysKt.first}) assumes the styled
 * string always carries at least one {@code Annotation} span. On a few OEM
 * builds (seen on Huawei/Honor EMUI, Android 10/11) {@code Context.getText(id)}
 * does <em>not</em> return the framework's {@code SpannedString} for a styled
 * resource, so the annotation span is missing, the array is empty, and
 * {@code first()} throws {@link NoSuchElementException NoSuchElementException:
 * Array is empty.} -- an uncaught crash inside Twitch's own Compose code that
 * we can't try/catch from Java. (Confirmed against AOSP {@code StringBlock} /
 * {@code SpannableStringInternal}: on stock Android the span is always present,
 * so this path never fires there -- which is why it can't be reproduced on a
 * normal device.)
 *
 * <p><b>The fix.</b> {@code retwitchAnnotationSpanGuardPatch} rewrites every
 * such {@code getSpans(...Annotation).first()} call site to route the array
 * through {@link #firstOrReport(Object[])} instead. When the array is non-empty
 * this is exactly {@code first()}; when it's empty we report it once and return
 * {@code null} (fail-soft) rather than throwing. The patch also clamps the
 * following {@code getSpanStart(...)} result through {@link #clampStart(int)}:
 * {@code getSpanStart(null)} returns {@code -1}, and a {@code -1} start would
 * flow into an inline-content {@code Range(-1, 0)}; clamping to {@code 0} keeps
 * the range valid so the title renders (icon at the start) instead of crashing.
 * On a healthy device both methods are no-ops (real span, real non-negative
 * start), so the normal render path is untouched.
 *
 * <p>Called from inside a Compose composable that can recompose repeatedly, so
 * this stays cheap and reports to the crash reporter at most once per process (one report
 * carries the device/OS/ABI metadata we need to confirm the affected builds;
 * we don't want to flood the dashboard on every frame).
 */
public final class AnnotationSpanGuard {

    private static final String TAG = "RTAnnSpan";

    /** Forward the empty-array case to the crash reporter at most once per process. */
    private static volatile boolean reported;

    private AnnotationSpanGuard() {
    }

    /**
     * Drop-in replacement for Kotlin's {@code ArraysKt.first(T[])} on the result
     * of {@code Spanned.getSpans(..., Annotation.class)}: returns the first
     * element, or -- when the array is {@code null}/empty (the OEM bug above) --
     * reports it once and returns {@code null} instead of throwing.
     */
    public static Object firstOrReport(Object[] spans) {
        try {
            if (spans != null && spans.length > 0) {
                return spans[0];
            }
            // Empty: the upstream/OEM crash. Report once (device metadata tells
            // us which builds hit it), then fail soft.
            Log.w(TAG, "getSpans(Annotation) returned empty; skipping inline annotation");
            if (!reported) {
                reported = true;
                CrashReporter.report(
                        TAG,
                        "getSpans(Annotation) empty (styled-string annotation missing)",
                        new NoSuchElementException("Array is empty."));
            }
        } catch (Throwable ignored) {
            // Never let the guard itself escape into Twitch's Compose code.
        }
        return null;
    }

    /**
     * Clamp a {@code Spanned.getSpanStart(...)} result to a non-negative index.
     * A missing span yields {@code -1}, which would otherwise become an invalid
     * inline-content {@code Range(-1, 0)}; {@code 0} is a harmless fallback
     * position. A real span's start is already {@code >= 0}, so this is a no-op
     * on the normal path.
     */
    public static int clampStart(int start) {
        return start < 0 ? 0 : start;
    }
}
