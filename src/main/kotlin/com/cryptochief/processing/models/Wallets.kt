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
    /** One of the [WalletType] constants. */
    @SerialName("wallet_type") val walletType: String,
    @SerialName("chain_family") val chainFamily: ChainFamily,
    /**
     * Which master a transit or static wallet settles to. Left unset the platform picks
     * the project's oldest master of the family — on an installation serving several
     * merchants that is somebody else's wallet. Not allowed on a master.
     */
    @SerialName("master_wallet_address") val masterWalletAddress: String? = null,
    /** Static wallets only; sending it for any other type is refused. */
    @SerialName("callback_url") val callbackUrl: String? = null,
    /**
     * A human-readable name for the wallet — what tells one address from another in a
     * list of a hundred. Applies to every wallet type, not just static ones.
     *
     * At most 255 characters, counted as characters rather than bytes so a label in a
     * non-Latin script measures the way its author would count it; longer is refused with
     * `LABEL_TOO_LONG`. Left null it stays off the wire — the endpoint reads an empty
     * string as no label at all, so there is nothing to gain by sending one.
     */
    @SerialName("label") val label: String? = null,
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
    /** One of the [WalletType] constants. */
    @SerialName("type") val type: String? = null,
    @SerialName("wallet_type") val walletType: String? = null,
    @SerialName("frozen") val frozen: Boolean = false,
    /**
     * Where this wallet's sweeps settle. `null` on a master — it is the destination — and
     * on anything the platform has not linked yet. The API answers `null` rather than an
     * empty string, so "no master" never has to be told apart from "the empty address".
     */
    @SerialName("master_wallet_address") val masterWalletAddress: String? = null,
    /**
     * Where deposits to this wallet are announced. Always `null` on a master or transit —
     * only a static wallet has a per-deposit callback — and `null` on a static wallet
     * whose callback was never set or has been cleared.
     */
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

@Serializable
internal data class RebindMasterRequest(
    @SerialName("address") val address: String,
    @SerialName("master_wallet_address") val masterWalletAddress: String,
)

/**
 * [callbackUrl] is a plain non-null [String] on purpose. An empty one is an instruction —
 * "stop announcing deposits for this address" — and the endpoint tells it apart from a
 * field that was never sent, which it refuses. Modelling it nullable would let the SDK
 * drop the difference on the floor, since this SDK omits nulls from the wire.
 */
@Serializable
internal data class SetCallbackUrlRequest(
    @SerialName("address") val address: String,
    @SerialName("callback_url") val callbackUrl: String,
)
