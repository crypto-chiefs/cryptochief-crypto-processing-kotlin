package com.cryptochief.processing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigInteger

class AmountTest {

    @Test
    fun `toBase converts human strings to wei`() {
        assertEquals(BigInteger("1500000000000000000"), Amount.toBase("1.5", 18))
        assertEquals(BigInteger("10000"), Amount.toBase("0.0001", 8))
        assertEquals(BigInteger("0"), Amount.toBase("0", 18))
        assertEquals(BigInteger("1"), Amount.toBase("0.000000000000000001", 18))
    }

    @Test
    fun `toBase truncates excess decimals`() {
        assertEquals(BigInteger("12500000"), Amount.toBase("12.50000099", 6))
    }

    @Test
    fun `toBase rejects negative and scientific notation`() {
        assertThrows<IllegalArgumentException> { Amount.toBase("-1", 18) }
        assertThrows<IllegalArgumentException> { Amount.toBase("1e5", 18) }
        assertThrows<IllegalArgumentException> { Amount.toBase("1.5E2", 18) }
        assertThrows<IllegalArgumentException> { Amount.toBase("  ", 18) }
    }

    @Test
    fun `fromBase inverts toBase`() {
        assertEquals("1.5", Amount.fromBase(BigInteger("1500000000000000000"), 18))
        assertEquals("0", Amount.fromBase(BigInteger.ZERO, 18))
        assertEquals("0.0001", Amount.fromBase(BigInteger("10000"), 8))
    }

    @Test
    fun `nanoTon helper`() {
        assertEquals("50000000", Amount.nanoTon("0.05"))
        assertEquals("0", Amount.nanoTon("0"))
    }

    @Test
    fun `extension shortcuts`() {
        assertEquals(BigInteger("100"), "100".toBaseUnits(0))
        assertEquals("1.5", BigInteger("1500000000000000000").toHumanString(18))
    }
}
