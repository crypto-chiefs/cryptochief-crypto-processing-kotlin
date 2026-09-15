package com.cryptochief.processing

import com.cryptochief.processing.models.WithdrawalStatus
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

class WithdrawalsServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var client: CryptoChiefClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = CryptoChiefClient(
            Options.builder().apply {
                merchantId = "mer_test"
                apiKey = "secret-key"
                baseUrl = server.url("/").toString().trimEnd('/')
                maxRetries = 0
                initialRetryDelay = Duration.ofMillis(1)
                maxRetryDelay = Duration.ofMillis(5)
            }.build(),
        )
    }

    @AfterEach
    fun tearDown() {
        client.close()
        server.shutdown()
    }

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
    }

    /** Reached the depth: the shape the API sends, gas top-up included. */
    private val completedBody = """
        {
          "uuid": "w-1", "status": "completed",
          "from_address": "0xfrom", "to_address": "0xto", "amount": "100.5",
          "network": "ETH_MAINNET", "coin": "USDT",
          "need_refuel": true, "refuel_tx_hash": "0xrefuel", "refuel_status": "done",
          "tx_hash": "0xtx", "confirmations": 14, "required_confirmations": 12,
          "estimated_fee_fiat": "1.20", "actual_fee_fiat": "1.18", "fee_mode": "service",
          "created_at": "2026-09-14T10:00:00Z", "completed_at": "2026-09-14T10:04:00Z"
        }
    """.trimIndent()

    /** Still queued: nothing sent, so no count - but the network's depth is already known. */
    private val queuedBody = """
        {
          "uuid": "w-2", "status": "queue",
          "from_address": "TFrom", "to_address": "TTo", "amount": "50",
          "network": "TRON_MAINNET", "coin": "USDT", "need_refuel": false,
          "required_confirmations": 19,
          "estimated_fee_fiat": "0.40", "fee_mode": "client",
          "created_at": "2026-09-14T10:00:00Z"
        }
    """.trimIndent()

    @Test
    fun `info carries the count and the depth of a completed withdrawal`() = runBlocking {
        enqueue(completedBody)

        val out = client.withdrawals.info("w-1")

        assertEquals(WithdrawalStatus.COMPLETED, out.status)
        assertTrue(out.succeeded)
        assertTrue(out.isTerminal)
        assertEquals(14, out.confirmations)
        assertEquals(12, out.requiredConfirmations)
        assertEquals("0xtx", out.txHash)
        assertTrue(out.needRefuel)
        assertEquals("0xrefuel", out.refuelTxHash)
        assertEquals("done", out.refuelStatus)
        assertEquals("1.18", out.actualFeeFiat)
        assertEquals("service", out.feeMode)
        assertEquals("2026-09-14T10:04:00Z", out.completedAt)
    }

    @Test
    fun `confirmations is absent until the transaction is in a block`() = runBlocking {
        enqueue(queuedBody)

        val out = client.withdrawals.info("w-2")

        assertNull(out.confirmations)
        assertNull(out.txHash)
        // The depth belongs to the network, so it is known before anything is sent.
        assertEquals(19, out.requiredConfirmations)
        assertFalse(out.isTerminal)
    }

    @Test
    fun `a withdrawal in a block but short of the depth is still confirm_check`() = runBlocking {
        enqueue(
            """
            {"uuid": "w-3", "status": "confirm_check", "amount": "1", "network": "ETH_MAINNET",
             "coin": "ETH", "tx_hash": "0xtx", "confirmations": 3, "required_confirmations": 12}
            """.trimIndent(),
        )

        val out = client.withdrawals.info("w-3")

        assertEquals(WithdrawalStatus.CONFIRM_CHECK, out.status)
        assertEquals(3, out.confirmations)
        assertEquals(12, out.requiredConfirmations)
        assertFalse(out.succeeded)
        assertFalse(out.isTerminal)
        assertNull(out.completedAt)
    }

    @Test
    fun `a sent withdrawal not yet in a block has no count`() = runBlocking {
        enqueue(
            """
            {"uuid": "w-4", "status": "confirm_check", "amount": "1", "network": "ETH_MAINNET",
             "coin": "ETH", "tx_hash": "0xtx", "required_confirmations": 12}
            """.trimIndent(),
        )

        val out = client.withdrawals.info("w-4")

        assertEquals("0xtx", out.txHash)
        assertNull(out.confirmations)
        assertEquals(12, out.requiredConfirmations)
    }

    @Test
    fun `a zero count is a value, not an absence`() = runBlocking {
        enqueue(
            """
            {"uuid": "w-5", "status": "confirm_check", "amount": "0.01", "network": "BTC_MAINNET",
             "coin": "BTC", "tx_hash": "abc", "confirmations": 0, "required_confirmations": 6}
            """.trimIndent(),
        )

        assertEquals(0, client.withdrawals.info("w-5").confirmations)
    }

    @Test
    fun `failed is terminal and carries the reason`() = runBlocking {
        enqueue(
            """
            {"uuid": "w-6", "status": "failed", "amount": "1", "network": "BSC_MAINNET", "coin": "BNB",
             "error_reason": "TX_CONFIRM_TIMEOUT", "required_confirmations": 15}
            """.trimIndent(),
        )

        val failed = client.withdrawals.info("w-6")

        assertTrue(failed.isTerminal)
        assertFalse(failed.succeeded)
        assertEquals("TX_CONFIRM_TIMEOUT", failed.errorReason)
    }

    @Test
    fun `payout status names are not withdrawal statuses`() {
        assertEquals(setOf("completed", "failed", "cancelled"), WithdrawalStatus.TERMINAL)
        assertFalse("paid" in WithdrawalStatus.TERMINAL)
        assertFalse("system_fail" in WithdrawalStatus.TERMINAL)
    }

    @Test
    fun `a withdrawal without required_confirmations still decodes`() = runBlocking {
        enqueue(
            """
            {"uuid": "w-8", "status": "completed", "amount": "1", "network": "TRON_MAINNET",
             "coin": "TRX", "tx_hash": "tx"}
            """.trimIndent(),
        )

        val out = client.withdrawals.info("w-8")

        assertTrue(out.succeeded)
        assertNull(out.confirmations)
        assertNull(out.requiredConfirmations)
    }

    @Test
    fun `history items carry their own counts`() = runBlocking {
        enqueue("""{"items": [$completedBody, $queuedBody], "meta": {"page": 1, "page_size": 20, "total": 2, "total_pages": 1}}""")

        val out = client.withdrawals.history()

        assertEquals(listOf(14, null), out.items.map { it.confirmations })
        assertEquals(listOf(12, 19), out.items.map { it.requiredConfirmations })
        assertEquals(listOf(WithdrawalStatus.COMPLETED, WithdrawalStatus.QUEUE), out.items.map { it.status })
        assertEquals(2, out.meta.total)
    }
}
