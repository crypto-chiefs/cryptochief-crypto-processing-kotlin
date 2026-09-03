package com.cryptochief.processing.webhook

import com.cryptochief.processing.http.CanonicalJson
import com.cryptochief.processing.http.RequestSigner
import kotlinx.serialization.SerializationException

/** Signature header did not match the body. */
public class WebhookSignatureException(message: String) : RuntimeException(message)

/** Webhook signature verification. Algorithm matches request signing. */
public object WebhookVerifier {

    public const val HEADER: String = "Signature"

    /**
     * Header carrying the delivery's uuid on every webhook the platform sends. Constant across
     * every attempt and resend of one delivery — use it as your receiver's idempotency key — and
     * the argument `client.webhooks.info()` / `resend()` take. Keep it when you log an incoming
     * webhook: there is no other way to name a delivery later.
     */
    public const val DELIVERY_HEADER: String = "X-Webhook-Delivery"

    public val SENDER_IPS: List<String> = listOf("164.90.231.203", "104.248.248.64")

    public fun verify(apiKey: String, body: ByteArray, signatureHeader: String?): Boolean {
        if (apiKey.isEmpty() || body.isEmpty() || signatureHeader.isNullOrEmpty()) return false
        val canonical = canonicalise(body) ?: return false
        val expected = RequestSigner.sign(canonical, apiKey)
        return constantTimeEquals(expected, signatureHeader)
    }

    @Throws(WebhookSignatureException::class)
    public fun requireValid(apiKey: String, body: ByteArray, signatureHeader: String?) {
        if (!verify(apiKey, body, signatureHeader)) {
            throw WebhookSignatureException("cryptochief: invalid webhook signature")
        }
    }

    private fun canonicalise(body: ByteArray): ByteArray? = try {
        val element = CanonicalJson.json.parseToJsonElement(body.toString(Charsets.UTF_8))
        CanonicalJson.encode(element)
    } catch (_: SerializationException) {
        null
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }
}
