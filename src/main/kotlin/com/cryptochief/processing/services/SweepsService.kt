package com.cryptochief.processing.services

import com.cryptochief.processing.Chain
import com.cryptochief.processing.http.HttpTransport
import com.cryptochief.processing.models.ForceSweepRequest
import com.cryptochief.processing.models.ForceSweepResponse
import com.cryptochief.processing.models.SweepHistoryQuery
import com.cryptochief.processing.models.SweepHistoryResponse
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
}
