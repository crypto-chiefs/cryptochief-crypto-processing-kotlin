package com.cryptochief.processing.solana

import java.math.BigInteger

/** Base58 without separators or version-byte handling. */
public object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val DECODE_TABLE = IntArray(128) { -1 }.also {
        ALPHABET.forEachIndexed { idx, ch -> it[ch.code] = idx }
    }

    public fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var zeros = 0
        while (zeros < input.size && input[zeros] == 0.toByte()) zeros++
        var num = BigInteger(1, input)
        val base = BigInteger.valueOf(58)
        val out = StringBuilder()
        while (num.signum() > 0) {
            val (div, mod) = num.divideAndRemainder(base)
            out.append(ALPHABET[mod.toInt()])
            num = div
        }
        repeat(zeros) { out.append(ALPHABET[0]) }
        return out.reverse().toString()
    }

    public fun decode(input: String): ByteArray {
        require(input.isNotEmpty()) { "empty base58 string" }
        var zeros = 0
        while (zeros < input.length && input[zeros] == ALPHABET[0]) zeros++
        var num = BigInteger.ZERO
        val base = BigInteger.valueOf(58)
        for (ch in input) {
            val code = ch.code
            val v = if (code < DECODE_TABLE.size) DECODE_TABLE[code] else -1
            require(v >= 0) { "invalid base58 char '$ch'" }
            num = num.multiply(base).add(BigInteger.valueOf(v.toLong()))
        }
        val body = if (num == BigInteger.ZERO) ByteArray(0) else num.toByteArray().let {
            if (it[0] == 0.toByte() && it.size > 1) it.copyOfRange(1, it.size) else it
        }
        return ByteArray(zeros) + body
    }
}
