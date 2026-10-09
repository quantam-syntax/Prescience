package com.atreides.voiceconsent

/**
 * A deliberately non-biometric avatar recipe.
 *
 * This contains visual preferences only. It must never be derived from, or
 * paired with, a face embedding, a voice embedding, a name, or a stable device
 * identifier. A peer may receive this recipe only after an explicit consent
 * approval; the peer renders the emoji locally instead of receiving an image.
 */
const val PERSONA_EMOJI_PROFILE_VERSION = 1

enum class PersonaPalette(val label: String) {
    SUNSET("Sunset"),
    OCEAN("Ocean"),
    MINT("Mint"),
    LILAC("Lilac"),
}

enum class PersonaHair(val label: String) {
    CURLY("Curly"),
    SWEEP("Sweep"),
    BOB("Bob"),
    CAP("Cap"),
}

enum class PersonaEyes(val label: String) {
    DOTS("Dots"),
    HAPPY("Happy"),
    SPARKLE("Sparkle"),
}

enum class PersonaMouth(val label: String) {
    SMILE("Smile"),
    CALM("Calm"),
    GRIN("Grin"),
}

enum class PersonaAccessory(val label: String) {
    NONE("None"),
    GLASSES("Glasses"),
    STAR("Star"),
}

data class PersonaEmojiProfile(
    val version: Int = PERSONA_EMOJI_PROFILE_VERSION,
    val palette: PersonaPalette = PersonaPalette.SUNSET,
    val hair: PersonaHair = PersonaHair.CURLY,
    val eyes: PersonaEyes = PersonaEyes.HAPPY,
    val mouth: PersonaMouth = PersonaMouth.SMILE,
    val accessory: PersonaAccessory = PersonaAccessory.NONE,
) {
    init {
        require(version == PERSONA_EMOJI_PROFILE_VERSION) { "Unsupported persona emoji version: $version" }
    }

    /** Compact, image-free payload suitable for an approved private BLE transfer. */
    fun toApprovedTransferPayload(): String = listOf(
        "v$version",
        palette.name,
        hair.name,
        eyes.name,
        mouth.name,
        accessory.name,
    ).joinToString("|")

    companion object {
        fun fromApprovedTransferPayload(payload: String): PersonaEmojiProfile? = runCatching {
            val fields = payload.split('|')
            require(fields.size == 6 && fields.first() == "v$PERSONA_EMOJI_PROFILE_VERSION")
            PersonaEmojiProfile(
                palette = PersonaPalette.valueOf(fields[1]),
                hair = PersonaHair.valueOf(fields[2]),
                eyes = PersonaEyes.valueOf(fields[3]),
                mouth = PersonaMouth.valueOf(fields[4]),
                accessory = PersonaAccessory.valueOf(fields[5]),
            )
        }.getOrNull()
    }
}
