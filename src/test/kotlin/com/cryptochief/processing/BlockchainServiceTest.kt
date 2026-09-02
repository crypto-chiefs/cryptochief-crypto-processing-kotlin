package com.cryptochief.processing

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

class BlockchainServiceTest {

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

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
    }

    private fun taken(): RecordedRequest = server.takeRequest()

    // ---- supported blockchains -----------------------------------------------------

    @Test
    fun `blockchainsList decodes a bare top-level array`() = runBlocking {
        // The wire shape is an ARRAY, not an {"items":[...]} envelope. A decoder written
        // for the envelope compiles perfectly and only fails here, against real JSON.
        enqueue(
            """
            [
              {"name":"ETH_MAINNET","type":"evm"},
              {"name":"ETH_SEPOLIA","type":"evm"},
              {"name":"TRON_MAINNET","type":"tron"},
              {"name":"SOLANA_MAINNET","type":"solana"}
            ]
            """.trimIndent(),
        )

        val chains = client.blockchain.blockchainsList()

        val req = taken()
        assertEquals("/v1/blockchains/list", req.path)
        // Nothing to filter by, but the empty object is still signed like every request.
        assertEquals("{}", req.body.readUtf8())
        assertNotNull(req.getHeader("Signature"))

        assertEquals(4, chains.size)
        assertEquals(Chain.ETH_MAINNET, chains[0].name)
        assertEquals("evm", chains[0].type)
        assertEquals(Chain.TRON_MAINNET, chains[2].name)
        assertEquals("tron", chains[2].type)
        // name is the chain key, so it feeds straight back into anything taking a Chain.
        assertEquals(ChainFamily.SOLANA, chains[3].name.family())
        // type is the scanner's lower-case protocol family, NOT the upper-case
        // chain_family carried everywhere else. Conflating the two would be wrong.
        assertEquals("solana", chains[3].type)
    }

    @Test
    fun `an empty chain list is an empty array, not an error`() = runBlocking {
        enqueue("[]")

        assertTrue(client.blockchain.blockchainsList().isEmpty())
    }

    @Test
    fun `a literal null body is an empty chain list`() = runBlocking {
        // The service builds the array from a Go slice, and an empty slice marshals as
        // null, not as []. A method promising a List has to answer with an empty one -
        // not a DecodeException, which is what the plain list serializer would raise.
        enqueue("null")

        assertTrue(client.blockchain.blockchainsList().isEmpty())
    }

    // ---- platform assets catalogue ---------------------------------------------------

    @Test
    fun `contractsList keeps chain_family, is_test and a native coin's empty contract`() = runBlocking {
        enqueue(
            """
            {
              "items": [
                {"network":"ETH_MAINNET","coin":"ETH","contract":"","chain_family":"EVM",
                 "type":"native","is_test":false,"decimals":18},
                {"network":"TRON_MAINNET","coin":"USDT","contract":"TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t",
                 "chain_family":"TRON","type":"token","is_test":false,"decimals":6},
                {"network":"ETH_SEPOLIA","coin":"ETH","contract":"","chain_family":"EVM",
                 "type":"native","is_test":true,"decimals":18}
              ]
            }
            """.trimIndent(),
        )

        val items = client.blockchain.contractsList().items

        val req = taken()
        assertEquals("/v1/blockchain/contracts/list", req.path)
        // Platform-wide: there is nothing to filter by project.
        assertEquals("{}", req.body.readUtf8())

        assertEquals(3, items.size)
        val ethNative = items[0]
        // "" is how a native coin says it has no contract. It must not arrive as null,
        // and it must not blow the decode up either.
        assertEquals("", ethNative.contract)
        assertEquals("native", ethNative.type)
        // Both fields the SDK used to drop on the floor.
        assertEquals(ChainFamily.EVM, ethNative.chainFamily)
        assertFalse(ethNative.isTest)
        assertEquals(18, ethNative.decimals)

        val tronToken = items[1]
        assertEquals(ChainFamily.TRON, tronToken.chainFamily)
        assertEquals("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", tronToken.contract)
        assertEquals(6, tronToken.decimals)

        // is_test is what tells a test-network asset from a real one; the catalogue
        // carries both, so reading it wrong puts a real payment on a test chain.
        assertTrue(items[2].isTest)
        assertEquals(Chain.ETH_SEPOLIA, items[2].network)
    }

    @Test
    fun `the project catalogue carries the same two fields`() = runBlocking {
        // contracts/available and contracts/list share an item type on purpose - the
        // fields added for the catalogue have to survive on the project endpoint too.
        enqueue(
            """
            {"items":[{"network":"ARBITRUM_SEPOLIA","coin":"ETH","contract":"",
              "chain_family":"EVM","type":"native","is_test":true,"decimals":18,
              "network_icon":"https://cdn.example/arbitrum.svg"}]}
            """.trimIndent(),
        )

        val item = client.blockchain.contractsAvailable(Chain.ARBITRUM_SEPOLIA).items.single()

        assertEquals(ChainFamily.EVM, item.chainFamily)
        assertTrue(item.isTest)
        assertEquals("", item.contract)
    }
}
