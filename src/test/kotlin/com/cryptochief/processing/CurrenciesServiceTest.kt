package com.cryptochief.processing

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

class CurrenciesServiceTest {

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

    // ---- fiats ----------------------------------------------------------------------

    @Test
    fun `fiats decodes a bare top-level array`() = runBlocking {
        // The wire shape is an ARRAY, not an {"items":[...]} envelope. A decoder written
        // for the envelope compiles perfectly and only fails here, against real JSON.
        enqueue(
            """
            [
              {"code":"JMD","name":"Jamaican Dollar"},
              {"code":"KYD","name":"Cayman Islands Dollar"},
              {"code":"SEK","name":"Swedish Krona"}
            ]
            """.trimIndent(),
        )

        val fiats = client.currencies.fiats()

        val req = taken()
        assertEquals("/v1/currencies/fiats", req.path)
        // Nothing to filter by, but the empty object is still the signed body.
        assertEquals("{}", req.body.readUtf8())
        assertNotNull(req.getHeader("Signature"))

        assertEquals(3, fiats.size)
        assertEquals("JMD", fiats[0].code)
        assertEquals("Jamaican Dollar", fiats[0].name)
        // These codes are what `currency` takes on a fiat-mode pay-in.
        assertEquals("SEK", fiats[2].code)
        assertEquals("Swedish Krona", fiats[2].name)
    }

    @Test
    fun `an empty fiat list is an empty array, not an error`() = runBlocking {
        enqueue("[]")

        assertTrue(client.currencies.fiats().isEmpty())
    }

    @Test
    fun `a literal null body is an empty fiat list`() = runBlocking {
        // The service builds the array from a Go slice, and an empty slice marshals as
        // null, not as []. A method promising a List has to answer with an empty one -
        // not a DecodeException, which is what the plain list serializer would raise.
        enqueue("null")

        assertTrue(client.currencies.fiats().isEmpty())
    }

    // ---- cryptos ---------------------------------------------------------------------

    @Test
    fun `cryptos decodes by_exchange across several exchanges`() = runBlocking {
        enqueue(
            """
            {
              "by_exchange": {
                "binance": ["0G","1000CAT","BTC","ETH","USDT"],
                "bybit":   ["0G","1INCH","AAVE","BTC"],
                "exmo":    ["ADA","BCH","BTC"],
                "kucoin":  ["0G","A2Z","AAVE","BTC"]
              },
              "count": 9,
              "quote": "USDT",
              "tickers": ["0G","1000CAT","1INCH","A2Z","AAVE","ADA","BCH","BTC","ETH","USDT"]
            }
            """.trimIndent(),
        )

        val rates = client.currencies.cryptos()

        val req = taken()
        assertEquals("/v1/currencies/cryptos", req.path)
        // Platform-wide: nothing to send, and the empty object is signed all the same.
        assertEquals("{}", req.body.readUtf8())
        assertNotNull(req.getHeader("Signature"))

        // by_exchange is a map from exchange name to that exchange's tickers, and more
        // than one exchange has to survive the decode - a single-exchange fixture would
        // pass against a model that only kept the first.
        assertEquals(setOf("binance", "bybit", "exmo", "kucoin"), rates.byExchange.keys)
        assertEquals(listOf("0G", "1000CAT", "BTC", "ETH", "USDT"), rates.byExchange["binance"])
        assertEquals(listOf("ADA", "BCH", "BTC"), rates.byExchange["exmo"])
        assertEquals(4, rates.byExchange["kucoin"]?.size)
        // Only exmo among these does not carry ETH; the union still does.
        assertTrue(rates.byExchange.getValue("exmo").none { it == "ETH" })

        assertEquals("USDT", rates.quote)
        assertEquals(9, rates.count)
        assertTrue(rates.tickers.contains("USDT"))
        assertEquals(10, rates.tickers.size)
    }

    @Test
    fun `a literal null body is an empty cryptos result`() = runBlocking {
        enqueue("null")

        val rates = client.currencies.cryptos()

        assertTrue(rates.tickers.isEmpty())
        assertTrue(rates.byExchange.isEmpty())
        assertEquals(0, rates.count)
        assertEquals("", rates.quote)
    }

    @Test
    fun `a null inside by_exchange is that exchange with no tickers`() = runBlocking {
        // The nested null is the one the top-level guard does not reach: by_exchange is a
        // map, and coercing a missing property to its default says nothing about a value
        // sitting under a key. One quiet exchange must not fail the whole response.
        enqueue(
            """
            {
              "by_exchange": {"binance": ["BTC","ETH"], "bybit": null},
              "count": 2,
              "quote": "USDT",
              "tickers": ["BTC","ETH"]
            }
            """.trimIndent(),
        )

        val rates = client.currencies.cryptos()

        assertEquals(setOf("binance", "bybit"), rates.byExchange.keys)
        assertEquals(listOf("BTC", "ETH"), rates.byExchange["binance"])
        // Present, and empty - not absent, and not null.
        assertEquals(emptyList<String>(), rates.byExchange["bybit"])
        assertTrue(rates.byExchange.getValue("bybit").isEmpty())
    }

    @Test
    fun `a null by_exchange and a null tickers list are both empty`() = runBlocking {
        enqueue("""{"by_exchange":null,"count":0,"quote":"USDT","tickers":null}""")

        val rates = client.currencies.cryptos()

        assertTrue(rates.byExchange.isEmpty())
        assertTrue(rates.tickers.isEmpty())
        assertEquals("USDT", rates.quote)
    }

    @Test
    fun `a quoted ticker is not an asset the project can be paid in`() = runBlocking {
        // The one way this endpoint is misread: cryptos() is rate availability. What the
        // project can actually take is contractsAvailable(), and it is a different, far
        // shorter list. Both fixtures below are what the two endpoints really answer.
        enqueue("""{"by_exchange":{"binance":["DOGE","USDT"]},"count":2,"quote":"USDT","tickers":["DOGE","USDT"]}""")
        enqueue(
            """
            {"items":[{"network":"TRON_MAINNET","coin":"USDT",
              "contract":"TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t","chain_family":"TRON",
              "type":"token","is_test":false,"decimals":6}]}
            """.trimIndent(),
        )

        val quoted = client.currencies.cryptos().tickers
        val payable = client.blockchain.contractsAvailable().items.map { it.coin }

        assertTrue(quoted.contains("DOGE"))
        // Quotable, and still not something an order may be created in.
        assertTrue(payable.none { it == "DOGE" })
        assertEquals(listOf("USDT"), payable)
    }
}
