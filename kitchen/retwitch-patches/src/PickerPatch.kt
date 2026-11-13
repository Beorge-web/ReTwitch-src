package tv.retwitch.patches

import app.morphe.patcher.fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/**
 * Matches Twitch's shared emote-grid view-wiring helper (historically
 * `Lwie;->y(LayoutInflater, View root, Context, int idToFind, <enum>,
 * <builder>): <viewHolder>`). Identified by the null-check debug string
 * `"emoteWidth"`, which a full-APK scan shows appears as a `const-string`
 * in exactly one method - combined with `"context"`/`"findViewById(...)"`
 * (the other two null-check/utility strings in the same method) to keep it
 * unambiguous even if some future build introduces another `"emoteWidth"`
 * elsewhere.
 *
 * This helper is the switch-free companion to the big `packed-switch`
 * view-delegate factory (`bv.a()` in one decode) that actually inflates
 * `res/layout/emote_picker.xml`. We hook *here*, not there, on purpose: the
 * factory is a single method R8 packs many unrelated inflation branches
 * into behind a `packed-switch`, and splicing into a switch-payload-bearing
 * method once caused a runtime `VerifyError` that blanked the whole picker
 * (see the incident note in `EmotePickerBridge`). This helper has no switch.
 *
 * `p1` (2nd parameter, `Landroid/view/View;`) is the freshly-inflated root
 * view handed in - for the emote picker that's the `emote_picker.xml`
 * `ConstraintLayout`. The helper is shared across several emote grids
 * (picker, subscriber-emote upsell, ...), so `PickerHook` self-filters to the
 * real picker by looking for its `@id/emote_picker` content frame and
 * no-ops on anything else.
 */
val pickerViewWiringFingerprint = fingerprint {
    // The static (inflater, root View, Context, id: Int, <enum>, <builder>)
    // view-wiring helper: unique by this shape in 30.2.0 and 31.8.0 (31.x
    // strips the "context"/"emoteWidth" parameter-name strings; it also has a
    // 5-param overload without the builder, excluded by the size check).
    custom { method, _ ->
        val p = method.parameterTypes.map { it.toString() }
        p.size == 6 && p[0] == "Landroid/view/LayoutInflater;" && p[1] == "Landroid/view/View;" &&
            p[2] == "Landroid/content/Context;" && p[3] == "I"
    }
}

val retwitchPickerButtonPatch = bytecodePatch(
    name = "ReTwitch emote picker provider buttons",
    description = "Adds 7TV/BTTV/FFZ quick-jump buttons to the emote picker's bottom bar that scroll to the matching ReTwitch emote sections.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        // Static method with 6 params: p0 inflater, p1 root View, p2
        // Context, p3 id, p4 enum, p5 builder. This method declares ~19
        // locals, which pushes its parameter registers well past v15, so
        // the plain 4-bit `invoke-static {p1}` encoding can't address p1 --
        // the /range form (any register count) is required. p1 is live at
        // method entry, before any of the helper's own code runs.
        val method = pickerViewWiringFingerprint.method
        method.addInstructions(
            0,
            """
                invoke-static/range {p1 .. p1}, Ltv/retwitch/mod/PickerHook;->onContainerCreated(Landroid/view/View;)V
            """.trimIndent(),
        )
    }
}
