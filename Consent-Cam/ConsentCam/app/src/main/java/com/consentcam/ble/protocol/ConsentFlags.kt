package com.consentcam.ble.protocol

object ConsentFlags {
    const val ALLOW_APPEARANCE: Int = 1 shl 0
    const val PRECISE_MATCH_AVAILABLE: Int = 1 shl 1
    const val MUTE_VOICE: Int = 1 shl 2
    const val VOICE_PROFILE_AVAILABLE: Int = 1 shl 3
    const val KNOWN_MASK: Int = ALLOW_APPEARANCE or PRECISE_MATCH_AVAILABLE or MUTE_VOICE or VOICE_PROFILE_AVAILABLE

    fun isAllow(flags: UByte): Boolean = flags.toInt() and ALLOW_APPEARANCE != 0

    fun isProtect(flags: UByte): Boolean = !isAllow(flags)

    fun hasPreciseMatch(flags: UByte): Boolean =
        flags.toInt() and PRECISE_MATCH_AVAILABLE != 0

    fun mutesVoice(flags: UByte): Boolean = flags.toInt() and MUTE_VOICE != 0

    fun hasVoiceProfile(flags: UByte): Boolean = flags.toInt() and VOICE_PROFILE_AVAILABLE != 0

    fun hasUnknownBits(flags: UByte): Boolean = flags.toInt() and KNOWN_MASK.inv() != 0
}

