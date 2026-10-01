package com.golf5.edc16flasher.usb

/** Thrown for malformed hex arguments on the Termux bridge. */
class InvalidHexException(message: String) : IllegalArgumentException(message)

/**
 * Pure (Android-free) parsing and authorization rules for [TermuxBridgeServer].
 */
object BridgeCommandPolicy {

    /** Always available: read-only / diagnostic commands. */
    val READ_ONLY_COMMANDS = setOf("PING", "VOLTAGE", "READ_EE", "READ_RAW", "DIAG", "ECU_ID")

    /** Commands that mutate adapter or bus state; require developer mode. */
    val DEVELOPER_COMMANDS = setOf(
        "WRITE_RAW", "WRITE_MASKED", "SET_BAUD", "SET_DTR", "SET_RTS",
        "FAST_INIT", "CAN_SETUP", "CAN_SEND", "CAN_RECV", "REINIT",
    )

    /** Never available, regardless of mode. */
    val DISABLED_COMMANDS = setOf("WRITE_EE")

    sealed interface Decision {
        data object Allow : Decision
        data class Deny(val response: String) : Decision
    }

    /**
     * @param exclusiveOperationActive the app is running an ECU operation; only PING is served
     */
    fun authorize(
        cmd: String,
        parts: List<String>,
        developerMode: Boolean,
        exclusiveOperationActive: Boolean,
    ): Decision {
        if (cmd == "PING") return Decision.Allow
        if (exclusiveOperationActive) return Decision.Deny("ERR_BUSY: ECU operation in progress")
        if (cmd in DISABLED_COMMANDS) return Decision.Deny("ERR_DISABLED: EEPROM writing is disabled for safety")
        if (cmd in READ_ONLY_COMMANDS) return Decision.Allow
        if (cmd == "CONTROL") {
            val reqType = parts.getOrNull(1)?.toIntOrNull(16)
                ?: return Decision.Deny("ERR_ARGS: CONTROL <reqType> <req> <value> <index> <len> [hex]")
            val isIn = (reqType and 0x80) != 0
            return if (isIn || developerMode) Decision.Allow
            else Decision.Deny("ERR_READ_ONLY: OUT CONTROL requires developer mode")
        }
        if (cmd in DEVELOPER_COMMANDS) {
            return if (developerMode) Decision.Allow
            else Decision.Deny("ERR_READ_ONLY: $cmd requires developer mode")
        }
        return Decision.Deny("ERR UNKNOWN_COMMAND $cmd")
    }

    /**
     * Strict hex parser. Accepts `AABBCC`, a single leading `0x`/`0X`, and space-separated
     * byte pairs (`AA BB CC`). Rejects odd length, non-hex characters, empty input and
     * embedded prefixes such as `AA0xBB` or `0x0xAA`.
     */
    fun parseHexStrict(input: String): ByteArray {
        var s = input.trim()
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2)
        val tokens = s.split(' ').filter { it.isNotEmpty() }
        if (tokens.size > 1 && tokens.any { it.length != 2 }) {
            throw InvalidHexException("space-separated hex must use 2-digit bytes: '$input'")
        }
        val clean = tokens.joinToString("")
        if (clean.isEmpty()) throw InvalidHexException("empty hex")
        if (clean.length % 2 != 0) throw InvalidHexException("odd-length hex: '$input'")
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[2 * i], 16)
            val lo = Character.digit(clean[2 * i + 1], 16)
            if (hi < 0 || lo < 0) throw InvalidHexException("non-hex character in '$input'")
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
