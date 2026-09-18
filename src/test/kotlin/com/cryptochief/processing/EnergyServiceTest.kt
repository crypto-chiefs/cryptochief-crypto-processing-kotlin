package com.cryptochief.processing

import com.cryptochief.processing.models.EnergyOrderStatus
import com.cryptochief.processing.models.EnergyQuoteRequest
import com.cryptochief.processing.models.EnergyRentRequest
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

class EnergyServiceTest {

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
          "id": 90210, "idempotency_key": "energy-2026-09-18-0001", "status": "delivered",
          "receive_address": "TFrom", "energy": 131000, "duration_sec": 3600,
          "price_sun": 28500000, "price_trx": "28.5", "price_usd": "8.12", "credits": 81200000,
          "trx_usd": "0.285", "delivered_energy": 131000, "settled": true, "needs_attention": false,
          "created_at": "2026-09-18T10:00:00Z", "delivered_at": "2026-09-18T10:00:03Z"
        }
    """.trimIndent()

    @Test
    fun `quote prices the rent against burning`() = runBlocking {
        enqueue(
            """
            {"ref": "q-1", "receive_address": "TFrom", "energy": 131000, "duration_sec": 3600,
             "price_sun": 28500000, "price_trx": "28.5", "price_usd": "8.12", "credits": 81200000,
             "trx_usd": "0.285", "recipient_state": "cold",
             "burn_price_sun": 131000000, "burn_price_trx": "131.0", "burn_price_usd": "37.34",
             "burn_price_credits": 373400000,
             "saving_trx": "102.5", "saving_usd": "29.22", "saving_credits": 292200000,
             "expires_at": "2026-09-18T10:05:00Z", "expires_in_sec": 300}
            """.trimIndent(),
        )

        val out = client.energy.quote(
            EnergyQuoteRequest(receiveAddress = "TFrom", energy = 131_000, durationSec = 3_600),
        )

        assertEquals("q-1", out.ref)
        assertEquals("TFrom", out.receiveAddress)
        assertEquals(131_000, out.energy)
        assertEquals(3_600, out.durationSec)
        assertEquals(28_500_000, out.priceSun)
        assertEquals("28.5", out.priceTrx)
        assertEquals("8.12", out.priceUsd)
        assertEquals(81_200_000, out.credits)
        assertEquals("0.285", out.trxUsd)
        assertEquals("cold", out.recipientState)
        assertEquals(131_000_000, out.burnPriceSun)
        assertEquals("131.0", out.burnPriceTrx)
        assertEquals("37.34", out.burnPriceUsd)
        assertEquals(373_400_000, out.burnPriceCredits)
        assertEquals("102.5", out.savingTrx)
        assertEquals("29.22", out.savingUsd)
        assertEquals(292_200_000, out.savingCredits)
        assertEquals("2026-09-18T10:05:00Z", out.expiresAt)
        assertEquals(300, out.expiresInSec)

        val sent = server.takeRequest().body.readByteArray().toString(Charsets.UTF_8)
        assertEquals("""{"receive_address":"TFrom","energy":131000,"duration_sec":3600}""", sent)
    }

    @Test
    fun `quote leaves unset sizing off the wire`() = runBlocking {
        enqueue("""{"ref": "q-2", "receive_address": "TFrom", "energy": 131000, "duration_sec": 3600}""")

        val out = client.energy.quote(EnergyQuoteRequest(receiveAddress = "TFrom"))

        assertEquals("q-2", out.ref)
        val sent = server.takeRequest().body.readByteArray().toString(Charsets.UTF_8)
        assertEquals("""{"receive_address":"TFrom"}""", sent)
    }

    @Test
    fun `rent is refused locally without an idempotency key`() {
        assertThrows<IllegalArgumentException> {
            runBlocking { client.energy.rent(EnergyRentRequest(receiveAddress = "TFrom")) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `rent delegates the energy under the idempotency key`() = runBlocking {
        enqueue(deliveredBody)

        val out = withIdempotencyKey("energy-2026-09-18-0001") {
            client.energy.rent(
                EnergyRentRequest(receiveAddress = "TFrom", energy = 131_000, quoteRef = "q-1"),
            )
        }

        assertEquals(90210, out.id)
        assertEquals("energy-2026-09-18-0001", out.idempotencyKey)
        assertEquals(EnergyOrderStatus.DELIVERED, out.status)
        assertTrue(out.succeeded)
        assertTrue(out.isTerminal)
        assertEquals(131_000, out.deliveredEnergy)
        assertTrue(out.settled)
        assertFalse(out.needsAttention)
        assertEquals(28_500_000, out.priceSun)
        assertEquals("8.12", out.priceUsd)
        assertEquals(81_200_000, out.credits)
        assertEquals("0.285", out.trxUsd)
        assertNull(out.error)
        assertNull(out.errorCode)
        assertEquals("2026-09-18T10:00:03Z", out.deliveredAt)

        val sent = server.takeRequest()
        assertEquals("energy-2026-09-18-0001", sent.getHeader("Idempotency-Key"))
        assertEquals(
            """{"receive_address":"TFrom","energy":131000,"quote_ref":"q-1"}""",
            sent.body.readByteArray().toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `a refused rent answers 502 with the order, which rent returns`() = runBlocking {
        enqueue(
            """
            {"id": 90211, "idempotency_key": "energy-2026-09-18-0002", "status": "refused",
             "receive_address": "TFrom", "energy": 131000, "duration_sec": 3600,
             "price_sun": 28500000, "price_trx": "28.5", "settled": true,
             "needs_attention": false, "error": "no supplier could fill this order",
             "created_at": "2026-09-18T10:00:00Z"}
            """.trimIndent(),
            code = 502,
        )

        val out = withIdempotencyKey("energy-2026-09-18-0002") {
            client.energy.rent(EnergyRentRequest(receiveAddress = "TFrom", energy = 131_000))
        }

        assertEquals(90211, out.id)
        assertEquals(EnergyOrderStatus.REFUSED, out.status)
        assertFalse(out.succeeded)
        assertTrue(out.isTerminal)
        assertTrue(out.settled)
        assertFalse(out.needsAttention)
        // Nothing was billed, so the billing fields are absent, not zeroed.
        assertNull(out.priceUsd)
        assertNull(out.credits)
        assertNull(out.trxUsd)
        assertEquals("no supplier could fill this order", out.error)
        assertNull(out.errorCode)
        assertNull(out.deliveredAt)
    }

    @Test
    fun `an unresolved rent answers 409 with the order, which rent returns`() = runBlocking {
        enqueue(
            """
            {"id": 90212, "idempotency_key": "energy-2026-09-18-0003", "status": "unresolved",
             "receive_address": "TFrom", "energy": 131000, "duration_sec": 3600,
             "price_sun": 28500000, "price_trx": "28.5", "price_usd": "8.12", "credits": 81200000,
             "trx_usd": "0.285", "settled": false, "needs_attention": true,
             "error": "supplier never answered", "created_at": "2026-09-18T10:00:00Z"}
            """.trimIndent(),
            code = 409,
        )

        val out = withIdempotencyKey("energy-2026-09-18-0003") {
            client.energy.rent(EnergyRentRequest(receiveAddress = "TFrom", energy = 131_000))
        }

        assertEquals(EnergyOrderStatus.UNRESOLVED, out.status)
        assertFalse(out.isTerminal)
        assertFalse(out.settled)
        assertTrue(out.needsAttention)
        assertEquals("supplier never answered", out.error)
        // Charged, because the energy may already be delegated.
        assertEquals(81_200_000, out.credits)
    }

    @Test
    fun `a rent short of credits answers 402 with the refused order, which rent returns`() = runBlocking {
        enqueue(
            """
            {"id": 90213, "idempotency_key": "energy-2026-09-18-0004", "status": "refused",
             "receive_address": "TFrom", "energy": 131000, "duration_sec": 3600,
             "price_sun": 28500000, "price_trx": "28.5", "settled": true,
             "needs_attention": false, "error": "credits balance is short",
             "error_code": "INSUFFICIENT_CREDITS", "created_at": "2026-09-18T10:00:00Z"}
            """.trimIndent(),
            code = 402,
        )

        val out = withIdempotencyKey("energy-2026-09-18-0004") {
            client.energy.rent(EnergyRentRequest(receiveAddress = "TFrom", energy = 131_000))
        }

        assertEquals(EnergyOrderStatus.REFUSED, out.status)
        assertEquals(ErrorCode.INSUFFICIENT_CREDITS, out.errorCode)
        assertNull(out.credits)
    }

    @Test
    fun `rent by quote ref alone puts no receive address on the wire`() = runBlocking {
        enqueue(deliveredBody)

        val out = withIdempotencyKey("energy-2026-09-18-0001") {
            client.energy.rent(EnergyRentRequest(quoteRef = "q-1"))
        }

        assertTrue(out.succeeded)
        val sent = server.takeRequest().body.readByteArray().toString(Charsets.UTF_8)
        assertEquals("""{"quote_ref":"q-1"}""", sent)
    }

    @Test
    fun `an error envelope from rent throws an ApiException`() = runBlocking {
        enqueue(
            """{"ok": false, "error": "SERVICE_ERROR", "msg": "QUOTE_EXPIRED"}""",
            code = 409,
        )

        val err = assertThrows<ApiException> {
            runBlocking {
                withIdempotencyKey("energy-2026-09-18-0005") {
                    client.energy.rent(EnergyRentRequest(quoteRef = "q-stale"))
                }
            }
        }
        assertEquals("QUOTE_EXPIRED", err.code)
        assertEquals(409, err.status)
    }

    @Test
    fun `order reads the rent back by its idempotency key`() = runBlocking {
        enqueue(deliveredBody)

        val out = client.energy.order("energy-2026-09-18-0001")

        assertEquals(90210, out.id)
        assertTrue(out.succeeded)
        val sent = server.takeRequest().body.readByteArray().toString(Charsets.UTF_8)
        assertEquals("""{"key":"energy-2026-09-18-0001"}""", sent)
    }

    @Test
    fun `an unresolved order is not terminal`() = runBlocking {
        enqueue(
            """
            {"id": 90214, "idempotency_key": "energy-2026-09-18-0006", "status": "unresolved",
             "receive_address": "TFrom", "energy": 131000, "duration_sec": 3600,
             "price_sun": 28500000, "price_trx": "28.5", "delivered_energy": 0,
             "settled": false, "needs_attention": true, "created_at": "2026-09-18T10:00:00Z"}
            """.trimIndent(),
        )

        val out = client.energy.order("energy-2026-09-18-0006")

        assertEquals(EnergyOrderStatus.UNRESOLVED, out.status)
        assertFalse(out.isTerminal)
        assertTrue(out.needsAttention)
    }
}
