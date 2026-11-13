package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.w3c.dom.Element

/**
 * Channel points balance under the icon in landscape (toggle, off by default). The
 * points button presenter state (31.9.0 `qb9`, `"State(buttonState="`, `", isLandscape="`)
 * feeds the view state's `shouldHideBalance = isKeyboardOpen || (isLandscape && !drawerOpen)`;
 * `isLandscape` is read nowhere else, so at constructor entry it goes through
 * `UiHider.pointsLandscape`. Skipped softly.
 */
/**
 * GONE templates holding the button's constraints with the balance under the icon: `UiHider.stackPoints`
 * takes their layout params in landscape. Not `layout-land`: the activity handles rotation itself, so the
 * button stays as inflated. Not set from the mod: `ConstraintLayout.LayoutParams` fields are renamed.
 */
private val pointsUnderIconPatch = resourcePatch {
    execute {
        try {
            val path = "res/layout/community_points_button_view.xml"
            val app = Regex("""(\w+):layout_constraintTop_toTopOf""").find(get(path).readText())!!.groupValues[1]
            document(path).use { document ->
                val all = document.getElementsByTagName("*")
                fun view(id: String) = (0 until all.length).map { all.item(it) as Element }
                    .single { it.getAttribute("android:id") == "@id/$id" }
                fun template(id: String, stack: Element.() -> Unit) {
                    val own = view("community_points_$id")
                    val copy = document.createElement("View")
                    for (i in 0 until own.attributes.length) {
                        val attribute = own.attributes.item(i)
                        if (":layout_" in attribute.nodeName) copy.setAttribute(attribute.nodeName, attribute.nodeValue)
                    }
                    copy.setAttribute("android:id", "@+id/rt_stacked_$id")
                    copy.setAttribute("android:visibility", "gone")
                    copy.stack()
                    own.parentNode.appendChild(copy)
                }
                val icon = "@id/community_points_icon"
                template("icon_animation") {
                    setAttribute("$app:layout_constraintTop_toTopOf", icon)
                    setAttribute("$app:layout_constraintBottom_toBottomOf", icon)
                    setAttribute("$app:layout_constraintEnd_toEndOf", "parent")
                }
                template("icon") {
                    removeAttribute("$app:layout_constraintBottom_toBottomOf")
                    setAttribute("$app:layout_constraintBottom_toTopOf", "@id/community_points_total")
                    setAttribute("$app:layout_constraintVertical_chainStyle", "packed")
                }
                template("total") {
                    removeAttribute("$app:layout_constraintStart_toEndOf")
                    removeAttribute("$app:layout_constraintTop_toTopOf")
                    removeAttribute("android:layout_marginStart")
                    setAttribute("$app:layout_constraintStart_toStartOf", "parent")
                    setAttribute("$app:layout_constraintTop_toBottomOf", icon)
                }
            }
            println("[ReTwitch] points under icon: templates in $path")
        } catch (e: Exception) {
            println("[ReTwitch] points under icon skipped: $e")
        }
    }
}

/**
 * Drawers under the chat input have no clickable root, unlike `community_points_container`: a tap on an
 * empty spot fell through to the home feed behind the theatre and opened a stream there. Seen on the
 * rewards header beside "⋮" and on an opened prediction (its layouts have bare roots too), so the host of
 * every drawer (`chat_drawer_container`) eats such taps, and the rewards root for the vertical theatre,
 * which hosts it elsewhere. Children still get taps first. The "Live with:" strip of a shared stream
 * (`guest_star_metadata_bar_layout`) under the player has the same bare root.
 */
private val rewardsTapThroughPatch = resourcePatch {
    execute {
        for (name in listOf("chat_drawer_container", "reward_layout_container", "guest_star_metadata_bar_layout")) {
            try {
                document("res/layout/$name.xml").use { document ->
                    document.documentElement.setAttribute("android:clickable", "true")
                }
                println("[ReTwitch] $name: clickable root")
            } catch (e: Exception) {
                println("[ReTwitch] $name tap-through skipped: $e")
            }
        }
    }
}

val retwitchPointsBalancePatch = bytecodePatch(
    name = "ReTwitch points balance in landscape",
    description = "Optionally shows the channel points balance in fullscreen.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch, pointsUnderIconPatch, rewardsTapThroughPatch)

    execute {
        try {
            val found = ArrayList<ClassDef>()
            classDefForEach { c ->
                val strings = c.methods.firstOrNull { it.name == "toString" }?.implementation?.instructions
                    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.orEmpty()
                if ("State(buttonState=" in strings && ", isLandscape=" in strings && ", isKeyboardOpen=" in strings) {
                    found += c
                }
            }
            val state = found.singleOrNull() ?: throw PatchException("points state: ${found.size} matches")
            val ctor = mutableClassDefBy(state.type).methods.single {
                it.name == "<init>" && it.parameterTypes.size == 7 && it.parameterTypes[1].toString() == "Z"
            }
            // p0 = this (not touched before Object.<init>), p1 = buttonState, p2 = isLandscape.
            ctor.addInstructions(
                0,
                """
                    invoke-static {p2}, Ltv/retwitch/mod/UiHider;->pointsLandscape(Z)Z
                    move-result p2
                """.trimIndent(),
            )
            println("[ReTwitch] points balance: ${state.type}")
        } catch (e: Exception) {
            println("[ReTwitch] points balance skipped: $e")
        }
    }
}
