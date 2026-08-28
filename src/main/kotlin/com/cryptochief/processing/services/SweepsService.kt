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

    public suspend fun history(query: SweepHistoryQuery = SweepHistoryQuery()): SweepHistoryResponse =
        transport.send(
            path = "/v1/sweeps/history",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = query,
        )

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
     * it was.
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
            ),
        )
    }
}
