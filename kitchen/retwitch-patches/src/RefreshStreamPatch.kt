package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Adds a "reload player" button to Twitch's live/VOD player-controls overlay
 * (ReTwitch's port of PurpleTV's "refresh stream"). The visible half is a pure
 * view-tree hook ([tv.retwitch.mod.ReloadHook]); the action half mirrors
 * Twitch's own native retry sequence in the stream-player presenter stack.
 *
 * Native reload (found in the IVS error handler) does three things on the main
 * thread:
 *  1. push `TheatreCoordinatorRequest.RetryVideoPlay` into `theatreRequestUpdater`;
 *  2. call `playerPresenter.r()` (re-fetch manifest / refresh stream model);
 *  3. call `playerPresenter.Z1()` (force player restart).
 *
 * Everything R8-renamed is read live at patch time. We capture the updater and
 * player presenter from the stream-player presenter's constructor, and
 * discover the request singleton + method names structurally.
 */
private const val APP_CLASS = "Ltv/twitch/android/app/consumer/TwitchApplication;"
private const val HOOK = "Ltv/retwitch/mod/ReloadHook;"
private const val IVS_EXCEPTION = "Lcom/amazonaws/ivs/player/PlayerException;"
private const val TOAST_UTIL = "Ltv/twitch/android/util/ToastUtil;"

/** (retryVideoPlayType, instanceFieldName, updaterPushMethodName, updaterInterface). */
private data class RetryPush(val type: String, val field: String, val push: String, val updaterType: String)

/** (presenterInterface, manifestRefreshMethod, playerRestartMethod). */
private data class PlayerRefresh(val presenterType: String, val refresh: String, val restart: String)

val retwitchRefreshStreamPatch = bytecodePatch(
    name = "ReTwitch reload player button",
    description = "Adds a refresh button to the player overlay that re-fetches the manifest and restarts the current stream/VOD. Toggle in the ReTwitch settings menu.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        val retry = findRetryVideoPlay()
        val playerRefresh = findPlayerRefreshMethods()

        val application = mutableClassDefByOrNull { it.type == APP_CLASS }
            ?: error("Application class $APP_CLASS not found")
        val onCreate = application.methods.single {
            it.name == "onCreate" && it.returnType == "V" && it.parameterTypes.isEmpty()
        }
        onCreate.addInstructions(
            0,
            """
                invoke-static/range {p0 .. p0}, $HOOK->install(Landroid/app/Application;)V
                sget-object v0, ${retry.type}->${retry.field}:${retry.type}
                const-string v1, "${retry.push}"
                const-string v2, "${playerRefresh.refresh}"
                const-string v3, "${playerRefresh.restart}"
                invoke-static {v0, v1, v2, v3}, $HOOK->configureReload(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
            """.trimIndent(),
        )

        // The stream-player presenter's constructor: its 2nd parameter is the
        // player-presenter interface and it takes Twitch's ToastUtil. Of its
        // three params typed as the request-updater interface (stream model /
        // player mode / theatre request), the theatre-request updater -- the
        // one RetryVideoPlay is pushed into -- is the last (30.2.0 and 31.8.0).
        // Found structurally: 31.x strips the parameter-name strings.
        val ctor = mutableClassDefByOrNull { classDef ->
            classDef.methods.any { isStreamPlayerCtor(it, playerRefresh.presenterType) }
        }?.methods?.first { isStreamPlayerCtor(it, playerRefresh.presenterType) }
            ?: error("stream-player presenter constructor (${playerRefresh.presenterType}) not found")
        val params = ctor.parameterTypes.map { it.toString() }
        val updaterIndex = params.lastIndexOf(retry.updaterType)
        check(updaterIndex >= 0) { "stream-player ctor has no ${retry.updaterType} param: $params" }
        captureParam(ctor, 1, "capturePlayerPresenter")
        captureParam(ctor, updaterIndex, "captureDispatcher")
    }
}

private fun isStreamPlayerCtor(method: com.android.tools.smali.dexlib2.iface.Method, presenterType: String): Boolean {
    val params = method.parameterTypes.map { it.toString() }
    return method.name == "<init>" && params.size > 2 && params[1] == presenterType && TOAST_UTIL in params
}

/** Hands constructor parameter [index] to the hook at method entry (params are intact there). */
private fun captureParam(
    ctor: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod,
    index: Int,
    hookMethod: String,
) {
    val params = ctor.parameterTypes.map { it.toString() }
    // p0 = this; J/D parameters take two registers.
    val register = 1 + params.take(index).sumOf { if (it == "J" || it == "D") 2 else 1 }
    ctor.addInstructions(
        0,
        "invoke-static/range {p$register .. p$register}, $HOOK->$hookMethod(Ljava/lang/Object;)V",
    )
}

private fun BytecodePatchContext.findRetryVideoPlay(): RetryPush {
    val singletonSubclassCount = HashMap<String, Int>()
    val superByType = HashMap<String, String>()
    val enumTypes = HashSet<String>()
    classDefForEach { classDef ->
        val superclass = classDef.superclass ?: return@classDefForEach
        superByType[classDef.type] = superclass
        if (superclass == "Ljava/lang/Enum;") {
            enumTypes.add(classDef.type)
            return@classDefForEach
        }
        val isSelfTypedSingleton = classDef.fields.any { it.type == classDef.type }
        if (isSelfTypedSingleton) {
            singletonSubclassCount[superclass] = (singletonSubclassCount[superclass] ?: 0) + 1
        }
    }

    val candidates = LinkedHashSet<RetryPush>()
    classDefForEach { classDef ->
        for (method in classDef.methods) {
            val insns = method.implementation?.instructions?.toList() ?: continue
            val referencesIvs = insns.any {
                (it as? ReferenceInstruction)?.reference?.toString()?.contains(IVS_EXCEPTION) == true
            }
            if (!referencesIvs) continue

            val singletonByReg = HashMap<Int, Pair<String, String>>()
            for (insn in insns) {
                when {
                    insn.opcode == Opcode.SGET_OBJECT -> {
                        val field = (insn as ReferenceInstruction).reference as? FieldReference
                        val dest = (insn as OneRegisterInstruction).registerA
                        if (field != null && field.type == field.definingClass) {
                            singletonByReg[dest] = field.definingClass to field.name
                        } else {
                            singletonByReg.remove(dest)
                        }
                    }

                    insn.opcode == Opcode.INVOKE_INTERFACE -> {
                        val ref = (insn as ReferenceInstruction).reference as? MethodReference
                        val five = insn as FiveRegisterInstruction
                        if (ref != null &&
                            five.registerCount == 2 &&
                            ref.returnType == "V" &&
                            ref.parameterTypes.map { it.toString() } == listOf("Ljava/lang/Object;")
                        ) {
                            singletonByReg[five.registerD]?.let { (type, field) ->
                                if (type !in enumTypes) candidates.add(RetryPush(type, field, ref.name, ref.definingClass))
                            }
                        }
                    }

                    insn.opcode.setsRegister() && insn is OneRegisterInstruction -> {
                        singletonByReg.remove(insn.registerA)
                    }
                }
            }
        }
    }

    check(candidates.isNotEmpty()) {
        "RetryVideoPlay push site (IVS PlayerException + singleton push) not found"
    }
    val scored = candidates.map { it to (singletonSubclassCount[superByType[it.type]] ?: 0) }
    val maxScore = scored.maxOf { it.second }
    val winners = scored.filter { it.second == maxScore }.map { it.first }.distinctBy { it.type }
    check(winners.size == 1) {
        "ambiguous RetryVideoPlay candidates (sealed-family sizes tied): $scored"
    }
    return winners.first()
}

/**
 * Finds the player-presenter interface and its two no-arg void methods that
 * Twitch's native retry tail (inside the IVS `PlayerException` handling) calls
 * back to back: manifest refresh, then restart (`xdy.r()`/`Z1()` in 30.2.0,
 * `s420.o()`/`A1()` in 31.8.0 -- names change with R8).
 */
private fun BytecodePatchContext.findPlayerRefreshMethods(): PlayerRefresh {
    var found: PlayerRefresh? = null
    classDefForEach { classDef ->
        if (found != null) return@classDefForEach
        for (method in classDef.methods) {
            val insns = method.implementation?.instructions?.toList() ?: continue
            if (insns.none {
                    (it as? ReferenceInstruction)?.reference?.toString()?.contains(IVS_EXCEPTION) == true
                }
            ) continue
            for (i in 0 until insns.size - 1) {
                val ref = noArgVoidInterfaceCall(insns[i]) ?: continue
                val next = noArgVoidInterfaceCall(insns[i + 1]) ?: continue
                if (ref.definingClass == next.definingClass) {
                    found = PlayerRefresh(ref.definingClass, ref.name, next.name)
                    return@classDefForEach
                }
            }
        }
    }
    return found ?: error("player-presenter refresh/restart call pair not found")
}

private fun noArgVoidInterfaceCall(insn: com.android.tools.smali.dexlib2.iface.instruction.Instruction): MethodReference? {
    if (insn.opcode != Opcode.INVOKE_INTERFACE) return null
    val ref = (insn as ReferenceInstruction).reference as? MethodReference ?: return null
    return if (ref.returnType == "V" && ref.parameterTypes.isEmpty()) ref else null
}
