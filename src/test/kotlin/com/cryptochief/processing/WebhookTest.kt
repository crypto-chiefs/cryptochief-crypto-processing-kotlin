package com.cryptochief.processing

import com.cryptochief.processing.http.CanonicalJson
import com.cryptochief.processing.http.RequestSigner
import com.cryptochief.processing.models.SweepStatus
import com.cryptochief.processing.webhook.PayoutWebhookEvent
import com.cryptochief.processing.webhook.SweepWebhookEvent
import com.cryptochief.processing.webhook.TransactionWebhookEvent
import com.cryptochief.processing.webhook.WebhookHandler
import com.cryptochief.processing.webhook.WebhookSignatureException
import com.cryptochief.processing.webhook.WebhookVerifier
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WebhookTest {

    private val apiKey = "secret"

    @Test
    fun `accepts canonical body`() {
        val body = """{"event":"payout.paid","status":"paid","uuid":"u","order_id":"o"}"""
        val canonical = CanonicalJson.encode(CanonicalJson.json.parseToJsonElement(body))
        val sig = RequestSigner.sign(canonical, apiKey)
        assertTrue(WebhookVerifier.verify(apiKey, body.toByteArray(), sig))
    }

    @Test
    fun `accepts re-ordered body by re-canonicalising`() {
        val canonicalBytes = CanonicalJson.encode(buildJsonObject {
            put("a", 1)
            put("b", 2)
        })
        val sig = RequestSigner.sign(canonicalBytes, apiKey)
        val reordered = """{"b":2,"a":1}"""
        assertTrue(WebhookVerifier.verify(apiKey, reordered.toByteArray(), sig))
    }

    @Test
    fun `rejects mutated body`() {
        val body = """{"event":"payout.paid","uuid":"u","order_id":"o","status":"paid"}"""
        val canonical = CanonicalJson.encode(CanonicalJson.json.parseToJsonElement(body))
        val sig = RequestSigner.sign(canonical, apiKey)
        val tampered = body.replace("\"paid\"", "\"failed\"")
        assertEquals(false, WebhookVerifier.verify(apiKey, tampered.toByteArray(), sig))
    }

    @Test
    fun `handle decodes typed event`() {
        val body = """{"event":"payout.paid","uuid":"u-1","order_id":"o-1","status":"paid"}"""
        val canonical = CanonicalJson.encode(CanonicalJson.json.parseToJsonElement(body))
        val sig = RequestSigner.sign(canonical, apiKey)
        val event = WebhookHandler.handle<PayoutWebhookEvent>(apiKey, body.toByteArray(), sig)
        assertEquals("u-1", event.uuid)
        assertEquals("paid", event.status)
    }

    private inline fun <reified T> signedHandle(body: String): T {
        val canonical = CanonicalJson.encode(CanonicalJson.json.parseToJsonElement(body))
        return WebhookHandler.handle<T>(apiKey, body.toByteArray(), RequestSigner.sign(canonical, apiKey))
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

    @Test
    fun `handle throws on bad signature`() {
        assertThrows<WebhookSignatureException> {
            WebhookHandler.handle<PayoutWebhookEvent>(apiKey, """{"event":"x","uuid":"u","order_id":"o","status":"paid"}""".toByteArray(), "bad")
        }
    }
}
