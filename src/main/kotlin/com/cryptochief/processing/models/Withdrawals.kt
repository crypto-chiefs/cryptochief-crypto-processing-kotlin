package com.cryptochief.processing.models

import com.cryptochief.processing.Chain
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class Withdrawal(
    @SerialName("uuid") val uuid: String,
    @SerialName("status") val status: String,
    @SerialName("network") val network: Chain,
    @SerialName("coin") val coin: String? = null,
    @SerialName("contract") val contract: String? = null,
    @SerialName("amount") val amount: String,
    @SerialName("amount_fiat") val amountFiat: String? = null,
    @SerialName("from_address") val fromAddress: String? = null,
    @SerialName("to_address") val toAddress: String? = null,
    @SerialName("tx_hash") val txHash: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("confirmed_at") val confirmedAt: String? = null,
    @SerialName("error") val error: String? = null,
)

@Serializable
public data class WithdrawalHistoryResponse(
    @SerialName("items") val items: List<Withdrawal> = emptyList(),
    @SerialName("meta") val meta: HistoryMeta = HistoryMeta(),
)
