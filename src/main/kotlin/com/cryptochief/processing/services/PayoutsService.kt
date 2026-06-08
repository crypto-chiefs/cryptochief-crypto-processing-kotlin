package com.cryptochief.processing.services

import com.cryptochief.processing.http.HttpTransport
import com.cryptochief.processing.models.BatchExecuteRequest
import com.cryptochief.processing.models.BatchExecuteResponse
import com.cryptochief.processing.models.EstimatePayoutRequest
import com.cryptochief.processing.models.EstimatePayoutResponse
import com.cryptochief.processing.models.ExecutePayoutRequest
import com.cryptochief.processing.models.HistoryQuery
import com.cryptochief.processing.models.PayoutHistoryResponse
import com.cryptochief.processing.models.PayoutInfo
import com.cryptochief.processing.models.UuidRequest
import kotlinx.serialization.serializer

/** Payout endpoints. Idempotency key is `ExecutePayoutRequest.orderId`. */
public class PayoutsService internal constructor(private val transport: HttpTransport) {

    public suspend fun estimate(request: EstimatePayoutRequest): EstimatePayoutResponse =
        transport.send(
            path = "/v1/payout/estimate",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = request,
        )

    public suspend fun execute(request: ExecutePayoutRequest): PayoutInfo =
        transport.send(
            path = "/v1/payout/execute",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = request,
        )

    public suspend fun info(uuid: String): PayoutInfo =
        transport.send(
            path = "/v1/payout/info",
            requestSerializer = serializer<UuidRequest>(),
            responseSerializer = serializer(),
            body = UuidRequest(uuid),
        )

    public suspend fun history(query: HistoryQuery = HistoryQuery()): PayoutHistoryResponse =
        transport.send(
            path = "/v1/payout/history",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = query,
        )

    public suspend fun batchEstimate(request: BatchExecuteRequest): BatchExecuteResponse =
        transport.send(
            path = "/v1/payout/batch/estimate",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = request,
        )

    public suspend fun batchExecute(request: BatchExecuteRequest): BatchExecuteResponse =
        transport.send(
            path = "/v1/payout/batch/execute",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = request,
        )
}
