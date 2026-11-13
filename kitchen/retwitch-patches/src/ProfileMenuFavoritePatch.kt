package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val OPTIONS_DIALOG = "Ltv/twitch/android/feature/profile/header/ChannelProfileOptionsDialogFragment;"

/**
 * "Add to favorites" in the native channel page's ⋮ menu (feedback idea #64; the React Native page gets it from
 * retwitch-rn.js). The menu is Compose (31.9.0 `mb00.a(state, channelName, displayName, block, confirmBlock,
 * cancelBlock, report, whisper, share, dismiss, Composer, changed)`, called by the options dialog), its rows one
 * composable `mb00.r(label, icon, onClick, Z, Z, Composer, changed, defaults)`. Right after the Share row a bridge
 * `retwitchFavoriteRow(channelName, dismiss, ..., Composer)` calls the same row with FavoriteChannels' label, icon and
 * click (a Proxy of Twitch's Function0), or nothing while the toggle is off. The dialog's entry hands its Args (the
 * channel id) to FavoriteChannels.onProfileMenu. Skipped softly.
 */
val retwitchProfileMenuFavoritePatch = bytecodePatch(
    name = "ReTwitch channel page menu favorite",
    description = "Adds a favorite row to the native channel page's ⋮ menu.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val dialog = mutableClassDefBy(OPTIONS_DIALOG)
            // The menu composable: (state, String, String, 7 × the same Function0, Composer, I)V.
            val menuRef = dialog.methods.asSequence().flatMap { it.implementation?.instructions?.asSequence().orEmpty() }
                .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                .first { ref ->
                    val p = ref.parameterTypes.map(Any::toString)
                    ref.returnType == "V" && p.size == 12 && p[1] == "Ljava/lang/String;" && p[2] == "Ljava/lang/String;" &&
                        (3..9).all { p[it] == p[3] } && p[11] == "I"
                }
            val params = menuRef.parameterTypes.map(Any::toString)
            val function0 = params[3]
            val composer = params[10]
            val menu = mutableClassDefBy(menuRef.definingClass).methods.first {
                it.name == menuRef.name && it.parameterTypes.map(Any::toString) == params
            }
            val rowParams = listOf("Ljava/lang/String;", "I", function0, "Z", "Z", composer, "I", "I")
            val code = menu.instructions.toList()
            val isRow = { i: Int ->
                val ref = (code[i] as? ReferenceInstruction)?.reference as? MethodReference
                code[i].opcode == Opcode.INVOKE_STATIC_RANGE && ref?.returnType == "V" &&
                    ref.parameterTypes.map(Any::toString) == rowParams
            }
            // The Share row: its click is the share parameter (p8), loaded just before the call.
            val first = menu.implementation!!.registerCount - params.size
            val share = code.indices.single { i ->
                isRow(i) && (code[i] as RegisterRangeInstruction).let { call ->
                    (i - 1 downTo maxOf(0, i - 12)).firstOrNull { j ->
                        (code[j] as? OneRegisterInstruction)?.registerA == call.startRegister + 2
                    }?.let { j ->
                        code[j].opcode == Opcode.MOVE_OBJECT_FROM16 && (code[j] as TwoRegisterInstruction).registerB == first + 8
                    } == true
                }
            }
            val call = code[share] as RegisterRangeInstruction
            val row = (call as ReferenceInstruction).reference as MethodReference
            val start = call.startRegister
            val defaults = (share - 1 downTo 0).firstNotNullOfOrNull { j ->
                code[j].takeIf { (it as? OneRegisterInstruction)?.registerA == start + 7 }
            }.let { (it as? NarrowLiteralInstruction)?.narrowLiteral ?: 0x18 }

            val owner = mutableClassDefBy(menuRef.definingClass)
            val bridge = ImmutableMethod(
                owner.type, "retwitchFavoriteRow",
                listOf("Ljava/lang/String;", function0, function0, "Z", "Z", composer).map { ImmutableMethodParameter(it, null, null) },
                "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value,
                null, null, MutableMethodImplementation(14),
            ).toMutable()
            bridge.addInstructionsWithLabels(
                0,
                """
                    invoke-static {p0}, Ltv/retwitch/mod/FavoriteChannels;->nativeRowLabel(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v0
                    if-eqz v0, :none
                    invoke-static {p0}, Ltv/retwitch/mod/FavoriteChannels;->nativeRowIcon(Ljava/lang/String;)I
                    move-result v1
                    const-class v2, $function0
                    invoke-static {p0, p1, v2}, Ltv/retwitch/mod/FavoriteChannels;->nativeRowAction(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;
                    move-result-object v2
                    check-cast v2, $function0
                    const/4 v3, 0x0
                    const/4 v4, 0x0
                    move-object v5, p5
                    const/4 v6, 0x0
                    const v7, $defaults
                    invoke-static/range {v0 .. v7}, ${row.definingClass}->${row.name}(${rowParams.joinToString("")})V
                    :none
                    return-void
                """.trimIndent(),
            )
            owner.methods.add(bridge)
            // After the Share row its label and icon registers are free; login and dismiss go there for the range call.
            menu.addInstructions(
                share + 1,
                """
                    move-object/from16 v$start, p1
                    move-object/from16 v${start + 1}, p9
                    invoke-static/range {v$start .. v${start + 5}}, ${owner.type}->retwitchFavoriteRow(Ljava/lang/String;$function0${function0}ZZ$composer)V
                """.trimIndent(),
            )
            dialog.methods.first { it.name == "onCreateView" }.addInstructions(
                0, "invoke-static {p0}, Ltv/retwitch/mod/FavoriteChannels;->onProfileMenu(Ljava/lang/Object;)V",
            )
            println("[ReTwitch] channel page menu favorite: ${owner.type}->${menu.name}, row ${row.name}, defaults $defaults")
        } catch (e: Exception) {
            println("[ReTwitch] channel page menu favorite skipped: $e")
        }
    }
}
