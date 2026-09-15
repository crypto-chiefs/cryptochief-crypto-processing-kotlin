package com.cryptochief.processing

import com.cryptochief.processing.models.EstimatePayoutRequest
import com.cryptochief.processing.models.ExecutePayoutRequest
import com.cryptochief.processing.models.PayoutInfo
import com.cryptochief.processing.models.PayoutStatus
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

class PayoutsServiceTest {

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

    /** Two sources on chain at different depths, and a gas top-up that was counted too. */
    private val paidBody = """
        {
          "uuid": "p-1", "order_id": "o-1", "user_id": "u-1", "status": "paid",
          "amount_requested": "150", "amount_to_receive": "150", "to_address": "TDest",
          "fee_info": {"fee_mode": "service", "estimated_fiat": "1.10", "limit_currency": "USD",
                       "total_fee_paid_fiat": "0.95"},
          "sources": [
            {"address": "TA", "network": "TRON_MAINNET", "coin": "USDT", "amount_crypto": "100",
             "need_refuel": false, "refuel_amount": "", "estimated_fee": "0.5", "estimated_fee_fiat": "0.40",
             "fee_paid": "0.4", "fee_paid_fiat": "0.35", "txid": "tx-a", "confirmations": 25},
            {"address": "TB", "network": "TRON_MAINNET", "coin": "USDT", "amount_crypto": "50",
             "need_refuel": true, "refuel_amount": "15", "estimated_fee": "0.5", "estimated_fee_fiat": "0.40",
             "txid": "tx-b", "confirmations": 19}
          ],
          "service_operations": [
            {"type": "gas_refuel", "context": "payout_prepare", "status": "done", "network": "TRON_MAINNET",
             "coin": "TRX", "amount_native": "15", "from_address": "TSvc", "to_address": "TB",
             "estimated_fee": "0.3", "estimated_fee_fiat": "0.10", "confirmations": 31}
          ],
          "confirmations": 19, "required_confirmations": 19,
          "created_at": "2026-09-14T10:00:00Z", "completed_at": "2026-09-14T10:02:00Z"
        }
    """.trimIndent()

    /** Queued: nothing sent, so no count anywhere and no service operations - but the depth is known. */
    private val queuedBody = """
        {
          "uuid": "p-2", "order_id": "o-2", "user_id": "u-2", "status": "queue",
          "amount_requested": "150", "amount_to_receive": "150", "to_address": "TDest",
          "fee_info": {"fee_mode": "client", "limit_currency": "USD"},
          "sources": [
            {"address": "TA", "network": "TRON_MAINNET", "coin": "USDT", "amount_crypto": "150",
             "need_refuel": false, "refuel_amount": "", "estimated_fee": "", "estimated_fee_fiat": ""}
          ],
          "required_confirmations": 19,
          "created_at": "2026-09-14T10:00:00Z", "completed_at": null
        }
    """.trimIndent()

    /** The 200 body of docs/payments/payout-information.md, verbatim. */
    private val documentedBody = """
        {
          "uuid": "b4ee6a7a-f7c2-474d-b002-e83ebe3e78db",
          "order_id": "12",
          "user_id": "4",
          "status": "queue",
          "amount_requested": "10",
          "amount_to_receive": "10",
          "to_address": "0x1f33f99676b65968885adDFaFda3a736424d93C9",

          "fee_info": {
            "fee_mode": "service",
            "estimated_fiat": "1.27",
            "limit_fiat": "2.00",
            "limit_currency": "USD",
            "total_fee_paid_fiat": null
          },

          "sources": [
            {
              "address": "0xA1b2c3d4e5f6",
              "network": "ETH_MAINNET",
              "coin": "USDT",
              "amount_crypto": "60.00",

              "need_refuel": false,
              "refuel_amount": "0",
              "estimated_fee": "0.00045",
              "estimated_fee_fiat": "0.58",
              "fee_paid": null,
              "fee_paid_fiat": null,
              "txid": null
            },
            {
              "address": "0xF9e8d7c6b5a4",
              "network": "ETH_MAINNET",
              "coin": "USDT",
              "amount_crypto": "40.00",

              "need_refuel": true,
              "refuel_amount": "0.003",
              "estimated_fee": "0.00038",
              "estimated_fee_fiat": "0.49",
              "fee_paid": null,
              "fee_paid_fiat": null,
              "txid": null
            }
          ],

          "service_operations": [
            {
              "type": "gas_refuel",
              "context": "payout_prepare",
              "status": "planned",

              "network": "ETH_MAINNET",
              "coin": "ETH",
              "amount_native": "0.003",

              "from_address": "0xSERVICE_WALLET",
              "to_address": "0xF9e8d7c6b5a4",

              "estimated_fee": "0.00021",
              "estimated_fee_fiat": "0.20",

              "fee_paid": null,
              "fee_paid_fiat": null,

              "txid": null
            }
          ],

          "required_confirmations": 32,

          "created_at": "2026-01-29T01:25:00Z",
          "completed_at": null
        }
    """.trimIndent()

    @Test
    fun `info decodes the documented response`() = runBlocking {
        enqueue(documentedBody)

        val out = client.payouts.info("b4ee6a7a-f7c2-474d-b002-e83ebe3e78db")

        assertEquals("12", out.orderId)
        assertEquals("4", out.userId)
        assertEquals(PayoutStatus.QUEUE, out.status)
        assertEquals("10", out.amountRequested)
        assertEquals("10", out.amountToReceive)
        assertEquals("0x1f33f99676b65968885adDFaFda3a736424d93C9", out.toAddress)
        assertEquals("service", out.feeInfo?.feeMode)
        assertEquals("1.27", out.feeInfo?.estimatedFiat)
        assertEquals("2.00", out.feeInfo?.limitFiat)
        assertNull(out.feeInfo?.totalFeePaidFiat)
        assertEquals(listOf("60.00", "40.00"), out.sources.map { it.amountCrypto })
        assertEquals(listOf(Chain.ETH_MAINNET, Chain.ETH_MAINNET), out.sources.map { it.network })
        assertEquals(listOf(false, true), out.sources.map { it.needRefuel })
        assertEquals("0.003", out.sources[1].refuelAmount)
        assertNull(out.sources[0].txid)
        assertEquals("gas_refuel", out.serviceOperations.single().type)
        assertNull(out.confirmations)
        assertEquals(32, out.requiredConfirmations)
        assertNull(out.completedAt)
    }

    @Test
    fun `info carries the payout, source and service operation confirmations`() = runBlocking {
        enqueue(paidBody)

        val out = client.payouts.info("p-1")

        assertEquals(PayoutStatus.PAID, out.status)
        assertEquals("150", out.amountRequested)
        assertEquals("0.95", out.feeInfo?.totalFeePaidFiat)
        assertEquals("2026-09-14T10:02:00Z", out.completedAt)
        assertEquals(19, out.confirmations)
        assertEquals(19, out.requiredConfirmations)
        assertEquals(listOf(25, 19), out.sources.map { it.confirmations })
        assertEquals(listOf("tx-a", "tx-b"), out.sources.map { it.txid })
        assertEquals(listOf("100", "50"), out.sources.map { it.amountCrypto })
        assertEquals("0.4", out.sources[0].feePaid)
        assertEquals(1, out.serviceOperations.size)
        val gas = out.serviceOperations.single()
        assertEquals("gas_refuel", gas.type)
        assertEquals(Chain.TRON_MAINNET, gas.network)
        assertEquals(31, gas.confirmations)
    }

    @Test
    fun `info leaves every count null while nothing is on chain`() = runBlocking {
        enqueue(queuedBody)

        val out = client.payouts.info("p-2")

        assertNull(out.confirmations)
        assertNull(out.sources.single().confirmations)
        assertNull(out.sources.single().txid)
        assertNull(out.feeInfo?.estimatedFiat)
        assertTrue(out.serviceOperations.isEmpty())
        // The depth belongs to the network, so it is known before anything is sent.
        assertEquals(19, out.requiredConfirmations)
    }

    @Test
    fun `a payout in a block but short of the depth is not paid yet`() = runBlocking {
        enqueue(
            """
            {"uuid": "p-4", "order_id": "o-4", "status": "confirm_check",
             "amount_requested": "30", "to_address": "0xdest",
             "sources": [
               {"address": "0xa", "amount_crypto": "20", "txid": "0xta", "confirmations": 12},
               {"address": "0xb", "amount_crypto": "10", "txid": "0xtb", "confirmations": 3}
             ],
             "confirmations": 3, "required_confirmations": 12}
            """.trimIndent(),
        )

        val out = client.payouts.info("p-4")

        // One source already at the depth is not enough: paid waits for every source.
        assertEquals(12, out.requiredConfirmations)
        assertEquals(listOf(12, 3), out.sources.map { it.confirmations })
        assertEquals(3, out.confirmations)
        assertEquals(PayoutStatus.CONFIRM_CHECK, out.status)
        assertFalse(out.succeeded)
        assertFalse(out.isTerminal)
    }

    @Test
    fun `the statuses the payout worker writes before paid are not terminal`() {
        val inFlight = listOf(
            PayoutStatus.QUEUE to "queue",
            PayoutStatus.REFUELING to "refueling",
            PayoutStatus.REFUEL_CONFIRMED to "refuel_confirmed",
            PayoutStatus.SENDING to "sending",
            PayoutStatus.BROADCASTING to "broadcasting",
            PayoutStatus.IN_MEMPOOL to "in_mempool",
            PayoutStatus.CONFIRM_CHECK to "confirm_check",
        )

        inFlight.forEach { (constant, wire) ->
            assertEquals(wire, constant)
            assertFalse(PayoutInfo(uuid = "p", status = constant).isTerminal, wire)
        }
        assertTrue(PayoutInfo(uuid = "p", status = PayoutStatus.PAID).isTerminal)
        assertTrue(PayoutInfo(uuid = "p", status = PayoutStatus.SYSTEM_FAIL).isTerminal)
    }

    @Test
    fun `a paid payout final without a block count reads at the depth, not at 0`() = runBlocking {
        enqueue(
            """
            {"uuid": "p-6", "order_id": "o-6", "status": "paid",
             "amount_requested": "30", "to_address": "So1dest",
             "sources": [
               {"address": "So1a", "amount_crypto": "20", "txid": "5ia", "confirmations": 40},
               {"address": "So1b", "amount_crypto": "10", "txid": "5ib", "confirmations": 32}
             ],
             "confirmations": 32, "required_confirmations": 32}
            """.trimIndent(),
        )

        val out = client.payouts.info("p-6")

        assertTrue(out.succeeded)
        assertEquals(32, out.sources[1].confirmations)
        assertTrue(out.confirmations!! >= out.requiredConfirmations!!)
    }

    @Test
    fun `a payout without required_confirmations still decodes`() = runBlocking {
        enqueue(
            """
            {"uuid": "p-5", "order_id": "o-5", "status": "paid",
             "amount_requested": "5", "to_address": "TDest",
             "sources": [{"address": "TA", "amount_crypto": "5", "txid": "tx-a", "confirmations": 20}],
             "confirmations": 20}
            """.trimIndent(),
        )

        val out = client.payouts.info("p-5")

        assertNull(out.requiredConfirmations)
        assertEquals(20, out.confirmations)
        assertTrue(out.succeeded)
    }

    @Test
    fun `a zero count is a value, not an absence`() = runBlocking {
        enqueue(
            """
            {"uuid": "p-3", "order_id": "o-3", "status": "confirm_check",
             "amount_requested": "1", "to_address": "0xdest",
             "sources": [{"address": "0xa", "amount_crypto": "1", "txid": "0xt", "confirmations": 0}],
             "confirmations": 0}
            """.trimIndent(),
        )

        val out = client.payouts.info("p-3")

        assertEquals(0, out.confirmations)
        assertEquals(0, out.sources.single().confirmations)
    }

    @Test
    fun `estimate decodes the central response`() = runBlocking {
        enqueue(
            """
            {"amount_requested": "10", "amount_to_receive": "10", "to_address": "0xdest",
             "fee_info": {"fee_mode": "mix", "estimated_fiat": "1.27", "limit_currency": "USD"},
             "sources": [
               {"address": "0xa", "network": "ETH_MAINNET", "coin": "USDT", "amount_crypto": "10",
                "need_refuel": true, "refuel_amount": "0.003", "estimated_fee": "0.00045", "estimated_fee_fiat": "0.58"}
             ],
             "service_operations": [
               {"type": "gas_refuel", "context": "payout_prepare", "status": "planned", "network": "ETH_MAINNET",
                "coin": "ETH", "amount_native": "0.003", "from_address": "0xsvc", "to_address": "0xa",
                "estimated_fee": "0.00021", "estimated_fee_fiat": "0.20"}
             ],
             "coins": [
               {"network": "ETH_MAINNET", "coin": "USDT", "address": "0xa", "amount_available": "25",
                "native_available": "0.001", "native_coin": "ETH"}
             ]}
            """.trimIndent(),
        )

        val out = client.payouts.estimate(
            EstimatePayoutRequest(
                network = Chain.ETH_MAINNET,
                coin = "USDT",
                amount = "10",
                toAddress = "0xdest",
            ),
        )

        assertEquals("10", out.amountRequested)
        assertEquals("10", out.amountToReceive)
        assertEquals("1.27", out.feeInfo?.estimatedFiat)
        assertEquals("10", out.sources.single().amountCrypto)
        assertTrue(out.sources.single().needRefuel)
        assertEquals("gas_refuel", out.serviceOperations.single().type)
        assertEquals("25", out.coins.single().amountAvailable)
    }

    @Test
    fun `history items carry their own counts`() = runBlocking {
        enqueue("""{"items": [$paidBody, $queuedBody], "meta": {"page": 1, "page_size": 20, "total": 2, "total_pages": 1}}""")

        val out = client.payouts.history()

        assertEquals(listOf(19, null), out.items.map { it.confirmations })
        assertEquals(listOf(19, 19), out.items.map { it.requiredConfirmations })
        assertEquals(31, out.items[0].serviceOperations.single().confirmations)
        assertNull(out.items[1].sources.single().confirmations)
    }

    @Test
    fun `an idempotent execute repeat returns the counts of the existing payout`() = runBlocking {
        enqueue(paidBody)

        val out = client.payouts.execute(
            ExecutePayoutRequest(
                orderId = "o-1",
                userId = "u-1",
                network = Chain.TRON_MAINNET,
                coin = "USDT",
                amount = "150",
                toAddress = "TDest",
                urlCallback = "https://your.app/webhooks/payout",
            ),
        )

        assertEquals(19, out.confirmations)
        assertEquals(19, out.requiredConfirmations)
        assertEquals(listOf(25, 19), out.sources.map { it.confirmations })
    }
}
