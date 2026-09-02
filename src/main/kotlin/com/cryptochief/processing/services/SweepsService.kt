package com.cryptochief.processing.services

import com.cryptochief.processing.Chain
import com.cryptochief.processing.http.HttpTransport
import com.cryptochief.processing.models.ForceSweepRequest
import com.cryptochief.processing.models.ForceSweepResponse
import com.cryptochief.processing.models.SweepFieldWrite
import com.cryptochief.processing.models.SweepHistoryQuery
import com.cryptochief.processing.models.SweepHistoryResponse
import com.cryptochief.processing.models.SweepSettings
import com.cryptochief.processing.models.SweepSettingsQuery
import com.cryptochief.processing.models.SweepSettingsUpdateRequest
import com.cryptochief.processing.models.SweepWalletHistoryQuery
import kotlinx.serialization.serializer

/** Transit → master sweep endpoints. */
public class SweepsService internal constructor(private val transport: HttpTransport) {

    public suspend fun force(address: String, network: Chain): ForceSweepResponse =
        transport.send(
            path = "/v1/sweeps/force",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = ForceSweepRequest(address, network),
        )

    /**
     * Sweep operations across the whole project, automatic and forced alike.
     *
     * [SweepHistoryQuery.status] narrows to one status — left out, every status comes
     * back, `skipped` among them — and [SweepHistoryQuery.search] is a substring match on
     * the wallet address, the sweep or gas-pump transaction hash, and the task id.
     *
     * Do not poll this to learn that a sweep settled: the sweep webhook fires the moment
     * one is confirmed on chain. This is for reconciliation, and for watching a sweep
     * that is still in flight.
     */
    public suspend fun history(query: SweepHistoryQuery = SweepHistoryQuery()): SweepHistoryResponse =
        transport.send(
            path = "/v1/sweeps/history",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = query,
        )

    /**
     * [history] for one wallet. Same filters, except that [SweepWalletHistoryQuery.search]
     * matches only the transaction hashes and the task id — the address is already fixed.
     */
    public suspend fun walletHistory(query: SweepWalletHistoryQuery): SweepHistoryResponse =
        transport.send(
            path = "/v1/sweeps/wallet/history",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = query,
        )

    /**
     * The auto-sweep policy in force for one wallet, together with what it overrides and
     * what it inherits. An empty address asks for the project's own default.
     *
     * Scoped to the caller's own wallets: an address that is not the project's answers
     * `WALLET_NOT_FOUND`.
     */
    public suspend fun settings(query: SweepSettingsQuery = SweepSettingsQuery()): SweepSettings =
        transport.send(
            path = "/v1/sweeps/settings",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = query,
        )

    /**
     * Write a wallet's auto-sweep policy. Returns the settings as they stand afterwards,
     * so the caller sees what the write resolved to without asking again.
     *
     * A `null` argument leaves that field alone; [SweepFieldWrite.Inherit] stops
     * overriding it. Inheritance is per field, so writing the mode leaves the fee mode as
     * it was. The four writable fields — the names that reach the wire's `fields` mask —
     * are `type_work`, `threshold_amount_usd`, `fee_mode` and `gas_source`.
     *
     * [feeMode] takes a [com.cryptochief.processing.models.SweepFeeMode] value and decides
     * only who covers a **shortfall** of gas — a deposit wallet holding enough native coin
     * pays for its own transfer in every mode. `service` has the platform supply it and
     * bills the cost to your API credits; `mix`, the default, tries the master wallet first
     * and falls back to `service`.
     *
     * [gasSource] takes a [com.cryptochief.processing.models.SweepGasSource] value and is
     * TRON-only; other chains carry it and ignore it. Leaving it `null` here leaves the
     * stored value untouched — which is **not** the same as choosing `native`: a wallet
     * that never chose one gets the platform default, `rented`, so energy is supplied and
     * billed to your API credits without anybody having switched it on.
     *
     * Refusals are named: `TYPE_WORK_INVALID`, `FEE_MODE_INVALID`, `THRESHOLD_INVALID`,
     * `THRESHOLD_MUST_BE_POSITIVE`, `THRESHOLD_REQUIRED_FOR_THRESHOLD_MODE`, and
     * `SWEEP_SETTINGS_LOCKED` when an operator has pinned the policy.
     */
    public suspend fun updateSettings(
        address: String,
        typeWork: SweepFieldWrite? = null,
        thresholdAmountUsd: SweepFieldWrite? = null,
        feeMode: SweepFieldWrite? = null,
        networkCode: Chain? = null,
        gasSource: SweepFieldWrite? = null,
    ): SweepSettings {
        val fields = mutableListOf<String>()
        fun named(wireName: String, write: SweepFieldWrite?): String? {
            if (write == null) return null
            fields += wireName
            return (write as? SweepFieldWrite.Set)?.value
        }

        val typeWorkValue = named("type_work", typeWork)
        val thresholdValue = named("threshold_amount_usd", thresholdAmountUsd)
        val feeModeValue = named("fee_mode", feeMode)
        val gasSourceValue = named("gas_source", gasSource)

        return transport.send(
            path = "/v1/sweeps/settings/update",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = SweepSettingsUpdateRequest(
                address = address,
                networkCode = networkCode,
                fields = fields.ifEmpty { null },
                typeWork = typeWorkValue,
                thresholdAmountUsd = thresholdValue,
                feeMode = feeModeValue,
                gasSource = gasSourceValue,
            ),
        )
    }
}
