package com.cryptochief.processing

import com.cryptochief.processing.evm.EvmAbi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigInteger

class EvmAbiTest {

    @Test
    fun `transfer selector matches keccak`() {
        val sel = EvmAbi.selector("transfer(address,uint256)")
        assertEquals(
            "a9059cbb",
            sel.joinToString("") { "%02x".format(it.toInt() and 0xFF) },
        )
    }

    @Test
    fun `transfer encoding matches reference`() {
        val data = EvmAbi.encodeCallHex(
            "transfer(address,uint256)",
            "0x000000000000000000000000000000000000dead",
            BigInteger("1000"),
        )
        val expected = "0xa9059cbb" +
            "000000000000000000000000000000000000000000000000000000000000dead" +
            "00000000000000000000000000000000000000000000000000000000000003e8"
        assertEquals(expected, data)
    }

    @Test
    fun `dynamic bytes are length prefixed and padded`() {
        val data = EvmAbi.encodeCall("setData(bytes)", byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
        val hex = data.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        assertEquals(true, hex.startsWith(EvmAbi.selector("setData(bytes)").joinToString("") { "%02x".format(it.toInt() and 0xFF) }))
        assertEquals(true, hex.contains("0000000000000000000000000000000000000000000000000000000000000020"))
        assertEquals(true, hex.contains("0000000000000000000000000000000000000000000000000000000000000004"))
        assertEquals(true, hex.contains("deadbeef" + "00".repeat(28)))
    }

    @Test
    fun `address accepts TRON base58 form`() {
        val data = EvmAbi.encodeCallHex(
            "transfer(address,uint256)",
            "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t",
            BigInteger.ONE,
        )
        assertEquals(
            true,
            data.contains("a614f803b6fd780986a42c78ec9c7f77e6ded13c"),
        )
    }

    @Test
    fun `unsupported type fails fast`() {
        assertThrows<IllegalArgumentException> { EvmAbi.encodeCall("bad(decimal)") }
    }

    @Test
    fun `wrong arg count fails fast`() {
        assertThrows<IllegalArgumentException> {
            EvmAbi.encodeCall("transfer(address,uint256)", "0xdead")
        }
    }
}
