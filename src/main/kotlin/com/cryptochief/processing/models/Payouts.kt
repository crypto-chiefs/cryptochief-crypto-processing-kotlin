package com.cryptochief.processing.models

import com.cryptochief.processing.AssetsPolicy
import com.cryptochief.processing.Chain
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Payout status.
 *
 * - [QUEUE] - accepted, not started.
 * - [REFUELING] - the source wallets are being topped up with gas.
 * - [REFUEL_CONFIRMED] - the source wallets have gas; the payout transactions are sent next.
 * - [SENDING] - the payout transactions are being sent.
 * - [BROADCASTING] - EVM only: queued for broadcast.
 * - [IN_MEMPOOL] - BTC family only: broadcast, not yet mined.
 * - [CONFIRM_CHECK] - sent, waiting for [PayoutInfo.requiredConfirmations].
 * - [PAID] - every source reached [PayoutInfo.requiredConfirmations]. Terminal.
 * - [SYSTEM_FAIL] - failed. Terminal.
 *
 * [PROCESS], [FAILED], [EXPIRED] and [CANCEL] are not produced by the API.
 */
public object PayoutStatus {
    public const val QUEUE: String = "queue"
    public const val REFUELING: String = "refueling"
    public const val REFUEL_CONFIRMED: String = "refuel_confirmed"
    public const val SENDING: String = "sending"
    public const val BROADCASTING: String = "broadcasting"
    public const val IN_MEMPOOL: String = "in_mempool"
    public const val CONFIRM_CHECK: String = "confirm_check"
    public const val PAID: String = "paid"
    public const val SYSTEM_FAIL: String = "system_fail"

    public const val PROCESS: String = "process"
    public const val FAILED: String = "failed"
    public const val EXPIRED: String = "expired"
    public const val CANCEL: String = "cancel"

    public val TERMINAL: Set<String> = setOf(PAID, FAILED, SYSTEM_FAIL, EXPIRED, CANCEL)
}

@Serializable
public data class EstimatePayoutRequest(
    @SerialName("network") val network: Chain,
    @SerialName("coin") val coin: String,
    @SerialName("amount") val amount: String,
    @SerialName("to_address") val toAddress: String,
    @SerialName("from_addresses") val fromAddresses: List<String>? = null,
    @SerialName("allow_multiple_sources") val allowMultipleSources: Boolean = false,
    @SerialName("auto_convert") val autoConvert: Boolean = false,
    @SerialName("auto_convert_policy") val autoConvertPolicy: AssetsPolicy? = null,
    @SerialName("max_fee_amount_fiat") val maxFeeAmountFiat: String? = null,
    @SerialName("memo") val memo: String? = null,
)

@Serializable
public data class ExecutePayoutRequest(
    @SerialName("order_id") val orderId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("network") val network: Chain,
    @SerialName("coin") val coin: String,
    @SerialName("amount") val amount: String,
    @SerialName("to_address") val toAddress: String,
    @SerialName("url_callback") val urlCallback: String,
    @SerialName("from_addresses") val fromAddresses: List<String>? = null,
    @SerialName("allow_multiple_sources") val allowMultipleSources: Boolean = false,
    @SerialName("auto_convert") val autoConvert: Boolean = false,
    @SerialName("auto_convert_policy") val autoConvertPolicy: AssetsPolicy? = null,
    @SerialName("max_fee_amount_fiat") val maxFeeAmountFiat: String? = null,
    @SerialName("memo") val memo: String? = null,
)

@Serializable
public data class PayoutFeeInfo(
    /** Who pays the fee: `client`, `service` or `mix`. */
    @SerialName("fee_mode") val feeMode: String = "",
    /** Estimated fee in USD. */
    @SerialName("estimated_fiat") val estimatedFiat: String? = null,
    @Deprecated("Not sent by the API; always null.")
    @SerialName("estimated_coin") val estimatedCoin: String? = null,
    @Deprecated("Not sent by the API; always null.")
    @SerialName("estimated_asset") val estimatedAsset: String? = null,
    /** Fee cap in [limitCurrency]: `max_fee_amount_fiat` of the request. */
    @SerialName("limit_fiat") val limitFiat: String? = null,
    @SerialName("limit_currency") val limitCurrency: String? = null,
    /** Fee actually paid, in USD; `null` until the payout is sent. */
    @SerialName("total_fee_paid_fiat") val totalFeePaidFiat: String? = null,
)

/** One wallet the payout is sent from. */
@Serializable
public data class PayoutSource(
    @SerialName("address") val address: String,
    @Deprecated("Not sent by the API; always null. Use amountCrypto.", ReplaceWith("amountCrypto"))
    @SerialName("amount") val amount: String? = null,
    @SerialName("coin") val coin: String? = null,
    /** Confirmations of this source's transaction; `null` or 0 while not in a block. */
    @SerialName("confirmations") val confirmations: Int? = null,
    /** Amount sent from this wallet. */
    @SerialName("amount_crypto") val amountCrypto: String? = null,
    @SerialName("network") val network: Chain? = null,
    /** This source's transaction; `null` until it has been sent. */
    @SerialName("txid") val txid: String? = null,
    /** Whether the wallet is topped up with gas before sending. */
    @SerialName("need_refuel") val needRefuel: Boolean = false,
    @SerialName("refuel_amount") val refuelAmount: String? = null,
    @SerialName("estimated_fee") val estimatedFee: String? = null,
    @SerialName("estimated_fee_fiat") val estimatedFeeFiat: String? = null,
    /** Fee actually paid; `null` until the transaction is sent. */
    @SerialName("fee_paid") val feePaid: String? = null,
    @SerialName("fee_paid_fiat") val feePaidFiat: String? = null,
)

/** A transaction the platform sends to make a payout possible, e.g. a gas top-up (`type` = `gas_refuel`). */
@Serializable
public data class PayoutServiceOperation(
    @SerialName("type") val type: String? = null,
    @SerialName("context") val context: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("network") val network: Chain? = null,
    @SerialName("coin") val coin: String? = null,
    @SerialName("amount_native") val amountNative: String? = null,
    @SerialName("from_address") val fromAddress: String? = null,
    @SerialName("to_address") val toAddress: String? = null,
    @SerialName("estimated_fee") val estimatedFee: String? = null,
    @SerialName("estimated_fee_fiat") val estimatedFeeFiat: String? = null,
    @SerialName("fee_paid") val feePaid: String? = null,
    @SerialName("fee_paid_fiat") val feePaidFiat: String? = null,
    @SerialName("txid") val txid: String? = null,
    /** Confirmations of this transaction; `null` or 0 while not in a block. */
    @SerialName("confirmations") val confirmations: Int? = null,
)

/** Balance of one wallet that can fund a payout. */
@Serializable
public data class PayoutCoinBalance(
    @SerialName("network") val network: Chain? = null,
    @SerialName("coin") val coin: String? = null,
    @SerialName("address") val address: String? = null,
    @SerialName("amount_available") val amountAvailable: String? = null,
    @SerialName("native_available") val nativeAvailable: String? = null,
    @SerialName("native_coin") val nativeCoin: String? = null,
)

@Serializable
public data class EstimatePayoutResponse(
    @Deprecated("Not sent by the API; always null.")
    @SerialName("network") val network: Chain? = null,
    @Deprecated("Not sent by the API; always null.")
    @SerialName("coin") val coin: String? = null,
    @Deprecated("Not sent by the API; always null. Use amountRequested.", ReplaceWith("amountRequested"))
    @SerialName("amount") val amount: String? = null,
    @SerialName("amount_to_receive") val amountToReceive: String = "",
    @SerialName("to_address") val toAddress: String = "",
    @SerialName("fee_info") val feeInfo: PayoutFeeInfo? = null,
    @SerialName("sources") val sources: List<PayoutSource> = emptyList(),
    @Deprecated("Not sent by the API; always false.")
    @SerialName("auto_convert_applied") val autoConvertApplied: Boolean = false,
    @SerialName("amount_requested") val amountRequested: String? = null,
    /** Transactions the platform will send to make the payout possible, e.g. a gas top-up. */
    @SerialName("service_operations") val serviceOperations: List<PayoutServiceOperation> = emptyList(),
    @SerialName("coins") val coins: List<PayoutCoinBalance> = emptyList(),
)

@Serializable
public data class PayoutInfo(
    @SerialName("uuid") val uuid: String,
    @SerialName("order_id") val orderId: String = "",
    @SerialName("status") val status: String,
    @Deprecated("Not sent by the API; always null. Use sources[].network.")
    @SerialName("network") val network: Chain? = null,
    @Deprecated("Not sent by the API; always null. Use sources[].coin.")
    @SerialName("coin") val coin: String? = null,
    @Deprecated("Not sent by the API; always null. Use amountRequested.", ReplaceWith("amountRequested"))
    @SerialName("amount") val amount: String? = null,
    @SerialName("to_address") val toAddress: String = "",
    @Deprecated("Not sent by the API; always null. Use sources[].txid.")
    @SerialName("txid") val txid: String? = null,
    @SerialName("sources") val sources: List<PayoutSource> = emptyList(),
    @Deprecated("Not sent by the API; always null.")
    @SerialName("url_callback") val urlCallback: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @Deprecated("Not sent by the API; always null. Use completedAt.", ReplaceWith("completedAt"))
    @SerialName("updated_at") val updatedAt: String? = null,
    @Deprecated("Not sent by the API; always null.")
    @SerialName("error") val error: String? = null,
    /** Transactions the platform sent to make the payout possible, e.g. a gas top-up. */
    @SerialName("service_operations") val serviceOperations: List<PayoutServiceOperation> = emptyList(),
    /** Lowest confirmation count among [sources]; `null` until a source has a transaction. */
    @SerialName("confirmations") val confirmations: Int? = null,
    /**
     * Confirmations the network requires. The payout stays `confirm_check` until every source
     * reaches it, then becomes [PayoutStatus.PAID]. Optional.
     */
    @SerialName("required_confirmations") val requiredConfirmations: Int? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("amount_requested") val amountRequested: String? = null,
    @SerialName("amount_to_receive") val amountToReceive: String? = null,
    @SerialName("fee_info") val feeInfo: PayoutFeeInfo? = null,
    /** When the payout became [PayoutStatus.PAID]; `null` before that. */
    @SerialName("completed_at") val completedAt: String? = null,
) {
    public val isTerminal: Boolean get() = status in PayoutStatus.TERMINAL
    public val succeeded: Boolean get() = status == PayoutStatus.PAID
}

@Serializable
public data class PayoutHistoryResponse(
    @SerialName("items") val items: List<PayoutInfo> = emptyList(),
    @SerialName("meta") val meta: HistoryMeta = HistoryMeta(),
)

@Serializable
public data class BatchExecuteRequest(
    @SerialName("url_callback") val urlCallback: String? = null,
    @SerialName("items") val items: List<ExecutePayoutRequest>,
)

@Serializable
public data class BatchItemResult(
    @SerialName("index") val index: Int,
    @SerialName("order_id") val orderId: String,
    @SerialName("status") val status: String,
    @SerialName("uuid") val uuid: String? = null,
    @SerialName("error") val error: String? = null,
)

@Serializable
public data class BatchExecuteResponse(
    @SerialName("batch_uuid") val batchUuid: String? = null,
    @SerialName("total") val total: Int = 0,
    @SerialName("accepted") val accepted: Int = 0,
    @SerialName("rejected") val rejected: Int = 0,
    @SerialName("items") val items: List<BatchItemResult> = emptyList(),
)

@Serializable
internal data class UuidRequest(@SerialName("uuid") val uuid: String)
