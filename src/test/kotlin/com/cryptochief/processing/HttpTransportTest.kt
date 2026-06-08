package com.cryptochief.processing

import com.cryptochief.processing.models.UuidRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

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
    }

    @Test
    fun `sends merchant signature headers and signs body`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"uuid":"abc","status":"paid","network":"ETH_MAINNET","coin":"ETH","amount":"1","to_address":"0x"}"""))
        client.payouts.info("abc")
        val recorded = server.takeRequest()
        assertEquals("mer_test", recorded.getHeader("Merchant"))
        assertTrue(!recorded.getHeader("Signature").isNullOrEmpty())
        assertEquals("application/json", recorded.getHeader("Content-Type"))
        assertEquals("application/json", recorded.getHeader("Accept"))
        assertTrue(recorded.getHeader("User-Agent")?.startsWith("cryptochief-kotlin/") == true)
        assertEquals("""{"uuid":"abc"}""", recorded.body.readUtf8())
    }

    @Test
    fun `5xx triggers retry`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"SERVICE_ERROR","msg":"try again"}"""))
        server.enqueue(MockResponse().setBody("""{"uuid":"abc","status":"paid","network":"ETH_MAINNET","coin":"ETH","amount":"1","to_address":"0x"}"""))
        val info = client.payouts.info("abc")
        assertEquals("abc", info.uuid)
        assertEquals(2, server.requestCount)
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
    fun `retry budget exhausted surfaces last error`() = runBlocking {
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":"SERVICE_ERROR","msg":"bad gateway"}"""))
        }
        val ex = assertThrows<ApiException> { runBlocking { client.payouts.info("a") } }
        assertEquals(502, ex.status)
        assertEquals(3, server.requestCount)
    }
}
