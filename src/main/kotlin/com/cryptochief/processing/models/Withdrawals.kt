package com.cryptochief.processing.models

import com.cryptochief.processing.Chain
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Withdrawal status. Not the same set as payout statuses.
 *
 * - [QUEUE] - accepted, not started.
 * - [REFUELING] - the source wallet is being topped up with gas.
 * - [REFUEL_CONFIRMED] - gas is in place or no top-up was needed; the withdrawal transaction is sent next.
 * - [BROADCASTING] - EVM only: queued for broadcast.
 * - [SENDING] - being signed and sent.
 * - [IN_MEMPOOL] - BTC family only: broadcast, not yet mined.
 * - [CONFIRM_CHECK] - sent, waiting for [Withdrawal.requiredConfirmations].
 * - [COMPLETED] - [Withdrawal.confirmations] reached [Withdrawal.requiredConfirmations]. Terminal.
 * - [FAILED] - see [Withdrawal.errorReason]. Terminal.
 * - [CANCELLED] - not produced by the API.
 */
public object WithdrawalStatus {
    public const val QUEUE: String = "queue"
    public const val REFUELING: String = "refueling"
    public const val REFUEL_CONFIRMED: String = "refuel_confirmed"
    public const val BROADCASTING: String = "broadcasting"
    public const val SENDING: String = "sending"
    public const val IN_MEMPOOL: String = "in_mempool"
    public const val CONFIRM_CHECK: String = "confirm_check"
    public const val COMPLETED: String = "completed"
    public const val FAILED: String = "failed"
    @Deprecated("Not produced by the API.")
    public const val CANCELLED: String = "cancelled"

    @Suppress("DEPRECATION")
    public val TERMINAL: Set<String> = setOf(COMPLETED, FAILED, CANCELLED)
}

/** A manual withdrawal. [status] is one of [WithdrawalStatus]. */
@Serializable
public data class Withdrawal(
    @SerialName("uuid") val uuid: String = "",
    @SerialName("status") val status: String = "",
    @SerialName("network") val network: Chain = Chain(""),
    @SerialName("coin") val coin: String? = null,
    @Deprecated("Not sent by the API; always null.")
    @SerialName("contract") val contract: String? = null,
    @SerialName("amount") val amount: String = "",
    @Deprecated("Not sent by the API; always null.")
    @SerialName("amount_fiat") val amountFiat: String? = null,
    @SerialName("from_address") val fromAddress: String? = null,
    @SerialName("to_address") val toAddress: String? = null,
    /** The withdrawal transaction; `null` until it has been sent. */
    @SerialName("tx_hash") val txHash: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @Deprecated("Not sent by the API; always null.")
    @SerialName("updated_at") val updatedAt: String? = null,
    @Deprecated("Not sent by the API; always null. Use completedAt.", ReplaceWith("completedAt"))
    @SerialName("confirmed_at") val confirmedAt: String? = null,
    @Deprecated("Not sent by the API; always null. Use errorReason.", ReplaceWith("errorReason"))
    @SerialName("error") val error: String? = null,
    /** Why the withdrawal is [WithdrawalStatus.FAILED]: a machine code. */
    @SerialName("error_reason") val errorReason: String? = null,
    /** Whether the source wallet had to be topped up with gas before sending. */
    @SerialName("need_refuel") val needRefuel: Boolean = false,
    /** The gas top-up transaction; `null` when none was sent. */
    @SerialName("refuel_tx_hash") val refuelTxHash: String? = null,
    /** State of the gas top-up; `null` when none was needed. */
    @SerialName("refuel_status") val refuelStatus: String? = null,
    /** Estimated fee in USD at creation. */
    @SerialName("estimated_fee_fiat") val estimatedFeeFiat: String? = null,
    /** Fee actually paid, in USD; `null` until completed. */
    @SerialName("actual_fee_fiat") val actualFeeFiat: String? = null,
    /** Who pays the fee: `client`, `service` or `mix`. */
    @SerialName("fee_mode") val feeMode: String? = null,
    /** When the withdrawal became [WithdrawalStatus.COMPLETED]; `null` on any other status. */
    @SerialName("completed_at") val completedAt: String? = null,
    /** Confirmations of the withdrawal transaction; `null` or 0 while not in a block. */
    @SerialName("confirmations") val confirmations: Int? = null,
    /**
     * Confirmations the network requires. The withdrawal stays [WithdrawalStatus.CONFIRM_CHECK]
     * until [confirmations] reaches it, then becomes [WithdrawalStatus.COMPLETED]. Always sent.
     */
    @SerialName("required_confirmations") val requiredConfirmations: Int? = null,
) {
    public val isTerminal: Boolean get() = status in WithdrawalStatus.TERMINAL
    public val succeeded: Boolean get() = status == WithdrawalStatus.COMPLETED
}

@Serializable
public data class WithdrawalHistoryResponse(
    @SerialName("items") val items: List<Withdrawal> = emptyList(),
    @SerialName("meta") val meta: HistoryMeta = HistoryMeta(),
)
