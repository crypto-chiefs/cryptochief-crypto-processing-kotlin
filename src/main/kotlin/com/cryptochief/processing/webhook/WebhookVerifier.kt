package com.cryptochief.processing.webhook

import com.cryptochief.processing.DecodeException
import com.cryptochief.processing.http.RequestSigner
import com.cryptochief.processing.http.SdkJson
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/** Webhook rejected by [WebhookVerifier]; the subclass names the reason. Answer the sender with 401. */
public sealed class WebhookVerificationException(message: String) : RuntimeException(message)

/**
 * [WebhookVerifier.TIMESTAMP_HEADER], [WebhookVerifier.DELIVERY_HEADER] or
 * [WebhookVerifier.SIGNATURE_HEADER] is missing, repeated, contains CR or LF, or is malformed.
 */
public class WebhookHeadersException(message: String) : WebhookVerificationException(message)

/** [WebhookVerifier.TIMESTAMP_HEADER] is further from the current time than the tolerance. */
public class WebhookTimestampException(message: String) : WebhookVerificationException(message)

/** [WebhookVerifier.SIGNATURE_HEADER] does not match the body, timestamp and delivery id. */
public class WebhookSignatureException(message: String) : WebhookVerificationException(message)

/**
 * Webhook signature verification, HMAC-SHA256 v1.
 *
 * String to sign, lines joined with `\n`:
 * [RequestSigner.WEBHOOK_V1_SCOPE], `X-CC-Timestamp`, `X-Webhook-Delivery`, lowercase hex SHA-256
 * of the raw body. `X-CC-Signature` is `v1=` followed by
 * `hex(HMAC-SHA256(key = apiKey, message = stringToSign))`.
 *
 * Checks, in order:
 * 1. each of the three headers is present once; header names are case-insensitive, spaces and
 *    tabs around values are removed; CR or LF, a timestamp that is not a decimal number or
 *    carries a leading zero, a signature that is not `v1=` and 64 hex characters, or a delivery
 *    id that is not 1–128 characters `[A-Za-z0-9_-]` — [WebhookHeadersException];
 * 2. `|now - X-CC-Timestamp|` is at most the tolerance, in whole seconds — [WebhookTimestampException];
 * 3. the signature matches, compared in constant time, hex in any case — [WebhookSignatureException].
 *
 * A repeated header is seen only by the overloads that take a map of every value received under
 * each name; a header function returns one value per name.
 *
 * The body must be the bytes as received, before any JSON parsing. The same delivery arrives
 * again on retries and resends with the same `X-Webhook-Delivery` and a new timestamp.
 */
public object WebhookVerifier {

    /**
     * Delivery id, 1–128 characters `[A-Za-z0-9_-]`; the same on every attempt and resend of one
     * delivery. The argument of `client.webhooks.info()` and `resend()`.
     */
    public const val DELIVERY_HEADER: String = "X-Webhook-Delivery"

    /** Unix time of the attempt, seconds. */
    public const val TIMESTAMP_HEADER: String = "X-CC-Timestamp"

    /** `v1=` followed by 64 hex characters. */
    public const val SIGNATURE_HEADER: String = "X-CC-Signature"

    /** Default allowed difference between `X-CC-Timestamp` and the current time. */
    public val DEFAULT_TOLERANCE: Duration = 300.seconds

    public val SENDER_IPS: List<String> = listOf("164.90.231.203", "104.248.248.64")

    @PublishedApi
    internal val json: Json = SdkJson.instance

    /**
     * Verifies [rawBody] against the headers returned by [header], e.g.
     * `{ name -> request.getHeader(name) }`. [header] is called with [TIMESTAMP_HEADER],
     * [DELIVERY_HEADER] and [SIGNATURE_HEADER] and returns `null` for a missing header.
     *
     * One value per name: a repeated header is not visible this way and is not rejected. Pass the
     * headers as a map of name to every value received under it to reject repeats.
     *
     * @throws WebhookHeadersException
     * @throws WebhookTimestampException
     * @throws WebhookSignatureException
     * @throws IllegalArgumentException if [apiKey] is empty or holds only spaces and tabs.
     */
    @JvmStatic
    @Throws(WebhookVerificationException::class)
    public fun verify(apiKey: String, rawBody: ByteArray, header: (String) -> String?) {
        verify(apiKey, rawBody, header, DEFAULT_TOLERANCE) { Instant.now() }
    }

    /**
     * [verify] with [tolerance] (a non-positive value means [DEFAULT_TOLERANCE]) and the clock
     * [now].
     */
    @Throws(WebhookVerificationException::class)
    public fun verify(
        apiKey: String,
        rawBody: ByteArray,
        header: (String) -> String?,
        tolerance: Duration = DEFAULT_TOLERANCE,
        now: () -> Instant = { Instant.now() },
    ) {
        require(!RequestSigner.isEmptyApiKey(apiKey)) { "API key is required" }
        verifyV1(apiKey, rawBody, tolerance, now) { name -> header(name)?.let { listOf(it) } ?: emptyList() }
    }

    /**
     * [verify] with [tolerance] (a non-positive value means [DEFAULT_TOLERANCE]) and [clock], for
     * Java callers.
     */
    @JvmStatic
    @Throws(WebhookVerificationException::class)
    public fun verify(
        apiKey: String,
        rawBody: ByteArray,
        header: (String) -> String?,
        tolerance: java.time.Duration,
        clock: Clock,
    ) {
        verify(apiKey, rawBody, header, tolerance.toKotlinDuration()) { clock.instant() }
    }

    /**
     * Verifies [rawBody] against [headers], a map of header name to every value received under
     * it, e.g. `com.sun.net.httpserver.Headers` or OkHttp `Headers.toMultimap()`. Names are
     * matched as Go `strings.EqualFold` matches them: ASCII letters in any case, U+212A for `k`,
     * U+017F for `s`. Values under names that match the same header count as repeats.
     *
     * @throws WebhookHeadersException
     * @throws WebhookTimestampException
     * @throws WebhookSignatureException
     * @throws IllegalArgumentException if [apiKey] is empty or holds only spaces and tabs.
     */
    @JvmStatic
    @Throws(WebhookVerificationException::class)
    public fun verify(apiKey: String, rawBody: ByteArray, headers: Map<String, List<String>>) {
        verify(apiKey, rawBody, headers, DEFAULT_TOLERANCE) { Instant.now() }
    }

    /**
     * [verify] with [tolerance] (a non-positive value means [DEFAULT_TOLERANCE]) and the clock
     * [now].
     */
    @Throws(WebhookVerificationException::class)
    public fun verify(
        apiKey: String,
        rawBody: ByteArray,
        headers: Map<String, List<String>>,
        tolerance: Duration = DEFAULT_TOLERANCE,
        now: () -> Instant = { Instant.now() },
    ) {
        require(!RequestSigner.isEmptyApiKey(apiKey)) { "API key is required" }
        verifyV1(apiKey, rawBody, tolerance, now) { name ->
            headers.entries.filter { headerNameMatches(it.key, name) }.flatMap { it.value }
        }
    }

    /**
     * [verify] with [tolerance] (a non-positive value means [DEFAULT_TOLERANCE]) and [clock], for
     * Java callers.
     */
    @JvmStatic
    @Throws(WebhookVerificationException::class)
    public fun verify(
        apiKey: String,
        rawBody: ByteArray,
        headers: Map<String, List<String>>,
        tolerance: java.time.Duration,
        clock: Clock,
    ) {
        verify(apiKey, rawBody, headers, tolerance.toKotlinDuration()) { clock.instant() }
    }

    /**
     * [verify], then decodes [rawBody] with [deserializer].
     *
     * @throws WebhookVerificationException
     * @throws DecodeException if the body does not decode.
     * @throws IllegalArgumentException if [apiKey] is empty or holds only spaces and tabs.
     */
    @Throws(WebhookVerificationException::class, DecodeException::class)
    public fun <T> parse(
        apiKey: String,
        rawBody: ByteArray,
        header: (String) -> String?,
        deserializer: DeserializationStrategy<T>,
        tolerance: Duration = DEFAULT_TOLERANCE,
        now: () -> Instant = { Instant.now() },
    ): T {
        verify(apiKey, rawBody, header, tolerance, now)
        return decode(rawBody, deserializer)
    }

    /**
     * [verify], then decodes [rawBody] with [deserializer].
     *
     * @throws WebhookVerificationException
     * @throws DecodeException if the body does not decode.
     * @throws IllegalArgumentException if [apiKey] is empty or holds only spaces and tabs.
     */
    @Throws(WebhookVerificationException::class, DecodeException::class)
    public fun <T> parse(
        apiKey: String,
        rawBody: ByteArray,
        headers: Map<String, List<String>>,
        deserializer: DeserializationStrategy<T>,
        tolerance: Duration = DEFAULT_TOLERANCE,
        now: () -> Instant = { Instant.now() },
    ): T {
        verify(apiKey, rawBody, headers, tolerance, now)
        return decode(rawBody, deserializer)
    }

    /**
     * [verify], then decodes [rawBody] with [deserializer], e.g.
     * `PayoutWebhookEvent.Companion.serializer()` from Java.
     *
     * @throws WebhookVerificationException
     * @throws DecodeException if the body does not decode.
     * @throws IllegalArgumentException if [apiKey] is empty or holds only spaces and tabs.
     */
    @JvmStatic
    @Throws(WebhookVerificationException::class, DecodeException::class)
    public fun <T> parse(
        apiKey: String,
        rawBody: ByteArray,
        header: (String) -> String?,
        deserializer: DeserializationStrategy<T>,
    ): T = parse(apiKey, rawBody, header, deserializer, DEFAULT_TOLERANCE) { Instant.now() }

    /**
     * [parse] with [tolerance] (a non-positive value means [DEFAULT_TOLERANCE]) and [clock], for
     * Java callers.
     */
    @JvmStatic
    @Throws(WebhookVerificationException::class, DecodeException::class)
    public fun <T> parse(
        apiKey: String,
        rawBody: ByteArray,
        header: (String) -> String?,
        deserializer: DeserializationStrategy<T>,
        tolerance: java.time.Duration,
        clock: Clock,
    ): T = parse(apiKey, rawBody, header, deserializer, tolerance.toKotlinDuration()) { clock.instant() }

    /**
     * [verify], then decodes [rawBody] with [deserializer], e.g.
     * `PayoutWebhookEvent.Companion.serializer()` from Java.
     *
     * @throws WebhookVerificationException
     * @throws DecodeException if the body does not decode.
     * @throws IllegalArgumentException if [apiKey] is empty or holds only spaces and tabs.
     */
    @JvmStatic
    @Throws(WebhookVerificationException::class, DecodeException::class)
    public fun <T> parse(
        apiKey: String,
        rawBody: ByteArray,
        headers: Map<String, List<String>>,
        deserializer: DeserializationStrategy<T>,
    ): T = parse(apiKey, rawBody, headers, deserializer, DEFAULT_TOLERANCE) { Instant.now() }

    /**
     * [parse] with [tolerance] (a non-positive value means [DEFAULT_TOLERANCE]) and [clock], for
     * Java callers.
     */
    @JvmStatic
    @Throws(WebhookVerificationException::class, DecodeException::class)
    public fun <T> parse(
        apiKey: String,
        rawBody: ByteArray,
        headers: Map<String, List<String>>,
        deserializer: DeserializationStrategy<T>,
        tolerance: java.time.Duration,
        clock: Clock,
    ): T = parse(apiKey, rawBody, headers, deserializer, tolerance.toKotlinDuration()) { clock.instant() }

    /**
     * [verify], then decodes [rawBody] as [T], e.g.
     * `parse<PayoutWebhookEvent>(apiKey, body) { name -> request.getHeader(name) }`.
     *
     * One value per name: a repeated header is not visible this way and is not rejected. Pass the
     * headers as a map of name to every value received under it to reject repeats.
     *
     * @throws WebhookVerificationException
     * @throws DecodeException if the body does not decode.
     * @throws IllegalArgumentException if [apiKey] is empty or holds only spaces and tabs.
     */
    public inline fun <reified T> parse(apiKey: String, rawBody: ByteArray, noinline header: (String) -> String?): T =
        parse(apiKey, rawBody, header, json.serializersModule.serializer<T>(), DEFAULT_TOLERANCE) { Instant.now() }

    /**
     * [verify], then decodes [rawBody] as [T].
     *
     * @throws WebhookVerificationException
     * @throws DecodeException if the body does not decode.
     * @throws IllegalArgumentException if [apiKey] is empty or holds only spaces and tabs.
     */
    public inline fun <reified T> parse(
        apiKey: String,
        rawBody: ByteArray,
        noinline header: (String) -> String?,
        tolerance: Duration,
        noinline now: () -> Instant = { Instant.now() },
    ): T = parse(apiKey, rawBody, header, json.serializersModule.serializer<T>(), tolerance, now)

    /**
     * [verify], then decodes [rawBody] as [T].
     *
     * @throws WebhookVerificationException
     * @throws DecodeException if the body does not decode.
     * @throws IllegalArgumentException if [apiKey] is empty or holds only spaces and tabs.
     */
    public inline fun <reified T> parse(
        apiKey: String,
        rawBody: ByteArray,
        headers: Map<String, List<String>>,
        tolerance: Duration = DEFAULT_TOLERANCE,
        noinline now: () -> Instant = { Instant.now() },
    ): T = parse(apiKey, rawBody, headers, json.serializersModule.serializer<T>(), tolerance, now)

    private fun <T> decode(rawBody: ByteArray, deserializer: DeserializationStrategy<T>): T = try {
        json.decodeFromString(deserializer, rawBody.toString(Charsets.UTF_8))
    } catch (e: IllegalArgumentException) {
        throw DecodeException("cryptochief: webhook decode failed: ${e.message}", e)
    }

    private fun verifyV1(
        apiKey: String,
        rawBody: ByteArray,
        tolerance: Duration,
        now: () -> Instant,
        values: (String) -> List<String>,
    ) {
        val timestampValue = single(values, TIMESTAMP_HEADER)
        if (!isCanonicalDecimal(timestampValue)) throw headers(TIMESTAMP_HEADER)
        val timestamp = timestampValue.toLongOrNull() ?: throw headers(TIMESTAMP_HEADER)

        val deliveryId = single(values, DELIVERY_HEADER)
        if (!RequestSigner.isValidDeliveryId(deliveryId)) throw headers(DELIVERY_HEADER)

        val signatureValue = single(values, SIGNATURE_HEADER)
        if (!signatureValue.startsWith(RequestSigner.HMAC_V1_SIGNATURE_PREFIX)) throw headers(SIGNATURE_HEADER)
        val received = decodeHex(signatureValue.substring(RequestSigner.HMAC_V1_SIGNATURE_PREFIX.length))
            ?: throw headers(SIGNATURE_HEADER)

        val toleranceSeconds = (if (tolerance.isPositive()) tolerance else DEFAULT_TOLERANCE).inWholeSeconds
        val nowSeconds = now().epochSecond
        val earliest = if (nowSeconds < Long.MIN_VALUE + toleranceSeconds) Long.MIN_VALUE else nowSeconds - toleranceSeconds
        val latest = if (nowSeconds > Long.MAX_VALUE - toleranceSeconds) Long.MAX_VALUE else nowSeconds + toleranceSeconds
        if (timestamp < earliest || timestamp > latest) {
            throw WebhookTimestampException("cryptochief: webhook $TIMESTAMP_HEADER is outside the allowed window")
        }

        if (timestamp <= 0) throw headers(TIMESTAMP_HEADER)
        val stringToSign = RequestSigner.webhookV1StringToSign(timestamp, deliveryId, rawBody)
        if (!MessageDigest.isEqual(RequestSigner.hmacSha256(apiKey, stringToSign), received)) {
            throw WebhookSignatureException("cryptochief: invalid webhook signature")
        }
    }

    /**
     * Digits only, and no leading zero unless the value is `0` itself. `01789430400` is not the
     * timestamp that was signed: the string to sign carries the decimal number, so a receiver that
     * accepted both spellings would accept one signature under two header values.
     */
    private fun isCanonicalDecimal(value: String): Boolean {
        if (value.isEmpty() || value.any { it !in '0'..'9' }) return false
        return value.length == 1 || value[0] != '0'
    }

    /** The only value of [name], without surrounding spaces and tabs. */
    private fun single(values: (String) -> List<String>, name: String): String {
        val all = values(name)
        if (all.size != 1) throw headers(name)
        val value = all[0].trim(' ', '\t')
        if (value.any { it == '\r' || it == '\n' }) throw headers(name)
        return value
    }

    /**
     * Go `strings.EqualFold(key, name)` for an ASCII [name]: ASCII letters match in any case,
     * U+212A KELVIN SIGN matches `k`, U+017F LATIN SMALL LETTER LONG S matches `s`.
     */
    private fun headerNameMatches(key: String, name: String): Boolean {
        if (key.length != name.length) return false
        for (i in key.indices) {
            val k = key[i]
            val n = asciiLower(name[i])
            val same = asciiLower(k) == n || (k == '\u212A' && n == 'k') || (k == '\u017F' && n == 's')
            if (!same) return false
        }
        return true
    }

    private fun asciiLower(c: Char): Char = if (c in 'A'..'Z') c + ('a' - 'A') else c

    /** 32 bytes from exactly 64 hex characters in any case; `null` otherwise. */
    private fun decodeHex(hex: String): ByteArray? {
        if (hex.length != 64) return null
        val out = ByteArray(32)
        for (i in 0 until 32) {
            val hi = hexDigit(hex[2 * i])
            val lo = hexDigit(hex[2 * i + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private fun headers(name: String) =
        WebhookHeadersException("cryptochief: webhook header $name is missing, repeated or malformed")
}
