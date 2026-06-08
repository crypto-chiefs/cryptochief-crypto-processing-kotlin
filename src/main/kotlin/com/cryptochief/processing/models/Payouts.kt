package com.cryptochief.processing.models

import com.cryptochief.processing.AssetsPolicy
import com.cryptochief.processing.Chain
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

public object PayoutStatus {
    public const val QUEUE: String = "queue"
    public const val PROCESS: String = "process"
    public const val PAID: String = "paid"
    public const val FAILED: String = "failed"
    public const val SYSTEM_FAIL: String = "system_fail"
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
    @SerialName("fee_mode") val feeMode: String,
    @SerialName("estimated_fiat") val estimatedFiat: String,
    @SerialName("estimated_coin") val estimatedCoin: String,
    @SerialName("estimated_asset") val estimatedAsset: String? = null,
)

@Serializable
public data class PayoutSource(
    @SerialName("address") val address: String,
    @SerialName("amount") val amount: String,
    @SerialName("coin") val coin: String? = null,
)

@Serializable
public data class EstimatePayoutResponse(
    @SerialName("network") val network: Chain,
    @SerialName("coin") val coin: String,
    @SerialName("amount") val amount: String,
    @SerialName("amount_to_receive") val amountToReceive: String,
    @SerialName("to_address") val toAddress: String,
    @SerialName("fee_info") val feeInfo: PayoutFeeInfo? = null,
    @SerialName("sources") val sources: List<PayoutSource> = emptyList(),
    @SerialName("auto_convert_applied") val autoConvertApplied: Boolean = false,
)

@Serializable
public data class PayoutInfo(
    @SerialName("uuid") val uuid: String,
    @SerialName("order_id") val orderId: String = "",
    @SerialName("status") val status: String,
    @SerialName("network") val network: Chain,
    @SerialName("coin") val coin: String,
    @SerialName("amount") val amount: String,
    @SerialName("to_address") val toAddress: String,
    @SerialName("txid") val txid: String? = null,
    @SerialName("sources") val sources: List<PayoutSource> = emptyList(),
    @SerialName("url_callback") val urlCallback: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("error") val error: String? = null,
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
