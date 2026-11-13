package tv.retwitch.patches

import app.morphe.patcher.fingerprint

/**
 * Matches the `toString()` of Twitch's own `EmoteModel.Generic`-equivalent
 * data class `(id: String, token: String, assetType: <enum>, type: <enum>)`
 * via its Kotlin-generated header `"Generic(id="` (unique in the APK, survives
 * R8). Consumers only want the *class* (`.method.definingClass`) --
 * `EmoteModelReflect` resolves and calls the real 4-arg constructor
 * reflectively at runtime, picking each enum parameter's first declared
 * constant (never hardcoding an enum class or constant name).
 *
 * Twitch 31.x strips the constructor's `checkNotNullParameter` name strings
 * ("id"/"token"/"assetType"/"type") this used to be matched by.
 */
val emoteModelConstructorFingerprint = fingerprint {
    returns("Ljava/lang/String;")
    strings("Generic(id=")
}

/**
 * Matches Twitch's own `OwnerEmoteSet`-equivalent class's `toString()`
 * override (not its constructor -- that only null-checks one common-word
 * parameter, "emotes", not trustworthy alone). Kotlin data classes bake
 * the literal *source* class name into their generated `toString()`,
 * which R8 cannot strip without changing observable behaviour:
 * `"OwnerEmoteSet(emoteOwner="` here is exactly that string.
 *
 * This is one of two concrete `EmoteSet` subtypes the picker's own
 * `EmoteSet -> PickerUiModel` builder (`Lqke.X2`-equivalent, called
 * reflectively from `EmotePickerBridge`) understands -- the *other*
 * being a plainer generic one whose header text Twitch's own presenter
 * hardcodes to one of exactly two fixed strings ("Twitch Emotes" if its
 * `setId` is literally `"0"`, "Unlocked" for anything else) instead of
 * taking it from the set. This one instead carries an
 * [emoteOwnerToStringFingerprint]-typed "owner" whose
 * `displayName`/`login` becomes the section's own free-text header --
 * see `EmotePickerBridge`'s class doc for why that matters (custom
 * "7TV Global"/"BTTV Channel"/etc. section titles instead of one
 * catch-all "Unlocked" section). Used only to reach the class
 * (`.classDef`) -- `EmoteModelReflect` reflectively calls its real
 * `(EmoteOwner, String setId, List<EmoteModel> emotes)` constructor.
 */
val emoteOwnerSetToStringFingerprint = fingerprint {
    returns("Ljava/lang/String;")
    strings(
        "OwnerEmoteSet(emoteOwner=",
        ", setId=",
        ", emotes=",
    )
}

/**
 * Matches Twitch's own `EmoteOwner`-equivalent data class's `toString()`
 * override -- `(id: Int, displayName: String, login: String, profileUrl:
 * String)`, the type [emoteOwnerSetToStringFingerprint]'s "owner"
 * constructor argument expects. Only its class is needed; the real
 * `(int, String, String, String)` constructor is unambiguous (this data
 * class only ever has the one, primary constructor -- no dead
 * default-parameter overload risk like [emoteModelConstructorFingerprint]'s
 * class), so `EmoteModelReflect` just calls
 * `getConstructor(int.class, String.class, String.class, String.class)`
 * directly.
 */
val emoteOwnerToStringFingerprint = fingerprint {
    returns("Ljava/lang/String;")
    strings(
        "EmoteOwner(id=",
        ", displayName=",
        ", login=",
        ", profileUrl=",
    )
}
