package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue

private const val HOOK = "Ltv/retwitch/mod/RnImageHook;"
private const val REACT_PROP = "Lcom/facebook/react/uimanager/annotations/ReactProp;"

/**
 * Live stream previews on React Native screens (the Following tab) refresh. The GQL preview
 * URL of a stream is constant (`static-cdn.jtvnw.net/previews-ttv/live_user_<login>-WxH.jpg`)
 * and Fresco caches by URI forever, so cards kept their first frame across pull-to-refresh and
 * restarts. RN classes are not obfuscated:
 * - `ReactImageManager.setSource(<ReactImageView>, ReadableArray)` (`@ReactProp("source")`, the
 *   only caller of the view's `setSource`; `src` goes through it too) passes the array through
 *   `RnImageHook.onSetSource`, which gives preview URIs a generation (`rt=` + a nudged `WxH`, the
 *   CDN key), new on a timer and on a refresh;
 * - `SwipeRefreshLayoutManager.setRefreshing(<view>, Z)` (`@ReactProp("refreshing")`, also
 *   reached by the `setNativeRefreshing` command) tells `RnImageHook.onRefreshing` that a
 *   refresh ended: React never re-sends an unchanged `source`, so mounted cards are re-applied;
 * - `NetworkingModule.sendRequestInternalReal` gives its body map (p5) to `RnImageHook.onRequest`:
 *   the Following pull is a JS gesture (`useStoriesShelf`), no RefreshControl, and only its GQL
 *   refetch reaches native. High register there (`.locals 24`), hence /range.
 * All are inserted at the method entry (parameter registers intact); each part is skipped on its
 * own if RN changes.
 */
val retwitchRnLivePreviewsPatch = bytecodePatch(
    name = "ReTwitch fresh live previews",
    description = "Live stream previews on React Native screens (Following) refresh instead of a frame cached forever.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        part("image source") {
            val setSource = reactProp("Lcom/facebook/react/views/image/ReactImageManager;", "setSource", "source",
                "Lcom/facebook/react/bridge/ReadableArray;")
            setSource.addInstructions(
                0,
                """
                    invoke-static {p0, p1, p2}, $HOOK->onSetSource(Ljava/lang/Object;Landroid/view/View;Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object p2
                    check-cast p2, Lcom/facebook/react/bridge/ReadableArray;
                """.trimIndent(),
            )
        }
        part("pull-to-refresh") {
            val setRefreshing = reactProp("Lcom/facebook/react/views/swiperefresh/SwipeRefreshLayoutManager;",
                "setRefreshing", "refreshing", "Z")
            setRefreshing.addInstructions(0, "invoke-static {p2}, $HOOK->onRefreshing(Z)V")
        }
        part("following refetch") {
            mutableClassDefBy("Lcom/facebook/react/modules/network/NetworkingModule;").methods
                .single { it.name == "sendRequestInternalReal" && it.parameterTypes[4].toString() == "Lcom/facebook/react/bridge/ReadableMap;" }
                .addInstructions(0, "invoke-static/range {p5 .. p5}, $HOOK->onRequest(Ljava/lang/Object;)V")
        }
    }
}

/** The `@ReactProp(name = prop)` setter `name(<view>, valueType)` of a view manager. */
private fun app.morphe.patcher.patch.BytecodePatchContext.reactProp(
    manager: String, name: String, prop: String, valueType: String,
): MutableMethod = mutableClassDefBy(manager).methods.single { m ->
    m.name == name && m.parameterTypes.size == 2 && m.parameterTypes[1].toString() == valueType &&
        m.annotations.any { a ->
            a.type == REACT_PROP && a.elements.any { it.name == "name" && (it.value as? StringEncodedValue)?.value == prop }
        }
}

private fun part(label: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        println("[ReTwitch] fresh live previews: $label skipped: $e")
    }
}
