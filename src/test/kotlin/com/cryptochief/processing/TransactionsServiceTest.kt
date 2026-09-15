package com.cryptochief.processing

import com.cryptochief.processing.models.TxStatus
import com.cryptochief.processing.poll.waitForTransaction
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

class TransactionsServiceTest {

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

    private fun txBody(status: String, confirmations: Int, required: Int): String = """
        {
          "uuid": "t-$status", "status": "$status", "network": "ETH_MAINNET", "chain_family": "EVM",
          "type": "native", "from_address": "0xfrom", "to_address": "0xto", "value": "1000",
          "tx_hash": "0xhash", "confirmations": $confirmations, "required_confirmations": $required,
          "expires_at": "2026-09-14T10:10:00Z", "created_at": "2026-09-14T10:00:00Z"
        }
    """.trimIndent()

    @Test
    fun `info carries the count it was confirmed at and the threshold applied`() = runBlocking {
        enqueue(txBody(TxStatus.CONFIRMED, confirmations = 14, required = 12))

        val out = client.transactions.info("t-confirmed")

        assertEquals(14, out.confirmations)
        assertEquals(12, out.requiredConfirmations)
        assertTrue(out.confirmations >= out.requiredConfirmations)
    }

    @Test
    fun `not yet in a block the count is zero and the threshold is already known`() = runBlocking {
        enqueue(txBody(TxStatus.BROADCASTED, confirmations = 0, required = 12))

        val out = client.transactions.info("t-broadcasted")

        assertEquals(0, out.confirmations)
        assertEquals(12, out.requiredConfirmations)
    }

    @Test
    fun `broadcasted in a block carries its growing count and is still not confirmed`() = runBlocking {
        enqueue(txBody(TxStatus.BROADCASTED, confirmations = 5, required = 12))

        val out = client.transactions.info("t-broadcasted")

        assertEquals(TxStatus.BROADCASTED, out.status)
        assertEquals(5, out.confirmations)
        assertEquals(12, out.requiredConfirmations)
        assertFalse(out.isTerminal)
        assertFalse(out.succeeded)
    }

    @Test
    fun `waitForTransaction keeps polling through a growing and a reorged count until confirmed`() = runBlocking {
        enqueue(txBody(TxStatus.BROADCASTED, confirmations = 0, required = 12))
        enqueue(txBody(TxStatus.BROADCASTED, confirmations = 4, required = 12))
        enqueue(txBody(TxStatus.BROADCASTED, confirmations = 9, required = 12))
        enqueue(txBody(TxStatus.BROADCASTED, confirmations = 2, required = 12)) // after a reorganisation
        enqueue(txBody(TxStatus.CONFIRMED, confirmations = 12, required = 12))

        val last = client.waitForTransaction(
            uuid = "t-broadcasted",
            options = PollOptions(interval = Duration.ofMillis(1), timeout = Duration.ofSeconds(10)),
        )

        assertEquals(TxStatus.CONFIRMED, last.status)
        assertTrue(last.succeeded)
        assertEquals(12, last.confirmations)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `execute and history decode the same fields`() = runBlocking {
        enqueue(txBody(TxStatus.BROADCASTED, confirmations = 0, required = 20))
        enqueue(
            """{"items": [${txBody(TxStatus.CONFIRMED, 20, 20)}, ${txBody(TxStatus.BROADCASTED, 7, 20)},
               ${txBody(TxStatus.FAILED, 0, 20)}],
               "meta": {"page": 1, "page_size": 20, "total": 3, "total_pages": 1}}""",
        )

        val executed = client.transactions.execute("t-broadcasted")
        val page = client.transactions.history()

        assertEquals(0, executed.confirmations)
        assertEquals(20, executed.requiredConfirmations)
        assertEquals(listOf(20, 7, 0), page.items.map { it.confirmations })
        assertEquals(listOf(20, 20, 20), page.items.map { it.requiredConfirmations })
    }
}
