package com.cryptochief.processing

import com.cryptochief.processing.http.RequestSigner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Rules of the HMAC v1 request string to sign, next to the vectors in [HmacV1VectorsTest]. */
class HmacV1SigningTest {

    private val apiKey = "test_api_key_123"
    private val merchant = "3f2a1b4c-5d6e-7f80-9a1b-2c3d4e5f6071"
    private val timestamp = "1789430400"
    private val nonce = "0123456789abcdef0123456789abcdef"

    private fun stringToSign(
        method: String = "POST",
        path: String = "/v1/credits/balance",
        query: String = "",
        idempotencyKey: String = "",
        body: ByteArray = ByteArray(0),
    ): String = RequestSigner.hmacV1StringToSign(
        timestamp = timestamp,
        nonce = nonce,
        method = method,
        path = path,
        query = query,
        merchant = merchant,
        idempotencyKey = idempotencyKey,
        body = body,
    )

    private fun line(index: Int, method: String = "POST", path: String = "/v1/credits/balance", query: String = ""): String =
        stringToSign(method = method, path = path, query = query).split('\n')[index]

    @Test
    fun `layout is the nine lines of the specification`() {
        assertEquals(
            "CC-HMAC-SHA256-REQ-V1\n" +
                "$timestamp\n" +
                "$nonce\n" +
                "POST\n" +
                "/v1/credits/balance\n" +
                "\n" +
                "$merchant\n" +
                "\n" +
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            stringToSign(),
        )
    }

    @Test
    fun `only a-z is upper-cased in the method`() {
        for ((method, want) in mapOf("post" to "POST", "POST" to "POST", "PoSt" to "POST", "m-Search" to "M-SEARCH")) {
            assertEquals(want, line(3, method = method), method)
        }

        // Bytes outside a-z stay as they are: a Unicode mapping would rewrite them into other
        // letters, or into more bytes than the server received, and the signature would not match.
        val nul = Char(0)
        val nonAscii = mapOf(
            "pıng" to "PıNG",
            "getµ" to "GETµ",
            "getß" to "GETß",
            "poﬅ" to "POﬅ",
            "poİst" to "POİST",
            "ｐｏｓｔ" to "ｐｏｓｔ",
            "пост" to "пост",
            "${nul}post" to "${nul}POST",
        )
        for ((method, want) in nonAscii) {
            val got = line(3, method = method)
            assertEquals(want, got, method)
            assertEquals(method.length, got.length, method)
            for (i in method.indices) {
                if (method[i] in 'a'..'z') continue
                assertEquals(method[i], got[i], "$method: character $i changed")
            }
        }
    }

    /** The path is signed percent-decoded, the query as sent. */
    @Test
    fun `the path is signed decoded and the query raw`() {
        assertEquals("/v1/orders/payout/8814", RequestSigner.pathToSign("/v1/orders/payout%2F8814"))
        assertEquals("/v1/orders/a b", RequestSigner.pathToSign("/v1/orders/a%20b"))
        assertEquals("/v1/заказ/№1", RequestSigner.pathToSign("/v1/%D0%B7%D0%B0%D0%BA%D0%B0%D0%B7/%E2%84%961"))
        assertEquals("/v1/заказ", RequestSigner.pathToSign("/v1/заказ"))
        assertEquals("/v1/a+b", RequestSigner.pathToSign("/v1/a+b"))
        assertEquals("/v1/orders/8814", RequestSigner.pathToSign("/v1/orders/8814?uuid=a%2Fb"))
        assertEquals("/v1/x/%", RequestSigner.pathToSign("/v1/x/%25"))
        for (bad in listOf("/v1/x/%", "/v1/x/%2", "/v1/x/%zz", "/v1/x/%2z")) {
            assertThrows<IllegalArgumentException>(bad) { RequestSigner.pathToSign(bad) }
        }

        assertEquals("/v1/orders/payout/8814", line(4, path = RequestSigner.pathToSign("/v1/orders/payout%2F8814")))
        assertEquals("a=%2F&b=%20&c=%D1%82", line(5, query = "a=%2F&b=%20&c=%D1%82"))
    }

    @Test
    fun `CR or LF in a value is rejected`() {
        for (bad in listOf("a\nb", "a\rb")) {
            assertThrows<IllegalArgumentException>(bad) { stringToSign(path = bad) }
            assertThrows<IllegalArgumentException>(bad) { stringToSign(query = bad) }
            assertThrows<IllegalArgumentException>(bad) { stringToSign(idempotencyKey = bad) }
            assertThrows<IllegalArgumentException>(bad) { stringToSign(method = "PO${bad}ST") }
            assertThrows<IllegalArgumentException>(bad) {
                RequestSigner.signHmacV1(apiKey, timestamp, nonce, "POST", bad, "", merchant, "", ByteArray(0))
            }
        }

        // Line breaks in the body are fine: the body enters as a hash.
        assertTrue(stringToSign(body = "{\r\n}\n".toByteArray()).endsWith(RequestSigner.bodySha256("{\r\n}\n".toByteArray())))
    }

    /** A key of spaces and tabs is the empty key: the server trims exactly those before it verifies. */
    @Test
    fun `an empty api key is refused`() {
        for (key in listOf("", " ", "\t", " \t ", "   ")) {
            assertThrows<IllegalArgumentException>(key) {
                RequestSigner.signHmacV1(key, timestamp, nonce, "POST", "/v1/x", "", merchant, "", ByteArray(0))
            }
            assertThrows<IllegalArgumentException>(key) {
                RequestSigner.signWebhookV1(key, 1789430400L, "1e0e1a2b-3c4d-5e6f-7081-92a3b4c5d6e7", ByteArray(0))
            }
            assertThrows<IllegalArgumentException>(key) {
                Options.builder().apply {
                    merchantId = merchant
                    apiKey = key
                }.build()
            }
        }

        // A key with spaces around it is not empty and signs as given.
        assertNotEquals(
            RequestSigner.signHmacV1(" $apiKey ", timestamp, nonce, "POST", "/v1/x", "", merchant, "", ByteArray(0)),
            RequestSigner.signHmacV1(apiKey, timestamp, nonce, "POST", "/v1/x", "", merchant, "", ByteArray(0)),
        )
    }

    @Test
    fun `nonce is 32 lowercase hex characters`() {
        val a = RequestSigner.newNonce()
        val b = RequestSigner.newNonce()
        assertTrue(Regex("^[0-9a-f]{32}$").matches(a), a)
        assertNotEquals(a, b)
    }
}
