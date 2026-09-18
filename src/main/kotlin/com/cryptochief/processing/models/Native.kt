package com.cryptochief.processing.models

import com.cryptochief.processing.Chain
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Outcome of a native coin buy. */
public object NativeOrderStatus {
    /** The coins are sent to the receiving address; see [NativeOrder.txHash]. Terminal. */
    public const val DELIVERED: String = "delivered"

    /** The platform would not place the buy; see [NativeOrder.error]. Nothing was billed. Terminal. */
    public const val REFUSED: String = "refused"

    /** The outcome is not known yet; read it back with `NativeService.order`. Not terminal. */
    public const val UNRESOLVED: String = "unresolved"

    public val TERMINAL: Set<String> = setOf(DELIVERED, REFUSED)
}

@Serializable
public data class NativeQuoteRequest(
    /** Network the coin is bought on — [Chain.TRON_MAINNET], [Chain.ETH_MAINNET], ... */
    @SerialName("network") val network: Chain,
    /** Address the coins are sent to. Any address works; the merchant pays the transfer fee. */
    @SerialName("receive_address") val receiveAddress: String,
    /** Amount of the native coin to buy, in human units — e.g. "0.05". */
    @SerialName("amount") val amount: String,
)

/**
 * Price of buying [amount] of the network's native coin from the platform's liquidity. Free: no
 * paid call and nothing is billed. [totalUsd] is the full sale price — the coins at the market
 * rate plus the fee of the platform's own transfer to the receiving address; [credits] is the
 * exact amount the buy bills. The quote holds until [expiresAt] (about 90 seconds, single use);
 * pass [ref] to `NativeService.buy` as [NativeBuyRequest.quoteRef] to buy at this price.
 */
@Serializable
public data class NativeQuote(
    /** Reference to buy at this price; goes to [NativeBuyRequest.quoteRef]. */
    @SerialName("ref") val ref: String = "",
    @SerialName("network") val network: Chain = Chain(""),
    @SerialName("receive_address") val receiveAddress: String = "",
    /** Amount of the native coin being bought, in human units. */
    @SerialName("amount") val amount: String = "",
    /** USD cost of the coins at [coinUsd]. */
    @SerialName("coin_price_usd") val coinPriceUsd: String = "",
    /** Fee of the platform's own transfer to [receiveAddress], in the native coin. */
    @SerialName("transfer_fee") val transferFee: String = "",
    /** [transferFee] in USD at [coinUsd]. */
    @SerialName("transfer_fee_usd") val transferFeeUsd: String = "",
    /** [coinPriceUsd] + [transferFeeUsd] — coins at the rate plus the transfer fee. */
    @SerialName("subtotal_usd") val subtotalUsd: String = "",
    /** Full sale price in USD. */
    @SerialName("total_usd") val totalUsd: String = "",
    /** Credits the buy bills to the shared balance (10_000_000 credits = 1 USD). */
    @SerialName("credits") val credits: Long = 0,
    /** Coin/USD rate the USD and credits prices are computed at. */
    @SerialName("coin_usd") val coinUsd: String = "",
    /** RFC 3339 timestamp the quote stops being accepted at. */
    @SerialName("expires_at") val expiresAt: String = "",
    /** Seconds until [expiresAt]. */
    @SerialName("expires_in_sec") val expiresInSec: Long = 0,
)

@Serializable
public data class NativeBuyRequest(
    /** Network the coin is bought on — [Chain.TRON_MAINNET], [Chain.ETH_MAINNET], ... */
    @SerialName("network") val network: Chain? = null,
    /** Address the coins are sent to. Any address works; the merchant pays the transfer fee. */
    @SerialName("receive_address") val receiveAddress: String? = null,
    /** Amount of the native coin to buy, in human units — e.g. "0.05". */
    @SerialName("amount") val amount: String? = null,
    /** Reference of an unexpired [NativeQuote]; buys at the quoted price instead of the three fields above. */
    @SerialName("quote_ref") val quoteRef: String? = null,
)

@Serializable
public data class NativeOrderRequest(
    /** Idempotency key the buy was placed with. */
    @SerialName("key") val key: String,
)

/**
 * One native coin buy. [txHash], [transferFee], [transferFeeUsd], [coinPriceUsd], [totalUsd],
 * [credits] and [coinUsd] are absent when nothing was billed — a `refused` order — and present
 * once the buy was charged to the credits balance.
 */
@Serializable
public data class NativeOrder(
    @SerialName("id") val id: Long = 0,
    /** The `Idempotency-Key` the buy was placed with. */
    @SerialName("idempotency_key") val idempotencyKey: String = "",
    /** One of [NativeOrderStatus]. */
    @SerialName("status") val status: String = "",
    @SerialName("network") val network: Chain = Chain(""),
    @SerialName("receive_address") val receiveAddress: String = "",
    /** Amount of the native coin bought, in human units. */
    @SerialName("amount") val amount: String = "",
    /** Hash of the platform's transfer to [receiveAddress]; absent when nothing was billed. */
    @SerialName("tx_hash") val txHash: String? = null,
    /** Fee of the platform's transfer, in the native coin; absent when nothing was billed. */
    @SerialName("transfer_fee") val transferFee: String? = null,
    /** [transferFee] in USD; absent when nothing was billed. */
    @SerialName("transfer_fee_usd") val transferFeeUsd: String? = null,
    /** USD cost of the coins at [coinUsd]; absent when nothing was billed. */
    @SerialName("coin_price_usd") val coinPriceUsd: String? = null,
    /** Total price in USD; absent when nothing was billed. */
    @SerialName("total_usd") val totalUsd: String? = null,
    /** Credits billed to the shared balance; absent when nothing was billed. */
    @SerialName("credits") val credits: Long? = null,
    /** Coin/USD rate the prices were computed at; absent when nothing was billed. */
    @SerialName("coin_usd") val coinUsd: String? = null,
    @SerialName("settled") val settled: Boolean = false,
    /**
     * True when the order needs a human, not a retry: on the 409 reply of `buy` a repeat with
     * the same idempotency key reads the same order back.
     */
    @SerialName("needs_attention") val needsAttention: Boolean = false,
    /** Why a `refused` order was refused, human-readable; absent otherwise. */
    @SerialName("error") val error: String? = null,
    /** Machine code of the refusal — the field to branch on; absent otherwise. */
    @SerialName("error_code") val errorCode: String? = null,
    /** RFC 3339 timestamp. */
    @SerialName("created_at") val createdAt: String = "",
    /** RFC 3339 timestamp; set once the coins are sent. */
    @SerialName("delivered_at") val deliveredAt: String? = null,
) {
    public val isTerminal: Boolean get() = status in NativeOrderStatus.TERMINAL
    public val succeeded: Boolean get() = status == NativeOrderStatus.DELIVERED
}
