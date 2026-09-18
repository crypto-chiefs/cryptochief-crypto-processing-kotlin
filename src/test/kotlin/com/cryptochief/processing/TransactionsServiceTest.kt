package com.cryptochief.processing

import com.cryptochief.processing.models.EstimateTransactionRequest
import com.cryptochief.processing.models.TxStatus
import com.cryptochief.processing.models.TxType
import com.cryptochief.processing.poll.waitForTransaction
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
import org.junit.jupiter.api.assertThrows
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
    fun `estimate of a native transfer prices fee plus value`() = runBlocking {
        enqueue(
            """
            {"network": "ETH_MAINNET", "chain_family": "EVM", "type": "native",
             "from_address": "0xfrom", "to_address": "0xto",
             "estimated_fee": "0.00045", "estimated_fee_fiat": "1.27",
             "required": "0.01045", "required_fiat": "29.50"}
            """.trimIndent(),
        )

        val out = client.transactions.estimate(
            EstimateTransactionRequest(
                network = Chain.ETH_MAINNET,
                fromAddress = "0xfrom",
                toAddress = "0xto",
                value = "10000000000000000",
            ),
        )

        assertEquals(Chain.ETH_MAINNET, out.network)
        assertEquals("EVM", out.chainFamily)
        assertEquals(TxType.NATIVE, out.type)
        assertEquals("0.00045", out.estimatedFee)
        assertEquals("1.27", out.estimatedFeeFiat)
        assertEquals("0.01045", out.required)
        assertEquals("29.50", out.requiredFiat)

        val sent = server.takeRequest().body.readByteArray().toString(Charsets.UTF_8)
        assertFalse("url_callback" in sent)
        // The default type stays home (`encodeDefaults = false`); the server reads it as native.
        assertFalse("\"type\"" in sent)
    }

    @Test
    fun `estimate of a token transfer prices the fee alone and tolerates a missing rate`() = runBlocking {
        enqueue(
            """
            {"network": "TRON_MAINNET", "chain_family": "TRON", "type": "token",
             "from_address": "TFrom", "to_address": "TTo",
             "estimated_fee": "14.5", "estimated_fee_fiat": "",
             "required": "14.5", "required_fiat": ""}
            """.trimIndent(),
        )

        val out = client.transactions.estimate(
            EstimateTransactionRequest(
                network = Chain.TRON_MAINNET,
                fromAddress = "TFrom",
                type = TxType.TOKEN,
                toAddress = "TTo",
                value = "12500000",
                contract = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t",
            ),
        )

        assertEquals(TxType.TOKEN, out.type)
        assertEquals("14.5", out.estimatedFee)
        assertEquals("", out.estimatedFeeFiat)
        assertEquals("14.5", out.required)
        assertEquals("", out.requiredFiat)
    }

    @Test
    fun `estimate of a TRON transfer carries the energy breakdown`() = runBlocking {
        enqueue(
            """
            {"network": "TRON_MAINNET", "chain_family": "TRON", "type": "token",
             "from_address": "TFrom", "to_address": "TTo",
             "estimated_fee": "13.8", "estimated_fee_fiat": "3.93",
             "required": "13.8", "required_fiat": "3.93",
             "fee_expected": "0.0", "fee_limit": "13.8", "energy": 131000,
             "energy_fee": "13.1", "bandwidth_fee": "0.7", "activation_fee": "0.0"}
            """.trimIndent(),
        )

        val out = client.transactions.estimate(
            EstimateTransactionRequest(
                network = Chain.TRON_MAINNET,
                fromAddress = "TFrom",
                type = TxType.TOKEN,
                toAddress = "TTo",
                value = "12500000",
                contract = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t",
            ),
        )

        assertEquals("0.0", out.feeExpected)
        assertEquals("13.8", out.feeLimit)
        assertEquals(131_000, out.energy)
        assertEquals("13.1", out.energyFee)
        assertEquals("0.7", out.bandwidthFee)
        assertEquals("0.0", out.activationFee)
    }

    @Test
    fun `estimate of a non-TRON transfer has no energy breakdown`() = runBlocking {
        enqueue(
            """
            {"network": "ETH_MAINNET", "chain_family": "EVM", "type": "native",
             "from_address": "0xfrom", "to_address": "0xto",
             "estimated_fee": "0.00045", "estimated_fee_fiat": "1.27",
             "required": "0.01045", "required_fiat": "29.50"}
            """.trimIndent(),
        )

        val out = client.transactions.estimate(
            EstimateTransactionRequest(
                network = Chain.ETH_MAINNET,
                fromAddress = "0xfrom",
                toAddress = "0xto",
                value = "10000000000000000",
            ),
        )

        assertEquals("0.00045", out.estimatedFee)
        assertNull(out.feeExpected)
        assertNull(out.feeLimit)
        assertNull(out.energy)
        assertNull(out.energyFee)
        assertNull(out.bandwidthFee)
        assertNull(out.activationFee)
    }

    @Test
    fun `estimate of a contract call passes the refusal through`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"ok":false,"error":"CONTRACT_ESTIMATE_UNSUPPORTED","msg":"contract calls cannot be estimated"}""",
            ),
        )

        val ex = assertThrows<ApiException> {
            runBlocking {
                client.transactions.estimate(
                    EstimateTransactionRequest(
                        network = Chain.ETH_MAINNET,
                        fromAddress = "0xfrom",
                        type = TxType.CONTRACT,
                    ),
                )
            }
        }

        assertEquals("CONTRACT_ESTIMATE_UNSUPPORTED", ex.code)
        assertEquals(400, ex.status)
    }

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
