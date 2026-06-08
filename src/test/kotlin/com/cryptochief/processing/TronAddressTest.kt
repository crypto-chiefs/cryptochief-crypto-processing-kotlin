package com.cryptochief.processing

import com.cryptochief.processing.tron.TronAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TronAddressTest {

    private val base58 = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"
    private val hex = "0x41a614f803b6fd780986a42c78ec9c7f77e6ded13c"

    @Test
    fun `base58 to hex matches reference`() {
        assertEquals(hex, TronAddress.toHex(base58))
    }

    @Test
    fun `hex to base58 round trips`() {
        assertEquals(base58, TronAddress.fromHex(hex))
    }

    @Test
    fun `20 byte hex gets 0x41 prefix on encode`() {
        val twentyByteHex = hex.removePrefix("0x").substring(2)
        assertEquals(base58, TronAddress.fromHex("0x$twentyByteHex"))
    }

    @Test
    fun `bad checksum is rejected`() {
        val tampered = base58.dropLast(4) + "AAAA"
        assertThrows<IllegalArgumentException> { TronAddress.toHex(tampered) }
    }
}
