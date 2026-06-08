package com.cryptochief.processing.services

import com.cryptochief.processing.http.HttpTransport
import com.cryptochief.processing.models.StaticDeposit
import com.cryptochief.processing.models.StaticDepositHistoryQuery
import com.cryptochief.processing.models.StaticDepositHistoryResponse
import com.cryptochief.processing.models.UuidRequest
import kotlinx.serialization.serializer

/** Read endpoints for static (per-customer) deposits. */
public class StaticDepositsService internal constructor(private val transport: HttpTransport) {

    public suspend fun info(uuid: String): StaticDeposit =
        transport.send(
            path = "/v1/static-deposit/info",
            requestSerializer = serializer<UuidRequest>(),
            responseSerializer = serializer(),
            body = UuidRequest(uuid),
        )

    public suspend fun history(
        query: StaticDepositHistoryQuery = StaticDepositHistoryQuery(),
    ): StaticDepositHistoryResponse = transport.send(
        path = "/v1/static-deposit/history",
        requestSerializer = serializer(),
        responseSerializer = serializer(),
        body = query,
    )
}
