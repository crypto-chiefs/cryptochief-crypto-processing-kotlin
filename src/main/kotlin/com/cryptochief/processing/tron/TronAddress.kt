package com.cryptochief.processing.tron

import com.cryptochief.processing.solana.Base58
import java.security.MessageDigest

/** TRON address conversions between base58 (`T…`) and 0x41-prefixed hex. */
public object TronAddress {

    @Throws(IllegalArgumentException::class)
    public fun toHex(base58Address: String): String {
        val decoded = Base58.decode(base58Address.trim())
        require(decoded.size == 25) { "TRON address: decoded length ${decoded.size}, want 25" }
        val payload = decoded.copyOfRange(0, 21)
        val sum = decoded.copyOfRange(21, 25)
        require(payload[0] == 0x41.toByte()) {
            "TRON address: leading byte 0x${payload[0].toUByte().toString(16)}, want 0x41"
        }
        val expected = sha256d(payload).copyOfRange(0, 4)
        require(expected.contentEquals(sum)) { "TRON address: checksum mismatch" }
        return "0x" + payload.toHex()
    }

    @Throws(IllegalArgumentException::class)
    public fun fromHex(hexAddress: String): String {
        val trimmed = hexAddress.trim().removePrefix("0x").removePrefix("0X")
        val raw = trimmed.hexToByteArray()
        val payload = when (raw.size) {
            20 -> ByteArray(21).also {
                it[0] = 0x41
                System.arraycopy(raw, 0, it, 1, 20)
            }
            21 -> {
                require(raw[0] == 0x41.toByte()) {
                    "TRON address: 21-byte input must start with 0x41, got 0x${raw[0].toUByte().toString(16)}"
                }
                raw
            }
            else -> throw IllegalArgumentException(
                "TRON address: want 20- or 21-byte hex, got ${raw.size} bytes",
            )
        }
        val sum = sha256d(payload).copyOfRange(0, 4)
        return Base58.encode(payload + sum)
    }

    private fun sha256d(data: ByteArray): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        val first = sha.digest(data)
        sha.reset()
        return sha.digest(first)
    }
}

internal fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(HEX[v ushr 4])
        out.append(HEX[v and 0x0F])
    }
    return out.toString()
}

internal fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "hex string must have even length, got $length" }
    val out = ByteArray(length / 2)
    for (i in indices step 2) {
        val hi = digit(this[i])
        val lo = digit(this[i + 1])
        out[i / 2] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private fun digit(c: Char): Int = when (c) {
    in '0'..'9' -> c.code - '0'.code
    in 'a'..'f' -> c.code - 'a'.code + 10
    in 'A'..'F' -> c.code - 'A'.code + 10
    else -> throw IllegalArgumentException("invalid hex character '$c'")
}

private val HEX = "0123456789abcdef".toCharArray()
