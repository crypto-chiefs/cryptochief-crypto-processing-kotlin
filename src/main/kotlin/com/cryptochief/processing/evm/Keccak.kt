package com.cryptochief.processing.evm

/** Legacy Keccak-256 — the variant Ethereum uses (pre-NIST SHA-3). */
internal object Keccak256 {
    private const val RATE_BYTES = 136
    private val RC = longArrayOf(
        0x0000000000000001UL.toLong(), 0x0000000000008082UL.toLong(),
        0x800000000000808aUL.toLong(), 0x8000000080008000UL.toLong(),
        0x000000000000808bUL.toLong(), 0x0000000080000001UL.toLong(),
        0x8000000080008081UL.toLong(), 0x8000000000008009UL.toLong(),
        0x000000000000008aUL.toLong(), 0x0000000000000088UL.toLong(),
        0x0000000080008009UL.toLong(), 0x000000008000000aUL.toLong(),
        0x000000008000808bUL.toLong(), 0x800000000000008bUL.toLong(),
        0x8000000000008089UL.toLong(), 0x8000000000008003UL.toLong(),
        0x8000000000008002UL.toLong(), 0x8000000000000080UL.toLong(),
        0x000000000000800aUL.toLong(), 0x800000008000000aUL.toLong(),
        0x8000000080008081UL.toLong(), 0x8000000000008080UL.toLong(),
        0x0000000080000001UL.toLong(), 0x8000000080008008UL.toLong(),
    )
    private val ROT = intArrayOf(
        0, 1, 62, 28, 27,
        36, 44, 6, 55, 20,
        3, 10, 43, 25, 39,
        41, 45, 15, 21, 8,
        18, 2, 61, 56, 14,
    )

    fun hash(input: ByteArray): ByteArray {
        val state = LongArray(25)
        var offset = 0
        while (offset + RATE_BYTES <= input.size) {
            absorbBlock(state, input, offset)
            keccakF(state)
            offset += RATE_BYTES
        }
        val tail = ByteArray(RATE_BYTES)
        System.arraycopy(input, offset, tail, 0, input.size - offset)
        tail[input.size - offset] = 0x01
        tail[RATE_BYTES - 1] = (tail[RATE_BYTES - 1].toInt() or 0x80).toByte()
        absorbBlock(state, tail, 0)
        keccakF(state)
        val out = ByteArray(32)
        for (i in 0 until 4) {
            val lane = state[i]
            for (j in 0 until 8) {
                out[i * 8 + j] = ((lane ushr (8 * j)) and 0xFF).toByte()
            }
        }
        return out
    }

    private fun absorbBlock(state: LongArray, data: ByteArray, offset: Int) {
        for (i in 0 until RATE_BYTES / 8) {
            var lane = 0L
            for (j in 0 until 8) {
                lane = lane or ((data[offset + i * 8 + j].toLong() and 0xFF) shl (8 * j))
            }
            state[i] = state[i] xor lane
        }
    }

    private fun keccakF(state: LongArray) {
        val c = LongArray(5)
        val d = LongArray(5)
        val b = LongArray(25)
        for (round in 0 until 24) {
            for (x in 0 until 5) {
                c[x] = state[x] xor state[x + 5] xor state[x + 10] xor state[x + 15] xor state[x + 20]
            }
            for (x in 0 until 5) {
                d[x] = c[(x + 4) % 5] xor rotl(c[(x + 1) % 5], 1)
            }
            for (i in 0 until 25) {
                state[i] = state[i] xor d[i % 5]
            }
            for (x in 0 until 5) {
                for (y in 0 until 5) {
                    val idx = x + 5 * y
                    val newX = y
                    val newY = (2 * x + 3 * y) % 5
                    b[newX + 5 * newY] = rotl(state[idx], ROT[idx])
                }
            }
            for (x in 0 until 5) {
                for (y in 0 until 5) {
                    val idx = x + 5 * y
                    state[idx] = b[idx] xor (b[(x + 1) % 5 + 5 * y].inv() and b[(x + 2) % 5 + 5 * y])
                }
            }
            state[0] = state[0] xor RC[round]
        }
    }

    private fun rotl(v: Long, n: Int): Long {
        val r = n and 0x3F
        if (r == 0) return v
        return (v shl r) or (v ushr (64 - r))
    }
}
