package com.cryptochief.processing.evm

import com.cryptochief.processing.tron.TronAddress
import com.cryptochief.processing.tron.hexToByteArray
import com.cryptochief.processing.tron.toHex
import java.math.BigInteger

/** Solidity-style ABI encoder for EVM / TRON contract calls. */
public object EvmAbi {

    @Throws(IllegalArgumentException::class)
    public fun encodeCall(signature: String, vararg args: Any?): ByteArray {
        val parsed = parseSignature(signature)
        require(parsed.types.size == args.size) {
            "signature has ${parsed.types.size} args, got ${args.size}"
        }
        val selector = selector(signature)
        val body = encodeTuple(parsed.types, args.toList())
        return selector + body
    }

    @Throws(IllegalArgumentException::class)
    public fun encodeCallHex(signature: String, vararg args: Any?): String =
        "0x" + encodeCall(signature, *args).toHex()

    public fun selector(signature: String): ByteArray {
        val canonical = canonicalSignature(signature)
        return Keccak256.hash(canonical.toByteArray(Charsets.UTF_8)).copyOfRange(0, 4)
    }

    private sealed class AbiType {
        abstract val dynamic: Boolean
        data class UInt(val bits: Int) : AbiType() { override val dynamic: Boolean = false }
        data class SInt(val bits: Int) : AbiType() { override val dynamic: Boolean = false }
        data object Address : AbiType() { override val dynamic: Boolean = false }
        data object Bool : AbiType() { override val dynamic: Boolean = false }
        data class FixedBytes(val length: Int) : AbiType() { override val dynamic: Boolean = false }
        data object DynamicBytes : AbiType() { override val dynamic: Boolean = true }
        data object Str : AbiType() { override val dynamic: Boolean = true }
        data class Array(val element: AbiType, val size: Int) : AbiType() {
            override val dynamic: Boolean = size < 0 || element.dynamic
        }
    }

    private data class ParsedSignature(val name: String, val types: List<AbiType>)

    private fun parseSignature(sig: String): ParsedSignature {
        val open = sig.indexOf('(')
        val close = sig.lastIndexOf(')')
        require(open >= 0 && close >= open) { "bad signature: \"$sig\"" }
        val name = sig.substring(0, open).trim()
        require(name.isNotEmpty()) { "signature missing name: \"$sig\"" }
        val body = sig.substring(open + 1, close).trim()
        if (body.isEmpty()) return ParsedSignature(name, emptyList())
        val types = body.split(',').map { spec ->
            val t = spec.trim().substringBefore(' ').trim()
            parseType(expandAlias(t))
        }
        return ParsedSignature(name, types)
    }

    private fun parseType(spec: String): AbiType {
        val s = spec.trim()
        require(s.isNotEmpty()) { "empty type" }
        if (s.endsWith(']')) {
            val open = s.lastIndexOf('[')
            require(open > 0) { "malformed type: \"$s\"" }
            val inner = parseType(s.substring(0, open))
            val span = s.substring(open + 1, s.length - 1)
            val size = if (span.isEmpty()) -1 else span.toIntOrNull()
                ?: throw IllegalArgumentException("bad array size \"$span\" in \"$s\"")
            require(size >= -1) { "negative array size" }
            return AbiType.Array(inner, size)
        }
        return when {
            s == "address" -> AbiType.Address
            s == "bool" -> AbiType.Bool
            s == "string" -> AbiType.Str
            s == "bytes" -> AbiType.DynamicBytes
            s.startsWith("uint") -> AbiType.UInt(intBits(s.removePrefix("uint"), "uint"))
            s.startsWith("int") -> AbiType.SInt(intBits(s.removePrefix("int"), "int"))
            s.startsWith("bytes") -> {
                val n = s.removePrefix("bytes").toIntOrNull()
                    ?: throw IllegalArgumentException("invalid fixed bytes type \"$s\"")
                require(n in 1..32) { "invalid fixed bytes width: $n" }
                AbiType.FixedBytes(n)
            }
            else -> throw IllegalArgumentException("unsupported type \"$s\"")
        }
    }

    private fun intBits(raw: String, kind: String): Int {
        if (raw.isEmpty()) return 256
        val bits = raw.toIntOrNull()
            ?: throw IllegalArgumentException("invalid $kind width \"$raw\"")
        require(bits in 8..256 && bits % 8 == 0) { "invalid $kind width $bits" }
        return bits
    }

    private fun expandAlias(t: String): String {
        if (t.endsWith(']')) {
            val open = t.lastIndexOf('[')
            return expandAlias(t.substring(0, open)) + t.substring(open)
        }
        return when (t) {
            "uint" -> "uint256"
            "int" -> "int256"
            "byte" -> "bytes1"
            else -> t
        }
    }

    private fun canonicalSignature(sig: String): String {
        val parsed = parseSignature(sig)
        val rendered = parsed.types.joinToString(",") { renderType(it) }
        return "${parsed.name}($rendered)"
    }

    private fun renderType(t: AbiType): String = when (t) {
        is AbiType.UInt -> "uint${t.bits}"
        is AbiType.SInt -> "int${t.bits}"
        AbiType.Address -> "address"
        AbiType.Bool -> "bool"
        is AbiType.FixedBytes -> "bytes${t.length}"
        AbiType.DynamicBytes -> "bytes"
        AbiType.Str -> "string"
        is AbiType.Array -> renderType(t.element) + if (t.size < 0) "[]" else "[${t.size}]"
    }

    private fun encodeTuple(types: List<AbiType>, values: List<Any?>): ByteArray {
        val tails = types.indices.map { idx -> encodeOne(types[idx], values[idx]) }
        val headSize = 32 * types.size
        val offsets = IntArray(types.size)
        var cursor = headSize
        for (i in types.indices) {
            if (types[i].dynamic) {
                offsets[i] = cursor
                cursor += tails[i].size
            }
        }
        val out = java.io.ByteArrayOutputStream(cursor)
        for (i in types.indices) {
            if (types[i].dynamic) out.write(uint256Bytes(BigInteger.valueOf(offsets[i].toLong())))
            else out.write(tails[i])
        }
        for (i in types.indices) {
            if (types[i].dynamic) out.write(tails[i])
        }
        return out.toByteArray()
    }

    private fun encodeOne(type: AbiType, value: Any?): ByteArray = when (type) {
        is AbiType.UInt -> uint256Bytes(toBigUint(value, type.bits))
        is AbiType.SInt -> int256Bytes(toBigInt(value, type.bits))
        AbiType.Address -> {
            val s = value as? String
                ?: throw IllegalArgumentException("address: want String, got ${value?.javaClass?.simpleName}")
            val addr = normaliseEvmAddress(s)
            ByteArray(12) + addr
        }
        AbiType.Bool -> {
            val b = value as? Boolean
                ?: throw IllegalArgumentException("bool: want Boolean, got ${value?.javaClass?.simpleName}")
            ByteArray(32).also { if (b) it[31] = 1 }
        }
        is AbiType.FixedBytes -> {
            val raw = toBytes(value)
            require(raw.size == type.length) {
                "bytes${type.length}: expected ${type.length} bytes, got ${raw.size}"
            }
            ByteArray(32).also { System.arraycopy(raw, 0, it, 0, raw.size) }
        }
        AbiType.DynamicBytes -> encodeDynBytes(toBytes(value))
        AbiType.Str -> {
            val s = value as? String
                ?: throw IllegalArgumentException("string: want String, got ${value?.javaClass?.simpleName}")
            encodeDynBytes(s.toByteArray(Charsets.UTF_8))
        }
        is AbiType.Array -> {
            val items = toList(value)
            if (type.size >= 0) require(items.size == type.size) {
                "fixed array T[${type.size}]: expected ${type.size} items, got ${items.size}"
            }
            val inner = List(items.size) { type.element }
            val body = encodeTuple(inner, items)
            if (type.size < 0) {
                uint256Bytes(BigInteger.valueOf(items.size.toLong())) + body
            } else {
                body
            }
        }
    }

    private fun encodeDynBytes(b: ByteArray): ByteArray {
        val pad = (32 - (b.size % 32)) % 32
        return uint256Bytes(BigInteger.valueOf(b.size.toLong())) + b + ByteArray(pad)
    }

    private fun uint256Bytes(n: BigInteger): ByteArray {
        val out = ByteArray(32)
        if (n.signum() == 0) return out
        val v = if (n.signum() < 0) n.add(BigInteger.ONE.shiftLeft(256)) else n
        val raw = v.toByteArray()
        val src = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
        System.arraycopy(src, 0, out, 32 - src.size, src.size)
        return out
    }

    private fun int256Bytes(n: BigInteger): ByteArray =
        if (n.signum() >= 0) uint256Bytes(n) else uint256Bytes(n.add(BigInteger.ONE.shiftLeft(256)))

    private fun toBigUint(v: Any?, bits: Int): BigInteger {
        val n = toBigInt(v, bits)
        require(n.signum() >= 0) { "uint$bits: negative value $n" }
        val max = BigInteger.ONE.shiftLeft(bits)
        require(n < max) { "uint$bits: value $n exceeds max" }
        return n
    }

    private fun toBigInt(v: Any?, @Suppress("UNUSED_PARAMETER") bits: Int): BigInteger = when (v) {
        is BigInteger -> v
        is Int -> BigInteger.valueOf(v.toLong())
        is Long -> BigInteger.valueOf(v)
        is Short -> BigInteger.valueOf(v.toLong())
        is Byte -> BigInteger.valueOf(v.toLong())
        is String -> {
            val s = v.trim()
            require(s.isNotEmpty()) { "empty integer string" }
            if (s.startsWith("0x", ignoreCase = true)) BigInteger(s.substring(2), 16)
            else BigInteger(s, 10)
        }
        else -> throw IllegalArgumentException("integer: unsupported type ${v?.javaClass?.simpleName}")
    }

    private fun toBytes(v: Any?): ByteArray = when (v) {
        is ByteArray -> v
        is String -> {
            val s = v.trim()
            if (s.startsWith("0x", ignoreCase = true)) s.substring(2).hexToByteArray()
            else s.toByteArray(Charsets.UTF_8)
        }
        else -> throw IllegalArgumentException("bytes: unsupported type ${v?.javaClass?.simpleName}")
    }

    private fun toList(v: Any?): List<Any?> = when (v) {
        is List<*> -> v
        is Array<*> -> v.toList()
        is IntArray -> v.toList()
        is LongArray -> v.toList()
        else -> throw IllegalArgumentException("array: unsupported type ${v?.javaClass?.simpleName}")
    }

    /** Accepts `0x…` hex, TRON `0x41`-prefixed hex, or TRON `T…` base58. Returns 20 bytes. */
    public fun normaliseEvmAddress(input: String): ByteArray {
        val s = input.trim()
        require(s.isNotEmpty()) { "address: empty" }
        if (s.length >= 30 && (s[0] == 'T' || s[0] == 't') && !s.startsWith("0x", ignoreCase = true)) {
            val hex = TronAddress.toHex(s).removePrefix("0x")
            val raw = hex.hexToByteArray()
            return when {
                raw.size == 21 && raw[0] == 0x41.toByte() -> raw.copyOfRange(1, 21)
                raw.size == 20 -> raw
                else -> throw IllegalArgumentException("address: unexpected TRON length ${raw.size}")
            }
        }
        var trimmed = s.removePrefix("0x").removePrefix("0X")
        if (trimmed.length == 42 && trimmed.startsWith("41")) trimmed = trimmed.substring(2)
        require(trimmed.length == 40) { "address: want 20 hex bytes, got ${trimmed.length} chars" }
        return trimmed.hexToByteArray()
    }
}
