package com.cryptochief.processing.services

import com.cryptochief.processing.http.HttpTransport
import com.cryptochief.processing.models.HistoryQuery
import com.cryptochief.processing.models.UuidRequest
import com.cryptochief.processing.models.Withdrawal
import com.cryptochief.processing.models.WithdrawalHistoryResponse
import kotlinx.serialization.serializer

/** Read-only withdrawal endpoints. */
public class WithdrawalsService internal constructor(private val transport: HttpTransport) {

    public suspend fun info(uuid: String): Withdrawal =
        transport.send(
            path = "/v1/withdrawal/info",
            requestSerializer = serializer<UuidRequest>(),
            responseSerializer = serializer(),
            body = UuidRequest(uuid),
        )

    public suspend fun history(query: HistoryQuery = HistoryQuery()): WithdrawalHistoryResponse =
        transport.send(
            path = "/v1/withdrawal/history",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = query,
        )
}
