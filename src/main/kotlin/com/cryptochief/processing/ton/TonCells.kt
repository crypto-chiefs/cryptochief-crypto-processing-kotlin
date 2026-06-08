package com.cryptochief.processing.ton

import java.math.BigInteger
import java.util.zip.CRC32C

/** TON cell builder. */
public class CellBuilder {
    private var bits: ULong = 0u
    private var bitLen: Int = 0
    private val bytes: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
    private val refs: MutableList<Cell> = mutableListOf()

    public fun storeUInt(value: ULong, bits: Int): CellBuilder = apply {
        require(bits in 0..64) { "storeUInt: bits must be in 0..64, got $bits" }
        if (bits == 0) return@apply
        for (i in bits - 1 downTo 0) {
            storeBit(((value shr i) and 1u).toInt() != 0)
        }
    }

    public fun storeUInt(value: Long, bits: Int): CellBuilder = storeUInt(value.toULong(), bits)
    public fun storeUInt(value: Int, bits: Int): CellBuilder = storeUInt(value.toULong(), bits)

    public fun storeBit(b: Boolean): CellBuilder = apply {
        bitLen++
        bits = bits shl 1
        if (b) bits = bits or 1u
        if (bitLen % 8 == 0) {
            bytes.write((bits and 0xFFu).toInt())
            bits = 0u
        }
    }

    public fun storeCoins(amount: BigInteger): CellBuilder = apply {
        require(amount.signum() >= 0) { "storeCoins: negative amount $amount" }
        val raw = if (amount.signum() == 0) ByteArray(0) else amount.toByteArray().let {
            if (it[0] == 0.toByte() && it.size > 1) it.copyOfRange(1, it.size) else it
        }
        require(raw.size <= 15) { "coins value too large: ${raw.size} bytes" }
        storeUInt(raw.size.toLong(), 4)
        for (b in raw) storeUInt((b.toInt() and 0xFF).toLong(), 8)
    }

    public fun storeAddress(address: TonAddress?): CellBuilder = apply {
        if (address == null) {
            storeUInt(0, 2)
            return@apply
        }
        storeUInt(2, 2)
        storeBit(false)
        storeUInt((address.workchain.toLong() and 0xFF), 8)
        for (b in address.hash) storeUInt((b.toInt() and 0xFF).toLong(), 8)
    }

    public fun storeMaybeRef(child: Cell?): CellBuilder = apply {
        if (child == null) storeBit(false)
        else {
            storeBit(true)
            storeRef(child)
        }
    }

    public fun storeRef(child: Cell): CellBuilder = apply {
        require(refs.size < 4) { "cell can hold at most 4 refs" }
        refs.add(child)
    }

    public fun storeStringSnake(text: String): CellBuilder = apply {
        val data = text.toByteArray(Charsets.UTF_8)
        for ((idx, b) in data.withIndex()) {
            if (bitLen + 8 > MAX_BITS) {
                val tail = CellBuilder()
                tail.storeStringSnake(String(data, idx, data.size - idx, Charsets.UTF_8))
                storeRef(tail.endCell())
                return@apply
            }
            storeUInt((b.toInt() and 0xFF).toLong(), 8)
        }
    }

    public fun endCell(): Cell {
        if (bitLen % 8 != 0) {
            val pad = 8 - (bitLen % 8)
            val padded = bits shl pad
            bytes.write((padded and 0xFFu).toInt())
        }
        return Cell(bytes.toByteArray(), bitLen, refs.toList())
    }

    private companion object {
        const val MAX_BITS = 1023
    }
}

/** Immutable TON cell. */
public class Cell internal constructor(
    public val data: ByteArray,
    public val bitLength: Int,
    public val refs: List<Cell>,
) {
    public fun toBoc(hasIdx: Boolean = false, hasCrc32C: Boolean = true): ByteArray =
        BocSerializer.serialize(this, hasIdx, hasCrc32C)
}

internal object BocSerializer {
    private const val BOC_MAGIC = 0xB5EE9C72.toInt()

    fun serialize(root: Cell, hasIdx: Boolean, hasCrc32c: Boolean): ByteArray {
        val flat = mutableListOf<Cell>()
        val indexMap = HashMap<Cell, Int>()
        fun visit(c: Cell) {
            if (indexMap.containsKey(c)) return
            for (r in c.refs) visit(r)
            indexMap[c] = flat.size
            flat.add(c)
        }
        visit(root)
        val order = flat.reversed()
        val orderMap = HashMap<Cell, Int>()
        order.forEachIndexed { idx, c -> orderMap[c] = idx }
        val cellsCount = order.size
        val cellSizeBytes = bytesNeeded(cellsCount.toLong())

        val cellBlobs = order.map { encodeCell(it, orderMap, cellSizeBytes) }
        val totalCellBytes = cellBlobs.sumOf { it.size }
        val offsetSizeBytes = bytesNeeded(totalCellBytes.toLong())

        val out = java.io.ByteArrayOutputStream()
        out.writeInt(BOC_MAGIC)
        var flags = 0
        if (hasIdx) flags = flags or 0x80
        if (hasCrc32c) flags = flags or 0x40
        flags = flags or (cellSizeBytes and 0x07)
        out.write(flags)
        out.write(offsetSizeBytes)
        writeUint(out, cellsCount.toLong(), cellSizeBytes)
        writeUint(out, 1, cellSizeBytes)
        writeUint(out, 0, cellSizeBytes)
        writeUint(out, totalCellBytes.toLong(), offsetSizeBytes)
        writeUint(out, 0, cellSizeBytes)
        if (hasIdx) {
            var cursor = 0
            for (blob in cellBlobs) {
                cursor += blob.size
                writeUint(out, cursor.toLong(), offsetSizeBytes)
            }
        }
        for (blob in cellBlobs) out.write(blob)
        if (hasCrc32c) {
            val crc = CRC32C()
            crc.update(out.toByteArray(), 0, out.size())
            val v = crc.value.toInt()
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 24) and 0xFF)
        }
        return out.toByteArray()
    }

    private fun encodeCell(c: Cell, orderMap: Map<Cell, Int>, cellSizeBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val refsCount = c.refs.size
        val d1 = refsCount
        val fullBytes = c.bitLength / 8
        val partial = c.bitLength % 8
        val dataLenBytes = fullBytes + if (partial > 0) 1 else 0
        val d2 = fullBytes + dataLenBytes
        out.write(d1)
        out.write(d2)
        if (partial == 0) {
            out.write(c.data, 0, dataLenBytes)
        } else {
            if (fullBytes > 0) out.write(c.data, 0, fullBytes)
            val lastByteIdx = dataLenBytes - 1
            val raw = c.data[lastByteIdx].toInt() and 0xFF
            val completion = 1 shl (8 - partial - 1)
            val mask = (0xFF shl (8 - partial)) and 0xFF
            out.write((raw and mask) or completion)
        }
        for (r in c.refs) writeUint(out, orderMap.getValue(r).toLong(), cellSizeBytes)
        return out.toByteArray()
    }

    private fun writeUint(out: java.io.ByteArrayOutputStream, value: Long, bytes: Int) {
        for (i in bytes - 1 downTo 0) {
            out.write(((value ushr (8 * i)) and 0xFF).toInt())
        }
    }

    private fun java.io.ByteArrayOutputStream.writeInt(v: Int) {
        write((v ushr 24) and 0xFF)
        write((v ushr 16) and 0xFF)
        write((v ushr 8) and 0xFF)
        write(v and 0xFF)
    }

    private fun bytesNeeded(value: Long): Int {
        if (value == 0L) return 1
        var v = value
        var n = 0
        while (v > 0) {
            v = v ushr 8
            n++
        }
        return n
    }
}
