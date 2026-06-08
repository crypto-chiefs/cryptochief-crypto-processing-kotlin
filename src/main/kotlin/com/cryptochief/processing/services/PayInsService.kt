package com.cryptochief.processing.services

import com.cryptochief.processing.http.HttpTransport
import com.cryptochief.processing.models.CreatePayInRequest
import com.cryptochief.processing.models.HistoryQuery
import com.cryptochief.processing.models.PayIn
import com.cryptochief.processing.models.PayInHistoryResponse
import com.cryptochief.processing.models.SelectAssetRequest
import com.cryptochief.processing.models.UuidRequest
import kotlinx.serialization.serializer

/** Incoming-payment endpoints. */
public class PayInsService internal constructor(private val transport: HttpTransport) {

    public suspend fun create(request: CreatePayInRequest): PayIn =
        transport.send(
            path = "/v1/payments/order/create",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = request,
        )

    public suspend fun selectAsset(request: SelectAssetRequest): PayIn =
        transport.send(
            path = "/v1/payments/asset/select",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = request,
        )

    public suspend fun resetAsset(uuid: String): PayIn =
        transport.send(
            path = "/v1/payments/asset/reset",
            requestSerializer = serializer<UuidRequest>(),
            responseSerializer = serializer(),
            body = UuidRequest(uuid),
        )

    public suspend fun cancel(uuid: String): PayIn =
        transport.send(
            path = "/v1/payments/order/cancel",
            requestSerializer = serializer<UuidRequest>(),
            responseSerializer = serializer(),
            body = UuidRequest(uuid),
        )

    public suspend fun info(uuid: String): PayIn =
        transport.send(
            path = "/v1/payments/order/info",
            requestSerializer = serializer<UuidRequest>(),
            responseSerializer = serializer(),
            body = UuidRequest(uuid),
        )

    public suspend fun history(query: HistoryQuery = HistoryQuery()): PayInHistoryResponse =
        transport.send(
            path = "/v1/payments/history",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = query,
        )
}
