package com.cryptochief.processing

import com.cryptochief.processing.http.HttpTransport
import com.cryptochief.processing.http.SdkJson
import com.cryptochief.processing.models.UuidRequest
import com.cryptochief.processing.webhook.WebhookVerifier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.net.ConnectException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class HttpTransportTest {

    @Serializable
    private data class Echo(@SerialName("uuid") val uuid: String, @SerialName("status") val status: String)

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
        for (http in ownHttpClients) {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    private val payout =
        """{"uuid":"abc","status":"paid","network":"ETH_MAINNET","coin":"ETH","amount":"1","to_address":"0x"}"""

    private val ownHttpClients = mutableListOf<OkHttpClient>()

    /** Fails the first [failures] calls with a connection error before anything is sent; counts every call. */
    private fun failingFirst(failures: AtomicInteger, attempts: AtomicInteger): OkHttpClient =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                attempts.incrementAndGet()
                if (failures.getAndDecrement() > 0) throw ConnectException("Connection refused")
                chain.proceed(chain.request())
            }
            .build()
            .also { ownHttpClients += it }

    private fun clientWith(http: OkHttpClient): CryptoChiefClient =
        CryptoChiefClient(
            Options.builder().apply {
                merchantId = "mer_test"
                apiKey = "secret-key"
                baseUrl = server.url("/").toString().trimEnd('/')
                maxRetries = 2
                initialRetryDelay = Duration.ofMillis(1)
                maxRetryDelay = Duration.ofMillis(5)
                httpClient = http
            }.build(),
        )

    @Test
    fun `sends merchant signature headers and signs body`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"uuid":"abc","status":"paid","network":"ETH_MAINNET","coin":"ETH","amount":"1","to_address":"0x"}"""))
        client.payouts.info("abc")
        val recorded = server.takeRequest()
        assertEquals("mer_test", recorded.getHeader("Merchant"))
        HmacV1Gateway.assertSigned(recorded, "secret-key")
        assertEquals("application/json", recorded.getHeader("Content-Type"))
        assertEquals("application/json", recorded.getHeader("Accept"))
        assertTrue(recorded.getHeader("User-Agent")?.startsWith("cryptochief-kotlin/") == true)
        assertEquals("""{"uuid":"abc"}""", recorded.body.readUtf8())
    }

    @Test
    fun `502, 503 and 504 are retried and a later success is returned`() = runBlocking {
        for (status in listOf(502, 503, 504)) {
            val before = server.requestCount
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":"SERVICE_ERROR","msg":"try again"}"""))
            server.enqueue(MockResponse().setBody(payout))
            val info = client.payouts.info("abc")
            assertEquals("abc", info.uuid)
            assertEquals(2, server.requestCount - before, "HTTP $status")
        }
    }

    @Test
    fun `502, 503 and 504 are attempted retries+1 times, then thrown`() = runBlocking {
        for (status in listOf(502, 503, 504)) {
            val before = server.requestCount
            repeat(3) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":"SERVICE_ERROR","msg":"try again"}"""))
            }
            val ex = assertThrows<ApiException> { runBlocking { client.payouts.info("abc") } }
            assertEquals(status, ex.status)
            assertTrue(ex.retryable, "HTTP $status")
            assertEquals(3, server.requestCount - before, "HTTP $status")
        }
    }

    @Test
    fun `500 is attempted exactly once`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"ok":false,"error":"INTERNAL_ERROR","msg":"internal error"}"""))
        server.enqueue(MockResponse().setBody(payout))
        val ex = assertThrows<ApiException> { runBlocking { client.payouts.info("abc") } }
        assertEquals(500, ex.status)
        assertEquals("INTERNAL_ERROR", ex.code)
        assertFalse(ex.retryable)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `statuses other than 502, 503 and 504 are attempted once`() = runBlocking {
        for (status in listOf(500, 501, 505, 520, 599, 429, 409, 400)) {
            val before = server.requestCount
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":"SERVICE_ERROR","msg":"nope"}"""))
            val ex = assertThrows<ApiException> { runBlocking { client.payouts.info("abc") } }
            assertEquals(status, ex.status)
            assertFalse(ex.retryable, "HTTP $status")
            assertEquals(1, server.requestCount - before, "HTTP $status")
        }
    }

    @Test
    fun `a connection failure is retried`() = runBlocking {
        val failures = AtomicInteger(1)
        val attempts = AtomicInteger()
        server.enqueue(MockResponse().setBody(payout))
        clientWith(failingFirst(failures, attempts)).use { c ->
            assertEquals("abc", c.payouts.info("abc").uuid)
        }
        assertEquals(2, attempts.get())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a connection failure on every attempt is attempted retries+1 times`() = runBlocking {
        val attempts = AtomicInteger()
        clientWith(failingFirst(AtomicInteger(Int.MAX_VALUE), attempts)).use { c ->
            assertThrows<NetworkException> { runBlocking { c.payouts.info("abc") } }
        }
        assertEquals(3, attempts.get())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a response body read failure is retried`() = runBlocking {
        server.enqueue(MockResponse().setBody(payout).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        server.enqueue(MockResponse().setBody(payout))
        assertEquals("abc", client.payouts.info("abc").uuid)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `retryable is true for 502, 503, 504 only`() {
        for (status in listOf(502, 503, 504)) {
            assertTrue(ApiException("SERVICE_ERROR", status, "x").retryable, "HTTP $status")
        }
        for (status in listOf(200, 400, 401, 404, 409, 429, 500, 501, 505, 520, 599)) {
            assertFalse(ApiException("SERVICE_ERROR", status, "x").retryable, "HTTP $status")
        }
        assertFalse(ApiException(ErrorCode.NETWORK_ERROR, 500, "x").retryable)
        assertTrue(ApiException(ErrorCode.NETWORK_ERROR, 0, "x").retryable)
    }

    @Test
    fun `4xx does not retry`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"INVALID_PARAMS"}"""))
        val ex = assertThrows<ApiException> {
            runBlocking { client.payouts.info("abc") }
        }
        assertEquals(ErrorCode.INVALID_PARAMS, ex.code)
        assertEquals(400, ex.status)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `parses error envelope variants`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"UNAUTHORIZED"}"""))
        val ex1 = assertThrows<ApiException> { runBlocking { client.payouts.info("a") } }
        assertEquals(ErrorCode.UNAUTHORIZED, ex1.code)

        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"SERVICE_ERROR","msg":"BATCH_EMPTY"}"""))
        val ex2 = assertThrows<ApiException> { runBlocking { client.payouts.info("b") } }
        assertEquals(ErrorCode.BATCH_EMPTY, ex2.code)

        server.enqueue(MockResponse().setResponseCode(418).setBody("teapot"))
        val ex3 = assertThrows<ApiException> { runBlocking { client.payouts.info("c") } }
        assertEquals("HTTP_418", ex3.code)
        assertEquals(418, ex3.status)
    }

    @Test
    fun `gateway envelope resolves the code from error and keeps the sentence`() = runBlocking {
        val body = """{"ok":false,"error":"LABEL_TOO_LONG","msg":"label is longer than 255 characters"}"""
        server.enqueue(MockResponse().setResponseCode(400).setBody(body))
        val ex = assertThrows<ApiException> { runBlocking { client.payouts.info("a") } }

        // The machine code is the one the gateway put in `error`, not the English sentence.
        assertEquals(ErrorCode.LABEL_TOO_LONG, ex.code)
        // The sentence survives as the human-readable half.
        assertEquals("label is longer than 255 characters", ex.description)
        assertTrue(ex.message!!.contains("label is longer than 255 characters"))
        // Nothing is lost: the raw body is kept whole.
        assertEquals(body, ex.raw)
    }

    @Test
    fun `a when over ErrorCode constants matches a gateway refusal`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"ok":false,"error":"LABEL_TOO_LONG","msg":"label is longer than 255 characters"}"""),
        )
        val ex = assertThrows<ApiException> { runBlocking { client.payouts.info("a") } }

        val matched = when (ex.code) {
            ErrorCode.LABEL_TOO_LONG -> true
            else -> false
        }
        assertTrue(matched, "ErrorCode.LABEL_TOO_LONG must match a gateway refusal, got code=${ex.code}")
    }

    @Test
    fun `upstream envelope still resolves the code from msg`() = runBlocking {
        val body = """{"ok":false,"error":"SERVICE_ERROR","msg":"wallet_not_found"}"""
        server.enqueue(MockResponse().setResponseCode(400).setBody(body))
        val ex = assertThrows<ApiException> { runBlocking { client.payouts.info("a") } }

        assertEquals("wallet_not_found", ex.code)
        assertEquals("wallet_not_found", ex.description)
        assertEquals(body, ex.raw)
    }


    /**
     * Responses, TON RPC and webhook events are decoded by one [SdkJson.instance]; a second
     * configuration would let the decoders diverge without a failing test.
     */
    @Test
    fun `one JSON configuration is shared`() {
        val options = Options.builder().apply {
            merchantId = "mer_test"
            apiKey = "secret-key"
        }.build()
        assertSame(SdkJson.instance, HttpTransport(options, OkHttpClient()).json)
        assertSame(SdkJson.instance, WebhookVerifier.json)

        val sources = File("src/main/kotlin")
        assumeTrue(sources.isDirectory, "run from the project directory")
        val builders = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "SdkJson.kt" }
            .filter { it.readText().contains("Json {") }
            .map { it.name }
            .toList()
        assertEquals(emptyList<String>(), builders, "Json configuration outside SdkJson")
    }
}
