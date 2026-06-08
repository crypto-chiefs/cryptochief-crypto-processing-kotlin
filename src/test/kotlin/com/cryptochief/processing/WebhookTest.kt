package com.cryptochief.processing

import com.cryptochief.processing.http.CanonicalJson
import com.cryptochief.processing.http.RequestSigner
import com.cryptochief.processing.webhook.PayoutWebhookEvent
import com.cryptochief.processing.webhook.WebhookHandler
import com.cryptochief.processing.webhook.WebhookSignatureException
import com.cryptochief.processing.webhook.WebhookVerifier
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun `handle throws on bad signature`() {
        assertThrows<WebhookSignatureException> {
            WebhookHandler.handle<PayoutWebhookEvent>(apiKey, """{"event":"x","uuid":"u","order_id":"o","status":"paid"}""".toByteArray(), "bad")
        }
    }
}
