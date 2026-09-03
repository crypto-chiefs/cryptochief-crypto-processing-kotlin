package com.cryptochief.processing

import com.cryptochief.processing.models.WebhookDeliveryStatus
import com.cryptochief.processing.webhook.WebhookVerifier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The outbound-webhook surface: reading a delivery with its attempts, the three routes, and
 * that a refusal is an ApiException with the machine code rather than a queued=false result.
 */
class WebhooksServiceTest {

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

    private val deliveryBody = """
        {
          "uuid": "44444444-4444-4444-8444-444444444444",
          "event_type": "invoice.paid", "reference": "order-1", "target_url": "https://m.example/hook",
          "status": "failed", "attempts": 3, "max_attempts": 10, "resend_count": 1,
          "last_error": "HTTP 500", "last_http_status": 500, "next_attempt_at": null, "delivered_at": null,
          "created_at": "2026-09-03T10:00:00Z", "superseded_by": null,
          "attempt_history": [
            {"attempt": 3, "http_status": 500, "error": "HTTP 500", "duration_ms": 120, "target_url": "https://m.example/hook",
             "created_at": "2026-09-03T10:02:00Z", "response_body": "<html>oops", "response_content_type": "text/html", "response_truncated": true},
            {"attempt": 2, "http_status": null, "error": "dial tcp: connection refused", "duration_ms": null, "target_url": "https://m.example/hook",
             "created_at": null, "response_body": null, "response_content_type": null, "response_truncated": false}
          ],
          "payload": {"body": "{\"event\":\"invoice.paid\"}", "bytes": 24, "truncated": false}
        }
    """.trimIndent()

    @Test
    fun `info reads attempts and keeps null as not recorded`() = runBlocking {
        server.enqueue(MockResponse().setBody(deliveryBody).addHeader("Content-Type", "application/json"))

        val d = client.webhooks.info("44444444-4444-4444-8444-444444444444")

        val req = server.takeRequest()
        assertEquals("/v1/webhooks/info", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()) as JsonObject
        assertEquals("44444444-4444-4444-8444-444444444444", body["uuid"]!!.jsonPrimitive.content)

        assertEquals(WebhookDeliveryStatus.FAILED, d.status)
        assertEquals(500, d.lastHttpStatus)
        assertNull(d.deliveredAt)
        assertNull(d.supersededBy)
        assertEquals(2, d.attemptHistory.size)
        val (answered, silent) = d.attemptHistory
        assertTrue(answered.responseTruncated)
        assertEquals("text/html", answered.responseContentType)
        // An attempt nothing answered has no status and no body — only the error.
        assertNull(silent.httpStatus)
        assertNull(silent.responseBody)
        assertNull(silent.createdAt)
        assertTrue(silent.error!!.contains("connection refused"))
        assertEquals(24, d.payload.bytes)
    }

    @Test
    fun `resend static deposit is addressed by the deposit uuid`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"uuid":"dep-1","deliveries":[{"uuid":"d-1","event_type":"static_deposit.paid","reference":"dep-1",
                   "status":"delivered","queued":true,"attempts":2,"resend_count":1}],"queued":1,"total":1}""",
            ).addHeader("Content-Type", "application/json"),
        )

        val out = client.webhooks.resendStaticDeposit("dep-1")

        val req = server.takeRequest()
        assertEquals("/v1/static-deposits/resend", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()) as JsonObject
        assertEquals("dep-1", body["uuid"]!!.jsonPrimitive.content)
        assertEquals(1, out.queued)
        assertTrue(out.deliveries[0].queued)
        assertEquals(1, out.deliveries[0].resendCount)
    }

    @Test
    fun `refusal is an ApiException with the code`() {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"ok":false,"error":"DELIVERY_SUPERSEDED","msg":"not the latest; resend invoice.paid instead","superseded_by":"invoice.paid"}""",
            ).addHeader("Content-Type", "application/json"),
        )

        val e = assertThrows(ApiException::class.java) {
            runBlocking { client.webhooks.resend("44444444-4444-4444-8444-444444444444") }
        }

        assertEquals(ErrorCode.DELIVERY_SUPERSEDED, e.code)
        assertEquals(409, e.status)
        assertTrue(e.message!!.contains("invoice.paid"))
    }

    @Test
    fun `delivery header name`() {
        assertEquals("X-Webhook-Delivery", WebhookVerifier.DELIVERY_HEADER)
    }
}
