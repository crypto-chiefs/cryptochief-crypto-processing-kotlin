package com.cryptochief.processing.models

import com.cryptochief.processing.Chain
import com.cryptochief.processing.ChainFamily
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One asset on one network.
 *
 * The same shape on both catalogue endpoints: the project's enabled assets
 * ([com.cryptochief.processing.services.BlockchainService.contractsAvailable]) and the
 * platform-wide catalogue ([com.cryptochief.processing.services.BlockchainService.contractsList]).
 */
@Serializable
public data class AvailableContract(
    @SerialName("network") val network: Chain,
    @SerialName("coin") val coin: String,
    /**
     * The token contract, and an **empty string** on a native coin. The API answers `""`
     * rather than `null` there, so an empty contract is a native coin saying it has none,
     * not a missing value.
     */
    @SerialName("contract") val contract: String? = null,
    /** `native` or `token`. */
    @SerialName("type") val type: String? = null,
    @SerialName("decimals") val decimals: Int,
    /**
     * The protocol family this asset's network belongs to. Sent by both catalogue
     * endpoints; this SDK dropped it until 0.7.0.
     */
    @SerialName("chain_family") val chainFamily: ChainFamily? = null,
    /**
     * The asset lives on a test network. Sent by both catalogue endpoints; this SDK
     * dropped it until 0.7.0.
     */
    @SerialName("is_test") val isTest: Boolean = false,
)

@Serializable
public data class AvailableContractsResponse(
    @SerialName("items") val items: List<AvailableContract> = emptyList(),
)

/**
 * One chain the platform's blockchain scanner is connected to.
 *
 * [type] is the protocol family the scanner reads the chain with — `evm`, `tron`,
 * `solana` — and is lower-case, where the `chain_family` carried by everything else in
 * this SDK ([ChainFamily]) is upper-case. It stays a plain [String] rather than being
 * mapped onto [ChainFamily] so that difference is not quietly papered over.
 */
@Serializable
public data class SupportedBlockchain(
    /** The chain key, the same value used everywhere a [Chain] is asked for. */
    @SerialName("name") val name: Chain,
    @SerialName("type") val type: String = "",
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
