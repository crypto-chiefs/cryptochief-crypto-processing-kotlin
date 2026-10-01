package com.cryptochief.processing.webhook

import com.cryptochief.processing.Chain
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
public data class PayoutWebhookEvent(
    @SerialName("event") val event: String = "",
    @SerialName("uuid") val uuid: String = "",
    @SerialName("order_id") val orderId: String = "",
    @SerialName("user_id") val userId: String? = null,
    @SerialName("status") val status: String = "",
    @SerialName("amount_requested") val amountRequested: String? = null,
    @SerialName("amount_to_receive") val amountToReceive: String? = null,
    @SerialName("to_address") val toAddress: String? = null,
    @SerialName("fee_info") val feeInfo: JsonElement? = null,
    @SerialName("sources") val sources: JsonElement? = null,
    @SerialName("service_operations") val serviceOperations: JsonElement? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("error_reason") val errorReason: String? = null,
    /**
     * Lowest confirmation count among the payout's sources; `null` until a source has a
     * transaction. Per-entry counts are under `confirmations` in [sources] and [serviceOperations].
     */
    @SerialName("confirmations") val confirmations: Int? = null,
    /** Confirmations the network requires; `payout.paid` is sent once every source reaches it. Optional. */
    @SerialName("required_confirmations") val requiredConfirmations: Int? = null,
)

/** `transaction.confirmed`, `transaction.failed`, `transaction.expired` or `transaction.cancelled`. */
@Serializable
public data class TransactionWebhookEvent(
    @SerialName("event") val event: String = "",
    @SerialName("uuid") val uuid: String = "",
    @SerialName("status") val status: String = "",
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
    /**
     * Confirmations of the transaction, always sent: at least [requiredConfirmations] on
     * `transaction.confirmed`, 0 on `transaction.expired`. Webhooks are sent only for final
     * statuses.
     */
    @SerialName("confirmations") val confirmations: Int = 0,
    /** Confirmations the network requires. Always sent. */
    @SerialName("required_confirmations") val requiredConfirmations: Int = 0,
)

@Serializable
public data class PayInWebhookEvent(
    @SerialName("event") val event: String = "",
    @SerialName("uuid") val uuid: String = "",
    @SerialName("order_id") val orderId: String = "",
    @SerialName("user_id") val userId: String? = null,
    @SerialName("status") val status: String = "",
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
    /**
     * The multi-payment fields below appear only on orders created with `is_payment_multiple`
     * (see `CreatePayInRequest.isPaymentMultiple`); on any other event they are absent and
     * decode to the defaults.
     */
    @SerialName("is_payment_multiple") val isPaymentMultiple: Boolean = false,
    /** Sum of every receipt so far, in the payment coin. */
    @SerialName("received_amount_crypto") val receivedAmountCrypto: String? = null,
    /** What is still missing; `null` once nothing is. */
    @SerialName("remaining_amount_crypto") val remainingAmountCrypto: String? = null,
    /** Every receipt accumulated by the order, oldest first. */
    @SerialName("payments") val payments: List<PayInWebhookPayment> = emptyList(),
) {
    public companion object {
        /**
         * A payment arrived but the invoiced amount is not yet collected — sent on EVERY
         * receipt, with [payments] grown by one. The order sits in
         * `PayInStatus.WRONG_AMOUNT_WAITING`; the remainder is payable until
         * `expired_at` + 1 hour.
         */
        public const val EVENT_WRONG_AMOUNT_WAITING: String = "invoice.wrong_amount_waiting"
        /**
         * A payment arrived after the order's final status, inside the observation window
         * (`expired_at` + 1 hour). The order status does NOT change; [prevStatus] holds the
         * final status the order had already settled in.
         */
        public const val EVENT_LATE_PAYMENT: String = "invoice.late_payment"
    }
}

/** One receipt of a multi-payment pay-in order, an entry of [PayInWebhookEvent.payments]. */
@Serializable
public data class PayInWebhookPayment(
    @SerialName("txid") val txid: String = "",
    @SerialName("amount_crypto") val amountCrypto: String = "",
    @SerialName("confirmations") val confirmations: Int = 0,
    /** `mempool`, `confirming`, `final` or `dropped`. */
    @SerialName("status") val status: String = "",
    /** When the platform first saw the transaction. */
    @SerialName("seen_at") val seenAt: String = "",
)

@Serializable
public data class StaticDepositWebhookEvent(
    @SerialName("event") val event: String = "",
    @SerialName("uuid") val uuid: String = "",
    @SerialName("status") val status: String = "",
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
 * Funds swept off a deposit wallet, confirmed on chain: [sweepConfirmations]
 * reached [requiredConfirmations]. Event name `sweep.confirmed` - the only sweep
 * event the platform emits.
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
 * @property sweepConfirmations confirmations of the sweep transaction, at least
 *   [requiredConfirmations]
 * @property requiredConfirmations network finality depth; optional
 * @property confirmedAt when the chain was observed to hold the sweep; not
 *   `Sweep.completedAt`, which is the send time
 * @property typeWork what triggered it: `momentum`, `threshold` or `force`
 * @property totalFeeUsd what the sweep cost: network fee plus any gas or energy
 *   the platform fronted to make it possible
 */
@Serializable
public data class SweepWebhookEvent(
    @SerialName("event") val event: String = "",
    @SerialName("task_id") val taskId: String = "",
    @SerialName("status") val status: String = "",
    @SerialName("wallet_address") val walletAddress: String = "",
    @SerialName("to_address") val toAddress: String? = null,
    @SerialName("network") val network: Chain? = null,
    @SerialName("chain_family") val chainFamily: String? = null,
    @SerialName("asset_symbol") val assetSymbol: String = "",
    @SerialName("asset_contract") val assetContract: String? = null,
    @SerialName("asset_type") val assetType: String? = null,
    @SerialName("amount_raw") val amountRaw: String? = null,
    @SerialName("amount_human") val amountHuman: String? = null,
    @SerialName("sweep_tx_hash") val sweepTxHash: String = "",
    @SerialName("gas_pump_tx_hash") val gasPumpTxHash: String? = null,
    @SerialName("sweep_confirmations") val sweepConfirmations: Int = 0,
    @SerialName("confirmed_at") val confirmedAt: String? = null,
    @SerialName("type_work") val typeWork: String? = null,
    @SerialName("total_fee_usd") val totalFeeUsd: String? = null,
    @SerialName("required_confirmations") val requiredConfirmations: Int? = null,
) {
    public companion object {
        /** The only sweep event the platform emits. */
        public const val EVENT_CONFIRMED: String = "sweep.confirmed"
    }
}
