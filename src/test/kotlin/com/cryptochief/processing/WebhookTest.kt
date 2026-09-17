package com.cryptochief.processing

import com.cryptochief.processing.http.RequestSigner
import com.cryptochief.processing.models.SweepStatus
import com.cryptochief.processing.webhook.PayInWebhookEvent
import com.cryptochief.processing.webhook.PayoutWebhookEvent
import com.cryptochief.processing.webhook.SweepWebhookEvent
import com.cryptochief.processing.webhook.TransactionWebhookEvent
import com.cryptochief.processing.webhook.WebhookHeadersException
import com.cryptochief.processing.webhook.WebhookSignatureException
import com.cryptochief.processing.webhook.WebhookTimestampException
import com.cryptochief.processing.webhook.WebhookVerificationException
import com.cryptochief.processing.webhook.WebhookVerifier
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WebhookTest {

    private val apiKey = "secret"
    private val deliveryId = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
    private val ts = 1789430400L
    private val at = Instant.ofEpochSecond(ts)
    private val payoutBody = """{"event":"payout.paid","uuid":"u-1","order_id":"o-1","status":"paid"}"""

    private fun headers(
        body: ByteArray,
        timestamp: Long = ts,
        key: String = apiKey,
        delivery: String = deliveryId,
    ): Map<String, List<String>> = mapOf(
        WebhookVerifier.TIMESTAMP_HEADER to listOf(timestamp.toString()),
        WebhookVerifier.DELIVERY_HEADER to listOf(delivery),
        WebhookVerifier.SIGNATURE_HEADER to listOf(RequestSigner.signWebhookV1(key, timestamp, delivery, body)),
    )

    private fun verifyAt(
        body: ByteArray,
        headers: Map<String, List<String>>,
        tolerance: Duration = 300.seconds,
        now: Instant = at,
    ) = WebhookVerifier.verify(apiKey, body, headers, tolerance) { now }

    @Test
    fun `raw body verifies with a header map and a header function`() {
        val body = payoutBody.toByteArray()
        val headers = headers(body)
        verifyAt(body, headers)
        WebhookVerifier.verify(apiKey, body, { name -> headers[name]?.single() }, 300.seconds) { at }

        val current = headers(body, timestamp = Instant.now().epochSecond)
        WebhookVerifier.verify(apiKey, body, current)
        WebhookVerifier.verify(apiKey, body) { name -> current[name]?.single() }
    }

    @Test
    fun `the same JSON value in other bytes is rejected`() {
        val body = payoutBody.toByteArray()
        val headers = headers(body)
        val others = listOf(
            """{"uuid":"u-1","event":"payout.paid","order_id":"o-1","status":"paid"}""",
            """{"event": "payout.paid", "uuid": "u-1", "order_id": "o-1", "status": "paid"}""",
            payoutBody + "\n",
        )
        for (other in others) {
            assertThrows<WebhookSignatureException> { verifyAt(other.toByteArray(), headers) }
        }
        assertThrows<WebhookSignatureException> { verifyAt(payoutBody.replace("u-1", "u-2").toByteArray(), headers) }
        assertThrows<WebhookSignatureException> { verifyAt(body, headers(body, key = "other-key")) }
    }

    @Test
    fun `Signature and X-Webhook-Signature are not read`() {
        val body = payoutBody.toByteArray()
        val signature = headers(body).getValue(WebhookVerifier.SIGNATURE_HEADER)
        val legacy = mapOf(
            WebhookVerifier.TIMESTAMP_HEADER to listOf(ts.toString()),
            WebhookVerifier.DELIVERY_HEADER to listOf(deliveryId),
            "Signature" to signature,
            "X-Webhook-Signature" to signature,
        )
        assertThrows<WebhookHeadersException> { verifyAt(body, legacy) }
    }

    @Test
    fun `tolerance and clock are parameters`() {
        val body = payoutBody.toByteArray()
        val headers = headers(body)
        val later = at.plusSeconds(600)
        assertThrows<WebhookTimestampException> { verifyAt(body, headers, now = later) }
        verifyAt(body, headers, tolerance = 600.seconds, now = later)
        verifyAt(body, headers, tolerance = 600.seconds, now = at.minusSeconds(600))
        assertThrows<WebhookTimestampException> { verifyAt(body, headers, tolerance = 600.seconds, now = at.plusSeconds(601)) }

        // A non-positive tolerance means the default.
        verifyAt(body, headers, tolerance = Duration.ZERO, now = at.plusSeconds(300))
        verifyAt(body, headers, tolerance = (-1).seconds, now = at.minusSeconds(300))
        assertThrows<WebhookTimestampException> { verifyAt(body, headers, tolerance = Duration.ZERO, now = at.plusSeconds(301)) }

        verifyAt(body, headers, tolerance = Duration.INFINITE, now = Instant.ofEpochSecond(1))

        // Whole seconds only.
        verifyAt(body, headers, tolerance = 500.milliseconds, now = at.plusMillis(999))
        assertThrows<WebhookTimestampException> { verifyAt(body, headers, tolerance = 1500.milliseconds, now = at.plusSeconds(2)) }

        // The default clock is the system clock.
        assertThrows<WebhookTimestampException> { WebhookVerifier.verify(apiKey, body, headers) }
    }

    @Test
    fun `timestamp format is checked before the window, the window before the signature`() {
        val body = payoutBody.toByteArray()
        val headers = headers(body)

        val overflow = headers + (WebhookVerifier.TIMESTAMP_HEADER to listOf("99999999999999999999"))
        assertThrows<WebhookHeadersException> { verifyAt(body, overflow) }

        val zero = headers + (WebhookVerifier.TIMESTAMP_HEADER to listOf("0"))
        assertThrows<WebhookTimestampException> { verifyAt(body, zero) }
        assertThrows<WebhookHeadersException> { verifyAt(body, zero, now = Instant.ofEpochSecond(100)) }

        val wrongSignature = headers + (WebhookVerifier.SIGNATURE_HEADER to listOf("v1=" + "0".repeat(64)))
        assertThrows<WebhookTimestampException> { verifyAt(body, wrongSignature, now = at.plusSeconds(1000)) }
        assertThrows<WebhookSignatureException> { verifyAt(body, wrongSignature) }
    }

    /**
     * A leading zero is a second spelling of a timestamp that was signed once: the signature is
     * over the decimal number, so accepting `0<ts>` would let the header be rewritten in transit
     * without invalidating the signature.
     */
    @Test
    fun `a timestamp with a leading zero is rejected`() {
        val body = payoutBody.toByteArray()
        val headers = headers(body)
        for (value in listOf("0$ts", "000$ts", "00")) {
            val padded = headers + (WebhookVerifier.TIMESTAMP_HEADER to listOf(value))
            assertThrows<WebhookHeadersException> { verifyAt(body, padded) }
            assertThrows<WebhookHeadersException> {
                WebhookVerifier.verify(apiKey, body, { name -> padded[name]?.single() }, 300.seconds) { at }
            }
        }
        verifyAt(body, headers + (WebhookVerifier.TIMESTAMP_HEADER to listOf(" $ts\t")))
    }

    @Test
    fun `window check does not overflow`() {
        val body = "{}".toByteArray()
        val max = headers(body, timestamp = Long.MAX_VALUE)
        val beforeEpoch = Instant.ofEpochSecond(-1)
        assertThrows<WebhookTimestampException> { verifyAt(body, max, now = beforeEpoch) }
        assertThrows<WebhookTimestampException> { verifyAt(body, max, tolerance = Duration.INFINITE, now = beforeEpoch) }
        verifyAt(body, max, tolerance = Duration.INFINITE, now = Instant.EPOCH)
        verifyAt(body, headers(body), tolerance = Duration.INFINITE, now = Instant.MIN)
        verifyAt(body, headers(body), tolerance = Duration.INFINITE, now = Instant.MAX)
        assertThrows<WebhookTimestampException> { verifyAt(body, headers(body), now = Instant.MIN) }
        assertThrows<WebhookTimestampException> { verifyAt(body, headers(body), now = Instant.MAX) }
    }

    /** A header function returns one value per name, so only the map form sees a repeat. */
    @Test
    fun `a repeated header is rejected by the map form and invisible to a header function`() {
        val body = payoutBody.toByteArray()
        val headers = headers(body)
        for (name in listOf(WebhookVerifier.SIGNATURE_HEADER, WebhookVerifier.TIMESTAMP_HEADER, WebhookVerifier.DELIVERY_HEADER)) {
            val value = headers.getValue(name).single()
            val repeated = headers + (name to listOf(value, value))
            assertThrows<WebhookHeadersException> { verifyAt(body, repeated) }
            WebhookVerifier.verify(apiKey, body, { n -> repeated[n]?.first() }, 300.seconds) { at }
        }
    }

    @Test
    fun `header map names match like Go strings EqualFold`() {
        val body = payoutBody.toByteArray()
        val headers = headers(body)
        fun renamed(from: String, to: String) = headers - from + (to to headers.getValue(from))

        verifyAt(body, headers.mapKeys { it.key.lowercase() })
        verifyAt(body, headers.mapKeys { it.key.uppercase() })

        // U+0131 and U+0130 do not fold to i.
        assertThrows<WebhookHeadersException> { verifyAt(body, renamed(WebhookVerifier.TIMESTAMP_HEADER, "X-CC-T\u0131mestamp")) }
        assertThrows<WebhookHeadersException> { verifyAt(body, renamed(WebhookVerifier.TIMESTAMP_HEADER, "X-CC-T\u0130MESTAMP")) }
        verifyAt(body, headers + ("X-Webhook-Del\u0131very" to listOf(deliveryId)))
        verifyAt(body, headers + ("X-CC-S\u0130GNATURE" to listOf("v1=" + "0".repeat(64))))

        // U+212A folds to k, U+017F to s.
        verifyAt(body, renamed(WebhookVerifier.DELIVERY_HEADER, "X-Webhoo\u212A-Delivery"))
        verifyAt(body, renamed(WebhookVerifier.SIGNATURE_HEADER, "X-CC-\u017Fignature"))
        assertThrows<WebhookHeadersException> { verifyAt(body, headers + ("X-Webhoo\u212A-Delivery" to listOf(deliveryId))) }
        assertThrows<WebhookHeadersException> { verifyAt(body, headers + ("X-CC-Time\u017Ftamp" to listOf(ts.toString()))) }

        assertThrows<WebhookHeadersException> { verifyAt(body, renamed(WebhookVerifier.TIMESTAMP_HEADER, "X-CC-Timestamp ")) }
    }

    @Test
    fun `empty api key and invalid signing input are rejected`() {
        val body = payoutBody.toByteArray()
        val headers = headers(body)
        assertThrows<IllegalArgumentException> { WebhookVerifier.verify("", body, headers) }
        assertThrows<IllegalArgumentException> { WebhookVerifier.verify("", body, emptyMap()) }
        assertThrows<IllegalArgumentException> { WebhookVerifier.verify("", body) { null } }
        assertThrows<IllegalArgumentException> { RequestSigner.signWebhookV1("", ts, deliveryId, body) }
        assertThrows<IllegalArgumentException> { RequestSigner.webhookV1StringToSign(0, deliveryId, body) }
        assertThrows<IllegalArgumentException> { RequestSigner.webhookV1StringToSign(-1, deliveryId, body) }
        for (bad in listOf("", "a".repeat(129), "dlv.1", "dlv\r1", "dlv\n1", " dlv")) {
            assertThrows<IllegalArgumentException> { RequestSigner.signWebhookV1(apiKey, ts, bad, body) }
        }
        assertEquals(
            "CC-HMAC-SHA256-WEBHOOK-V1\n$ts\n$deliveryId\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            RequestSigner.webhookV1StringToSign(ts, deliveryId, ByteArray(0)),
        )
    }

    @Test
    fun `refusals share one base class`() {
        val e = assertThrows<WebhookVerificationException> { verifyAt(payoutBody.toByteArray(), emptyMap()) }
        assertTrue(e is WebhookHeadersException)
    }

    @Test
    fun `parse verifies, then decodes`() {
        val body = payoutBody.toByteArray()
        val headers = headers(body)

        val event = WebhookVerifier.parse<PayoutWebhookEvent>(apiKey, body, headers, 300.seconds) { at }
        assertEquals("u-1", event.uuid)
        assertEquals("paid", event.status)

        val viaFunction = WebhookVerifier.parse<PayoutWebhookEvent>(apiKey, body, { name -> headers[name]?.single() }, 300.seconds) { at }
        assertEquals(event, viaFunction)

        val viaSerializer = WebhookVerifier.parse(apiKey, body, headers, serializer<PayoutWebhookEvent>(), 300.seconds) { at }
        assertEquals(event, viaSerializer)

        val tampered = payoutBody.replace("u-1", "u-2").toByteArray()
        assertThrows<WebhookSignatureException> {
            WebhookVerifier.parse<PayoutWebhookEvent>(apiKey, tampered, headers, 300.seconds) { at }
        }

        val notAnEvent = "[1,2,3]".toByteArray()
        assertThrows<DecodeException> {
            WebhookVerifier.parse<PayoutWebhookEvent>(apiKey, notAnEvent, headers(notAnEvent), 300.seconds) { at }
        }
        val notJson = "not json".toByteArray()
        assertThrows<DecodeException> {
            WebhookVerifier.parse<PayoutWebhookEvent>(apiKey, notJson, headers(notJson), 300.seconds) { at }
        }
    }

    @Test
    fun `pay-in body with null members decodes`() {
        val body = (
            """{"event":"invoice.confirming","uuid":"i-1","order_id":"o-1","user_id":"u-1",""" +
                """"status":"confirm_check","prev_status":"pending","txid":null}"""
            ).toByteArray()
        val event = WebhookVerifier.parse<PayInWebhookEvent>(apiKey, body, headers(body), 300.seconds) { at }
        assertEquals("invoice.confirming", event.event)
        assertNull(event.txid)
    }

    @Test
    fun `JDK HTTP server handler verifies the body and headers as received`() {
        val events = LinkedBlockingQueue<PayoutWebhookEvent>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/webhook") { exchange ->
            val body = exchange.requestBody.readAllBytes()
            val status = try {
                events.add(WebhookVerifier.parse<PayoutWebhookEvent>(apiKey, body, exchange.requestHeaders))
                200
            } catch (e: WebhookVerificationException) {
                401
            } catch (e: DecodeException) {
                400
            }
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        val http = OkHttpClient()
        try {
            val url = "http://127.0.0.1:${server.address.port}/webhook"
            val now = Instant.now().epochSecond
            val body = payoutBody.toByteArray()
            val signature = RequestSigner.signWebhookV1(apiKey, now, deliveryId, body)
            fun post(sent: ByteArray, vararg headers: Pair<String, String>): Int {
                val builder = Request.Builder().url(url).post(sent.toRequestBody("application/json".toMediaType()))
                headers.forEach { (name, value) -> builder.addHeader(name, value) }
                return http.newCall(builder.build()).execute().use { it.code }
            }

            val upperHex = "v1=" + signature.removePrefix("v1=").uppercase()
            assertEquals(
                200,
                post(body, "x-cc-timestamp" to " $now\t", "X-WEBHOOK-DELIVERY" to deliveryId, "X-Cc-Signature" to upperHex),
            )
            assertEquals("u-1", events.poll(1, TimeUnit.SECONDS)!!.uuid)

            val signed = arrayOf("X-CC-Timestamp" to now.toString(), "X-Webhook-Delivery" to deliveryId, "X-CC-Signature" to signature)
            assertEquals(401, post(payoutBody.replace("u-1", "u-3").toByteArray(), *signed))
            assertEquals(401, post(body, *signed, "x-cc-signature" to signature))
            assertEquals(401, post(body, "Signature" to signature, "X-Webhook-Signature" to signature))
            val old = now - 301
            assertEquals(
                401,
                post(
                    body,
                    "X-CC-Timestamp" to old.toString(),
                    "X-Webhook-Delivery" to deliveryId,
                    "X-CC-Signature" to RequestSigner.signWebhookV1(apiKey, old, deliveryId, body),
                ),
            )
            assertEquals(400, post("[]".toByteArray(), "X-CC-Timestamp" to now.toString(), "X-Webhook-Delivery" to deliveryId,
                "X-CC-Signature" to RequestSigner.signWebhookV1(apiKey, now, deliveryId, "[]".toByteArray())))
            assertTrue(events.isEmpty())
        } finally {
            server.stop(0)
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    private inline fun <reified T> signedHandle(body: String): T {
        val bytes = body.toByteArray()
        return WebhookVerifier.parse<T>(apiKey, bytes, headers(bytes), 300.seconds) { at }
    }

    @Test
    fun `payout webhook carries the lowest source count, and each source its own`() {
        val event = signedHandle<PayoutWebhookEvent>(
            """
            {"event":"payout.paid","uuid":"p-1","order_id":"o-1","user_id":"u-1","status":"paid",
             "amount_requested":"150","amount_to_receive":"150","to_address":"TDest",
             "fee_info":{"fee_mode":"client","limit_currency":"USD"},
             "sources":[
               {"address":"TA","network":"TRON_MAINNET","coin":"USDT","amount_crypto":"100","need_refuel":false,
                "refuel_amount":"0","estimated_fee":"1","estimated_fee_fiat":"0.3","txid":"tx-a","confirmations":25},
               {"address":"TB","network":"TRON_MAINNET","coin":"USDT","amount_crypto":"50","need_refuel":true,
                "refuel_amount":"15","estimated_fee":"1","estimated_fee_fiat":"0.3","txid":"tx-b","confirmations":19}
             ],
             "service_operations":[
               {"type":"gas_refuel","context":"payout_prepare","status":"done","network":"TRON_MAINNET","coin":"TRX",
                "amount_native":"15","from_address":"TSvc","to_address":"TB","txid":"tx-gas","confirmations":31}
             ],
             "confirmations":19,"required_confirmations":19,
             "created_at":"2026-09-14T10:00:00Z","completed_at":"2026-09-14T10:02:00Z"}
            """.trimIndent(),
        )

        assertEquals(19, event.confirmations)
        // payout.paid went out because every source reached the depth, the lowest one included.
        assertEquals(19, event.requiredConfirmations)
        val sources = event.sources!!.jsonArray.map { it.jsonObject["confirmations"]!!.jsonPrimitive.int }
        assertEquals(listOf(25, 19), sources)
        val gas = event.serviceOperations!!.jsonArray.single().jsonObject
        assertEquals(31, gas["confirmations"]!!.jsonPrimitive.int)
    }

    @Test
    fun `payout webhook without any transaction has no count`() {
        val event = signedHandle<PayoutWebhookEvent>(
            """
            {"event":"payout.system_fail","uuid":"p-2","order_id":"o-2","user_id":"u-1","status":"system_fail",
             "amount_requested":"150","amount_to_receive":"150","to_address":"TDest",
             "fee_info":{"fee_mode":"client","limit_currency":"USD"},
             "sources":[{"address":"TA","network":"TRON_MAINNET","coin":"USDT","amount_crypto":"150",
                         "need_refuel":false,"refuel_amount":"0","estimated_fee":"1","estimated_fee_fiat":"0.3"}],
             "service_operations":[],
             "created_at":"2026-09-14T10:00:00Z","completed_at":null,"error_reason":"insufficient balance"}
            """.trimIndent(),
        )

        assertNull(event.confirmations)
        assertFalse(event.sources!!.jsonArray.single().jsonObject.containsKey("confirmations"))
        // A payout that predates the published depth has none, and that is not a decode error.
        assertNull(event.requiredConfirmations)
    }

    private fun sweepBody(sweepConfirmations: Int, required: Int?): String {
        val requiredField = if (required == null) "" else """"required_confirmations":$required,"""
        return """
            {"event":"sweep.confirmed","task_id":"task-1","status":"completed","wallet_address":"0xdeposit",
             "to_address":"0xmaster","network":"ETH_MAINNET","chain_family":"EVM","asset_symbol":"USDT",
             "asset_contract":"0xdAC17F958D2ee523a2206206994597C13D831ec7","asset_type":"token",
             "amount_raw":"1500000","amount_human":"1.5","sweep_tx_hash":"0xsweep",
             "sweep_confirmations":$sweepConfirmations,$requiredField
             "confirmed_at":"2026-09-14T10:05:00Z","type_work":"momentum","total_fee_usd":"0.4200"}
        """.trimIndent()
    }

    @Test
    fun `sweep webhook carries the depth it was held to`() {
        val event = signedHandle<SweepWebhookEvent>(sweepBody(sweepConfirmations = 12, required = 12))

        assertEquals(SweepWebhookEvent.EVENT_CONFIRMED, event.event)
        assertEquals(SweepStatus.COMPLETED, event.status)
        assertEquals(12, event.sweepConfirmations)
        assertEquals(12, event.requiredConfirmations)
        assertTrue(event.sweepConfirmations >= event.requiredConfirmations!!)
    }

    @Test
    fun `sweep webhook from an older sweep service has no depth and still decodes`() {
        val event = signedHandle<SweepWebhookEvent>(sweepBody(sweepConfirmations = 1, required = null))

        assertNull(event.requiredConfirmations)
        assertEquals(1, event.sweepConfirmations)
        assertEquals("task-1", event.taskId)
    }

    @Test
    fun `transaction webhook carries the count and the threshold`() {
        val confirmed = signedHandle<TransactionWebhookEvent>(
            """
            {"event":"transaction.confirmed","uuid":"t-1","status":"confirmed","network":"ETH_MAINNET",
             "chain_family":"EVM","type":"native","from_address":"0xfrom","to_address":"0xto","value":"1000",
             "tx_hash":"0xhash","confirmations":12,"required_confirmations":12,
             "expires_at":"2026-09-14T10:10:00Z","created_at":"2026-09-14T10:00:00Z","completed_at":"2026-09-14T10:03:00Z"}
            """.trimIndent(),
        )
        val expired = signedHandle<TransactionWebhookEvent>(
            """
            {"event":"transaction.expired","uuid":"t-2","status":"expired","network":"ETH_MAINNET",
             "chain_family":"EVM","type":"native","from_address":"0xfrom","to_address":"0xto",
             "confirmations":0,"required_confirmations":12,
             "expires_at":"2026-09-14T10:10:00Z","created_at":"2026-09-14T10:00:00Z"}
            """.trimIndent(),
        )

        assertEquals(12, confirmed.confirmations)
        assertEquals(12, confirmed.requiredConfirmations)
        assertEquals(0, expired.confirmations)
        assertEquals(12, expired.requiredConfirmations)
    }
}
