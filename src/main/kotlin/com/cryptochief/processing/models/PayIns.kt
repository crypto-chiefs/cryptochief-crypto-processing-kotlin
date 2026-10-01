package com.cryptochief.processing.models

import com.cryptochief.processing.Asset
import com.cryptochief.processing.AssetsPolicy
import com.cryptochief.processing.Chain
import com.cryptochief.processing.ChainFamily
import com.cryptochief.processing.webhook.PayInWebhookPayment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

public object PayInMode {
    public const val FIAT: String = "fiat"
    public const val CRYPTO: String = "crypto"
}

public object PayInStatus {
    public const val WAITING_ASSET_SELECT: String = "waiting_asset_select"
    public const val PENDING: String = "pending"
    public const val PROCESSING: String = "processing"
    public const val PROCESS: String = "process"
    /**
     * An underpayment on a multi-payment order (see [CreatePayInRequest.isPaymentMultiple]):
     * the remainder stays payable until `expired_at` + 1 hour. NOT terminal - the order can
     * still reach [PAID], so it is not in [TERMINAL].
     */
    public const val WRONG_AMOUNT_WAITING: String = "wrong_amount_waiting"
    public const val PAID: String = "paid"
    /** Final, paid short of the invoiced amount - within [CreatePayInRequest.accuracyPaymentPercent]. */
    public const val PAID_LESS: String = "paid_less"
    /** Final, paid beyond the invoiced amount - within [CreatePayInRequest.accuracyPaymentPercent]. */
    public const val PAID_OVER: String = "paid_over"
    public const val CANCEL: String = "cancel"
    public const val EXPIRED: String = "expired"

    public val TERMINAL: Set<String> = setOf(PAID, PAID_LESS, PAID_OVER, CANCEL, EXPIRED)
}

/**
 * The two environments an order can belong to.
 *
 * A project may be allowed one or both; asking for testnet on a project that does not
 * permit it is refused with `TESTNET_NOT_ALLOWED` rather than quietly served on mainnet,
 * and a value that is neither is `ENVIRONMENT_INVALID` rather than a silent fallback.
 */
public object Environment {
    public const val MAINNET: String = "mainnet"
    public const val TESTNET: String = "testnet"
}

@Serializable
public data class CreatePayInRequest(
    @SerialName("order_id") val orderId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("mode") val mode: String,
    @SerialName("to_address") val toAddress: String? = null,
    /**
     * Pin the transit deposit wallet of THIS order to the given master wallet of the
     * project - the address the funds are swept to. The order's asset/network chain
     * family must match the master wallet's; a foreign or mismatched address is rejected
     * with 400. Omit for the project-default behaviour.
     */
    @SerialName("master_wallet_address") val masterWalletAddress: String? = null,
    /**
     * Constrain the asset the platform PICKS for this order to the real chains or the
     * test ones - one of the [Environment] constants. Omit to use the project's own
     * default.
     *
     * It changes nothing when [asset] names a concrete network - that is the caller's
     * choice. It matters in fiat mode and when the network is `ANY`, where the platform
     * selects the asset and an unconstrained pick could put a real payment on a test
     * network.
     */
    @SerialName("environment") val environment: String? = null,
    @SerialName("lifetime_sec") val lifetimeSec: Int? = null,
    @SerialName("url_callback") val urlCallback: String? = null,
    @SerialName("url_success") val urlSuccess: String? = null,
    @SerialName("url_error") val urlError: String? = null,
    @SerialName("additional_data") val additionalData: String? = null,
    /**
     * Acceptable deviation of the received amount from the invoiced one, percent: min `-1`,
     * max `15`, default `5` when omitted. `-1` is the wildcard - ANY received amount counts,
     * and the final status is `paid`, `paid_less` or `paid_over` by direction.
     */
    @SerialName("accuracy_payment_percent") val accuracyPaymentPercent: Int? = null,
    /**
     * Let the invoice be paid by several transactions: an underpayment moves the order to
     * [PayInStatus.WRONG_AMOUNT_WAITING] (webhook event
     * `invoice.wrong_amount_waiting` on EVERY receipt), and the remainder stays payable until
     * `expired_at` + 1 hour. Omit or `false` for the default single-payment behaviour.
     */
    @SerialName("is_payment_multiple") val isPaymentMultiple: Boolean? = null,
    @SerialName("amount_fiat") val amountFiat: String? = null,
    @SerialName("currency") val currency: String? = null,
    @SerialName("course_source") val courseSource: String? = null,
    @SerialName("assets") val assets: AssetsPolicy? = null,
    @SerialName("amount_crypto") val amountCrypto: String? = null,
    @SerialName("asset") val asset: Asset? = null,
)

@Serializable
public data class CoinOption(
    @SerialName("chain_family") val chainFamily: ChainFamily = ChainFamily(""),
    @SerialName("coin") val coin: String = "",
    @SerialName("network") val network: Chain = Chain(""),
    @SerialName("contract") val contract: String? = null,
)

@Serializable
public data class PayIn(
    @SerialName("type") val type: String = "",
    @SerialName("uuid") val uuid: String = "",
    @SerialName("order_id") val orderId: String = "",
    @SerialName("user_id") val userId: String? = null,
    @SerialName("status") val status: String = "",
    @SerialName("mode") val mode: String? = null,
    @SerialName("amount_crypto") val amountCrypto: String? = null,
    @SerialName("amount_fiat") val amountFiat: String? = null,
    @SerialName("currency") val currency: String? = null,
    @SerialName("payment_coin") val paymentCoin: String? = null,
    @SerialName("payment_network") val paymentNetwork: Chain? = null,
    @SerialName("to_address") val toAddress: String? = null,
    @SerialName("coins") val coins: List<CoinOption> = emptyList(),
    @SerialName("payment_link") val paymentLink: String? = null,
    @SerialName("url_callback") val urlCallback: String? = null,
    @SerialName("url_success") val urlSuccess: String? = null,
    @SerialName("url_error") val urlError: String? = null,
    @SerialName("additional_data") val additionalData: String? = null,
    @SerialName("can_cancel") val canCancel: Boolean? = null,
    /**
     * The multi-payment fields below appear only on orders created with
     * [CreatePayInRequest.isPaymentMultiple]; on any other order they are absent and decode
     * to the defaults.
     */
    @SerialName("is_payment_multiple") val isPaymentMultiple: Boolean = false,
    /** Sum of every receipt so far, in the payment coin. */
    @SerialName("received_amount_crypto") val receivedAmountCrypto: String? = null,
    /** What is still missing; `null` once nothing is. */
    @SerialName("remaining_amount_crypto") val remainingAmountCrypto: String? = null,
    /** Every receipt accumulated by the order, oldest first - the same entries the webhook carries. */
    @SerialName("payments") val payments: List<PayInWebhookPayment> = emptyList(),
    @SerialName("expired_at") val expiredAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    public val isTerminal: Boolean get() = status in PayInStatus.TERMINAL
    public val succeeded: Boolean get() = status == PayInStatus.PAID
}

@Serializable
public data class PayInHistoryResponse(
    @SerialName("items") val items: List<PayIn> = emptyList(),
    @SerialName("meta") val meta: HistoryMeta = HistoryMeta(),
)

@Serializable
public data class SelectAssetRequest(
    @SerialName("uuid") val uuid: String,
    @SerialName("coin") val coin: String,
    @SerialName("network") val network: Chain,
    /**
     * Pin the order's transit deposit wallet to the given project master wallet; see
     * [CreatePayInRequest.masterWalletAddress]. A value here overrides one supplied at
     * order create.
     */
    @SerialName("master_wallet_address") val masterWalletAddress: String? = null,
)
