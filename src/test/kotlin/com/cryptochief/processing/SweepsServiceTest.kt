package com.cryptochief.processing

import com.cryptochief.processing.models.CreatePayInRequest
import com.cryptochief.processing.models.Environment
import com.cryptochief.processing.models.SweepFieldWrite
import com.cryptochief.processing.models.SweepGasSource
import com.cryptochief.processing.models.SweepHistoryQuery
import com.cryptochief.processing.models.SweepMode
import com.cryptochief.processing.models.SweepPolicyMode
import com.cryptochief.processing.models.SweepSettingsQuery
import com.cryptochief.processing.models.SweepStatus
import com.cryptochief.processing.models.SweepWalletHistoryQuery
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
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
          "effective": {"type_work":"threshold","threshold_amount_usd":"250","fee_mode":"mix","gas_source":"rented","source":"wallet"},
          "override": {"network_code":"","type_work":"threshold","threshold_amount_usd":"250","fee_mode":null,"gas_source":null,"source":"merchant","locked":false},
          "project_default": {"type_work":"momentum","fee_mode":"client","gas_source":"rented"}
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
               "sweep_confirmations":2,"required_confirmations":12,"completed_at":"2026-08-28T09:59:00Z",
               "type_work":"threshold","total_fee_usd":"1.20"},
              {"task_id":"t2","status":"completed","wallet_address":"0xb","chain":"ETH_MAINNET",
               "sweep_confirmations":12,"required_confirmations":12,"completed_at":"2026-08-28T10:00:00Z","real_sweep_fee_usd":"0.98"}
            ],"meta":{"total":2,"page":1,"page_size":50}}
            """.trimIndent(),
        )

        val out = client.sweeps.history()

        val inFlight = out.items[0]
        val settled = out.items[1]
        assertEquals(SweepStatus.BROADCASTED, inFlight.status)
        // In a block, counting, and still not final: above zero is not settlement.
        assertEquals(2, inFlight.sweepConfirmations)
        assertEquals(12, inFlight.requiredConfirmations)
        assertEquals(12, settled.sweepConfirmations)
        assertEquals(12, settled.requiredConfirmations)
        // completed_at is the send time, so a broadcasted sweep already carries it.
        assertEquals("2026-08-28T09:59:00Z", inFlight.completedAt)
        assertEquals("threshold", inFlight.typeWork)
        assertEquals("1.20", inFlight.totalFeeUsd)
        assertEquals(SweepStatus.COMPLETED, settled.status)
        assertEquals("2026-08-28T10:00:00Z", settled.completedAt)
        assertEquals("0.98", settled.realSweepFeeUsd)
        assertFalse(inFlight.isSettled)
        assertTrue(settled.isSettled)
    }

    @Test
    fun `a failed sweep carries completedAt too, so it is not the settlement signal`() = runBlocking {
        // completed_at is the send time on broadcasted/completed and the time the status was
        // recorded on failed/skipped. Reading its presence as "the funds moved" books a
        // failure as money received.
        enqueue(
            """
            {"items":[
              {"task_id":"t3","status":"failed","wallet_address":"0xc","chain":"ETH_MAINNET",
               "sweep_confirmations":0,"required_confirmations":12,"completed_at":"2026-08-28T11:00:00Z"},
              {"task_id":"t4","status":"skipped","wallet_address":"0xd","chain":"ETH_MAINNET",
               "completed_at":"2026-08-28T11:05:00Z"},
              {"task_id":"t5","status":"completed","wallet_address":"0xe","chain":"ETH_MAINNET",
               "sweep_confirmations":19,"required_confirmations":12,"completed_at":"2026-08-28T11:10:00Z"}
            ],"meta":{"total":3,"page":1,"page_size":50}}
            """.trimIndent(),
        )

        val items = client.sweeps.history().items

        // All three are stamped. The timestamp separates none of them.
        assertTrue(items.all { it.completedAt != null })

        val failed = items[0]
        val skipped = items[1]
        val settled = items[2]
        assertEquals(SweepStatus.FAILED, failed.status)
        assertEquals(0, failed.sweepConfirmations)
        // Settlement is isSettled: completed and the count at the depth. The webhook's
        // confirmedAt is the other answer.
        assertFalse(failed.isSettled)
        assertEquals(SweepStatus.SKIPPED, skipped.status)
        assertNull(skipped.sweepConfirmations)
        assertFalse(skipped.isSettled)
        assertEquals(SweepStatus.COMPLETED, settled.status)
        assertTrue(settled.isSettled)
    }

    @Test
    fun `an older completed row with no confirmations is not settled`() = runBlocking {
        enqueue(
            """
            {"items":[
              {"task_id":"t9","status":"completed","wallet_address":"0xh","chain":"ETH_MAINNET",
               "sweep_tx_hash":"0xh1","sweep_confirmations":0,"required_confirmations":12,
               "completed_at":"2026-08-20T10:00:00Z"},
              {"task_id":"t11","status":"completed","wallet_address":"0xj","chain":"ETH_MAINNET",
               "sweep_tx_hash":"0xj1","required_confirmations":12,
               "completed_at":"2026-08-10T10:00:00Z"}
            ],"meta":{"total":2,"page":1,"page_size":50}}
            """.trimIndent(),
        )

        val items = client.sweeps.history().items

        assertTrue(items.all { it.status == SweepStatus.COMPLETED })
        assertEquals(listOf(false, false), items.map { it.isSettled })
    }

    @Test
    fun `a broadcasted sweep with confirmations is still in flight`() = runBlocking {
        enqueue(
            """
            {"items":[
              {"task_id":"t6","status":"broadcasted","wallet_address":"0xf","chain":"ETH_MAINNET",
               "sweep_tx_hash":"0xsweep","sweep_confirmations":3,"required_confirmations":12,
               "completed_at":"2026-09-14T10:00:00Z"},
              {"task_id":"t7","status":"completed","wallet_address":"So1ana","chain":"SOLANA_MAINNET",
               "sweep_tx_hash":"5ig","sweep_confirmations":32,"required_confirmations":32,
               "completed_at":"2026-09-14T10:01:00Z"}
            ],"meta":{"total":2,"page":1,"page_size":50}}
            """.trimIndent(),
        )

        val (inBlock, finalNoCount) = client.sweeps.history().items

        // The old "above zero" test would book this one as received. It is not final yet.
        assertEquals(SweepStatus.BROADCASTED, inBlock.status)
        assertTrue(inBlock.sweepConfirmations!! > 0)
        assertTrue(inBlock.sweepConfirmations!! < inBlock.requiredConfirmations!!)
        assertFalse(inBlock.isSettled)
        // Final without a block count is published at the depth itself, not as 4294967295.
        assertEquals(SweepStatus.COMPLETED, finalNoCount.status)
        assertEquals(finalNoCount.requiredConfirmations, finalNoCount.sweepConfirmations)
        assertTrue(finalNoCount.isSettled)
    }

    @Test
    fun `history from a server without required_confirmations still decodes`() = runBlocking {
        enqueue(
            """
            {"items":[
              {"task_id":"t8","status":"completed","wallet_address":"0xg","chain":"ETH_MAINNET",
               "sweep_confirmations":1,"completed_at":"2026-08-01T10:00:00Z"}
            ],"meta":{"total":1,"page":1,"page_size":50}}
            """.trimIndent(),
        )

        val item = client.sweeps.history().items.single()

        assertNull(item.requiredConfirmations)
        assertEquals(1, item.sweepConfirmations)
        // Without a depth, one confirmation is the floor.
        assertTrue(item.isSettled)
        assertFalse(item.copy(sweepConfirmations = 0).isSettled)
        assertFalse(item.copy(sweepConfirmations = null).isSettled)
        assertFalse(item.copy(requiredConfirmations = 0, sweepConfirmations = 0).isSettled)
    }

    // ---- gas_source ------------------------------------------------------------------

    @Test
    fun `a null gas_source on the override is undecided, not switched off`() = runBlocking {
        enqueue(settingsBody)

        val out = client.sweeps.settings(SweepSettingsQuery(address = "0xabc"))

        // The wallet does not decide it: null means inherited, NOT "native". The
        // difference is money - an inherited gas source is the platform default,
        // `rented`, whose energy is billed to API credits.
        assertNull(out.override?.gasSource)
        // And the effective layer says what will actually happen, always concretely.
        assertEquals(SweepGasSource.RENTED, out.effective.gasSource)
        assertEquals(SweepGasSource.RENTED, out.projectDefault.gasSource)
    }

    @Test
    fun `a wallet that chose native reads native on both layers`() = runBlocking {
        enqueue(
            """
            {
              "wallet_address": "TXn",
              "network_code": "TRON_MAINNET",
              "effective": {"type_work":"momentum","fee_mode":"mix","gas_source":"native","source":"wallet"},
              "override": {"network_code":"","type_work":null,"threshold_amount_usd":null,
                           "fee_mode":null,"gas_source":"native","source":"merchant","locked":false},
              "project_default": {"type_work":"momentum","fee_mode":"mix","gas_source":"rented"}
            }
            """.trimIndent(),
        )

        val out = client.sweeps.settings(SweepSettingsQuery(address = "TXn"))

        assertEquals(SweepGasSource.NATIVE, out.override?.gasSource)
        assertEquals(SweepGasSource.NATIVE, out.effective.gasSource)
        // The wallet burns its own TRX even though the project would have rented.
        assertEquals(SweepGasSource.RENTED, out.projectDefault.gasSource)
        // Every other field is still inherited: overriding is per field.
        assertNull(out.override?.typeWork)
        assertNull(out.override?.feeMode)
    }

    @Test
    fun `updateSettings sends gas_source and names it in the mask`() = runBlocking {
        enqueue(settingsBody)

        client.sweeps.updateSettings(
            address = "TXn",
            networkCode = Chain.TRON_MAINNET,
            gasSource = SweepFieldWrite.Set(SweepGasSource.NATIVE),
        )

        val body = sentBody()
        assertEquals("native", body["gas_source"]?.jsonPrimitive?.content)
        assertEquals(listOf("gas_source"), body["fields"]!!.jsonArray.map { (it as JsonPrimitive).content })
        // Nothing else was written, so nothing else is on the wire.
        assertFalse(body.containsKey("type_work"))
        assertFalse(body.containsKey("fee_mode"))
    }

    @Test
    fun `inheriting gas_source names it in the mask with no value`() = runBlocking {
        enqueue(settingsBody)

        client.sweeps.updateSettings(
            address = "TXn",
            feeMode = SweepFieldWrite.Set("mix"),
            gasSource = SweepFieldWrite.Inherit,
        )

        val body = sentBody()
        // Naming the field with no value is the only way to drop one override and keep
        // the others; sending a value would set it instead of clearing it.
        assertFalse(body.containsKey("gas_source"))
        assertEquals(
            listOf("fee_mode", "gas_source"),
            body["fields"]!!.jsonArray.map { (it as JsonPrimitive).content },
        )
        assertEquals("mix", body["fee_mode"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an untouched gas_source stays off the wire entirely`() = runBlocking {
        enqueue(settingsBody)

        client.sweeps.updateSettings(
            address = "TXn",
            typeWork = SweepFieldWrite.Set(SweepPolicyMode.MOMENTUM),
        )

        val body = sentBody()
        // Not naming it leaves the stored value alone - which is NOT the same as
        // choosing native. A wallet that never chose one still gets rented.
        assertFalse(body.containsKey("gas_source"))
        assertEquals(listOf("type_work"), body["fields"]!!.jsonArray.map { (it as JsonPrimitive).content })
    }

    // ---- history filters ---------------------------------------------------------

    @Test
    fun `history sends the status and search filters`() = runBlocking {
        enqueue("""{"items":[],"meta":{"total":0,"page":1,"page_size":20}}""")

        client.sweeps.history(
            SweepHistoryQuery(
                mode = SweepMode.AUTO,
                status = SweepStatus.FAILED,
                search = "0x77EDde",
                page = 3,
                pageSize = 50,
            ),
        )

        val body = sentBody()
        assertEquals("auto", body["mode"]?.jsonPrimitive?.content)
        assertEquals("failed", body["status"]?.jsonPrimitive?.content)
        assertEquals("0x77EDde", body["search"]?.jsonPrimitive?.content)
        assertEquals(3, body["page"]?.jsonPrimitive?.int)
        assertEquals(50, body["page_size"]?.jsonPrimitive?.int)
    }

    @Test
    fun `an unfiltered history asks for every status, skipped ones included`() = runBlocking {
        enqueue(
            """
            {"items":[
              {"task_id":"t1","status":"skipped","wallet_address":"0xa","chain":"ETH_MAINNET"}
            ],"meta":{"total":1,"page":1,"page_size":20}}
            """.trimIndent(),
        )

        val out = client.sweeps.history()

        // Absent means "every status", so status and search must not be sent empty -
        // an empty string is a filter that matches one nameless status, not no filter.
        assertEquals(JsonObject(emptyMap()), sentBody())
        // And `skipped` is a normal outcome that comes back with the rest.
        assertEquals(SweepStatus.SKIPPED, out.items.single().status)
    }

    @Test
    fun `wallet history sends the address alongside the filters`() = runBlocking {
        enqueue("""{"items":[],"meta":{"total":0,"page":1,"page_size":20}}""")

        client.sweeps.walletHistory(
            SweepWalletHistoryQuery(
                address = "0xabc",
                status = SweepStatus.COMPLETED,
                search = "898cdbd0-d583-4089-9c53-15f5ca9b53dc",
            ),
        )

        val body = sentBody()
        assertEquals("0xabc", body["address"]?.jsonPrimitive?.content)
        assertEquals("completed", body["status"]?.jsonPrimitive?.content)
        // On the wallet variant the search matches the hashes and the task id.
        assertEquals("898cdbd0-d583-4089-9c53-15f5ca9b53dc", body["search"]?.jsonPrimitive?.content)
        assertEquals(setOf("address", "search", "status"), body.keys)
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
