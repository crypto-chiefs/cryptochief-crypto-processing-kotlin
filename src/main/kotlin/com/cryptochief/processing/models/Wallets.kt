package com.cryptochief.processing.models

import com.cryptochief.processing.Chain
import com.cryptochief.processing.ChainFamily
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

public object WalletType {
    public const val MASTER: String = "master"
    public const val TRANSIT: String = "transit"
    public const val STATIC: String = "static"
}

@Serializable
public data class GenerateWalletRequest(
    @SerialName("wallet_type") val walletType: String,
    @SerialName("chain_family") val chainFamily: ChainFamily,
    @SerialName("master_wallet_address") val masterWalletAddress: String? = null,
    @SerialName("callback_url") val callbackUrl: String? = null,
)

@Serializable
public data class WalletCoinBalance(
    @SerialName("address") val address: String,
    @SerialName("chain") val chain: Chain,
    @SerialName("coin") val coin: String,
    @SerialName("contract") val contract: String? = null,
    @SerialName("decimals") val decimals: Int,
    @SerialName("value") val value: String,
    @SerialName("human_value") val humanValue: String,
    @SerialName("amount_usd") val amountUsd: String? = null,
    @SerialName("timestamp") val timestamp: Long? = null,
)

@Serializable
public data class Wallet(
    @SerialName("address") val address: String,
    @SerialName("chain_family") val chainFamily: ChainFamily,
    @SerialName("type") val type: String? = null,
    @SerialName("wallet_type") val walletType: String? = null,
    @SerialName("frozen") val frozen: Boolean = false,
    @SerialName("master_wallet_address") val masterWalletAddress: String? = null,
    @SerialName("callback_url") val callbackUrl: String? = null,
    @SerialName("private_key_encrypted") val privateKeyEncrypted: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("coins") val coins: List<WalletCoinBalance> = emptyList(),
    @SerialName("total_balance_usd") val totalBalanceUsd: String? = null,
)

@Serializable
public data class ListWalletsResponse(
    @SerialName("items") val items: List<Wallet> = emptyList(),
)

@Serializable
internal data class AddressRequest(@SerialName("address") val address: String)
