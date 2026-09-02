package com.cryptochief.processing.models

import com.cryptochief.processing.Chain
import com.cryptochief.processing.ChainFamily
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

public object SweepMode {
    public const val AUTO: String = "auto"
    public const val FORCE: String = "force"
}

/**
 * Sweep status.
 *
 * A sweep is broadcast first and confirmed after: [BROADCASTED] means the transaction is
 * out and not yet confirmed, [COMPLETED] means the chain confirmed it. The platform used
 * to report `completed` at broadcast, so a sweep could read as settled while its
 * transaction was still unconfirmed or had been dropped.
 *
 * [SKIPPED] is a sweep the platform decided against - almost always a balance below the
 * wallet's threshold. A normal outcome, not a failure.
 */
public object SweepStatus {
    public const val PENDING: String = "pending"
    public const val WAITING_GAS: String = "waiting_gas"
    public const val BROADCASTED: String = "broadcasted"
    public const val COMPLETED: String = "completed"
    public const val FAILED: String = "failed"
    public const val SKIPPED: String = "skipped"
}

/**
 * Auto-sweep modes.
 *
 * - [OFF]: never swept on its own. A force sweep still works.
 * - [MOMENTUM]: swept as soon as funds arrive.
 * - [THRESHOLD]: swept once the balance reaches the threshold. A held balance is
 *   re-checked periodically, so a wallet that crosses the threshold through price
 *   movement alone is still swept.
 */
public object SweepPolicyMode {
    public const val OFF: String = "turned_off"
    public const val MOMENTUM: String = "momentum"
    public const val THRESHOLD: String = "threshold"
}

/**
 * Who covers a **shortfall** of gas on a sweep.
 *
 * A deposit wallet already holding enough of the chain's native coin pays for its own
 * transfer, whatever the mode says. These three only decide where the difference comes from
 * when it does not:
 *
 * - [CLIENT]: your own master wallet.
 * - [SERVICE]: the platform supplies it, and **the cost is billed to your API credits**.
 * - [MIX]: the default. [CLIENT] first, falling back to [SERVICE] when the master wallet
 *   cannot cover it.
 *
 * None of this is the TRON energy question, which [SweepGasSource] answers and which is
 * billed to your credits in every one of these modes.
 */
public object SweepFeeMode {
    public const val CLIENT: String = "client"
    public const val SERVICE: String = "service"
    public const val MIX: String = "mix"
}

/**
 * What is bought to move the funds, where [SweepFeeMode] answers who pays the network
 * fee. The two are independent: energy can be supplied under any fee mode, and it is
 * billed to your API credits whichever one you chose. TRON only — every other chain
 * carries the value and ignores it.
 *
 * - [NATIVE]: the wallet burns its own TRX for energy.
 * - [RENTED]: the platform supplies the energy for the transfer, so nothing is burnt.
 *
 * **Not setting it is not the same as setting [NATIVE].** A wallet that has never chosen
 * one gets the platform default, which is [RENTED] — so energy is supplied, and billed to
 * your credits, without anybody having switched it on. To have the wallet burn its own
 * TRX, send [NATIVE] explicitly. Read [SweepPolicy.gasSource] on
 * [SweepSettings.effective] to see what will actually happen.
 */
public object SweepGasSource {
    public const val NATIVE: String = "native"
    public const val RENTED: String = "rented"
}

@Serializable
public data class SweepHistoryQuery(
    @SerialName("mode") val mode: String? = null,
    @SerialName("page") val page: Int? = null,
    @SerialName("page_size") val pageSize: Int? = null,
    /**
     * One [SweepStatus]. Left null every status is included, `skipped` among them — so
     * this is how you ask for the failures alone, or leave out the sweeps the platform
     * decided against.
     */
    @SerialName("status") val status: String? = null,
    /**
     * Substring match on the wallet address, the sweep or gas-pump transaction hash, and
     * the `task_id`.
     */
    @SerialName("search") val search: String? = null,
)

@Serializable
public data class SweepWalletHistoryQuery(
    @SerialName("address") val address: String,
    @SerialName("mode") val mode: String? = null,
    @SerialName("page") val page: Int? = null,
    @SerialName("page_size") val pageSize: Int? = null,
    /** One [SweepStatus]; see [SweepHistoryQuery.status]. */
    @SerialName("status") val status: String? = null,
    /**
     * Substring match on the sweep or gas-pump transaction hash and the `task_id`. The
     * wallet is already fixed by [address], so unlike [SweepHistoryQuery.search] this one
     * does not match addresses.
     */
    @SerialName("search") val search: String? = null,
)

@Serializable
public data class Sweep(
    @SerialName("task_id") val taskId: String,
    @SerialName("sweep_tx_hash") val sweepTxHash: String? = null,
    @SerialName("gas_pump_tx_hash") val gasPumpTxHash: String? = null,
    /** One of the [SweepStatus] constants. */
    @SerialName("status") val status: String,
    @SerialName("wallet_address") val walletAddress: String,
    @SerialName("chain") val chain: Chain,
    @SerialName("chain_family") val chainFamily: ChainFamily? = null,
    @SerialName("asset_symbol") val assetSymbol: String? = null,
    @SerialName("asset_type") val assetType: String? = null,
    @SerialName("amount_human") val amountHuman: String? = null,
    /** What triggered this sweep: momentum, threshold or force. */
    @SerialName("type_work") val typeWork: String? = null,
    /**
     * Confirmations seen on the sweep transaction, and when the platform stopped working on
     * the task.
     *
     * **[completedAt] is not proof the sweep settled.** The sweeper stamps it at every
     * terminal outcome, failures included — a `failed` sweep is no more in flight than a
     * `completed` one, so it carries a time too. Read its presence as settlement and a
     * failed sweep books as money received.
     *
     * What settlement looks like: [sweepConfirmations] above zero (with [status]
     * [SweepStatus.COMPLETED]). Or take `confirmed_at` off the `sweep.confirmed` webhook —
     * [com.cryptochief.processing.webhook.SweepWebhookEvent.confirmedAt], which exists as a
     * separate field for exactly this reason.
     */
    @SerialName("sweep_confirmations") val sweepConfirmations: Int? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    /**
     * Fees. [totalFeeUsd] is the whole cost of the sweep; the gas-pump half is the funding
     * transfer that pays for it on chains needing one. The `real*` figures are what the
     * chain actually charged, filled in once the transaction settles; the others are the
     * estimate made up front.
     */
    @SerialName("total_fee_usd") val totalFeeUsd: String? = null,
    @SerialName("gas_pump_source") val gasPumpSource: String? = null,
    @SerialName("gas_pump_fee_human") val gasPumpFeeHuman: String? = null,
    @SerialName("gas_pump_fee_usd") val gasPumpFeeUsd: String? = null,
    @SerialName("sweep_fee_human") val sweepFeeHuman: String? = null,
    @SerialName("sweep_fee_usd") val sweepFeeUsd: String? = null,
    @SerialName("real_gas_pump_fee_human") val realGasPumpFeeHuman: String? = null,
    @SerialName("real_gas_pump_fee_usd") val realGasPumpFeeUsd: String? = null,
    @SerialName("real_sweep_fee_human") val realSweepFeeHuman: String? = null,
    @SerialName("real_sweep_fee_usd") val realSweepFeeUsd: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    /**
     * Never populated. The API reports fees under the names above; these were guesses at a
     * shape it does not send. Kept so existing code still compiles.
     */
    @Deprecated("Never populated by the API")
    @SerialName("gas_fee_human") val gasFeeHuman: String? = null,
    @Deprecated("Never populated by the API")
    @SerialName("gas_fee_fiat") val gasFeeFiat: String? = null,
    @Deprecated("Never populated by the API")
    @SerialName("service_fee_fiat") val serviceFeeFiat: String? = null,
    @Deprecated("Never populated by the API - sweeps carry createdAt and completedAt")
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** A resolved set of sweep rules. */
@Serializable
public data class SweepPolicy(
    @SerialName("type_work") val typeWork: String = "",
    /** Meaningful only when [typeWork] is [SweepPolicyMode.THRESHOLD]. */
    @SerialName("threshold_amount_usd") val thresholdAmountUsd: String? = null,
    /** One of the [SweepFeeMode] constants — who covers a shortfall of gas, not who is charged. */
    @SerialName("fee_mode") val feeMode: String = "",
    /**
     * Which layer the mode came from: `wallet_network`, `wallet`, `project` or `default`.
     * Present on [SweepSettings.effective], where the question arises.
     */
    @SerialName("source") val source: String? = null,
    /**
     * One of the [SweepGasSource] constants. On [SweepSettings.effective] this is always a
     * concrete value — read it to see what will actually happen, since a wallet that never
     * chose one gets the platform default, `rented`, and has its energy billed to your API
     * credits. TRON only.
     */
    @SerialName("gas_source") val gasSource: String = "",
)

/**
 * What one wallet decides for itself. A `null` field is not overridden - it is inherited,
 * which no ordinary value can express.
 */
@Serializable
public data class SweepOverride(
    /**
     * Empty covers the address on every network it exists on; set, it covers that one
     * network and takes precedence over the address-wide override.
     */
    @SerialName("network_code") val networkCode: String? = null,
    @SerialName("type_work") val typeWork: String? = null,
    @SerialName("threshold_amount_usd") val thresholdAmountUsd: String? = null,
    /** One of the [SweepFeeMode] constants, or `null` where this layer does not decide it. */
    @SerialName("fee_mode") val feeMode: String? = null,
    /** Who wrote it: `merchant` or `operator`. */
    @SerialName("source") val source: String? = null,
    /**
     * An operator pinned this policy. While it is set, a merchant write answers
     * `SWEEP_SETTINGS_LOCKED` and changes nothing.
     */
    @SerialName("locked") val locked: Boolean = false,
    /**
     * One of the [SweepGasSource] constants, or `null` — which says this layer does not
     * decide it, so the value is **inherited, not switched off**. A wallet inheriting it
     * still gets the platform default, `rented`, with the energy billed to your API
     * credits: `null` here and `native` are different answers.
     */
    @SerialName("gas_source") val gasSource: String? = null,
)

/**
 * Three layers, on purpose.
 *
 * [effective] is what will actually happen, [override] is what this wallet decides for
 * itself (null if it decides nothing), and [projectDefault] is what it falls back to. Only
 * the three together answer "is this value mine or inherited" - the difference between
 * changing it here and changing it on the project. Inheritance is per field: a wallet can
 * override the mode and keep inheriting the fee mode.
 */
@Serializable
public data class SweepSettings(
    @SerialName("wallet_address") val walletAddress: String? = null,
    @SerialName("network_code") val networkCode: String? = null,
    @SerialName("effective") val effective: SweepPolicy = SweepPolicy(),
    @SerialName("override") val override: SweepOverride? = null,
    @SerialName("project_default") val projectDefault: SweepPolicy = SweepPolicy(),
)

/**
 * A sweep-policy field being written.
 *
 * [Set] writes a value; [Inherit] stops overriding the field and goes back to inheriting
 * it. The API expresses the second by naming the field with no value, which `null` cannot
 * say here because it already means "not supplied - leave it alone".
 */
public sealed interface SweepFieldWrite {
    public data class Set(val value: String) : SweepFieldWrite
    public data object Inherit : SweepFieldWrite
}

/** An empty [address] asks for the project's own default rather than a wallet's policy. */
@Serializable
public data class SweepSettingsQuery(
    @SerialName("address") val address: String? = null,
    @SerialName("network_code") val networkCode: Chain? = null,
)

@Serializable
internal data class SweepSettingsUpdateRequest(
    @SerialName("address") val address: String,
    @SerialName("network_code") val networkCode: Chain? = null,
    @SerialName("fields") val fields: List<String>? = null,
    @SerialName("type_work") val typeWork: String? = null,
    @SerialName("threshold_amount_usd") val thresholdAmountUsd: String? = null,
    @SerialName("fee_mode") val feeMode: String? = null,
    @SerialName("gas_source") val gasSource: String? = null,
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
