package com.cryptochief.processing.webhook

import com.cryptochief.processing.Chain
import com.cryptochief.processing.http.CanonicalJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
public data class PayoutWebhookEvent(
    @SerialName("event") val event: String,
    @SerialName("uuid") val uuid: String,
    @SerialName("order_id") val orderId: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("status") val status: String,
    @SerialName("amount_requested") val amountRequested: String? = null,
    @SerialName("amount_to_receive") val amountToReceive: String? = null,
    @SerialName("to_address") val toAddress: String? = null,
    @SerialName("fee_info") val feeInfo: JsonElement? = null,
    @SerialName("sources") val sources: JsonElement? = null,
    @SerialName("service_operations") val serviceOperations: JsonElement? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("error_reason") val errorReason: String? = null,
)

@Serializable
public data class TransactionWebhookEvent(
    @SerialName("event") val event: String,
    @SerialName("uuid") val uuid: String,
    @SerialName("status") val status: String,
    @SerialName("network") val network: Chain? = null,
    @SerialName("chain_family") val chainFamily: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("from_address") val fromAddress: String? = null,
    @SerialName("to_address") val toAddress: String? = null,
    @SerialName("value") val value: String? = null,
    @SerialName("contract") val contract: String? = null,
    @SerialName("tx_hash") val txHash: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("error_reason") val errorReason: String? = null,
)

@Serializable
public data class PayInWebhookEvent(
    @SerialName("event") val event: String,
    @SerialName("uuid") val uuid: String,
    @SerialName("order_id") val orderId: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("status") val status: String,
    @SerialName("prev_status") val prevStatus: String? = null,
    @SerialName("mode") val mode: String? = null,
    @SerialName("amount_crypto") val amountCrypto: String? = null,
    @SerialName("amount_fiat") val amountFiat: String? = null,
    @SerialName("fact_amount_crypto") val factAmountCrypto: String? = null,
    @SerialName("fact_amount_fiat") val factAmountFiat: String? = null,
    @SerialName("currency") val currency: String? = null,
    @SerialName("payment_coin") val paymentCoin: String? = null,
    @SerialName("payment_network") val paymentNetwork: Chain? = null,
    @SerialName("to_address") val toAddress: String? = null,
    @SerialName("txid") val txid: String? = null,
)

@Serializable
public data class StaticDepositWebhookEvent(
    @SerialName("event") val event: String,
    @SerialName("uuid") val uuid: String,
    @SerialName("status") val status: String,
    @SerialName("network") val network: Chain? = null,
    @SerialName("chain_family") val chainFamily: String? = null,
    @SerialName("coin") val coin: String? = null,
    @SerialName("contract") val contract: String? = null,
    @SerialName("decimals") val decimals: Int = 0,
    @SerialName("to_address") val toAddress: String? = null,
    @SerialName("from_address") val fromAddress: String? = null,
    @SerialName("tx_hash") val txHash: String? = null,
    @SerialName("amount") val amount: String? = null,
    @SerialName("amount_fiat") val amountFiat: String? = null,
    @SerialName("confirmations") val confirmations: Int = 0,
    @SerialName("required_confirmations") val requiredConfirmations: Int = 0,
    @SerialName("found_in_mempool") val foundInMempool: Boolean = false,
    @SerialName("log_type") val logType: String? = null,
    @SerialName("block_number") val blockNumber: Long? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("confirmed_at") val confirmedAt: String? = null,
    @SerialName("paid_at") val paidAt: String? = null,
)

/** Verify + decode in one call. */
public object WebhookHandler {
    public inline fun <reified T> handle(
        apiKey: String,
        body: ByteArray,
        signatureHeader: String?,
    ): T {
        WebhookVerifier.requireValid(apiKey, body, signatureHeader)
        return try {
            CanonicalJson.json.decodeFromString(
                kotlinx.serialization.serializer(),
                body.toString(Charsets.UTF_8),
            )
        } catch (e: Exception) {
            throw com.cryptochief.processing.DecodeException(
                "cryptochief: webhook decode failed: ${e.message}",
                e,
            )
        }
    }
}
