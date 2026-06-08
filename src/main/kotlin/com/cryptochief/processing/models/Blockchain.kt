package com.cryptochief.processing.models

import com.cryptochief.processing.Chain
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class AvailableContract(
    @SerialName("network") val network: Chain,
    @SerialName("coin") val coin: String,
    @SerialName("contract") val contract: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("decimals") val decimals: Int,
)

@Serializable
public data class AvailableContractsResponse(
    @SerialName("items") val items: List<AvailableContract> = emptyList(),
)

@Serializable
public data class WalletBalanceRow(
    @SerialName("contract") val contract: String? = null,
    @SerialName("address") val address: String,
    @SerialName("value") val value: String,
    @SerialName("human_value") val humanValue: String,
    @SerialName("decimals") val decimals: Int,
)

@Serializable
public data class TxStatusRow(
    @SerialName("confirmations") val confirmations: Int = 0,
    @SerialName("fee") val fee: String? = null,
    @SerialName("human_fee") val humanFee: String? = null,
    @SerialName("block_number") val blockNumber: Long? = null,
    @SerialName("status") val status: String? = null,
)

@Serializable
internal data class NetworkRequest(@SerialName("network") val network: Chain)

@Serializable
internal data class WalletBalanceRequest(
    @SerialName("chain") val chain: Chain,
    @SerialName("addresses") val addresses: List<String>,
    @SerialName("contracts") val contracts: List<String>? = null,
)

@Serializable
internal data class TransactionStatusRequest(
    @SerialName("chain") val chain: Chain,
    @SerialName("hash") val hash: String,
)
