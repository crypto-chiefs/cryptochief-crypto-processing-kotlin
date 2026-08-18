package com.cryptochief.processing.services

import com.cryptochief.processing.http.HttpTransport
import com.cryptochief.processing.models.CreditsBalance
import com.cryptochief.processing.models.CreditsTopup
import com.cryptochief.processing.models.CreditsTopupRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** Credits balance and top-up. Billing-exempt — these calls never spend a paid call. */
public class CreditsService internal constructor(private val transport: HttpTransport) {

    public suspend fun balance(): CreditsBalance =
        transport.send(
            path = "/v1/credits/balance",
            requestSerializer = JsonObject.serializer(),
            responseSerializer = serializer(),
            body = JsonObject(emptyMap()),
        )

    public suspend fun topup(request: CreditsTopupRequest): CreditsTopup =
        transport.send(
            path = "/v1/credits/topup",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = request,
        )
}
