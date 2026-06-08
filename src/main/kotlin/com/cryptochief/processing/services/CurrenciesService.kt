package com.cryptochief.processing.services

import com.cryptochief.processing.http.HttpTransport
import com.cryptochief.processing.models.ConvertRequest
import com.cryptochief.processing.models.ConvertResponse
import kotlinx.serialization.serializer

/** Fiat ↔ crypto rate quotes. */
public class CurrenciesService internal constructor(private val transport: HttpTransport) {

    public suspend fun fiatToCrypto(request: ConvertRequest): ConvertResponse =
        transport.send(
            path = "/v1/currencies/convert/fiat-crypto",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = request,
        )

    public suspend fun cryptoToFiat(request: ConvertRequest): ConvertResponse =
        transport.send(
            path = "/v1/currencies/convert/crypto-fiat",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = request,
        )
}
