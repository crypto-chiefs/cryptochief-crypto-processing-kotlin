package com.cryptochief.processing

import com.cryptochief.processing.ton.TonAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TonAddressTest {

    private val userFriendly = "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs"

    @Test
    fun `parse user friendly bounceable mainnet address`() {
        val addr = TonAddress.parse(userFriendly)
        assertEquals(0, addr.workchain)
        assertEquals(true, addr.bounceable)
        assertEquals(false, addr.testnet)
        assertEquals(32, addr.hash.size)
    }

    @Test
    fun `roundtrip through string`() {
        val addr = TonAddress.parse(userFriendly)
        assertEquals(userFriendly, addr.toString())
    }

    @Test
    fun `raw form parses to same hash`() {
        val parsed = TonAddress.parse(userFriendly)
        val raw = parsed.raw()
        val again = TonAddress.parse(raw)
        assertEquals(parsed.workchain, again.workchain)
        assertEquals(parsed.hash.toList(), again.hash.toList())
    }

    @Test
    fun `crc mismatch is rejected`() {
        val tampered = userFriendly.dropLast(2) + "AA"
        assertThrows<IllegalArgumentException> { TonAddress.parse(tampered) }
    }

    @Test
    fun `non bounceable flag flips tag`() {
        val addr = TonAddress.parse(userFriendly)
        val nonBounce = addr.copy(bounceable = false).toString()
        assertNotEquals(userFriendly, nonBounce)
        assertEquals(false, nonBounce.startsWith("EQ"))
    }
}
