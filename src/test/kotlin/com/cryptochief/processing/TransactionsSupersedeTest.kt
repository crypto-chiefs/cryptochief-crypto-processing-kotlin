package com.cryptochief.processing

import com.cryptochief.processing.models.SignTransactionRequest
import com.cryptochief.processing.models.TransactionInfo
import com.cryptochief.processing.models.TxStatus
import com.cryptochief.processing.models.TxType
import com.cryptochief.processing.poll.waitForTransaction
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

/**
 * EVM signature supersede: the `cancelled` status, `superseded_uuids` on the sign answer,
 * `error_reason` on the transaction, and the new error codes.
 */
class TransactionsSupersedeTest {

    private val old = "0c1d9f3e-5a7b-4c2e-9f1a-3b6d8e2f4a10"
    private val new = "b4ee6a7a-f7c2-474d-b002-e83ebe3e78db"

    private val signReq = SignTransactionRequest(
        network = Chain.ETH_MAINNET,
        fromAddress = "0xfrom",
        type = TxType.NATIVE,
        toAddress = "0xto",
        value = "1",
    )

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

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    @Test
    fun `cancelled is final`() {
        assertEquals("cancelled", TxStatus.CANCELLED)
        assertTrue(TransactionInfo(status = TxStatus.CANCELLED).isTerminal)
        assertFalse(TransactionInfo(status = TxStatus.CANCELLED).succeeded)
        for (live in listOf(TxStatus.SIGNED, TxStatus.BROADCASTING, TxStatus.BROADCASTED)) {
            assertFalse(TransactionInfo(status = live).isTerminal, live)
        }
    }

    @Test
    fun `waiting returns a superseded signature at once`() = runBlocking {
        val body = """{"uuid": "$old", "status": "cancelled", "error_reason": "SUPERSEDED_BY:$new"}"""
        repeat(50) { enqueue(body) }

        val tx = client.waitForTransaction(
            old,
            PollOptions(interval = Duration.ofMillis(10), timeout = Duration.ofMillis(300)),
        )

        assertEquals(TxStatus.CANCELLED, tx.status)
        assertEquals("SUPERSEDED_BY:$new", tx.errorReason)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `sign reads the replaced signatures`() = runBlocking {
        enqueue(
            """
            {"uuid": "$new", "status": "signed", "network": "ETH_MAINNET", "chain_family": "EVM",
             "signed_tx_hex": "0x02", "tx_hash": "0xabc", "expires_at": "2026-06-01T12:10:00Z",
             "superseded_uuids": ["$old"]}
            """.trimIndent(),
        )

        val res = client.transactions.sign(signReq)

        assertEquals(listOf(old), res.supersededUuids)
    }

    @Test
    fun `sign without replaced signatures reads empty`() = runBlocking {
        enqueue("""{"uuid": "$new", "status": "signed"}""")

        val res = client.transactions.sign(signReq)

        assertEquals(emptyList<String>(), res.supersededUuids)
    }

    @Test
    fun `info reads the nonce gap reason`() = runBlocking {
        val reason = "NONCE_GAP: missing_nonce=7 blocking_uuid=$old"
        enqueue("""{"uuid": "$new", "status": "signed", "error_reason": "$reason"}""")

        val tx = client.transactions.info(new)

        assertEquals(reason, tx.errorReason)
        assertNull(tx.error)
    }

    @ParameterizedTest
    @ValueSource(strings = [ErrorCode.NONCE_GAP, ErrorCode.NONCE_ALREADY_USED])
    fun `execute surfaces the nonce codes`(code: String) {
        enqueue("""{"error": "SERVICE_ERROR", "msg": "$code", "ok": false}""", 400)

        val ex = assertThrows<ApiException> { runBlocking { client.transactions.execute(new) } }

        assertEquals(code, ex.code)
        assertEquals(400, ex.status)
    }

    @Test
    fun `sign is refused while an execute is unresolved`() {
        enqueue(
            """{"error": "SERVICE_ERROR", "msg": "PREVIOUS_EXECUTE_UNRESOLVED: uuid=$old", "ok": false}""",
            400,
        )

        val ex = assertThrows<ApiException> { runBlocking { client.transactions.sign(signReq) } }

        assertTrue(ex.code.startsWith(ErrorCode.PREVIOUS_EXECUTE_UNRESOLVED), ex.code)
        assertTrue(ex.code.endsWith(old), ex.code)
    }
}
