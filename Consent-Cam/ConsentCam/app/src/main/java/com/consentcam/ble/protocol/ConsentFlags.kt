package com.consentcam.ble.protocol

object ConsentFlags {
    const val ALLOW_APPEARANCE: Int = 1 shl 0
    const val PRECISE_MATCH_AVAILABLE: Int = 1 shl 1
    const val KNOWN_MASK: Int = ALLOW_APPEARANCE or PRECISE_MATCH_AVAILABLE

    fun isAllow(flags: UByte): Boolean = flags.toInt() and ALLOW_APPEARANCE != 0

    fun isProtect(flags: UByte): Boolean = !isAllow(flags)

    fun hasPreciseMatch(flags: UByte): Boolean =
        flags.toInt() and PRECISE_MATCH_AVAILABLE != 0

    fun hasUnknownBits(flags: UByte): Boolean = flags.toInt() and KNOWN_MASK.inv() != 0
}

