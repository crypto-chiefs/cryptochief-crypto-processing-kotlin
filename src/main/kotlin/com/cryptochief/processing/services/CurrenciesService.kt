package com.cryptochief.processing.services

import com.cryptochief.processing.http.HttpTransport
import com.cryptochief.processing.models.ConvertRequest
import com.cryptochief.processing.models.ConvertResponse
import com.cryptochief.processing.models.CryptoCurrencies
import com.cryptochief.processing.models.FiatCurrency
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** Fiat ↔ crypto rate quotes, and the currency lists the rates are drawn from. */
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

    /**
     * Every fiat currency the platform can price an order in and quote a rate against —
     * the codes `currency` accepts on a fiat-mode pay-in and either side of a conversion.
     *
     * Platform-wide, so there is nothing to pass; the empty request body is still signed.
     *
     * The endpoint answers a **bare JSON array**, not an `items` envelope, which is why
     * this returns a [List] rather than a response type — the same shape as
     * [BlockchainService.blockchainsList].
     *
     * The service builds that array from a Go slice, so an empty one marshals as a literal
     * `null` and not as `[]`. Both arrive here as the empty list: a method whose signature
     * promises a list answers with one, never with a decode error.
     */
    public suspend fun fiats(): List<FiatCurrency> =
        transport.send(
            path = "/v1/currencies/fiats",
            requestSerializer = JsonObject.serializer(),
            responseSerializer = ListSerializer(FiatCurrency.serializer()).nullable,
            body = JsonObject(emptyMap()),
        ) ?: emptyList()

    /**
     * Every crypto ticker the platform has a rate for, against USDT, and which exchange
     * each one comes from.
     *
     * **Rate availability only:** a ticker here can be quoted, which does not mean the
     * platform takes deposits, sweeps or payouts in it. For what this project can actually
     * be paid in, read [BlockchainService.contractsAvailable] — build an asset picker from
     * that, or it offers assets the order will be refused for.
     *
     * A body of literal `null` — what the service sends when it has no rates at all —
     * answers as an empty [CryptoCurrencies] rather than throwing, and so does a `null`
     * standing in for one exchange's tickers inside [CryptoCurrencies.byExchange].
     */
    public suspend fun cryptos(): CryptoCurrencies =
        transport.send(
            path = "/v1/currencies/cryptos",
            requestSerializer = JsonObject.serializer(),
            responseSerializer = CryptoCurrencies.serializer().nullable,
            body = JsonObject(emptyMap()),
        ) ?: CryptoCurrencies()
}
