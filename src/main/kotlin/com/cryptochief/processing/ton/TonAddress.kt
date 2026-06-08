package com.cryptochief.processing.ton

import com.cryptochief.processing.tron.hexToByteArray
import com.cryptochief.processing.tron.toHex
import java.util.Base64

/** Parsed TON address: user-friendly EQ/UQ/kQ/0Q, or raw `workchain:hex`. */
public data class TonAddress(
    val workchain: Int,
    val hash: ByteArray,
    val bounceable: Boolean = true,
    val testnet: Boolean = false,
) {
    init {
        require(hash.size == 32) { "TON address hash must be 32 bytes, got ${hash.size}" }
        require(workchain in -128..127) { "TON workchain $workchain out of int8 range" }
    }

    override fun toString(): String {
        var tag: Int = if (bounceable) 0x11 else 0x51
        if (testnet) tag = tag or 0x80
        val buf = ByteArray(36)
        buf[0] = tag.toByte()
        buf[1] = workchain.toByte()
        System.arraycopy(hash, 0, buf, 2, 32)
        val crc = crc16Xmodem(buf, 0, 34)
        buf[34] = ((crc ushr 8) and 0xFF).toByte()
        buf[35] = (crc and 0xFF).toByte()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }

    public fun raw(): String = "$workchain:${hash.toHex()}"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TonAddress) return false
        return workchain == other.workchain && hash.contentEquals(other.hash) &&
            bounceable == other.bounceable && testnet == other.testnet
    }

    override fun hashCode(): Int {
        var result = workchain
        result = 31 * result + hash.contentHashCode()
        result = 31 * result + bounceable.hashCode()
        result = 31 * result + testnet.hashCode()
        return result
    }

    public companion object {
        @Throws(IllegalArgumentException::class)
        public fun parse(input: String): TonAddress {
            val s = input.trim()
            require(s.isNotEmpty()) { "empty TON address" }
            val colon = s.indexOf(':')
            return if (colon > 0) parseRaw(s, colon) else parseUserFriendly(s)
        }

        private fun parseRaw(s: String, colon: Int): TonAddress {
            val wc = s.substring(0, colon).toIntOrNull()
                ?: throw IllegalArgumentException("bad raw workchain \"${s.substring(0, colon)}\"")
            require(wc in -128..127) { "workchain $wc out of int8 range" }
            val hashHex = s.substring(colon + 1)
            require(hashHex.length == 64) { "hash hex length ${hashHex.length}, want 64" }
            return TonAddress(workchain = wc, hash = hashHex.hexToByteArray(), bounceable = true)
        }

        private fun parseUserFriendly(s: String): TonAddress {
            require(s.length == 48) { "user-friendly TON address length ${s.length}, want 48" }
            val raw = try {
                Base64.getUrlDecoder().decode(padBase64(s))
            } catch (_: IllegalArgumentException) {
                try {
                    Base64.getDecoder().decode(padBase64(s))
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("TON address: base64 decode failed", e)
                }
            }
            require(raw.size == 36) { "decoded TON address length ${raw.size}, want 36" }
            val want = crc16Xmodem(raw, 0, 34)
            val got = (raw[34].toInt() and 0xFF shl 8) or (raw[35].toInt() and 0xFF)
            require(want == got) { "TON address CRC mismatch" }
            val tag = raw[0].toInt() and 0xFF
            return TonAddress(
                workchain = raw[1].toInt(),
                hash = raw.copyOfRange(2, 34),
                bounceable = (tag and 0x40) == 0,
                testnet = (tag and 0x80) != 0,
            )
        }

        private fun padBase64(s: String): String {
            val mod = s.length % 4
            return if (mod == 0) s else s + "=".repeat(4 - mod)
        }

        private fun crc16Xmodem(data: ByteArray, off: Int, len: Int): Int {
            var crc = 0
            for (i in off until off + len) {
                crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
                repeat(8) {
                    crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x1021) else crc shl 1
                }
                crc = crc and 0xFFFF
            }
            return crc
        }
    }
}
