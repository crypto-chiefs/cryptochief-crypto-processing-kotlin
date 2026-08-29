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

/**
 * Funds swept off a deposit wallet, confirmed on chain. Event name
 * `sweep.confirmed` - the only sweep event the platform emits.
 *
 * There is deliberately no `sweep.broadcasted`: "we sent it" is not something
 * you can act on, and an event that means "maybe" is one more thing to
 * reconcile.
 *
 * A `static_deposit.paid` tells you a customer paid you. This tells you the
 * money has finished moving into your own custody - until it fires, the balance
 * still sits on the deposit address. Reconciliation, treasury reporting and
 * "funds available to pay out" all key off this event, not off the deposit.
 *
 * Sweeps run on static deposit wallets AND on the transit wallets issued per
 * pay-in order; both deliver here, to the callback URL configured for the wallet
 * the funds left.
 *
 * @property taskId the sweeper task; one sweep settles once, so use it as your
 *   idempotency key
 * @property status always `completed` - a sweep reaches you in no other state
 * @property walletAddress the wallet the funds left, i.e. the address your
 *   customer paid into
 * @property toAddress the master wallet they landed on
 * @property assetType `native` or `token`
 * @property gasPumpTxHash set when the platform had to fund gas on the wallet
 *   before it could sweep
 * @property sweepConfirmations what makes this event true rather than hopeful,
 *   and never zero; it travels with the event rather than being implied by it,
 *   because "confirmed" is not the same number on every chain and your own
 *   finality policy needs the count to apply it
 * @property confirmedAt when the chain was observed to hold the sweep; NOT the
 *   task's completion timestamp, which is stamped on every terminal outcome
 *   including failures and so says nothing about settlement
 * @property typeWork what triggered it: `momentum`, `threshold` or `force`
 * @property totalFeeUsd what the sweep cost: network fee plus any gas or energy
 *   the platform fronted to make it possible
 */
@Serializable
public data class SweepWebhookEvent(
    @SerialName("event") val event: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("status") val status: String,
    @SerialName("wallet_address") val walletAddress: String,
    @SerialName("to_address") val toAddress: String? = null,
    @SerialName("network") val network: Chain? = null,
    @SerialName("chain_family") val chainFamily: String? = null,
    @SerialName("asset_symbol") val assetSymbol: String,
    @SerialName("asset_contract") val assetContract: String? = null,
    @SerialName("asset_type") val assetType: String? = null,
    @SerialName("amount_raw") val amountRaw: String? = null,
    @SerialName("amount_human") val amountHuman: String? = null,
    @SerialName("sweep_tx_hash") val sweepTxHash: String,
    @SerialName("gas_pump_tx_hash") val gasPumpTxHash: String? = null,
    @SerialName("sweep_confirmations") val sweepConfirmations: Int = 0,
    @SerialName("confirmed_at") val confirmedAt: String? = null,
    @SerialName("type_work") val typeWork: String? = null,
    @SerialName("total_fee_usd") val totalFeeUsd: String? = null,
) {
    public companion object {
        /** The only sweep event the platform emits. */
        public const val EVENT_CONFIRMED: String = "sweep.confirmed"
    }
}

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
