package com.cryptochief.processing.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Outcome of a TRON energy rent. */
public object EnergyOrderStatus {
    /** The energy is delegated to the receiving address. Terminal. */
    public const val DELIVERED: String = "delivered"

    /** The platform would not place the rent; see [EnergyOrder.error]. Nothing was billed. Terminal. */
    public const val REFUSED: String = "refused"

    /** The outcome is not known yet; read it back with `EnergyService.order`. Not terminal. */
    public const val UNRESOLVED: String = "unresolved"

    public val TERMINAL: Set<String> = setOf(DELIVERED, REFUSED)
}

@Serializable
public data class EnergyQuoteRequest(
    /** Sender of the planned transfer — the address the rented energy is delegated to. */
    @SerialName("receive_address") val receiveAddress: String,
    /** Energy units to rent; omitted, the platform sizes it to one transfer for the address. */
    @SerialName("energy") val energy: Long? = null,
    /** Rent duration in seconds; omitted, the platform default applies. */
    @SerialName("duration_sec") val durationSec: Long? = null,
)

/**
 * Price of one energy rent against burning TRX for the same transfer. Free: no paid call and
 * nothing is billed. The quote holds until [expiresAt]; pass [ref] to `EnergyService.rent` as
 * [EnergyRentRequest.quoteRef] to rent at this price.
 */
@Serializable
public data class EnergyQuote(
    /** Reference to rent at this price; goes to [EnergyRentRequest.quoteRef]. */
    @SerialName("ref") val ref: String = "",
    @SerialName("receive_address") val receiveAddress: String = "",
    @SerialName("energy") val energy: Long = 0,
    @SerialName("duration_sec") val durationSec: Long = 0,
    /** Rent price in sun (1 TRX = 1_000_000 sun). */
    @SerialName("price_sun") val priceSun: Long = 0,
    /** [priceSun] in TRX, human-readable. */
    @SerialName("price_trx") val priceTrx: String = "",
    /** Rent price in USD. */
    @SerialName("price_usd") val priceUsd: String = "",
    /** Credits the rent bills to the shared balance (10_000_000 credits = 1 USD). */
    @SerialName("credits") val credits: Long = 0,
    /** TRX/USD rate the USD and credits prices are computed at. */
    @SerialName("trx_usd") val trxUsd: String = "",
    /** State of the receiving address — e.g. whether it is already activated on chain. */
    @SerialName("recipient_state") val recipientState: String = "",
    /** What burning TRX for the same energy would cost, in sun. */
    @SerialName("burn_price_sun") val burnPriceSun: Long = 0,
    /** [burnPriceSun] in TRX, human-readable. */
    @SerialName("burn_price_trx") val burnPriceTrx: String = "",
    /** [burnPriceSun] in USD. */
    @SerialName("burn_price_usd") val burnPriceUsd: String = "",
    /** [burnPriceSun] in credits. */
    @SerialName("burn_price_credits") val burnPriceCredits: Long = 0,
    /** What renting saves against burning, in TRX. */
    @SerialName("saving_trx") val savingTrx: String = "",
    /** [savingTrx] in USD. */
    @SerialName("saving_usd") val savingUsd: String = "",
    /** [savingTrx] in credits. */
    @SerialName("saving_credits") val savingCredits: Long = 0,
    /** RFC 3339 timestamp the quote stops being accepted at. */
    @SerialName("expires_at") val expiresAt: String = "",
    /** Seconds until [expiresAt]. */
    @SerialName("expires_in_sec") val expiresInSec: Long = 0,
)

@Serializable
public data class EnergyRentRequest(
    /**
     * Sender of the planned transfer — the address the rented energy is delegated to. Omitted
     * when [quoteRef] rents at a quote: the quote already binds the address.
     */
    @SerialName("receive_address") val receiveAddress: String? = null,
    /** Energy units to rent; omitted, the platform sizes it to one transfer for the address. */
    @SerialName("energy") val energy: Long? = null,
    /** Rent duration in seconds; omitted, the platform default applies. */
    @SerialName("duration_sec") val durationSec: Long? = null,
    /** Reference of an unexpired [EnergyQuote]; rents at the quoted price. */
    @SerialName("quote_ref") val quoteRef: String? = null,
)

@Serializable
public data class EnergyOrderRequest(
    /** Idempotency key the rent was placed with. */
    @SerialName("key") val key: String,
)

/**
 * One energy rent. [priceUsd], [credits] and [trxUsd] are absent when nothing was billed — a
 * `refused` order — and present once the rent was charged to the credits balance.
 */
@Serializable
public data class EnergyOrder(
    @SerialName("id") val id: Long = 0,
    /** The `Idempotency-Key` the rent was placed with. */
    @SerialName("idempotency_key") val idempotencyKey: String = "",
    /** One of [EnergyOrderStatus]. */
    @SerialName("status") val status: String = "",
    @SerialName("receive_address") val receiveAddress: String = "",
    @SerialName("energy") val energy: Long = 0,
    @SerialName("duration_sec") val durationSec: Long = 0,
    /** Rent price in sun (1 TRX = 1_000_000 sun). */
    @SerialName("price_sun") val priceSun: Long = 0,
    /** [priceSun] in TRX, human-readable. */
    @SerialName("price_trx") val priceTrx: String = "",
    /** Rent price in USD; absent when nothing was billed. */
    @SerialName("price_usd") val priceUsd: String? = null,
    /** Credits billed to the shared balance; absent when nothing was billed. */
    @SerialName("credits") val credits: Long? = null,
    /** TRX/USD rate the prices were computed at; absent when nothing was billed. */
    @SerialName("trx_usd") val trxUsd: String? = null,
    /** Energy actually delegated to the receiving address. */
    @SerialName("delivered_energy") val deliveredEnergy: Long = 0,
    @SerialName("settled") val settled: Boolean = false,
    /**
     * True when the order needs a human, not a retry: on the 409 reply of `rent` a repeat with
     * the same idempotency key reads the same order back.
     */
    @SerialName("needs_attention") val needsAttention: Boolean = false,
    /** Why a `refused` order was refused, human-readable; absent otherwise. */
    @SerialName("error") val error: String? = null,
    /** Machine code of the refusal — the field to branch on; absent otherwise. */
    @SerialName("error_code") val errorCode: String? = null,
    /** RFC 3339 timestamp. */
    @SerialName("created_at") val createdAt: String = "",
    /** RFC 3339 timestamp; set once the energy is delegated. */
    @SerialName("delivered_at") val deliveredAt: String? = null,
) {
    public val isTerminal: Boolean get() = status in EnergyOrderStatus.TERMINAL
    public val succeeded: Boolean get() = status == EnergyOrderStatus.DELIVERED
}
