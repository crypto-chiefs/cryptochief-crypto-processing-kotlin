package com.cryptochief.processing

import com.cryptochief.processing.models.NativeBuyRequest
import com.cryptochief.processing.models.NativeOrderStatus
import com.cryptochief.processing.models.NativeQuoteRequest
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

class NativeServiceTest {

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

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    private val deliveredBody = """
        {
          "id": 42, "idempotency_key": "native-2026-09-18-0001", "status": "delivered",
          "network": "ETH_MAINNET", "receive_address": "0xTo", "amount": "0.05",
          "tx_hash": "0xhash", "transfer_fee": "0.00021", "transfer_fee_usd": "0.62",
          "coin_price_usd": "148.50", "total_usd": "193.86", "credits": 1938600000,
          "coin_usd": "2970.00", "settled": true, "needs_attention": false,
          "created_at": "2026-09-18T10:00:00Z", "delivered_at": "2026-09-18T10:00:04Z"
        }
    """.trimIndent()

    @Test
    fun `quote prices the buy with the coins at the rate plus the transfer fee`() = runBlocking {
        enqueue(
            """
            {"ref": "nq-1", "network": "ETH_MAINNET", "receive_address": "0xTo", "amount": "0.05",
             "coin_price_usd": "148.50", "transfer_fee": "0.00021", "transfer_fee_usd": "0.62",
             "subtotal_usd": "149.12", "total_usd": "193.86",
             "credits": 1938600000, "coin_usd": "2970.00",
             "expires_at": "2026-09-18T10:01:30Z", "expires_in_sec": 90}
            """.trimIndent(),
        )

        val out = client.native.quote(
            NativeQuoteRequest(network = Chain.ETH_MAINNET, receiveAddress = "0xTo", amount = "0.05"),
        )

        assertEquals("nq-1", out.ref)
        assertEquals(Chain.ETH_MAINNET, out.network)
        assertEquals("0xTo", out.receiveAddress)
        assertEquals("0.05", out.amount)
        assertEquals("148.50", out.coinPriceUsd)
        assertEquals("0.00021", out.transferFee)
        assertEquals("0.62", out.transferFeeUsd)
        assertEquals("149.12", out.subtotalUsd)
        assertEquals("193.86", out.totalUsd)
        assertEquals(1_938_600_000, out.credits)
        assertEquals("2970.00", out.coinUsd)
        assertEquals("2026-09-18T10:01:30Z", out.expiresAt)
        assertEquals(90, out.expiresInSec)

        val sent = server.takeRequest().body.readByteArray().toString(Charsets.UTF_8)
        assertEquals("""{"network":"ETH_MAINNET","receive_address":"0xTo","amount":"0.05"}""", sent)
    }

    @Test
    fun `buy is refused locally without an idempotency key`() {
        assertThrows<IllegalArgumentException> {
            runBlocking {
                client.native.buy(
                    NativeBuyRequest(network = Chain.ETH_MAINNET, receiveAddress = "0xTo", amount = "0.05"),
                )
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `buy delivers the coins under the idempotency key`() = runBlocking {
        enqueue(deliveredBody)

        val out = withIdempotencyKey("native-2026-09-18-0001") {
            client.native.buy(
                NativeBuyRequest(
                    network = Chain.ETH_MAINNET,
                    receiveAddress = "0xTo",
                    amount = "0.05",
                    quoteRef = "nq-1",
                ),
            )
        }

        assertEquals(42, out.id)
        assertEquals("native-2026-09-18-0001", out.idempotencyKey)
        assertEquals(NativeOrderStatus.DELIVERED, out.status)
        assertTrue(out.succeeded)
        assertTrue(out.isTerminal)
        assertEquals(Chain.ETH_MAINNET, out.network)
        assertEquals("0xTo", out.receiveAddress)
        assertEquals("0.05", out.amount)
        assertEquals("0xhash", out.txHash)
        assertEquals("0.00021", out.transferFee)
        assertEquals("0.62", out.transferFeeUsd)
        assertEquals("148.50", out.coinPriceUsd)
        assertEquals("193.86", out.totalUsd)
        assertEquals(1_938_600_000, out.credits)
        assertEquals("2970.00", out.coinUsd)
        assertTrue(out.settled)
        assertFalse(out.needsAttention)
        assertNull(out.error)
        assertNull(out.errorCode)
        assertEquals("2026-09-18T10:00:04Z", out.deliveredAt)

        val sent = server.takeRequest()
        assertEquals("native-2026-09-18-0001", sent.getHeader("Idempotency-Key"))
        assertEquals(
            """{"network":"ETH_MAINNET","receive_address":"0xTo","amount":"0.05","quote_ref":"nq-1"}""",
            sent.body.readByteArray().toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `a refused buy answers 502 with the order, which buy returns`() = runBlocking {
        enqueue(
            """
            {"id": 43, "idempotency_key": "native-2026-09-18-0002", "status": "refused",
             "network": "ETH_MAINNET", "receive_address": "0xTo", "amount": "0.05",
             "transfer_fee": "", "transfer_fee_usd": "0.00", "coin_price_usd": "0.00",
             "settled": true, "needs_attention": false,
             "error": "liquidity pool could not deliver",
             "created_at": "2026-09-18T10:00:00Z"}
            """.trimIndent(),
            code = 502,
        )

        val out = withIdempotencyKey("native-2026-09-18-0002") {
            client.native.buy(
                NativeBuyRequest(network = Chain.ETH_MAINNET, receiveAddress = "0xTo", amount = "0.05"),
            )
        }

        assertEquals(43, out.id)
        assertEquals(NativeOrderStatus.REFUSED, out.status)
        assertFalse(out.succeeded)
        assertTrue(out.isTerminal)
        assertTrue(out.settled)
        assertFalse(out.needsAttention)
        // Nothing was sent or charged: the transfer fields come back blank, and what was
        // never billed stays absent — a zero would read as "this was free".
        assertNull(out.txHash)
        assertEquals("", out.transferFee)
        assertEquals("0.00", out.transferFeeUsd)
        assertEquals("0.00", out.coinPriceUsd)
        assertNull(out.totalUsd)
        assertNull(out.credits)
        assertNull(out.coinUsd)
        assertEquals("liquidity pool could not deliver", out.error)
        assertNull(out.errorCode)
        assertNull(out.deliveredAt)
    }

    @Test
    fun `an unresolved buy answers 409 with the order, which buy returns`() = runBlocking {
        enqueue(
            """
            {"id": 44, "idempotency_key": "native-2026-09-18-0003", "status": "unresolved",
             "network": "ETH_MAINNET", "receive_address": "0xTo", "amount": "0.05",
             "transfer_fee": "0.00021", "transfer_fee_usd": "0.62", "coin_price_usd": "148.50",
             "total_usd": "193.86", "credits": 1938600000, "coin_usd": "2970.00",
             "settled": false, "needs_attention": true,
             "error": "the transfer outcome never arrived",
             "created_at": "2026-09-18T10:00:00Z"}
            """.trimIndent(),
            code = 409,
        )

        val out = withIdempotencyKey("native-2026-09-18-0003") {
            client.native.buy(NativeBuyRequest(quoteRef = "nq-1"))
        }

        assertEquals(NativeOrderStatus.UNRESOLVED, out.status)
        assertFalse(out.isTerminal)
        assertFalse(out.settled)
        assertTrue(out.needsAttention)
        assertEquals("the transfer outcome never arrived", out.error)
        // Charged, because the coins may already be sent.
        assertEquals(1_938_600_000, out.credits)
    }

    @Test
    fun `a buy short of credits answers 402 with the refused order, which buy returns`() = runBlocking {
        enqueue(
            """
            {"id": 45, "idempotency_key": "native-2026-09-18-0004", "status": "refused",
             "network": "ETH_MAINNET", "receive_address": "0xTo", "amount": "0.05",
             "transfer_fee": "", "transfer_fee_usd": "0.00", "coin_price_usd": "0.00",
             "settled": true, "needs_attention": false,
             "error": "credits balance is short", "error_code": "INSUFFICIENT_CREDITS",
             "created_at": "2026-09-18T10:00:00Z"}
            """.trimIndent(),
            code = 402,
        )

        val out = withIdempotencyKey("native-2026-09-18-0004") {
            client.native.buy(
                NativeBuyRequest(network = Chain.ETH_MAINNET, receiveAddress = "0xTo", amount = "0.05"),
            )
        }

        assertEquals(NativeOrderStatus.REFUSED, out.status)
        assertEquals(ErrorCode.INSUFFICIENT_CREDITS, out.errorCode)
        assertNull(out.credits)
    }

    @Test
    fun `an error envelope from buy throws an ApiException`() = runBlocking {
        enqueue(
            """{"ok": false, "error": "SERVICE_ERROR", "msg": "QUOTE_EXPIRED"}""",
            code = 409,
        )

        val err = assertThrows<ApiException> {
            runBlocking {
                withIdempotencyKey("native-2026-09-18-0005") {
                    client.native.buy(NativeBuyRequest(quoteRef = "nq-stale"))
                }
            }
        }
        assertEquals("QUOTE_EXPIRED", err.code)
        assertEquals(409, err.status)
    }

    @Test
    fun `order reads the buy back by its idempotency key`() = runBlocking {
        enqueue(deliveredBody)

        val out = client.native.order("native-2026-09-18-0001")

        assertEquals(42, out.id)
        assertTrue(out.succeeded)
        val sent = server.takeRequest().body.readByteArray().toString(Charsets.UTF_8)
        assertEquals("""{"key":"native-2026-09-18-0001"}""", sent)
    }

    @Test
    fun `an unresolved order is not terminal`() = runBlocking {
        enqueue(
            """
            {"id": 46, "idempotency_key": "native-2026-09-18-0006", "status": "unresolved",
             "network": "ETH_MAINNET", "receive_address": "0xTo", "amount": "0.05",
             "settled": false, "needs_attention": true, "created_at": "2026-09-18T10:00:00Z"}
            """.trimIndent(),
        )

        val out = client.native.order("native-2026-09-18-0006")

        assertEquals(NativeOrderStatus.UNRESOLVED, out.status)
        assertFalse(out.isTerminal)
        assertTrue(out.needsAttention)
    }
}
