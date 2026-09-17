package com.cryptochief.processing.http

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HMAC-SHA256 v1 signatures of requests ([signHmacV1]) and webhooks ([signWebhookV1]). */
public object RequestSigner {

    /** First line of the HMAC v1 request string to sign. */
    public const val HMAC_V1_SCOPE: String = "CC-HMAC-SHA256-REQ-V1"

    /** First line of the HMAC v1 webhook string to sign. */
    public const val WEBHOOK_V1_SCOPE: String = "CC-HMAC-SHA256-WEBHOOK-V1"

    /** Prefix of the `X-CC-Signature` value. */
    public const val HMAC_V1_SIGNATURE_PREFIX: String = "v1="

    public const val HEADER_TIMESTAMP: String = "X-CC-Timestamp"
    public const val HEADER_NONCE: String = "X-CC-Nonce"
    public const val HEADER_HMAC_SIGNATURE: String = "X-CC-Signature"
    public const val HEADER_IDEMPOTENCY_KEY: String = "Idempotency-Key"

    /**
     * HMAC v1 string to sign: [HMAC_V1_SCOPE], [timestamp], [nonce], [method] with `a`–`z` in
     * upper case, [path], [query], [merchant], [idempotencyKey] and the lowercase hex SHA-256 of
     * [body], joined with `\n`.
     *
     * [path] is the route as the server reads it, with `%`-sequences decoded ([pathToSign]);
     * [query] is the string as sent, without `?` and without decoding.
     *
     * @throws IllegalArgumentException if a value contains CR or LF.
     */
    @JvmStatic
    public fun hmacV1StringToSign(
        timestamp: String,
        nonce: String,
        method: String,
        path: String,
        query: String,
        merchant: String,
        idempotencyKey: String,
        body: ByteArray,
    ): String {
        val fields = listOf(
            timestamp,
            nonce,
            upperAscii(method),
            path,
            query,
            merchant,
            idempotencyKey,
        )
        for (field in fields) {
            require(field.none { it == '\r' || it == '\n' }) { "HMAC v1: value contains CR or LF" }
        }
        val out = StringBuilder(HMAC_V1_SCOPE)
        for (field in fields) {
            out.append('\n').append(field)
        }
        out.append('\n').append(bodySha256(body))
        return out.toString()
    }

    /**
     * `hex(HMAC-SHA256(key = apiKey, message = hmacV1StringToSign(...)))`, 64 lowercase hex
     * characters. `X-CC-Signature` is [HMAC_V1_SIGNATURE_PREFIX] followed by this value.
     *
     * @throws IllegalArgumentException if [apiKey] is empty or holds only spaces and tabs, or if
     * a value contains CR or LF.
     */
    @JvmStatic
    public fun signHmacV1(
        apiKey: String,
        timestamp: String,
        nonce: String,
        method: String,
        path: String,
        query: String,
        merchant: String,
        idempotencyKey: String,
        body: ByteArray,
    ): String {
        requireApiKey(apiKey)
        val stringToSign = hmacV1StringToSign(timestamp, nonce, method, path, query, merchant, idempotencyKey, body)
        return hmacSha256(apiKey, stringToSign).toHexLower()
    }

    /**
     * The path the signature covers: [path] with its `%`-sequences decoded, which is the form the
     * server reads. The escaped spelling is what goes on the wire, so
     * `/v1/orders/payout%2F8814` is sent as written and signed as `/v1/orders/payout/8814`.
     *
     * A query is cut off at `?`; `+` is left alone, it is a literal plus in a path.
     *
     * @throws IllegalArgumentException if a `%` is not followed by two hex digits.
     */
    @JvmStatic
    public fun pathToSign(path: String): String {
        val raw = path.substringBefore('?')
        if (!raw.contains('%')) return raw
        val bytes = raw.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            if (b == '%'.code.toByte()) {
                require(i + 2 < bytes.size) { "HMAC v1: invalid percent-escape in path: $raw" }
                val hi = hexDigit(bytes[i + 1])
                val lo = hexDigit(bytes[i + 2])
                require(hi >= 0 && lo >= 0) { "HMAC v1: invalid percent-escape in path: $raw" }
                out.write((hi shl 4) or lo)
                i += 3
            } else {
                out.write(b.toInt())
                i++
            }
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    /**
     * HMAC v1 webhook string to sign: [WEBHOOK_V1_SCOPE], [timestamp] in decimal, [deliveryId]
     * and the lowercase hex SHA-256 of [body], joined with `\n`.
     *
     * @throws IllegalArgumentException if [timestamp] is not positive or [deliveryId] is not
     * 1–128 characters `[A-Za-z0-9_-]`.
     */
    @JvmStatic
    public fun webhookV1StringToSign(timestamp: Long, deliveryId: String, body: ByteArray): String {
        require(timestamp > 0) { "webhook signature: timestamp must be positive" }
        require(isValidDeliveryId(deliveryId)) { "webhook signature: delivery id must be 1-128 characters [A-Za-z0-9_-]" }
        return StringBuilder(WEBHOOK_V1_SCOPE.length + 20 + deliveryId.length + 67)
            .append(WEBHOOK_V1_SCOPE).append('\n')
            .append(timestamp).append('\n')
            .append(deliveryId).append('\n')
            .append(bodySha256(body))
            .toString()
    }

    /**
     * `X-CC-Signature` value of a webhook: [HMAC_V1_SIGNATURE_PREFIX] followed by
     * `hex(HMAC-SHA256(key = apiKey, message = webhookV1StringToSign(timestamp, deliveryId, body)))`.
     *
     * @throws IllegalArgumentException if [apiKey] is empty or holds only spaces and tabs, or if
     * [webhookV1StringToSign] rejects the values.
     */
    @JvmStatic
    public fun signWebhookV1(apiKey: String, timestamp: Long, deliveryId: String, body: ByteArray): String {
        requireApiKey(apiKey)
        val stringToSign = webhookV1StringToSign(timestamp, deliveryId, body)
        return HMAC_V1_SIGNATURE_PREFIX + hmacSha256(apiKey, stringToSign).toHexLower()
    }

    /** Lowercase hex SHA-256 of [body]. */
    @JvmStatic
    public fun bodySha256(body: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(body).toHexLower()

    /** 32 lowercase hex characters from 16 random bytes. */
    @JvmStatic
    public fun newNonce(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.toHexLower()
    }

    internal fun hmacSha256(apiKey: String, message: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(apiKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    /**
     * A key that is empty or holds only spaces and tabs is the empty key. The platform refuses a
     * project with one, so a signature made with it is never accepted.
     */
    internal fun isEmptyApiKey(apiKey: String): Boolean = apiKey.trim(' ', '\t').isEmpty()

    private fun requireApiKey(apiKey: String) {
        require(!isEmptyApiKey(apiKey)) { "API key is required" }
    }

    /**
     * Upper case over `a`–`z` only. The HTTP method is a byte token: a Unicode mapping would
     * rewrite bytes outside that range (`ı`→`I`, `ﬅ`→`FT`) and the signature would not match the
     * server's.
     */
    internal fun upperAscii(value: String): String =
        String(CharArray(value.length) { i -> value[i].let { if (it in 'a'..'z') it - ('a' - 'A') else it } })

    private fun hexDigit(b: Byte): Int = when (val c = b.toInt().toChar()) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    internal fun isValidDeliveryId(value: String): Boolean {
        if (value.isEmpty() || value.length > 128) return false
        return value.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-' }
    }

    private val random = SecureRandom()

    private fun ByteArray.toHexLower(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4])
            out.append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
