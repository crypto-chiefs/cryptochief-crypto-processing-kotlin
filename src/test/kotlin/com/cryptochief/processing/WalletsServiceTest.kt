package com.cryptochief.processing

import com.cryptochief.processing.models.GenerateWalletRequest
import com.cryptochief.processing.models.PayIn
import com.cryptochief.processing.models.PayInHistoryResponse
import com.cryptochief.processing.models.PayInStatus
import com.cryptochief.processing.models.WalletHistoryQuery
import com.cryptochief.processing.models.WalletType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
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

    /** A static wallet with every nullable field filled in. */
    private val staticWalletBody = """
        {
          "type": "static",
          "address": "0xdead",
          "chain_family": "EVM",
          "frozen": false,
          "master_wallet_address": "0xbeef",
          "callback_url": "https://your.app/webhooks/deposit",
          "label": "shop-42 checkout"
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

    // ---- label ---------------------------------------------------------------------

    @Test
    fun `setLabel posts the address and the label`() = runBlocking {
        enqueue(staticWalletBody)

        val out = client.wallets.setLabel("0xdead", "shop-42 checkout")

        val req = taken()
        assertEquals("/v1/wallets/label", req.path)
        val body = req.json()
        assertEquals("0xdead", body["address"]?.jsonPrimitive?.content)
        assertEquals("shop-42 checkout", body["label"]?.jsonPrimitive?.content)
        assertEquals(setOf("address", "label"), body.keys)
        assertEquals("shop-42 checkout", out.label)
    }

    @Test
    fun `an empty label is sent rather than omitted`() = runBlocking {
        enqueue(
            """{"type":"static","address":"0xdead","chain_family":"EVM","frozen":false,
               "master_wallet_address":"0xbeef","callback_url":null,"label":null}""".trimIndent(),
        )

        val out = client.wallets.setLabel("0xdead", "")

        val body = taken().json()
        // "" means "this wallet has no name". Dropping it would turn a clear into a
        // missing field, which the endpoint refuses - the caller's intent would be lost
        // with an INVALID_PARAMS rather than carried out.
        assertTrue(body.containsKey("label"))
        assertEquals("", body["label"]?.jsonPrimitive?.content)
        // And a cleared name reads back as null, never as the empty string that cleared it.
        assertNull(out.label)
    }

    @Test
    fun `clearLabel is setLabel with an empty label`() = runBlocking {
        enqueue(
            """{"type":"static","address":"0xdead","chain_family":"EVM","frozen":false,
               "master_wallet_address":"0xbeef","callback_url":null,"label":null}""".trimIndent(),
        )

        client.wallets.clearLabel("0xdead")

        val body = taken().json()
        assertEquals("", body["label"]?.jsonPrimitive?.content)
        assertEquals(setOf("address", "label"), body.keys)
    }

    @Test
    fun `a master wallet can be renamed too, unlike its callback url`() = runBlocking {
        enqueue(
            """{"type":"master","address":"TX","chain_family":"TRON","frozen":false,
               "master_wallet_address":null,"callback_url":null,"label":"treasury"}""".trimIndent(),
        )

        val out = client.wallets.setLabel("TX", "treasury")

        assertEquals("/v1/wallets/label", taken().path)
        assertEquals(WalletType.MASTER, out.type)
        assertEquals("treasury", out.label)
        // The callback stays a static-only concept; a name is not.
        assertNull(out.callbackUrl)
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

    // ---- pay-in history for one deposit address -------------------------------------

    @Test
    fun `history posts the address with the optional window and page`() = runBlocking {
        enqueue("""{"items":[],"meta":{"page":2,"page_size":50,"total":0}}""")

        val out = client.wallets.history(
            WalletHistoryQuery(
                address = "0x77EDde3213b70c9dd224C874c28f41B23B070f65",
                dateFrom = "2026-01-01T00:00:00+00:00",
                dateTo = "2026-02-01T00:00:00+00:00",
                page = 2,
                pageSize = 50,
            ),
        )

        val req = taken()
        assertEquals("/v1/wallets/history", req.path)
        val body = req.json()
        assertEquals("0x77EDde3213b70c9dd224C874c28f41B23B070f65", body["address"]?.jsonPrimitive?.content)
        assertEquals("2026-01-01T00:00:00+00:00", body["date_from"]?.jsonPrimitive?.content)
        assertEquals("2026-02-01T00:00:00+00:00", body["date_to"]?.jsonPrimitive?.content)
        assertEquals(2, body["page"]?.jsonPrimitive?.int)
        assertEquals(50, body["page_size"]?.jsonPrimitive?.int)
        // An address you do not own is an empty page, not an error - so an empty items
        // list has to read as a normal answer.
        assertTrue(out.items.isEmpty())
        assertEquals(2, out.meta.page)
        assertEquals(0, out.meta.total)
    }

    @Test
    fun `history omits the window when it was not asked for`() = runBlocking {
        enqueue("""{"items":[],"meta":{"page":1,"page_size":20,"total":0}}""")

        client.wallets.history(WalletHistoryQuery(address = "0xdead"))

        // The dates are optional; an empty string is a value the endpoint would have to
        // reject, not the "no window" the caller meant.
        assertEquals(setOf("address"), taken().json().keys)
    }

    @Test
    fun `history answers in the same shape as pay-in history`() = runBlocking {
        // Same records as /v1/payments/history, narrowed to one address - so they decode
        // into the very same order type, not a parallel one.
        val order = """
            {
              "type": "PayIn",
              "uuid": "0a1b2c3d-4e5f-6789-abcd-ef0123456789",
              "order_id": "invoice-1002",
              "user_id": "user-777",
              "status": "paid",
              "mode": "crypto",
              "amount_crypto": "10.5",
              "payment_coin": "USDT",
              "payment_network": "TRON_MAINNET",
              "to_address": "TQrY8bYc2yQ8sM8nJ1sZ9c2Zx7L2wq7pQb",
              "created_at": "2026-01-20T02:55:49.976372Z"
            }
        """.trimIndent()
        enqueue("""{"items":[$order],"meta":{"page":1,"page_size":20,"total":1}}""")

        val out: PayInHistoryResponse = client.wallets.history(
            WalletHistoryQuery(address = "TQrY8bYc2yQ8sM8nJ1sZ9c2Zx7L2wq7pQb"),
        )

        val payIn: PayIn = out.items.single()
        assertEquals("invoice-1002", payIn.orderId)
        assertEquals(PayInStatus.PAID, payIn.status)
        assertTrue(payIn.succeeded)
        assertTrue(payIn.isTerminal)
        assertEquals("10.5", payIn.amountCrypto)
        assertEquals("USDT", payIn.paymentCoin)
        assertEquals(Chain.TRON_MAINNET, payIn.paymentNetwork)
        assertEquals("TQrY8bYc2yQ8sM8nJ1sZ9c2Zx7L2wq7pQb", payIn.toAddress)
        assertEquals(1, out.meta.total)
    }

    // ---- the wallet-info response shape --------------------------------------------

    @Test
    fun `a null master wallet address, callback url and label decode cleanly`() = runBlocking {
        enqueue(
            """
            {
              "type": "master",
              "address": "0xbeef",
              "chain_family": "EVM",
              "frozen": false,
              "master_wallet_address": null,
              "callback_url": null,
              "label": null
            }
            """.trimIndent(),
        )

        val out = client.wallets.rebindMaster("0xdead", "0xbeef")

        // All three keys are always present and null when there is no value - never
        // absent, never an empty string. A null has to read as "no value", not blow up.
        assertEquals(WalletType.MASTER, out.type)
        assertEquals("0xbeef", out.address)
        assertEquals(ChainFamily.EVM, out.chainFamily)
        assertFalse(out.frozen)
        assertNull(out.masterWalletAddress)
        assertNull(out.callbackUrl)
        assertNull(out.label)
    }

    @Test
    fun `the label rides along on info and on the list`() = runBlocking {
        enqueue(staticWalletBody)
        enqueue(
            """
            {
              "items": [
                {"type":"master","address":"0xbeef","chain_family":"EVM","frozen":false,
                 "master_wallet_address":null,"callback_url":null,"label":"treasury"},
                {"type":"static","address":"0xcafe","chain_family":"EVM","frozen":false,
                 "master_wallet_address":"0xbeef","callback_url":null,"label":null}
              ]
            }
            """.trimIndent(),
        )

        assertEquals("shop-42 checkout", client.wallets.info("0xdead").label)

        val items = client.wallets.list().items
        assertEquals("treasury", items[0].label)
        // An unnamed wallet in a list is null, and reads as one without a special case.
        assertNull(items[1].label)
    }

    @Test
    fun `a transit wallet decodes with a master and no callback`() = runBlocking {
        enqueue(
            """{"type":"transit","address":"0xcafe","chain_family":"EVM","frozen":true,
               "master_wallet_address":"0xbeef","callback_url":null,"label":"hot transit"}""".trimIndent(),
        )

        val out = client.wallets.setCallbackUrl("0xcafe", "https://ignored")

        assertEquals(WalletType.TRANSIT, out.type)
        assertTrue(out.frozen)
        assertEquals("0xbeef", out.masterWalletAddress)
        assertEquals("hot transit", out.label)
        // A transit wallet has no per-deposit callback: always null.
        assertNull(out.callbackUrl)
    }
}
