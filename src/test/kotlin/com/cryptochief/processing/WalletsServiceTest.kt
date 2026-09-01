package com.cryptochief.processing

import com.cryptochief.processing.models.GenerateWalletRequest
import com.cryptochief.processing.models.WalletType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

class WalletsServiceTest {

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

    /** A static wallet with both nullable links filled in. */
    private val staticWalletBody = """
        {
          "type": "static",
          "address": "0xdead",
          "chain_family": "EVM",
          "frozen": false,
          "master_wallet_address": "0xbeef",
          "callback_url": "https://your.app/webhooks/deposit"
        }
    """.trimIndent()

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
    }

    private fun taken(): RecordedRequest = server.takeRequest()

    private fun RecordedRequest.json(): JsonObject =
        Json.parseToJsonElement(body.readUtf8()) as JsonObject

    // ---- label on generation -------------------------------------------------------

    @Test
    fun `generate sends the label under its documented name`() = runBlocking {
        enqueue(staticWalletBody)

        client.wallets.generate(
            GenerateWalletRequest(
                walletType = WalletType.STATIC,
                chainFamily = ChainFamily.EVM,
                masterWalletAddress = "0xbeef",
                callbackUrl = "https://your.app/webhooks/deposit",
                label = "shop-42 checkout",
            ),
        )

        val req = taken()
        assertEquals("/v1/wallets/generate", req.path)
        val body = req.json()
        assertEquals("static", body["wallet_type"]?.jsonPrimitive?.content)
        assertEquals("EVM", body["chain_family"]?.jsonPrimitive?.content)
        assertEquals("0xbeef", body["master_wallet_address"]?.jsonPrimitive?.content)
        assertEquals("https://your.app/webhooks/deposit", body["callback_url"]?.jsonPrimitive?.content)
        assertEquals("shop-42 checkout", body["label"]?.jsonPrimitive?.content)
    }

    @Test
    fun `generate leaves the label off the wire when it is unset`() = runBlocking {
        enqueue(staticWalletBody)

        client.wallets.generate(
            GenerateWalletRequest(
                walletType = WalletType.MASTER,
                chainFamily = ChainFamily.TRON,
            ),
        )

        val body = taken().json()
        // The endpoint refuses unknown and malformed fields rather than ignoring them,
        // and reads an empty label as no label - so an unset one must be absent, not "".
        assertFalse(body.containsKey("label"))
        assertFalse(body.containsKey("master_wallet_address"))
        assertFalse(body.containsKey("callback_url"))
        assertEquals(setOf("chain_family", "wallet_type"), body.keys)
    }

    @Test
    fun `a label applies to a master wallet too, not only a static one`() = runBlocking {
        enqueue("""{"type":"master","address":"TX","chain_family":"TRON","frozen":false,"master_wallet_address":null,"callback_url":null}""")

        val out = client.wallets.generate(
            GenerateWalletRequest(
                walletType = WalletType.MASTER,
                chainFamily = ChainFamily.TRON,
                label = "treasury",
            ),
        )

        assertEquals("treasury", taken().json()["label"]?.jsonPrimitive?.content)
        assertEquals(WalletType.MASTER, out.type)
    }

    // ---- rebind-master -------------------------------------------------------------

    @Test
    fun `rebindMaster posts both addresses under their documented names`() = runBlocking {
        enqueue(staticWalletBody)

        val out = client.wallets.rebindMaster(address = "0xdead", masterWalletAddress = "0xbeef")

        val req = taken()
        assertEquals("/v1/wallets/rebind-master", req.path)
        val body = req.json()
        assertEquals("0xdead", body["address"]?.jsonPrimitive?.content)
        // master_wallet_address is the canonical name across the whole surface; the
        // legacy master_address spelling must not be what this SDK sends.
        assertEquals("0xbeef", body["master_wallet_address"]?.jsonPrimitive?.content)
        assertEquals(setOf("address", "master_wallet_address"), body.keys)
        assertEquals("0xbeef", out.masterWalletAddress)
    }

    // ---- callback-url --------------------------------------------------------------

    @Test
    fun `setCallbackUrl posts the address and the url`() = runBlocking {
        enqueue(staticWalletBody)

        val out = client.wallets.setCallbackUrl("0xdead", "https://your.app/webhooks/deposit")

        val req = taken()
        assertEquals("/v1/wallets/callback-url", req.path)
        val body = req.json()
        assertEquals("0xdead", body["address"]?.jsonPrimitive?.content)
        assertEquals("https://your.app/webhooks/deposit", body["callback_url"]?.jsonPrimitive?.content)
        assertEquals(setOf("address", "callback_url"), body.keys)
        assertEquals("https://your.app/webhooks/deposit", out.callbackUrl)
    }

    @Test
    fun `an empty callback url is sent rather than omitted`() = runBlocking {
        enqueue(
            """{"type":"static","address":"0xdead","chain_family":"EVM","frozen":false,
               "master_wallet_address":"0xbeef","callback_url":null}""".trimIndent(),
        )

        val out = client.wallets.setCallbackUrl("0xdead", "")

        val body = taken().json()
        // "" means "stop announcing deposits here". Dropping it would turn a clear into
        // a missing field, which the endpoint refuses - the caller's intent would be lost
        // with an INVALID_PARAMS rather than carried out.
        assertTrue(body.containsKey("callback_url"))
        assertEquals("", body["callback_url"]?.jsonPrimitive?.content)
        assertNull(out.callbackUrl)
    }

    @Test
    fun `clearCallbackUrl is setCallbackUrl with an empty url`() = runBlocking {
        enqueue(
            """{"type":"static","address":"0xdead","chain_family":"EVM","frozen":false,
               "master_wallet_address":"0xbeef","callback_url":null}""".trimIndent(),
        )

        client.wallets.clearCallbackUrl("0xdead")

        val body = taken().json()
        assertEquals("", body["callback_url"]?.jsonPrimitive?.content)
        assertEquals(setOf("address", "callback_url"), body.keys)
    }

    // ---- the wallet-info response shape --------------------------------------------

    @Test
    fun `a null master wallet address and callback url decode cleanly`() = runBlocking {
        enqueue(
            """
            {
              "type": "master",
              "address": "0xbeef",
              "chain_family": "EVM",
              "frozen": false,
              "master_wallet_address": null,
              "callback_url": null
            }
            """.trimIndent(),
        )

        val out = client.wallets.rebindMaster("0xdead", "0xbeef")

        // Both keys are always present and null when there is no value - never absent,
        // never an empty string. A null has to read as "no value", not blow up.
        assertEquals(WalletType.MASTER, out.type)
        assertEquals("0xbeef", out.address)
        assertEquals(ChainFamily.EVM, out.chainFamily)
        assertFalse(out.frozen)
        assertNull(out.masterWalletAddress)
        assertNull(out.callbackUrl)
    }

    @Test
    fun `a transit wallet decodes with a master and no callback`() = runBlocking {
        enqueue(
            """{"type":"transit","address":"0xcafe","chain_family":"EVM","frozen":true,
               "master_wallet_address":"0xbeef","callback_url":null}""".trimIndent(),
        )

        val out = client.wallets.setCallbackUrl("0xcafe", "https://ignored")

        assertEquals(WalletType.TRANSIT, out.type)
        assertTrue(out.frozen)
        assertEquals("0xbeef", out.masterWalletAddress)
        // A transit wallet has no per-deposit callback: always null.
        assertNull(out.callbackUrl)
    }
}
