package com.cryptochief.processing

import com.cryptochief.processing.models.CreditsTopupRequest
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
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

class CreditsServiceTest {

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
                maxRetries = 2
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

    @Test
    fun `balance posts signed empty object and maps response`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "credits_balance": -15200000,
                  "usd_balance": "-1.52",
                  "is_postpaid": true,
                  "debt_limit_credits": 500000000,
                  "can_execute_gas_operations": true,
                  "gas_ops_min_credits": 3000000,
                  "timestamp": "2026-08-18T12:00:00Z"
                }
                """.trimIndent(),
            ),
        )

        val balance = client.credits.balance()

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/credits/balance", recorded.path)
        assertEquals("{}", recorded.body.readUtf8())
        assertEquals("mer_test", recorded.getHeader("Merchant"))
        assertEquals(expectedSignature("{}", "secret-key"), recorded.getHeader("Signature"))

        assertEquals(-15_200_000L, balance.creditsBalance)
        assertEquals("-1.52", balance.usdBalance)
        assertTrue(balance.isPostpaid)
        assertEquals(500_000_000L, balance.debtLimitCredits)
        assertTrue(balance.canExecuteGasOperations)
        assertEquals(3_000_000L, balance.gasOpsMinCredits)
        assertEquals("2026-08-18T12:00:00Z", balance.timestamp)
    }

    @Test
    fun `balance maps zero prepaid response`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "credits_balance": 42000000,
                  "usd_balance": "4.20",
                  "is_postpaid": false,
                  "debt_limit_credits": 0,
                  "can_execute_gas_operations": false,
                  "gas_ops_min_credits": 3000000,
                  "timestamp": "2026-08-18T12:00:00Z"
                }
                """.trimIndent(),
            ),
        )

        val balance = client.credits.balance()

        assertEquals(42_000_000L, balance.creditsBalance)
        assertEquals("4.20", balance.usdBalance)
        assertFalse(balance.isPostpaid)
        assertEquals(0L, balance.debtLimitCredits)
        assertFalse(balance.canExecuteGasOperations)
    }

    @Test
    fun `topup posts signed body with urls and maps full response`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "invoice_id": 987654321,
                  "payment_link": "https://pay.cryptochief.example/topup/abc123",
                  "amount": "100.00",
                  "currency": "USDT",
                  "status": "pending",
                  "order_uuid": "3f8f5e0a-1c2d-4e5f-8a9b-0c1d2e3f4a5b",
                  "expired_at": 1755518400
                }
                """.trimIndent(),
            ),
        )

        val topup = client.credits.topup(
            CreditsTopupRequest(
                amount = "100.00",
                currency = "USDT",
                urlSuccess = "https://shop.example/paid",
                urlError = "https://shop.example/failed",
            ),
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/credits/topup", recorded.path)
        val expectedBody = """{"amount":"100.00","currency":"USDT","url_error":"https://shop.example/failed","url_success":"https://shop.example/paid"}"""
        assertEquals(expectedBody, recorded.body.readUtf8())
        assertEquals("mer_test", recorded.getHeader("Merchant"))
        assertEquals(expectedSignature(expectedBody, "secret-key"), recorded.getHeader("Signature"))

        assertEquals(987_654_321L, topup.invoiceId)
        assertEquals("https://pay.cryptochief.example/topup/abc123", topup.paymentLink)
        assertEquals("100.00", topup.amount)
        assertEquals("USDT", topup.currency)
        assertEquals("pending", topup.status)
        assertEquals("3f8f5e0a-1c2d-4e5f-8a9b-0c1d2e3f4a5b", topup.orderUuid)
        assertEquals(1_755_518_400L, topup.expiredAt)
    }

    @Test
    fun `topup omits absent optional urls and maps minimal response`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "invoice_id": 555,
                  "payment_link": "https://pay.cryptochief.example/topup/def456",
                  "amount": "25",
                  "currency": "USDC",
                  "status": "pending"
                }
                """.trimIndent(),
            ),
        )

        val topup = client.credits.topup(CreditsTopupRequest(amount = "25", currency = "USDC"))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/credits/topup", recorded.path)
        val expectedBody = """{"amount":"25","currency":"USDC"}"""
        assertEquals(expectedBody, recorded.body.readUtf8())
        assertEquals(expectedSignature(expectedBody, "secret-key"), recorded.getHeader("Signature"))

        assertEquals(555L, topup.invoiceId)
        assertEquals("https://pay.cryptochief.example/topup/def456", topup.paymentLink)
        assertEquals("25", topup.amount)
        assertEquals("USDC", topup.currency)
        assertEquals("pending", topup.status)
        assertNull(topup.orderUuid)
        assertNull(topup.expiredAt)
    }

    private fun expectedSignature(canonical: String, key: String): String {
        val b64 = Base64.getEncoder().encodeToString(canonical.toByteArray())
        val md5 = MessageDigest.getInstance("MD5")
        md5.update((b64 + key).toByteArray())
        return md5.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
