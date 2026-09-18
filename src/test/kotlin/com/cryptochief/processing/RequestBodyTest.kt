package com.cryptochief.processing

import com.cryptochief.processing.models.BatchExecuteRequest
import com.cryptochief.processing.models.ContractCall
import com.cryptochief.processing.models.ConvertRequest
import com.cryptochief.processing.models.CreatePayInRequest
import com.cryptochief.processing.models.CreditsTopupRequest
import com.cryptochief.processing.models.EnergyQuoteRequest
import com.cryptochief.processing.models.EnergyRentRequest
import com.cryptochief.processing.models.EstimatePayoutRequest
import com.cryptochief.processing.models.ExecutePayoutRequest
import com.cryptochief.processing.models.ExecuteTransactionRequest
import com.cryptochief.processing.models.GenerateWalletRequest
import com.cryptochief.processing.models.HistoryQuery
import com.cryptochief.processing.models.NativeBuyRequest
import com.cryptochief.processing.models.NativeQuoteRequest
import com.cryptochief.processing.models.SelectAssetRequest
import com.cryptochief.processing.models.SignTransactionRequest
import com.cryptochief.processing.models.StaticDepositHistoryQuery
import com.cryptochief.processing.models.SweepFieldWrite
import com.cryptochief.processing.models.SweepHistoryQuery
import com.cryptochief.processing.models.SweepSettingsQuery
import com.cryptochief.processing.models.SweepWalletHistoryQuery
import com.cryptochief.processing.models.WalletHistoryQuery
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Request bodies for calls with optional arguments left out, set to `null`, or set to values the
 * API treats specially. Expected bodies are the JSON values 0.9.0 sent: a `null` argument stays off
 * the wire. The server checks HMAC v1 over the bytes received.
 */
class RequestBodyTest {

    private val merchant = "3f2a1b4c-5d6e-7f80-9a1b-2c3d4e5f6071"
    private val apiKey = "test_api_key_123"
    private val evm = "0x1111111111111111111111111111111111111111"

    private lateinit var server: MockWebServer
    private lateinit var client: CryptoChiefClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = HmacV1Gateway(merchant, apiKey) { request ->
            val path = request.path.orEmpty()
            val body = when {
                path.startsWith("/v1/blockchain/wallet/balance") -> "[]"
                path.startsWith("/v1/transaction/") -> response("confirmed")
                else -> response("paid")
            }
            MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        }
        server.start()
        client = CryptoChiefClient(
            Options.builder().apply {
                merchantId = merchant
                apiKey = this@RequestBodyTest.apiKey
                baseUrl = server.url("/").toString().trimEnd('/')
                maxRetries = 0
                initialRetryDelay = Duration.ofMillis(1)
                maxRetryDelay = Duration.ofMillis(2)
            }.build(),
        )
    }

    @AfterEach
    fun tearDown() {
        client.close()
        server.shutdown()
    }

    private fun response(status: String): String =
        """{"uuid":"u1","order_id":"o1","status":"$status","network":"ETH","coin":"ETH","amount":"1",""" +
            """"to_address":"0xabc","from_address":"0xabc","address":"0xabc","chain_family":"EVM",""" +
            """"signed_tx_hex":"00","tx_hash":"h","expires_at":"e","credits_balance":1,"usd_balance":"1",""" +
            """"invoice_id":1,"payment_link":"l","currency":"USDT"}"""

    private class Case(val name: String, val path: String, val expected: String, val call: suspend () -> Any?)

    private fun executeRequired() = ExecutePayoutRequest("o1", "u1", Chain("ETH"), "USDT", "1", evm, "https://cb")

    private val executeRequiredBody =
        """{"amount":"1","coin":"USDT","network":"ETH","order_id":"o1","to_address":"$evm","url_callback":"https://cb","user_id":"u1"}"""

    private val cases: List<Case> = listOf(
        Case("blockchain.contractsAvailable(null)", "/v1/blockchain/contracts/available", "{}") {
            client.blockchain.contractsAvailable(null)
        },
        Case("blockchain.walletBalance, empty contracts", "/v1/blockchain/wallet/balance", """{"addresses":["$evm"],"chain":"ETH"}""") {
            client.blockchain.walletBalance(Chain("ETH"), listOf(evm), emptyList())
        },
        Case("credits.topup, null URLs", "/v1/credits/topup", """{"amount":"10","currency":"USDT"}""") {
            client.credits.topup(CreditsTopupRequest("10", "USDT", urlSuccess = null, urlError = null))
        },
        Case("energy.quote, every optional null", "/v1/energy/quote", """{"receive_address":"TFrom"}""") {
            client.energy.quote(EnergyQuoteRequest("TFrom", null, null))
        },
        Case("energy.rent, null energy, duration and quote_ref", "/v1/energy/rent", """{"receive_address":"TFrom"}""") {
            withIdempotencyKey("energy-req-body-test") {
                client.energy.rent(EnergyRentRequest("TFrom", null, null, null))
            }
        },
        Case("energy.order", "/v1/energy/order", """{"key":"k1"}""") {
            client.energy.order("k1")
        },
        Case("native.quote", "/v1/native/quote", """{"amount":"0.05","network":"ETH_MAINNET","receive_address":"$evm"}""") {
            client.native.quote(NativeQuoteRequest(Chain.ETH_MAINNET, evm, "0.05"))
        },
        Case("native.buy, quote_ref only", "/v1/native/buy", """{"quote_ref":"nq-1"}""") {
            withIdempotencyKey("native-req-body-test") {
                client.native.buy(NativeBuyRequest(null, null, null, "nq-1"))
            }
        },
        Case("native.order", "/v1/native/order", """{"key":"k1"}""") {
            client.native.order("k1")
        },
        Case("currencies.fiatToCrypto, null provider", "/v1/currencies/convert/fiat-crypto", """{"amount":"1","from":"USD","to":"BTC"}""") {
            client.currencies.fiatToCrypto(ConvertRequest(null, "USD", "BTC", "1"))
        },
        Case("currencies.cryptoToFiat, null provider", "/v1/currencies/convert/crypto-fiat", """{"amount":"0.001","from":"BTC","to":"USD"}""") {
            client.currencies.cryptoToFiat(ConvertRequest(null, "BTC", "USD", "0.001"))
        },
        Case("payIns.create, required only", "/v1/payments/order/create", """{"mode":"crypto","order_id":"o1","user_id":"u1"}""") {
            client.payIns.create(CreatePayInRequest("o1", "u1", "crypto"))
        },
        Case("payIns.create, every optional null", "/v1/payments/order/create", """{"mode":"crypto","order_id":"o1","user_id":"u1"}""") {
            client.payIns.create(
                CreatePayInRequest(
                    orderId = "o1", userId = "u1", mode = "crypto", toAddress = null, masterWalletAddress = null,
                    environment = null, lifetimeSec = null, urlCallback = null, urlSuccess = null, urlError = null,
                    additionalData = null, accuracyPaymentPercent = null, amountFiat = null, currency = null,
                    courseSource = null, assets = null, amountCrypto = null, asset = null,
                ),
            )
        },
        Case(
            "payIns.create, assets with null members",
            "/v1/payments/order/create",
            """{"asset":{},"assets":{"allow":[{"coin":"USDT"}]},"mode":"fiat","order_id":"o1","user_id":"u1"}""",
        ) {
            client.payIns.create(
                CreatePayInRequest(
                    "o1", "u1", "fiat",
                    assets = AssetsPolicy(allow = listOf(Asset(network = null, coin = "USDT"))),
                    asset = Asset(null, null),
                ),
            )
        },
        Case("payIns.selectAsset, null master", "/v1/payments/asset/select", """{"coin":"USDT","network":"TRX","uuid":"u1"}""") {
            client.payIns.selectAsset(SelectAssetRequest("u1", "USDT", Chain("TRX"), null))
        },
        Case("payIns.history, every filter null", "/v1/payments/history", "{}") {
            client.payIns.history(HistoryQuery(null, null, null, null, null, null, null))
        },
        Case("payouts.estimate, every optional null", "/v1/payout/estimate", """{"amount":"1","coin":"USDT","network":"ETH","to_address":"$evm"}""") {
            client.payouts.estimate(
                EstimatePayoutRequest(
                    Chain("ETH"), "USDT", "1", evm, fromAddresses = null, allowMultipleSources = false,
                    autoConvert = false, autoConvertPolicy = null, maxFeeAmountFiat = null, memo = null,
                ),
            )
        },
        Case("payouts.execute, every optional null", "/v1/payout/execute", executeRequiredBody) {
            client.payouts.execute(
                ExecutePayoutRequest(
                    "o1", "u1", Chain("ETH"), "USDT", "1", evm, "https://cb", fromAddresses = null,
                    allowMultipleSources = false, autoConvert = false, autoConvertPolicy = null,
                    maxFeeAmountFiat = null, memo = null,
                ),
            )
        },
        Case("payouts.batchEstimate, null url_callback", "/v1/payout/batch/estimate", """{"items":[$executeRequiredBody]}""") {
            client.payouts.batchEstimate(BatchExecuteRequest(null, listOf(executeRequired())))
        },
        Case("payouts.batchExecute, null url_callback", "/v1/payout/batch/execute", """{"items":[$executeRequiredBody]}""") {
            client.payouts.batchExecute(BatchExecuteRequest(null, listOf(executeRequired())))
        },
        Case("payouts.history, every filter null", "/v1/payout/history", "{}") {
            client.payouts.history(HistoryQuery(null, null, null, null, null, null, null))
        },
        Case("staticDeposits.history, every filter null", "/v1/static-deposit/history", "{}") {
            client.staticDeposits.history(StaticDepositHistoryQuery(null, null, null, null, null, null, null, null))
        },
        Case("sweeps.history, every filter null", "/v1/sweeps/history", "{}") {
            client.sweeps.history(SweepHistoryQuery(null, null, null, null, null))
        },
        Case("sweeps.walletHistory, every filter null", "/v1/sweeps/wallet/history", """{"address":"$evm"}""") {
            client.sweeps.walletHistory(SweepWalletHistoryQuery(evm, null, null, null, null, null))
        },
        Case("sweeps.settings, null address and network", "/v1/sweeps/settings", "{}") {
            client.sweeps.settings(SweepSettingsQuery(null, null))
        },
        Case("sweeps.settings, project default", "/v1/sweeps/settings", """{"address":""}""") {
            client.sweeps.settings(SweepSettingsQuery(""))
        },
        Case("sweeps.updateSettings, every field null", "/v1/sweeps/settings/update", """{"address":"$evm"}""") {
            client.sweeps.updateSettings(evm, null, null, null, null, null)
        },
        Case(
            "sweeps.updateSettings, Inherit on every field",
            "/v1/sweeps/settings/update",
            """{"address":"$evm","fields":["type_work","threshold_amount_usd","fee_mode","gas_source"]}""",
        ) {
            client.sweeps.updateSettings(
                evm,
                typeWork = SweepFieldWrite.Inherit,
                thresholdAmountUsd = SweepFieldWrite.Inherit,
                feeMode = SweepFieldWrite.Inherit,
                gasSource = SweepFieldWrite.Inherit,
            )
        },
        Case(
            "sweeps.updateSettings, Set and Inherit",
            "/v1/sweeps/settings/update",
            """{"address":"$evm","fields":["type_work","threshold_amount_usd"],"type_work":"momentum"}""",
        ) {
            client.sweeps.updateSettings(evm, typeWork = SweepFieldWrite.Set("momentum"), thresholdAmountUsd = SweepFieldWrite.Inherit)
        },
        Case(
            "sweeps.updateSettings, Inherit fee_mode on one network",
            "/v1/sweeps/settings/update",
            """{"address":"$evm","fields":["fee_mode"],"network_code":"ETH"}""",
        ) {
            client.sweeps.updateSettings(evm, feeMode = SweepFieldWrite.Inherit, networkCode = Chain("ETH"))
        },
        Case(
            "transactions.sign, call with null members",
            "/v1/transaction/signature",
            """{"calls":[{"data":"0x","to":"t"}],"from_address":"$evm","network":"ETH","type":"token"}""",
        ) {
            client.transactions.sign(
                SignTransactionRequest(
                    Chain("ETH"), evm, "token", toAddress = null, value = null, contract = null,
                    calls = listOf(ContractCall("t", null, "0x", null, null)), urlCallback = null,
                ),
            )
        },
        Case("transactions.execute, null signed_tx_hex", "/v1/transaction/execute", """{"uuid":"u1"}""") {
            client.transactions.execute(ExecuteTransactionRequest("u1", null))
        },
        Case(
            "transactions.signTonCall, null bounce and url_callback",
            "/v1/transaction/signature",
            """{"calls":[{"data":"AQ==","to":"EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs","value":"0"}],""" +
                """"from_address":"EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs","network":"TON","type":"contract"}""",
        ) {
            val ton = "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs"
            client.transactions.signTonCall(Chain("TON"), ton, ton, byteArrayOf(1), "0", null, null)
        },
        Case(
            "transactions.signEvmCall, null url_callback",
            "/v1/transaction/signature",
            """{"calls":[{"data":"0x8456cb59","to":"$evm","value":"0"}],"from_address":"$evm","network":"ETH","type":"contract"}""",
        ) {
            client.transactions.signEvmCall(Chain("ETH"), evm, evm, "pause()", urlCallback = null)
        },
        Case("transactions.history, every filter null", "/v1/transaction/history", "{}") {
            client.transactions.history(HistoryQuery(null, null, null, null, null, null, null))
        },
        Case("wallets.generate, every optional null", "/v1/wallets/generate", """{"chain_family":"EVM","wallet_type":"transit"}""") {
            client.wallets.generate(GenerateWalletRequest("transit", ChainFamily("EVM"), null, null, null))
        },
        Case(
            "wallets.generate, empty strings",
            "/v1/wallets/generate",
            """{"callback_url":"","chain_family":"EVM","label":"","master_wallet_address":"","wallet_type":"static"}""",
        ) {
            client.wallets.generate(GenerateWalletRequest("static", ChainFamily("EVM"), "", "", ""))
        },
        Case("wallets.history, every filter null", "/v1/wallets/history", """{"address":"$evm"}""") {
            client.wallets.history(WalletHistoryQuery(evm, null, null, null, null))
        },
        Case("wallets.clearCallbackUrl", "/v1/wallets/callback-url", """{"address":"$evm","callback_url":""}""") {
            client.wallets.clearCallbackUrl(evm)
        },
        Case("wallets.clearLabel", "/v1/wallets/label", """{"address":"$evm","label":""}""") {
            client.wallets.clearLabel(evm)
        },
        Case("withdrawals.history, every filter null", "/v1/withdrawal/history", "{}") {
            client.withdrawals.history(HistoryQuery(null, null, null, null, null, null, null))
        },
    )

    @TestFactory
    fun `request bodies match 0_9_0 and carry no null members`(): List<DynamicTest> = cases.map { case ->
        DynamicTest.dynamicTest(case.name) {
            runBlocking { case.call() }
            val recorded = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals(case.path, recorded.path)
            val body = HmacV1Gateway.assertSigned(recorded, apiKey, case.path).toString(Charsets.UTF_8)
            HmacV1Gateway.assertSameJson(case.expected, body, case.name)
            assertFalse(hasNullMember(Json.parseToJsonElement(body)), "body has a null member: $body")
            assertEquals(null, server.takeRequest(0, TimeUnit.MILLISECONDS), "unexpected extra request")
        }
    }

    @Serializable
    private data class Numbers(
        @SerialName("order_id") val orderId: String,
        @SerialName("max") val max: Long,
        @SerialName("min") val min: Long,
        @SerialName("above_2_53") val above253: Long,
        @SerialName("fraction") val fraction: Double,
        @SerialName("absent") val absent: Long? = null,
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `integers are sent exactly and the body is signed as sent`() = runBlocking {
        val transport = client.transport
        val model = Numbers("o1", Long.MAX_VALUE, Long.MIN_VALUE, 9007199254740993L, 0.1)
        transport.send("/v1/test", serializer<Numbers>(), serializer<JsonObject>(), model)
        val modelBody = HmacV1Gateway.assertSigned(server.takeRequest(1, TimeUnit.SECONDS)!!, apiKey, "/v1/test")
        assertEquals(
            """{"order_id":"o1","max":9223372036854775807,"min":-9223372036854775808,"above_2_53":9007199254740993,"fraction":0.1}""",
            modelBody.toString(Charsets.UTF_8),
        )

        val element = buildJsonObject {
            put("z", JsonUnquotedLiteral("123456789012345678901234567890"))
            put("a", JsonPrimitive(9007199254740993L))
            put("nested", buildJsonArray { add(JsonUnquotedLiteral("-18446744073709551617")) })
            put("keep_null", JsonNull)
        }
        transport.send("/v1/test", serializer<JsonObject>(), serializer<JsonObject>(), element)
        val elementBody = HmacV1Gateway.assertSigned(server.takeRequest(1, TimeUnit.SECONDS)!!, apiKey, "/v1/test")
        assertEquals(
            """{"z":123456789012345678901234567890,"a":9007199254740993,"nested":[-18446744073709551617],"keep_null":null}""",
            elementBody.toString(Charsets.UTF_8),
        )
    }

    private fun hasNullMember(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.values.any { it is JsonNull || hasNullMember(it) }
        is JsonArray -> element.any { hasNullMember(it) }
        else -> false
    }
}
