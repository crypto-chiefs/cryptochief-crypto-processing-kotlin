package com.cryptochief.processing.models

import com.cryptochief.processing.Chain
import com.cryptochief.processing.ChainFamily
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

public object StaticDepositStatus {
    public const val IN_MEMPOOL: String = "in_mempool"
    public const val CONFIRM_CHECK: String = "confirm_check"
    public const val PAID: String = "paid"
    public const val DROPPED: String = "dropped"
    public const val REORGED: String = "reorged"
}

@Serializable
public data class StaticDeposit(
    @SerialName("uuid") val uuid: String,
    @SerialName("status") val status: String,
    @SerialName("network") val network: Chain,
    @SerialName("chain_family") val chainFamily: ChainFamily? = null,
    @SerialName("coin") val coin: String,
    @SerialName("contract") val contract: String? = null,
    @SerialName("decimals") val decimals: Int = 0,
    @SerialName("to_address") val toAddress: String,
    @SerialName("from_address") val fromAddress: String? = null,
    @SerialName("tx_hash") val txHash: String? = null,
    @SerialName("block_number") val blockNumber: Long? = null,
    @SerialName("amount") val amount: String,
    @SerialName("amount_fiat") val amountFiat: String? = null,
    @SerialName("confirmations") val confirmations: Int = 0,
    @SerialName("required_confirmations") val requiredConfirmations: Int = 0,
    @SerialName("found_in_mempool") val foundInMempool: Boolean = false,
    @SerialName("log_type") val logType: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("confirmed_at") val confirmedAt: String? = null,
    @SerialName("paid_at") val paidAt: String? = null,
)

@Serializable
public data class StaticDepositHistoryQuery(
    @SerialName("address") val address: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("coin") val coin: String? = null,
    @SerialName("network") val network: Chain? = null,
    @SerialName("date_from") val dateFrom: String? = null,
    @SerialName("date_to") val dateTo: String? = null,
    @SerialName("page") val page: Int? = null,
    @SerialName("page_size") val pageSize: Int? = null,
)

@Serializable
public data class StaticDepositHistoryResponse(
    @SerialName("items") val items: List<StaticDeposit> = emptyList(),
    @SerialName("meta") val meta: HistoryMeta = HistoryMeta(),
)
