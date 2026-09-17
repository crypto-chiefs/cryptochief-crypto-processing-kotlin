package com.cryptochief.processing

import com.cryptochief.processing.http.HttpTransport
import com.cryptochief.processing.http.RequestSigner
import com.cryptochief.processing.models.UuidRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import kotlin.math.abs

class HmacTransportTest {

    private val merchant = "3f2a1b4c-5d6e-7f80-9a1b-2c3d4e5f6071"
    private val apiKey = "test_api_key_123"
    private val path = "/v1/payout/info"
    private val payout = """{"uuid":"abc","status":"paid","network":"ETH_MAINNET","coin":"ETH","amount":"1","to_address":"0x"}"""
    private val outOfRange =
        """{"ok":false,"error":"SIGNATURE_TIMESTAMP_OUT_OF_RANGE","msg":"X-CC-Timestamp differs from server time by more than 300 seconds","server_time":1789430400}"""
    private val wlOutOfRange =
        """{"data":null,"error":{"status":401,"name":"UnauthorizedError","message":"Request timestamp is outside the allowed window","details":{"code":"SIGNATURE_TIMESTAMP_OUT_OF_RANGE","server_time":1789430400}},"server_time":1789430400}"""

    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun options(
        maxRetries: Int = 2,
        prefix: String = "",
        merchantId: String = merchant,
    ): Options =
        Options.builder().apply {
            this.merchantId = merchantId
            apiKey = this@HmacTransportTest.apiKey
            baseUrl = server.url("/$prefix").toString().trimEnd('/')
            this.maxRetries = maxRetries
            initialRetryDelay = Duration.ofMillis(1)
            maxRetryDelay = Duration.ofMillis(5)
            httpClient = http
        }.build()

    private suspend fun HttpTransport.info(uuid: String = "abc"): JsonObject =
        send(path, serializer<UuidRequest>(), serializer<JsonObject>(), UuidRequest(uuid))

    /** Checks `X-CC-Signature` against the request's own headers and body; returns the body. */
    private fun assertHmacValid(recorded: RecordedRequest): ByteArray {
        val body = recorded.body.readByteArray()
        val timestamp = recorded.getHeader("X-CC-Timestamp")!!
        val nonce = recorded.getHeader("X-CC-Nonce")!!
        assertTrue(Regex("^[0-9]+$").matches(timestamp), timestamp)
        assertTrue(Regex("^[0-9a-f]{32}$").matches(nonce), nonce)
        val expected = "v1=" + RequestSigner.signHmacV1(
            apiKey = apiKey,
            timestamp = timestamp,
            nonce = nonce,
            method = recorded.method!!,
            path = path,
            query = recorded.requestUrl!!.encodedQuery.orEmpty(),
            merchant = recorded.getHeader("Merchant")!!,
            idempotencyKey = recorded.getHeader(RequestSigner.HEADER_IDEMPOTENCY_KEY).orEmpty(),
            body = body,
        )
        assertEquals(expected, recorded.getHeader("X-CC-Signature"))
        assertNull(recorded.getHeader("Signature"))
        return body
    }

    @Test
    fun `client sends HMAC v1 headers and no Signature header`() = runBlocking {
        server.enqueue(MockResponse().setBody(payout))
        CryptoChiefClient(options(prefix = "api")).use { client -> client.payouts.info("abc") }

        val recorded = server.takeRequest()
        assertEquals("/api/v1/payout/info", recorded.path)
        val body = assertHmacValid(recorded)
        assertEquals("""{"uuid":"abc"}""", body.toString(Charsets.UTF_8))
        assertEquals(merchant, recorded.getHeader("Merchant"))
        val skew = abs(recorded.getHeader("X-CC-Timestamp")!!.toLong() - System.currentTimeMillis() / 1000)
        assertTrue(skew <= 5, "timestamp skew $skew s")
    }

    /**
     * The signed path is the route alone: a path in `baseUrl` is sent but not signed, so a gateway
     * reached under one refuses the request. `baseUrl` is an origin.
     */
    @Test
    fun `a path in the baseUrl is sent but not signed`() = runBlocking {
        server.enqueue(MockResponse().setBody(payout))
        CryptoChiefClient(options(prefix = "api")).use { client -> client.payouts.info("abc") }

        val recorded = server.takeRequest()
        assertEquals("/api$path", recorded.path)
        val body = recorded.body.clone().readByteArray()
        fun signedOver(signedPath: String): String = "v1=" + RequestSigner.signHmacV1(
            apiKey = apiKey,
            timestamp = recorded.getHeader("X-CC-Timestamp")!!,
            nonce = recorded.getHeader("X-CC-Nonce")!!,
            method = "POST",
            path = signedPath,
            query = "",
            merchant = merchant,
            idempotencyKey = "",
            body = body,
        )
        assertEquals(signedOver(path), recorded.getHeader("X-CC-Signature"))
        assertNotEquals(signedOver("/api$path"), recorded.getHeader("X-CC-Signature"))

        server.dispatcher = HmacV1Gateway(merchant, apiKey) { MockResponse().setBody(payout) }
        val refused = assertThrows<ApiException> {
            runBlocking { CryptoChiefClient(options(prefix = "api", maxRetries = 0)).use { it.payouts.info("abc") } }
        }
        assertEquals(ErrorCode.INVALID_SIGNATURE, refused.code)
        CryptoChiefClient(options(maxRetries = 0)).use { client ->
            assertEquals("abc", client.payouts.info("abc").uuid)
        }
    }

    @Test
    fun `merchantId with surrounding spaces and tabs signs as the trimmed value`() = runBlocking {
        for (padded in listOf(" $merchant ", "\t$merchant \t")) {
            val transport = HttpTransport(options(merchantId = padded), http) { 1789430400L }
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            transport.info()

            val recorded = server.takeRequest()
            val body = recorded.body.readByteArray()
            assertEquals(merchant, recorded.getHeader("Merchant"))
            val expected = "v1=" + RequestSigner.signHmacV1(
                apiKey = apiKey,
                timestamp = "1789430400",
                nonce = recorded.getHeader("X-CC-Nonce")!!,
                method = "POST",
                path = path,
                query = "",
                merchant = merchant,
                idempotencyKey = "",
                body = body,
            )
            assertEquals(expected, recorded.getHeader("X-CC-Signature"))
        }
    }

    @Test
    fun `no Idempotency-Key is sent unless one is set`() = runBlocking {
        server.enqueue(MockResponse().setBody(payout))
        CryptoChiefClient(options()).use { client -> client.payouts.info("abc") }

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader(RequestSigner.HEADER_IDEMPOTENCY_KEY))
        assertHmacValid(recorded)
    }

    @Test
    fun `an idempotency key is sent, signed, and accepted by the gateway`() = runBlocking {
        val key = "payout-2026-09-16-0001"
        server.dispatcher = HmacV1Gateway(merchant, apiKey) { MockResponse().setBody(payout) }
        CryptoChiefClient(options(maxRetries = 0)).use { client ->
            withIdempotencyKey(key) { client.payouts.info("abc") }
            // The key covers only the block it scopes.
            client.payouts.info("abc")
        }

        val withKey = server.takeRequest()
        assertEquals(key, withKey.getHeader(RequestSigner.HEADER_IDEMPOTENCY_KEY))
        assertHmacValid(withKey)
        assertNull(server.takeRequest().getHeader(RequestSigner.HEADER_IDEMPOTENCY_KEY))
    }

    /** Signed over the key it carries: a key added after signing is not covered and is refused. */
    @Test
    fun `a key added after signing is refused by the gateway`() = runBlocking {
        server.dispatcher = HmacV1Gateway(merchant, apiKey) { MockResponse().setBody(payout) }
        val adder = http.newBuilder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header(RequestSigner.HEADER_IDEMPOTENCY_KEY, "late").build())
        }.build()
        val options = Options.builder().apply {
            merchantId = merchant
            apiKey = this@HmacTransportTest.apiKey
            baseUrl = server.url("/").toString().trimEnd('/')
            maxRetries = 0
            httpClient = adder
        }.build()

        val refused = assertThrows<ApiException> { runBlocking { CryptoChiefClient(options).use { it.payouts.info("abc") } } }
        assertEquals(ErrorCode.INVALID_SIGNATURE, refused.code)
    }

    @Test
    fun `the key is resigned on every retry and survives a clock correction`() = runBlocking {
        var now = 1789430400L
        val transport = HttpTransport(options(), http) { now++ }
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"SERVICE_ERROR","msg":"try again"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody(outOfRange))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        withIdempotencyKey("batch-7") { transport.info() }

        assertEquals(3, server.requestCount)
        repeat(3) {
            val recorded = server.takeRequest()
            assertEquals("batch-7", recorded.getHeader(RequestSigner.HEADER_IDEMPOTENCY_KEY))
            assertHmacValid(recorded)
        }
    }

    @Test
    fun `an unsendable idempotency key is refused before the request`() = runBlocking {
        val transport = HttpTransport(options(), http)
        val bad = listOf("", " key", "key ", "ke\ty", "ke\ny", "ke­y", "клю ч", "key\u0000")
        for (value in bad) {
            assertThrows<IllegalArgumentException>(value) { runBlocking { withIdempotencyKey(value) { transport.info() } } }
        }
        assertEquals(0, server.requestCount)

        for (value in listOf("k", "a b", "order/1?x=2", "~!@#\$%^&*()_+", "x".repeat(512))) {
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            withIdempotencyKey(value) { transport.info() }
            assertEquals(value, server.takeRequest().getHeader(RequestSigner.HEADER_IDEMPOTENCY_KEY))
        }
    }

    @Test
    fun `nested scopes and a directly set context element apply`() = runBlocking {
        val transport = HttpTransport(options(), http)
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        withIdempotencyKey("outer") {
            withIdempotencyKey("inner") { transport.info() }
            transport.info()
        }
        assertEquals("inner", server.takeRequest().getHeader(RequestSigner.HEADER_IDEMPOTENCY_KEY))
        assertEquals("outer", server.takeRequest().getHeader(RequestSigner.HEADER_IDEMPOTENCY_KEY))

        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        withContext(IdempotencyKey("element")) { transport.info() }
        assertEquals("element", server.takeRequest().getHeader(RequestSigner.HEADER_IDEMPOTENCY_KEY))
    }

    @Test
    fun `a signed GET with a query passes the gateway`() = runBlocking {
        val balance = """{"credits":"10"}"""
        server.dispatcher = HmacV1Gateway(merchant, apiKey) { MockResponse().setBody(balance) }
        CryptoChiefClient(options(maxRetries = 0)).use { client ->
            assertEquals(balance, client.request("GET", "/v1/balance?uuid=5b0c7a52&a=1").toString(Charsets.UTF_8))
            assertEquals(
                "10",
                client.request("GET", "/v1/balance", responseSerializer = serializer<JsonObject>())
                    .getValue("credits").jsonPrimitive.content,
            )
        }

        val withQuery = server.takeRequest()
        assertEquals("GET", withQuery.method)
        assertEquals("/v1/balance?uuid=5b0c7a52&a=1", withQuery.path)
        assertEquals(0L, withQuery.bodySize)
        assertNull(withQuery.getHeader("Content-Type"))
        HmacV1Gateway.assertSigned(withQuery, apiKey)
        HmacV1Gateway.assertSigned(server.takeRequest(), apiKey)
    }

    /** The path is signed as the server reads it, the query as it was sent. */
    @Test
    fun `percent-escapes in the path are signed decoded and the query raw`() = runBlocking {
        server.dispatcher = HmacV1Gateway(merchant, apiKey) { MockResponse().setBody("""{"ok":true}""") }
        val sentPath = "/v1/orders/payout%2F8814/%D0%B7%D0%B0%D0%BA%D0%B0%D0%B7%20%E2%84%961"
        val sentQuery = "id=a%2Fb&note=%D1%82%D0%B5%D1%81%D1%82&sp=%20"
        CryptoChiefClient(options(maxRetries = 0)).use { it.request("GET", "$sentPath?$sentQuery") }

        val recorded = server.takeRequest()
        assertEquals("$sentPath?$sentQuery", recorded.path)
        val expected = "v1=" + RequestSigner.signHmacV1(
            apiKey = apiKey,
            timestamp = recorded.getHeader("X-CC-Timestamp")!!,
            nonce = recorded.getHeader("X-CC-Nonce")!!,
            method = "GET",
            path = "/v1/orders/payout/8814/заказ №1",
            query = sentQuery,
            merchant = merchant,
            idempotencyKey = "",
            body = ByteArray(0),
        )
        assertEquals(expected, recorded.getHeader("X-CC-Signature"))
    }

    /** Non-ASCII goes on the wire percent-encoded: the path is signed as written, the query as sent. */
    @Test
    fun `non-ASCII in the path and the query is signed as the server reads it`() = runBlocking {
        server.dispatcher = HmacV1Gateway(merchant, apiKey) { MockResponse().setBody("""{"ok":true}""") }
        CryptoChiefClient(options(maxRetries = 0)).use { it.request("GET", "/v1/заказ/№1?note=тест&plus=a+b") }

        val recorded = server.takeRequest()
        assertEquals("/v1/%D0%B7%D0%B0%D0%BA%D0%B0%D0%B7/%E2%84%961?note=%D1%82%D0%B5%D1%81%D1%82&plus=a+b", recorded.path)
        val expected = "v1=" + RequestSigner.signHmacV1(
            apiKey = apiKey,
            timestamp = recorded.getHeader("X-CC-Timestamp")!!,
            nonce = recorded.getHeader("X-CC-Nonce")!!,
            method = "GET",
            path = "/v1/заказ/№1",
            query = "note=%D1%82%D0%B5%D1%81%D1%82&plus=a+b",
            merchant = merchant,
            idempotencyKey = "",
            body = ByteArray(0),
        )
        assertEquals(expected, recorded.getHeader("X-CC-Signature"))
    }

    @Test
    fun `a method in mixed case is signed and sent with a-z in upper case`() = runBlocking {
        server.dispatcher = HmacV1Gateway(merchant, apiKey) { MockResponse().setBody("""{"ok":true}""") }
        CryptoChiefClient(options(maxRetries = 0)).use { client ->
            client.request("gEt", "/v1/balance")
            withIdempotencyKey("low-level-1") { client.request("DeLeTe", "/v1/wallets/1", """{}""".toByteArray()) }
        }

        val get = server.takeRequest()
        assertEquals("GET", get.method)
        HmacV1Gateway.assertSigned(get, apiKey)

        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("low-level-1", delete.getHeader(RequestSigner.HEADER_IDEMPOTENCY_KEY))
        assertEquals("""{}""", HmacV1Gateway.assertSigned(delete, apiKey).toString(Charsets.UTF_8))
    }

    @Test
    fun `a low-level request checks its arguments before sending`() = runBlocking {
        CryptoChiefClient(options(maxRetries = 0)).use { client ->
            for (bad in listOf("v1/balance", "", "https://other.example/v1/balance")) {
                assertThrows<IllegalArgumentException>(bad) { runBlocking { client.request("GET", bad) } }
            }
            assertThrows<IllegalArgumentException> { runBlocking { client.request("", "/v1/balance") } }
            assertThrows<IllegalArgumentException> {
                runBlocking { client.request("GET", "/v1/balance", """{}""".toByteArray()) }
            }
            assertThrows<IllegalArgumentException> { runBlocking { client.request("GET", "/v1/x/%2") } }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `client passes a gateway that checks HMAC v1`() = runBlocking {
        server.dispatcher = HmacV1Gateway(merchant, apiKey) { request ->
            when (request.requestUrl!!.encodedPath) {
                "/v1/credits/balance" -> MockResponse().setBody(
                    """{"credits_balance":1,"usd_balance":"0.01","is_postpaid":false,"timestamp":"2026-09-15T00:00:00Z"}""",
                )
                else -> MockResponse().setBody(payout)
            }
        }
        CryptoChiefClient(options(maxRetries = 0)).use { client ->
            assertEquals("abc", client.payouts.info("abc").uuid)
            assertEquals(1L, client.credits.balance().creditsBalance)
            assertEquals("abc", client.payouts.info("abc").uuid)
        }
        assertEquals(3, server.requestCount)
        repeat(3) { assertNull(server.takeRequest().getHeader("Signature")) }
    }

    @Test
    fun `client with a skewed clock passes the gateway after one correction`() = runBlocking {
        server.dispatcher = HmacV1Gateway(merchant, apiKey, now = { 1789430400L }) { MockResponse().setBody("""{"ok":true}""") }
        val transport = HttpTransport(options(maxRetries = 0), http) { 1_000_000_000L }
        transport.info()
        transport.info()

        assertEquals(3, server.requestCount)
        assertEquals("1000000000", server.takeRequest().getHeader("X-CC-Timestamp"))
        assertEquals("1789430400", server.takeRequest().getHeader("X-CC-Timestamp"))
        assertEquals("1789430400", server.takeRequest().getHeader("X-CC-Timestamp"))
    }

    @Test
    fun `gateway mock rejects Signature without X-CC headers, a changed body and a replayed nonce`() {
        server.dispatcher = HmacV1Gateway(merchant, apiKey) { MockResponse().setBody("""{"ok":true}""") }
        val url = server.url(path)
        val body = """{"uuid":"abc"}"""
        fun post(block: okhttp3.Request.Builder.() -> Unit, sent: String = body): Pair<Int, String> {
            val request = okhttp3.Request.Builder().url(url)
                .post(sent.toByteArray().toRequestBody("application/json".toMediaType()))
                .header("Merchant", merchant)
                .apply(block)
                .build()
            return http.newCall(request).execute().use { it.code to it.body!!.string() }
        }

        val signatureOnly = post({ header("Signature", "0123456789abcdef0123456789abcdef") })
        assertEquals(400, signatureOnly.first)
        assertTrue(signatureOnly.second.contains("BAD_AUTH_HEADERS"), signatureOnly.second)

        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val nonce = RequestSigner.newNonce()
        val signature = "v1=" + RequestSigner.signHmacV1(apiKey, timestamp, nonce, "POST", path, "", merchant, "", body.toByteArray())
        val signed: okhttp3.Request.Builder.() -> Unit = {
            header("X-CC-Timestamp", timestamp)
            header("X-CC-Nonce", nonce)
            header("X-CC-Signature", signature)
        }

        val changed = post(signed, """{"uuid":"abd"}""")
        assertEquals(401, changed.first)
        assertTrue(changed.second.contains("INVALID_SIGNATURE"), changed.second)

        assertEquals(200, post(signed).first)
        val replayed = post(signed)
        assertEquals(401, replayed.first)
        assertTrue(replayed.second.contains("SIGNATURE_REPLAYED"), replayed.second)
    }

    @Test
    fun `retry recomputes timestamp nonce and signature`() = runBlocking {
        var now = 1789430400L
        val transport = HttpTransport(options(), http) { now++ }
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"SERVICE_ERROR","msg":"try again"}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        transport.info()

        assertEquals(2, server.requestCount)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertHmacValid(first)
        assertHmacValid(second)
        assertEquals("1789430400", first.getHeader("X-CC-Timestamp"))
        assertEquals("1789430401", second.getHeader("X-CC-Timestamp"))
        assertNotEquals(first.getHeader("X-CC-Nonce"), second.getHeader("X-CC-Nonce"))
        assertNotEquals(first.getHeader("X-CC-Signature"), second.getHeader("X-CC-Signature"))
    }

    @Test
    fun `timestamp out of range corrects the clock once and repeats the request`() = runBlocking {
        val transport = HttpTransport(options(maxRetries = 0), http) { 1_000_000_000L }
        server.enqueue(MockResponse().setResponseCode(401).setBody(outOfRange))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        transport.info()

        assertEquals(2, server.requestCount)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("1000000000", first.getHeader("X-CC-Timestamp"))
        assertEquals("1789430400", second.getHeader("X-CC-Timestamp"))
        assertNotEquals(first.getHeader("X-CC-Nonce"), second.getHeader("X-CC-Nonce"))
        assertHmacValid(second)

        transport.info()
        assertEquals("1789430400", server.takeRequest().getHeader("X-CC-Timestamp"))
    }

    @Test
    fun `white-label timestamp out of range corrects the clock once and repeats the request`() = runBlocking {
        val transport = HttpTransport(options(maxRetries = 0), http) { 1_000_000_000L }
        server.enqueue(MockResponse().setResponseCode(401).setBody(wlOutOfRange))
        server.enqueue(MockResponse().setResponseCode(401).setBody(wlOutOfRange))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        val ex = assertThrows<ApiException> { runBlocking { transport.info() } }

        assertEquals(ErrorCode.SIGNATURE_TIMESTAMP_OUT_OF_RANGE, ex.code)
        assertEquals(401, ex.status)
        assertEquals("Request timestamp is outside the allowed window", ex.description)
        assertEquals(2, server.requestCount)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("1000000000", first.getHeader("X-CC-Timestamp"))
        assertEquals("1789430400", second.getHeader("X-CC-Timestamp"))
        assertNotEquals(first.getHeader("X-CC-Nonce"), second.getHeader("X-CC-Nonce"))
        assertHmacValid(second)

        transport.info()
        assertEquals(3, server.requestCount)
        assertEquals("1789430400", server.takeRequest().getHeader("X-CC-Timestamp"))
    }

    @Test
    fun `white-label server_time is read from error details`() = runBlocking {
        val transport = HttpTransport(options(maxRetries = 0), http) { 1_000_000_000L }
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"data":null,"error":{"status":401,"name":"UnauthorizedError","message":"Request timestamp is outside the allowed window","details":{"code":"SIGNATURE_TIMESTAMP_OUT_OF_RANGE","server_time":1789430400}}}""",
            ),
        )
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        transport.info()

        assertEquals(2, server.requestCount)
        server.takeRequest()
        assertEquals("1789430400", server.takeRequest().getHeader("X-CC-Timestamp"))
    }

    @Test
    fun `white-label refusals map to error codes`() = runBlocking {
        val transport = HttpTransport(options(), http)
        val cases = listOf(
            Triple(401, ErrorCode.INVALID_SIGNATURE, "Invalid signature"),
            Triple(401, ErrorCode.SIGNATURE_REPLAYED, "Nonce has already been used"),
            Triple(400, ErrorCode.BAD_AUTH_HEADERS, "Invalid authentication headers"),
            Triple(413, ErrorCode.PAYLOAD_TOO_LARGE, "Request body is larger than 1048576 bytes"),
        )
        val names = mapOf(400 to "ValidationError", 401 to "UnauthorizedError", 413 to "RequestEntityTooLargeError")
        for ((status, code, message) in cases) {
            val body = """{"data":null,"error":{"status":$status,"name":"${names[status]}","message":"$message","details":{"code":"$code"}}}"""
            server.enqueue(MockResponse().setResponseCode(status).setBody(body))
            val ex = assertThrows<ApiException> { runBlocking { transport.info() } }
            assertEquals(code, ex.code)
            assertEquals(status, ex.status)
            assertEquals(message, ex.description)
            assertEquals(body, ex.raw)
        }
        assertEquals(cases.size, server.requestCount)

        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"data":null,"error":{"status":404,"name":"NotFoundError","message":"Not Found","details":{}}}"""),
        )
        val noCode = assertThrows<ApiException> { runBlocking { transport.info() } }
        assertEquals("NotFoundError", noCode.code)
        assertEquals("Not Found", noCode.description)
    }

    @Test
    fun `white-label refusal without details code uses error name`() = runBlocking {
        val transport = HttpTransport(options(), http)
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"data":null,"error":{"status":401,"name":"UnauthorizedError","message":"Invalid signature","details":{}}}"""),
        )
        val unauthorized = assertThrows<ApiException> { runBlocking { transport.info() } }
        assertEquals("UnauthorizedError", unauthorized.code)
        assertEquals("Invalid signature", unauthorized.description)
        assertEquals(401, unauthorized.status)

        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"data":null,"error":{"status":400,"name":"ValidationError","message":"Invalid","details":{"code":""}}}"""),
        )
        assertEquals("ValidationError", assertThrows<ApiException> { runBlocking { transport.info() } }.code)

        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"data":null,"error":{"status":409,"message":"Conflict"}}"""),
        )
        assertEquals("HTTP_409", assertThrows<ApiException> { runBlocking { transport.info() } }.code)
    }

    @Test
    fun `second timestamp out of range is returned`() = runBlocking {
        val transport = HttpTransport(options(), http) { 1_000_000_000L }
        server.enqueue(MockResponse().setResponseCode(401).setBody(outOfRange))
        server.enqueue(MockResponse().setResponseCode(401).setBody(outOfRange))
        val ex = assertThrows<ApiException> { runBlocking { transport.info() } }

        assertEquals(ErrorCode.SIGNATURE_TIMESTAMP_OUT_OF_RANGE, ex.code)
        assertEquals(401, ex.status)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `timestamp out of range without server_time is not repeated`() = runBlocking {
        val transport = HttpTransport(options(), http) { 1_000_000_000L }
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"ok":false,"error":"SIGNATURE_TIMESTAMP_OUT_OF_RANGE","msg":"X-CC-Timestamp differs from server time by more than 300 seconds"}"""),
        )
        val ex = assertThrows<ApiException> { runBlocking { transport.info() } }

        assertEquals(ErrorCode.SIGNATURE_TIMESTAMP_OUT_OF_RANGE, ex.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `replayed nonce and oversized body map to error codes`() = runBlocking {
        val transport = HttpTransport(options(), http)
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"ok":false,"error":"SIGNATURE_REPLAYED","msg":"X-CC-Nonce has already been used"}"""),
        )
        val replayed = assertThrows<ApiException> { runBlocking { transport.info() } }
        assertEquals(ErrorCode.SIGNATURE_REPLAYED, replayed.code)

        server.enqueue(
            MockResponse().setResponseCode(413)
                .setBody("""{"ok":false,"error":"PAYLOAD_TOO_LARGE","msg":"request body exceeds 4096 bytes"}"""),
        )
        val tooLarge = assertThrows<ApiException> { runBlocking { transport.info() } }
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, tooLarge.code)
        assertEquals(413, tooLarge.status)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `signature error codes match the gateway`() {
        assertEquals("BAD_AUTH_HEADERS", ErrorCode.BAD_AUTH_HEADERS)
        assertEquals("SIGNATURE_TIMESTAMP_OUT_OF_RANGE", ErrorCode.SIGNATURE_TIMESTAMP_OUT_OF_RANGE)
        assertEquals("INVALID_SIGNATURE", ErrorCode.INVALID_SIGNATURE)
        assertEquals("SIGNATURE_REPLAYED", ErrorCode.SIGNATURE_REPLAYED)
        assertEquals("PAYLOAD_TOO_LARGE", ErrorCode.PAYLOAD_TOO_LARGE)
    }
}
