package com.cryptochief.processing

import com.cryptochief.processing.http.SdkJson
import com.cryptochief.processing.models.AvailableContract
import com.cryptochief.processing.models.AvailableContractsResponse
import com.cryptochief.processing.models.BatchExecuteResponse
import com.cryptochief.processing.models.BatchItemResult
import com.cryptochief.processing.models.CoinOption
import com.cryptochief.processing.models.ConvertResponse
import com.cryptochief.processing.models.CreditsBalance
import com.cryptochief.processing.models.CreditsTopup
import com.cryptochief.processing.models.CryptoCurrencies
import com.cryptochief.processing.models.EstimatePayoutResponse
import com.cryptochief.processing.models.FiatCurrency
import com.cryptochief.processing.models.ForceSweepResponse
import com.cryptochief.processing.models.ListWalletsResponse
import com.cryptochief.processing.models.PayIn
import com.cryptochief.processing.models.PayInHistoryResponse
import com.cryptochief.processing.models.PayoutCoinBalance
import com.cryptochief.processing.models.PayoutFeeInfo
import com.cryptochief.processing.models.PayoutHistoryResponse
import com.cryptochief.processing.models.PayoutInfo
import com.cryptochief.processing.models.PayoutServiceOperation
import com.cryptochief.processing.models.PayoutSource
import com.cryptochief.processing.models.SignTransactionResponse
import com.cryptochief.processing.models.StaticDeposit
import com.cryptochief.processing.models.StaticDepositHistoryResponse
import com.cryptochief.processing.models.StaticDepositResendResult
import com.cryptochief.processing.models.SupportedBlockchain
import com.cryptochief.processing.models.Sweep
import com.cryptochief.processing.models.TransactionInfo
import com.cryptochief.processing.models.TxStatusRow
import com.cryptochief.processing.models.Wallet
import com.cryptochief.processing.models.WalletBalanceRow
import com.cryptochief.processing.models.WalletCoinBalance
import com.cryptochief.processing.models.WebhookAttempt
import com.cryptochief.processing.models.WebhookDelivery
import com.cryptochief.processing.models.WebhookPayload
import com.cryptochief.processing.models.WebhookResendResult
import com.cryptochief.processing.models.Withdrawal
import com.cryptochief.processing.webhook.PayInWebhookEvent
import com.cryptochief.processing.webhook.PayoutWebhookEvent
import com.cryptochief.processing.webhook.StaticDepositWebhookEvent
import com.cryptochief.processing.webhook.SweepWebhookEvent
import com.cryptochief.processing.webhook.TransactionWebhookEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

/**
 * Everything the SDK decodes from the platform — API responses and webhook events — survives a
 * member the platform did not send. Request models keep their required arguments.
 */
class ResponseDecodeTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun client(): CryptoChiefClient = CryptoChiefClient(
        Options.builder().apply {
            merchantId = "3f2a1b4c-5d6e-7f80-9a1b-2c3d4e5f6071"
            apiKey = "test_api_key_123"
            baseUrl = server.url("/").toString().trimEnd('/')
            maxRetries = 0
            httpClient = http
        }.build(),
    )

    @Test
    fun `a 200 body without the wallet members decodes`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"data":{},"result":{}}"""))
        val wallet = client().use { it.wallets.info("0xdeposit") }

        assertEquals("", wallet.address)
        assertEquals("", wallet.chainFamily.code)
        assertEquals(emptyList<WalletCoinBalance>(), wallet.coins)
    }

    @TestFactory
    fun `an empty object decodes into every inbound model`(): List<DynamicTest> = inbound.map { (name, serializer) ->
        DynamicTest.dynamicTest(name) {
            SdkJson.instance.decodeFromString(serializer, "{}")
        }
    }

    private val inbound: List<Pair<String, KSerializer<*>>> = listOf(
        "AvailableContract" to serializer<AvailableContract>(),
        "AvailableContractsResponse" to serializer<AvailableContractsResponse>(),
        "BatchExecuteResponse" to serializer<BatchExecuteResponse>(),
        "BatchItemResult" to serializer<BatchItemResult>(),
        "CoinOption" to serializer<CoinOption>(),
        "ConvertResponse" to serializer<ConvertResponse>(),
        "CreditsBalance" to serializer<CreditsBalance>(),
        "CreditsTopup" to serializer<CreditsTopup>(),
        "CryptoCurrencies" to serializer<CryptoCurrencies>(),
        "EstimatePayoutResponse" to serializer<EstimatePayoutResponse>(),
        "FiatCurrency" to serializer<FiatCurrency>(),
        "ForceSweepResponse" to serializer<ForceSweepResponse>(),
        "ListWalletsResponse" to serializer<ListWalletsResponse>(),
        "PayIn" to serializer<PayIn>(),
        "PayInHistoryResponse" to serializer<PayInHistoryResponse>(),
        "PayInWebhookEvent" to serializer<PayInWebhookEvent>(),
        "PayoutCoinBalance" to serializer<PayoutCoinBalance>(),
        "PayoutFeeInfo" to serializer<PayoutFeeInfo>(),
        "PayoutHistoryResponse" to serializer<PayoutHistoryResponse>(),
        "PayoutInfo" to serializer<PayoutInfo>(),
        "PayoutServiceOperation" to serializer<PayoutServiceOperation>(),
        "PayoutSource" to serializer<PayoutSource>(),
        "PayoutWebhookEvent" to serializer<PayoutWebhookEvent>(),
        "SignTransactionResponse" to serializer<SignTransactionResponse>(),
        "StaticDeposit" to serializer<StaticDeposit>(),
        "StaticDepositHistoryResponse" to serializer<StaticDepositHistoryResponse>(),
        "StaticDepositResendResult" to serializer<StaticDepositResendResult>(),
        "StaticDepositWebhookEvent" to serializer<StaticDepositWebhookEvent>(),
        "SupportedBlockchain" to serializer<SupportedBlockchain>(),
        "Sweep" to serializer<Sweep>(),
        "SweepWebhookEvent" to serializer<SweepWebhookEvent>(),
        "TransactionInfo" to serializer<TransactionInfo>(),
        "TransactionWebhookEvent" to serializer<TransactionWebhookEvent>(),
        "TxStatusRow" to serializer<TxStatusRow>(),
        "Wallet" to serializer<Wallet>(),
        "WalletBalanceRow" to serializer<WalletBalanceRow>(),
        "WalletCoinBalance" to serializer<WalletCoinBalance>(),
        "WebhookAttempt" to serializer<WebhookAttempt>(),
        "WebhookDelivery" to serializer<WebhookDelivery>(),
        "WebhookPayload" to serializer<WebhookPayload>(),
        "WebhookResendResult" to serializer<WebhookResendResult>(),
        "Withdrawal" to serializer<Withdrawal>(),
    )

    /** A member sent as `null` reads as the same default. */
    @Test
    fun `null members read as the default`() {
        val wallet = SdkJson.instance.decodeFromString(
            serializer<Wallet>(),
            """{"address":null,"chain_family":null,"coins":null,"frozen":null}""",
        )
        assertEquals("", wallet.address)
        assertEquals("", wallet.chainFamily.code)
        assertEquals(emptyList<WalletCoinBalance>(), wallet.coins)
        assertEquals(false, wallet.frozen)
    }

    /** A body that is not an object is still a decode failure. */
    @Test
    fun `a body of the wrong shape still fails`() = runBlocking {
        server.enqueue(MockResponse().setBody("[1,2,3]"))
        val failure = assertThrows<DecodeException> {
            runBlocking { client().use { it.wallets.info("0xdeposit") } }
        }
        assertEquals(true, failure.message!!.contains("/v1/wallets/info"))
    }
}
