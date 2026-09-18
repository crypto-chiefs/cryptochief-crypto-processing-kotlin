package com.cryptochief.processing

import com.cryptochief.processing.http.RequestSigner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.security.MessageDigest

/**
 * Vectors in `src/test/resources/hmac_v1_vectors.json`, a byte copy of the gateway's own file.
 * Every record is signed here and verified by [HmacV1Gateway.check], the refusals included: a
 * record that names a refusal has to give exactly that refusal, and exactly one defect causes it.
 */
class HmacV1VectorsTest {

    private class Vector(val json: JsonObject) {
        val name = str("name")
        val apiKey = str("api_key")
        val method = str("method")
        val path = str("path")
        val query = str("query")
        val merchant = str("merchant")
        val idempotencyKey = str("idempotency_key")
        val timestamp = str("timestamp")
        val nonce = str("nonce")
        val body: ByteArray = str("body").toByteArray(Charsets.UTF_8)
        val signature = str("signature")
        val now: Long = json.getValue("now").jsonPrimitive.long
        val expect = str("expect")

        /** Header values the receiver sees: defaults from the record, replaced by `headers`. */
        val headers: Map<String, List<String>> = buildMap {
            put("Merchant", listOf(merchant))
            put(RequestSigner.HEADER_TIMESTAMP, listOf(timestamp))
            put(RequestSigner.HEADER_NONCE, listOf(nonce))
            put(RequestSigner.HEADER_HMAC_SIGNATURE, listOf(RequestSigner.HMAC_V1_SIGNATURE_PREFIX + signature))
            if (idempotencyKey.isNotEmpty()) put(RequestSigner.HEADER_IDEMPOTENCY_KEY, listOf(idempotencyKey))
            if (body.isNotEmpty()) put("Content-Type", listOf("application/json"))
            json["headers"]?.jsonObject?.forEach { (name, values) ->
                keys.filter { it.equals(name, ignoreCase = true) }.forEach { remove(it) }
                put(name, values.jsonArray.map { it.jsonPrimitive.content })
            }
        }

        fun str(field: String): String = json.getValue(field).jsonPrimitive.content
    }

    private val vectors: List<Vector> = run {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/hmac_v1_vectors.json")) {
            "hmac_v1_vectors.json not found"
        }.use { it.readBytes() }
        assertEquals(
            "a87df4921399dc14c7ceaa7e4c0dfa02495ad0400a5e722adfc0d3e3c1e064fe",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            "hmac_v1_vectors.json is not the gateway's file",
        )
        Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonArray.map { Vector(it.jsonObject) }
    }

    private fun expected(expect: String): HmacV1Outcome = when (expect) {
        "ok" -> HmacV1Outcome.OK
        "bad_auth_headers" -> HmacV1Outcome.BAD_AUTH_HEADERS
        "timestamp_out_of_range" -> HmacV1Outcome.TIMESTAMP_OUT_OF_RANGE
        "invalid_signature" -> HmacV1Outcome.INVALID_SIGNATURE
        else -> error("unknown expect: $expect")
    }

    private fun check(v: Vector, headers: Map<String, List<String>> = v.headers, now: Long = v.now): HmacV1Outcome =
        HmacV1Gateway.check(
            apiKey = v.apiKey,
            method = v.method,
            path = v.path,
            query = v.query,
            headers = headers,
            body = v.body,
            now = now,
            merchant = v.merchant,
        )

    @Test
    fun `vector file has every kind of outcome`() {
        assertEquals(50, vectors.size)
        assertEquals(
            mapOf("ok" to 21, "bad_auth_headers" to 24, "timestamp_out_of_range" to 2, "invalid_signature" to 3),
            vectors.groupingBy { it.expect }.eachCount(),
        )
    }

    @TestFactory
    fun `string to sign and signature match the gateway`(): List<DynamicTest> = vectors.map { v ->
        DynamicTest.dynamicTest(v.name) {
            assertEquals(v.str("body_sha256"), RequestSigner.bodySha256(v.body))
            assertEquals(
                v.str("string_to_sign"),
                RequestSigner.hmacV1StringToSign(
                    timestamp = v.timestamp,
                    nonce = v.nonce,
                    method = v.method,
                    path = v.path,
                    query = v.query,
                    merchant = v.merchant,
                    idempotencyKey = v.idempotencyKey,
                    body = v.body,
                ),
            )
            assertEquals(
                // The vector file carries the bare hex; the header value adds the prefix.
                RequestSigner.HMAC_V1_SIGNATURE_PREFIX + v.signature,
                RequestSigner.signHmacV1(
                    apiKey = v.apiKey,
                    timestamp = v.timestamp,
                    nonce = v.nonce,
                    method = v.method,
                    path = v.path,
                    query = v.query,
                    merchant = v.merchant,
                    idempotencyKey = v.idempotencyKey,
                    body = v.body,
                ),
            )
        }
    }

    @TestFactory
    fun `verification gives the expected outcome`(): List<DynamicTest> = vectors.map { v ->
        DynamicTest.dynamicTest(v.name) {
            assertEquals(expected(v.expect), check(v))
            // Header names in another case.
            assertEquals(expected(v.expect), check(v, headers = v.headers.mapKeys { it.key.lowercase() }))
        }
    }

    /** A record that names a refusal carries one defect: without it the same record passes. */
    @TestFactory
    fun `a refused record has exactly one defect`(): List<DynamicTest> =
        vectors.filter { it.expect != "ok" }.map { v ->
            DynamicTest.dynamicTest(v.name) {
                val clean = Vector(JsonObject(v.json - "headers"))
                assertEquals(HmacV1Outcome.OK, check(clean, now = clean.timestamp.toLong()))
            }
        }

    @Test
    fun `a replayed nonce is refused`() {
        val v = vectors.first { it.expect == "ok" }
        val seen = HashSet<String>()
        repeat(2) { attempt ->
            val outcome = HmacV1Gateway.check(
                apiKey = v.apiKey,
                method = v.method,
                path = v.path,
                query = v.query,
                headers = v.headers,
                body = v.body,
                now = v.now,
                merchant = v.merchant,
                seenNonce = { key -> !seen.add(key) },
            )
            assertEquals(if (attempt == 0) HmacV1Outcome.OK else HmacV1Outcome.REPLAYED, outcome)
        }
    }

    /** A project whose key is empty or only spaces and tabs verifies nothing. */
    @Test
    fun `an empty project key is refused`() {
        val v = vectors.first { it.expect == "ok" }
        for (key in listOf("", " ", "\t", " \t ")) {
            assertEquals(
                HmacV1Outcome.INVALID_SIGNATURE,
                HmacV1Gateway.check(
                    apiKey = key,
                    method = v.method,
                    path = v.path,
                    query = v.query,
                    headers = v.headers,
                    body = v.body,
                    now = v.now,
                    merchant = v.merchant,
                ),
                key,
            )
        }
    }
}
