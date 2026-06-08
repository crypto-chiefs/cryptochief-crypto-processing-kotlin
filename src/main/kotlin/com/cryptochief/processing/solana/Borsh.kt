package com.cryptochief.processing.solana

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Borsh-typed value for Anchor instruction encoding. */
public class Borsh private constructor(private val encoder: () -> ByteArray) {
    public fun encode(): ByteArray = encoder()

    public companion object {
        public fun u8(n: Int): Borsh = Borsh {
            require(n in 0..0xFF) { "u8 out of range: $n" }
            byteArrayOf(n.toByte())
        }

        public fun u16(n: Int): Borsh = Borsh {
            require(n in 0..0xFFFF) { "u16 out of range: $n" }
            ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(n.toShort()).array()
        }

        public fun u32(n: Long): Borsh = Borsh {
            require(n in 0..0xFFFFFFFFL) { "u32 out of range: $n" }
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n.toInt()).array()
        }

        public fun u64(n: Long): Borsh = Borsh {
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(n).array()
        }

        public fun u64(n: BigInteger): Borsh = Borsh {
            require(n.signum() >= 0) { "u64 negative: $n" }
            require(n < BigInteger.ONE.shiftLeft(64)) { "u64 overflow: $n" }
            val out = ByteArray(8)
            val raw = n.toByteArray()
            for (i in raw.indices) out[i] = raw[raw.size - 1 - i]
            out
        }

        public fun i8(n: Int): Borsh = u8(n and 0xFF)
        public fun i16(n: Int): Borsh = u16(n and 0xFFFF)
        public fun i32(n: Int): Borsh = u32(n.toLong() and 0xFFFFFFFFL)
        public fun i64(n: Long): Borsh = u64(n)

        public fun u128(n: BigInteger): Borsh = Borsh {
            require(n.signum() >= 0) { "u128 negative: $n" }
            require(n < BigInteger.ONE.shiftLeft(128)) { "u128 overflow: $n" }
            val out = ByteArray(16)
            val raw = n.toByteArray()
            for (i in raw.indices) out[i] = raw[raw.size - 1 - i]
            out
        }

        public fun bool(b: Boolean): Borsh = Borsh { byteArrayOf(if (b) 1 else 0) }

        public fun string(s: String): Borsh = Borsh {
            val data = s.toByteArray(Charsets.UTF_8)
            ByteBuffer.allocate(4 + data.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(data.size).put(data).array()
        }

        public fun bytes(b: ByteArray): Borsh = Borsh {
            ByteBuffer.allocate(4 + b.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(b.size).put(b).array()
        }

        /** Anchor `[u8; N]` — no length prefix. */
        public fun fixedBytes(b: ByteArray, n: Int): Borsh = Borsh {
            require(b.size == n) { "fixedBytes: expected $n bytes, got ${b.size}" }
            b.copyOf()
        }

        /** Solana 32-byte pubkey from base58 string or raw bytes. */
        public fun pubkey(value: Any): Borsh = Borsh {
            val raw = when (value) {
                is String -> Base58.decode(value)
                is ByteArray -> value
                else -> throw IllegalArgumentException(
                    "pubkey: want String or ByteArray, got ${value.javaClass.simpleName}",
                )
            }
            require(raw.size == 32) { "pubkey: want 32 bytes, got ${raw.size}" }
            raw.copyOf()
        }

        public fun option(inner: Borsh?): Borsh = Borsh {
            if (inner == null) byteArrayOf(0) else byteArrayOf(1) + inner.encode()
        }

        public fun vec(items: List<Borsh>): Borsh = Borsh {
            val buf = java.io.ByteArrayOutputStream()
            buf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(items.size).array())
            for (it in items) buf.write(it.encode())
            buf.toByteArray()
        }

        public fun struct(vararg fields: Borsh): Borsh = Borsh {
            val buf = java.io.ByteArrayOutputStream()
            for (f in fields) buf.write(f.encode())
            buf.toByteArray()
        }
    }
}

/** Anchor instruction encoder. */
public object Anchor {

    public fun discriminator(method: String): ByteArray {
        val sum = MessageDigest.getInstance("SHA-256").digest("global:$method".toByteArray(Charsets.UTF_8))
        return sum.copyOfRange(0, 8)
    }

    public fun encodeInstruction(method: String, vararg args: Borsh): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(discriminator(method))
        for (a in args) out.write(a.encode())
        return out.toByteArray()
    }
}
