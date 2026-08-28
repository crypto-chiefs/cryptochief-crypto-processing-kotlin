package com.cryptochief.processing

import com.cryptochief.processing.models.CreatePayInRequest
import com.cryptochief.processing.models.Environment
import com.cryptochief.processing.models.SweepFieldWrite
import com.cryptochief.processing.models.SweepPolicyMode
import com.cryptochief.processing.models.SweepSettingsQuery
import com.cryptochief.processing.models.SweepStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

class SweepsServiceTest {

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

    private val settingsBody = """
        {
          "wallet_address": "0xabc",
          "network_code": "ETH_MAINNET",
          "effective": {"type_work":"threshold","threshold_amount_usd":"250","fee_mode":"mix","source":"wallet"},
          "override": {"network_code":"","type_work":"threshold","threshold_amount_usd":"250","fee_mode":null,"source":"merchant","locked":false},
          "project_default": {"type_work":"momentum","fee_mode":"client"}
        }
    """.trimIndent()

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
    }

    private fun sentBody(): JsonObject =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()) as JsonObject

    @Test
    fun `settings returns three distinguishable layers`() = runBlocking {
        enqueue(settingsBody)

        val out = client.sweeps.settings(SweepSettingsQuery(address = "0xabc"))

        assertEquals(SweepPolicyMode.THRESHOLD, out.effective.typeWork)
        assertEquals("250", out.effective.thresholdAmountUsd)
        assertEquals("wallet", out.effective.source)
        // An inherited field reads as null on the override while the effective policy
        // still has a value. That difference is the point of the three-layer shape.
        assertNull(out.override?.feeMode)
        assertEquals("threshold", out.override?.typeWork)
        assertEquals(SweepPolicyMode.MOMENTUM, out.projectDefault.typeWork)
        assertFalse(out.override?.locked ?: true)
    }

    @Test
    fun `update writes only the fields it was given`() = runBlocking {
        enqueue(settingsBody)

        client.sweeps.updateSettings(
            address = "0xabc",
            typeWork = SweepFieldWrite.Set(SweepPolicyMode.THRESHOLD),
            thresholdAmountUsd = SweepFieldWrite.Set("250"),
        )

        val body = sentBody()
        assertEquals("threshold", body["type_work"]?.jsonPrimitive?.content)
        assertEquals("250", body["threshold_amount_usd"]?.jsonPrimitive?.content)
        // Sending fee_mode at all would rewrite it; untouched means absent.
        assertFalse(body.containsKey("fee_mode"))
        assertEquals(
            listOf("type_work", "threshold_amount_usd"),
            body["fields"]!!.jsonArray.map { (it as JsonPrimitive).content },
        )
    }

    @Test
    fun `inherit names the field and sends no value`() = runBlocking {
        enqueue(settingsBody)

        client.sweeps.updateSettings(address = "0xabc", typeWork = SweepFieldWrite.Inherit)

        val body = sentBody()
        // The API's way of saying "inherit this again": named, with no value. null cannot
        // express it because it already means "not supplied".
        assertEquals(listOf("type_work"), body["fields"]!!.jsonArray.map { (it as JsonPrimitive).content })
        assertFalse(body.containsKey("type_work"))
    }

    @Test
    fun `history tells a broadcast sweep from a settled one`() = runBlocking {
        enqueue(
            """
            {"items":[
              {"task_id":"t1","status":"broadcasted","wallet_address":"0xa","chain":"ETH_MAINNET",
               "sweep_confirmations":2,"type_work":"threshold","total_fee_usd":"1.20"},
              {"task_id":"t2","status":"completed","wallet_address":"0xb","chain":"ETH_MAINNET",
               "sweep_confirmations":12,"completed_at":"2026-08-28T10:00:00Z","real_sweep_fee_usd":"0.98"}
            ],"meta":{"total":2,"page":1,"page_size":50}}
            """.trimIndent(),
        )

        val out = client.sweeps.history()

        val inFlight = out.items[0]
        val settled = out.items[1]
        assertEquals(SweepStatus.BROADCASTED, inFlight.status)
        assertEquals(2, inFlight.sweepConfirmations)
        // Still in flight: there is no settlement moment to report yet.
        assertNull(inFlight.completedAt)
        assertEquals("threshold", inFlight.typeWork)
        assertEquals("1.20", inFlight.totalFeeUsd)
        assertEquals(SweepStatus.COMPLETED, settled.status)
        assertEquals("2026-08-28T10:00:00Z", settled.completedAt)
        assertEquals("0.98", settled.realSweepFeeUsd)
    }

    @Test
    fun `environment reaches the wire and is omitted when unset`() = runBlocking {
        enqueue("""{"uuid":"u1","order_id":"o1","status":"pending"}""")

        client.payIns.create(
            CreatePayInRequest(
                orderId = "o1",
                userId = "u",
                mode = "fiat",
                environment = Environment.TESTNET,
                amountFiat = "10",
                currency = "USD",
            ),
        )
        assertEquals("testnet", sentBody()["environment"]?.jsonPrimitive?.content)

        enqueue("""{"uuid":"u2","order_id":"o2","status":"pending"}""")
        client.payIns.create(
            CreatePayInRequest(
                orderId = "o2",
                userId = "u",
                mode = "fiat",
                amountFiat = "10",
                currency = "USD",
            ),
        )
        // Unset must stay off the wire: an empty string is a value the platform has to
        // reject, not the "use the project default" the caller meant.
        assertTrue(!sentBody().containsKey("environment"))
    }
}
