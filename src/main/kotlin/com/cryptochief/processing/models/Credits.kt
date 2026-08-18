package com.cryptochief.processing.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Merchant credits balance. `10_000_000` credits = 1 USD. */
@Serializable
public data class CreditsBalance(
    @SerialName("credits_balance") val creditsBalance: Long,
    /** Pre-formatted USD with 2 decimals; can be negative, e.g. `"-1.52"`. */
    @SerialName("usd_balance") val usdBalance: String,
    @SerialName("is_postpaid") val isPostpaid: Boolean = false,
    /** Effective debt limit in credits (postpaid only, `0` for prepaid). */
    @SerialName("debt_limit_credits") val debtLimitCredits: Long = 0,
    /** Whether gas-paying operations (`/v1/transaction/execute` etc.) would pass the gate. */
    @SerialName("can_execute_gas_operations") val canExecuteGasOperations: Boolean = false,
    /** Minimum credits required for gas-paying operations. */
    @SerialName("gas_ops_min_credits") val gasOpsMinCredits: Long = 0,
    /** RFC 3339 timestamp. */
    @SerialName("timestamp") val timestamp: String? = null,
)

@Serializable
public data class CreditsTopupRequest(
    /** Positive decimal string, max `100000` (USD-pegged). */
    @SerialName("amount") val amount: String,
    /** `"USDT"` or `"USDC"`. */
    @SerialName("currency") val currency: String,
    /** Absolute http(s) URL — browser redirect after successful payment. */
    @SerialName("url_success") val urlSuccess: String? = null,
    /** Absolute http(s) URL — browser redirect after failed payment. */
    @SerialName("url_error") val urlError: String? = null,
)

@Serializable
public data class CreditsTopup(
    @SerialName("invoice_id") val invoiceId: Long,
    /** Hosted payment page URL (QR, network selection, live status). */
    @SerialName("payment_link") val paymentLink: String,
    @SerialName("amount") val amount: String,
    @SerialName("currency") val currency: String,
    /** `"pending"` on creation. */
    @SerialName("status") val status: String,
    @SerialName("order_uuid") val orderUuid: String? = null,
    /** Unix seconds. */
    @SerialName("expired_at") val expiredAt: Long? = null,
)
