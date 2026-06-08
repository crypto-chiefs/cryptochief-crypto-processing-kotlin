package com.cryptochief.processing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChainTest {

    @Test
    fun `known chain maps to family`() {
        assertEquals(ChainFamily.EVM, Chain.ETH_MAINNET.family())
        assertEquals(ChainFamily.TON, Chain.TON_MAINNET.family())
        assertEquals(ChainFamily.SOLANA, Chain.SOLANA_MAINNET.family())
    }

    @Test
    fun `unknown chain has null family`() {
        assertNull(Chain("FUTURE_CHAIN").family())
    }

    @Test
    fun `contract families flag matches spec`() {
        assertTrue(ChainFamily.EVM.supportsContractCalls())
        assertTrue(ChainFamily.TRON.supportsContractCalls())
        assertTrue(ChainFamily.SOLANA.supportsContractCalls())
        assertTrue(ChainFamily.TON.supportsContractCalls())
        assertEquals(false, ChainFamily.XRP_LEDGER.supportsContractCalls())
        assertEquals(false, ChainFamily.BTC_UTXO.supportsContractCalls())
    }
}
