package com.cryptochief.processing

import com.cryptochief.processing.http.CanonicalJson
import com.cryptochief.processing.http.RequestSigner
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64

class SigningTest {

    private val apiKey = "test_api_key_4242"

    @Test
    fun `empty body signs as md5 of api key`() {
        val sig = RequestSigner.sign(ByteArray(0), apiKey)
        val expected = expected("", apiKey)
        assertEquals(expected, sig)
    }

    @Test
    fun `canonical signing matches reference algorithm`() {
        val payload = buildJsonObject {
            put("uuid", "abcd-1234")
            put("network", "ETH_MAINNET")
        }
        val canonical = CanonicalJson.encode(payload)
        assertEquals(
            """{"network":"ETH_MAINNET","uuid":"abcd-1234"}""",
            canonical.toString(Charsets.UTF_8),
        )
        val sig = RequestSigner.sign(canonical, apiKey)
        val expected = expected(canonical.toString(Charsets.UTF_8), apiKey)
        assertEquals(expected, sig)
    }

    @Test
    fun `signing parity test vector locks the algorithm`() {
        val canonical = """{"a":1,"b":[2,3]}""".toByteArray()
        val sig = RequestSigner.sign(canonical, "my-secret-key")
        val b64 = Base64.getEncoder().encodeToString(canonical)
        val md5 = MessageDigest.getInstance("MD5")
        md5.update((b64 + "my-secret-key").toByteArray())
        val expected = md5.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        assertEquals(expected, sig)
    }

    @Test
    fun `signing rejects empty api key`() {
        val ex = runCatching {
            RequestSigner.sign("test".toByteArray(), "")
        }.exceptionOrNull()
        assertEquals(true, ex is IllegalArgumentException)
    }

    private fun expected(canonical: String, key: String): String {
        val b64 = Base64.getEncoder().encodeToString(canonical.toByteArray())
        val md5 = MessageDigest.getInstance("MD5")
        md5.update((b64 + key).toByteArray())
        return md5.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
