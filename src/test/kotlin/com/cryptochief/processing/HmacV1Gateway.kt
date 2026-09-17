package com.cryptochief.processing

import com.cryptochief.processing.http.RequestSigner
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import java.security.MessageDigest
import java.util.Collections

/** Outcome of the gateway's check of one request. */
enum class HmacV1Outcome {
    OK,

    /** 400 `BAD_AUTH_HEADERS`. */
    BAD_AUTH_HEADERS,

    /** 401 `SIGNATURE_TIMESTAMP_OUT_OF_RANGE`. */
    TIMESTAMP_OUT_OF_RANGE,

    /** 401 `INVALID_SIGNATURE`. */
    INVALID_SIGNATURE,

    /** 401 `SIGNATURE_REPLAYED`. */
    REPLAYED,
}

/**
 * MockWebServer dispatcher that checks requests the way the gateway does (HMAC v1 only) and
 * passes accepted requests to [respond]. [HmacV1Gateway.check] holds the rules; the vectors in
 * `hmac_v1_vectors.json` run through the same function.
 */
class HmacV1Gateway(
    private val merchant: String,
    private val apiKey: String,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val respond: (RecordedRequest) -> MockResponse,
) : Dispatcher() {

    private val nonces: MutableSet<String> = Collections.synchronizedSet(HashSet())

    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.requestUrl!!
        val body = request.body.clone().readByteArray()
        val serverTime = now()
        val outcome = check(
            apiKey = apiKey,
            method = request.method!!,
            // The path as the server reads it: the wire spelling, percent-decoded.
            path = RequestSigner.pathToSign(url.encodedPath),
            query = url.encodedQuery.orEmpty(),
            headers = request.headers.toMultimap(),
            body = body,
            now = serverTime,
            merchant = merchant,
            seenNonce = { key -> !nonces.add(key) },
        )
        return when (outcome) {
            HmacV1Outcome.OK -> respond(request)
            HmacV1Outcome.BAD_AUTH_HEADERS -> error(400, ErrorCode.BAD_AUTH_HEADERS)
            HmacV1Outcome.TIMESTAMP_OUT_OF_RANGE ->
                error(401, ErrorCode.SIGNATURE_TIMESTAMP_OUT_OF_RANGE, serverTime)
            HmacV1Outcome.INVALID_SIGNATURE -> error(401, ErrorCode.INVALID_SIGNATURE)
            HmacV1Outcome.REPLAYED -> error(401, ErrorCode.SIGNATURE_REPLAYED)
        }
    }

    private fun error(status: Int, code: String, serverTime: Long? = null): MockResponse {
        val time = if (serverTime == null) "" else ""","server_time":$serverTime"""
        return MockResponse().setResponseCode(status)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"ok":false,"error":"$code","msg":"$code"$time}""")
    }

    companion object {
        private val NONCE = Regex("^[A-Za-z0-9_-]{16,64}$")
        private const val WINDOW_SECONDS = 300L
        private const val TIMESTAMP_MAX_DIGITS = 18

        /**
         * The gateway's check of one request, in its order:
         * 1. `Merchant`, `X-CC-Timestamp`, `X-CC-Nonce`, `X-CC-Signature`, `Idempotency-Key`
         *    present at most once, spaces and tabs trimmed off the value, no CR or LF; merchant
         *    not empty, timestamp decimal and at most 18 digits, nonce 16–64 `[A-Za-z0-9_-]`,
         *    signature `v1=` and 64 hex characters, `Content-Type: application/json` on a
         *    non-empty body — else [HmacV1Outcome.BAD_AUTH_HEADERS]. `Signature` is not read;
         * 2. timestamp within 300 s of [now] — else [HmacV1Outcome.TIMESTAMP_OUT_OF_RANGE];
         * 3. project key not empty and the signature matching the request as received — else
         *    [HmacV1Outcome.INVALID_SIGNATURE];
         * 4. nonce not seen before — else [HmacV1Outcome.REPLAYED].
         *
         * [path] is the route with its `%`-sequences decoded, [query] the string as sent.
         * [merchant] is the project the key belongs to, `null` to accept whichever merchant the
         * request names.
         */
        fun check(
            apiKey: String,
            method: String,
            path: String,
            query: String,
            headers: Map<String, List<String>>,
            body: ByteArray,
            now: Long,
            merchant: String? = null,
            seenNonce: (String) -> Boolean = { false },
        ): HmacV1Outcome {
            val merchantValue = single(headers, "Merchant") ?: return HmacV1Outcome.BAD_AUTH_HEADERS
            val timestamp = single(headers, RequestSigner.HEADER_TIMESTAMP) ?: return HmacV1Outcome.BAD_AUTH_HEADERS
            val nonce = single(headers, RequestSigner.HEADER_NONCE) ?: return HmacV1Outcome.BAD_AUTH_HEADERS
            val signature = single(headers, RequestSigner.HEADER_HMAC_SIGNATURE)
                ?: return HmacV1Outcome.BAD_AUTH_HEADERS
            val idempotencyKey = single(headers, RequestSigner.HEADER_IDEMPOTENCY_KEY)
                ?: return HmacV1Outcome.BAD_AUTH_HEADERS

            if (merchantValue.isEmpty() || !isDecimal(timestamp) || !NONCE.matches(nonce)) {
                return HmacV1Outcome.BAD_AUTH_HEADERS
            }
            val ts = timestamp.toLongOrNull() ?: return HmacV1Outcome.BAD_AUTH_HEADERS
            if (!signature.startsWith(RequestSigner.HMAC_V1_SIGNATURE_PREFIX)) {
                return HmacV1Outcome.BAD_AUTH_HEADERS
            }
            val hex = signature.substring(RequestSigner.HMAC_V1_SIGNATURE_PREFIX.length)
            if (hex.length != 64 || !hex.all { isHex(it) }) return HmacV1Outcome.BAD_AUTH_HEADERS
            if (body.isNotEmpty() && !isJsonContentType(first(headers, "Content-Type"))) {
                return HmacV1Outcome.BAD_AUTH_HEADERS
            }

            val expected = try {
                RequestSigner.hmacV1StringToSign(
                    timestamp = timestamp,
                    nonce = nonce,
                    method = method,
                    path = path,
                    query = query,
                    merchant = merchantValue,
                    idempotencyKey = idempotencyKey,
                    body = body,
                )
            } catch (_: IllegalArgumentException) {
                return HmacV1Outcome.BAD_AUTH_HEADERS
            }

            if (ts < now - WINDOW_SECONDS || ts > now + WINDOW_SECONDS) {
                return HmacV1Outcome.TIMESTAMP_OUT_OF_RANGE
            }

            // A project whose key is empty or only spaces and tabs signs nothing; it is refused
            // before the comparison, and the refusal is not a signature mismatch to count.
            if (RequestSigner.isEmptyApiKey(apiKey)) return HmacV1Outcome.INVALID_SIGNATURE
            if (merchant != null && merchantValue != merchant) return HmacV1Outcome.INVALID_SIGNATURE
            val mac = RequestSigner.hmacSha256(apiKey, expected)
            if (!MessageDigest.isEqual(mac, decodeHex(hex))) return HmacV1Outcome.INVALID_SIGNATURE

            if (seenNonce("$merchantValue|$nonce")) return HmacV1Outcome.REPLAYED
            return HmacV1Outcome.OK
        }

        /**
         * The only value of [name], without surrounding spaces and tabs; `""` when the header is
         * absent, `null` when it is repeated or holds CR or LF.
         */
        private fun single(headers: Map<String, List<String>>, name: String): String? {
            val values = headers.entries.filter { it.key.equals(name, ignoreCase = true) }.flatMap { it.value }
            if (values.size > 1) return null
            if (values.isEmpty()) return ""
            val trimmed = values[0].trim(' ', '\t')
            return if (trimmed.any { it == '\r' || it == '\n' }) null else trimmed
        }

        /** First value of [name], as `http.Header.Get` reads it; `""` when the header is absent. */
        private fun first(headers: Map<String, List<String>>, name: String): String =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull().orEmpty()

        private fun isDecimal(value: String): Boolean =
            value.isNotEmpty() &&
                value.length <= TIMESTAMP_MAX_DIGITS &&
                !(value.length > 1 && value[0] == '0') &&
                value.all { it in '0'..'9' }

        private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

        private fun decodeHex(hex: String): ByteArray =
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

        /** Media type `application/json` in any case, parameters allowed but each with a value. */
        private fun isJsonContentType(value: String): Boolean {
            if (value.isEmpty()) return false
            val parts = value.split(';')
            if (!parts[0].trim(' ', '\t').equals("application/json", ignoreCase = true)) return false
            for (param in parts.drop(1)) {
                val (name, v) = param.split('=', limit = 2).let { it[0] to it.getOrNull(1) }
                if (name.trim(' ', '\t').isEmpty()) return false
                if (v == null || v.trim(' ', '\t').isEmpty()) return false
            }
            return true
        }

        /**
         * Checks `X-CC-Signature` of [recorded] against its own headers and body, and that no
         * `Signature` header was sent. Returns the body.
         */
        fun assertSigned(
            recorded: RecordedRequest,
            apiKey: String,
            path: String = RequestSigner.pathToSign(recorded.requestUrl!!.encodedPath),
        ): ByteArray {
            val body = recorded.body.clone().readByteArray()
            val expected = "v1=" + RequestSigner.signHmacV1(
                apiKey = apiKey,
                timestamp = recorded.getHeader(RequestSigner.HEADER_TIMESTAMP)!!,
                nonce = recorded.getHeader(RequestSigner.HEADER_NONCE)!!,
                method = recorded.method!!,
                path = path,
                query = recorded.requestUrl!!.encodedQuery.orEmpty(),
                merchant = recorded.getHeader("Merchant")!!,
                idempotencyKey = recorded.getHeader(RequestSigner.HEADER_IDEMPOTENCY_KEY)?.trim(' ', '\t').orEmpty(),
                body = body,
            )
            assertEquals(expected, recorded.getHeader(RequestSigner.HEADER_HMAC_SIGNATURE))
            assertNull(recorded.getHeader("Signature"))
            return body
        }

        /** [expected] and [actual] are the same JSON value; object member order is ignored. */
        fun assertSameJson(expected: String, actual: String, message: String? = null) {
            assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual), message ?: actual)
        }
    }
}
