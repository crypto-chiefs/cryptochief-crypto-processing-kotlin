package com.cryptochief.processing.models

import com.cryptochief.processing.Chain
import com.cryptochief.processing.ChainFamily
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

public object SweepMode {
    public const val AUTO: String = "auto"
    public const val FORCE: String = "force"
}

@Serializable
public data class SweepHistoryQuery(
    @SerialName("mode") val mode: String? = null,
    @SerialName("page") val page: Int? = null,
    @SerialName("page_size") val pageSize: Int? = null,
)

@Serializable
public data class SweepWalletHistoryQuery(
    @SerialName("address") val address: String,
    @SerialName("mode") val mode: String? = null,
    @SerialName("page") val page: Int? = null,
    @SerialName("page_size") val pageSize: Int? = null,
)

@Serializable
public data class Sweep(
    @SerialName("task_id") val taskId: String,
    @SerialName("sweep_tx_hash") val sweepTxHash: String? = null,
    @SerialName("status") val status: String,
    @SerialName("wallet_address") val walletAddress: String,
    @SerialName("chain") val chain: Chain,
    @SerialName("chain_family") val chainFamily: ChainFamily? = null,
    @SerialName("asset_symbol") val assetSymbol: String? = null,
    @SerialName("asset_type") val assetType: String? = null,
    @SerialName("amount_human") val amountHuman: String? = null,
    @SerialName("gas_fee_human") val gasFeeHuman: String? = null,
    @SerialName("gas_fee_fiat") val gasFeeFiat: String? = null,
    @SerialName("service_fee_fiat") val serviceFeeFiat: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
public data class SweepHistoryResponse(
    @SerialName("items") val items: List<Sweep> = emptyList(),
    @SerialName("meta") val meta: HistoryMeta = HistoryMeta(),
)

@Serializable
public data class ForceSweepResponse(
    @SerialName("status") val status: String,
)

@Serializable
internal data class ForceSweepRequest(
    @SerialName("address") val address: String,
    @SerialName("network_code") val networkCode: Chain,
)
