package com.cryptochief.processing.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
public data class ConvertRequest(
    @SerialName("provider") val provider: String? = null,
    @SerialName("from") val from: String,
    @SerialName("to") val to: String,
    @SerialName("amount") val amount: String,
)

@Serializable
public data class ConvertResponse(
    @SerialName("amount_crypto") val amountCrypto: Double = 0.0,
    @SerialName("amount_fiat") val amountFiat: Double = 0.0,
    @SerialName("crypto") val crypto: String = "",
    @SerialName("crypto_to_usdt") val cryptoToUsdt: Double = 0.0,
    @SerialName("exchange") val exchange: String = "",
    @SerialName("fiat") val fiat: String = "",
    @SerialName("fiat_to_usd") val fiatToUsd: Double = 0.0,
    @SerialName("timestamp_crypto") val timestampCrypto: Long = 0,
    @SerialName("timestamp_fiat") val timestampFiat: Long = 0,
)

/**
 * One fiat currency the platform can price an order in.
 *
 * [code] is what `CreatePayInRequest.currency` accepts on a fiat-mode order, and what the
 * fiat side of a rate quote ([ConvertRequest.from] / [ConvertRequest.to]) accepts.
 */
@Serializable
public data class FiatCurrency(
    /** ISO 4217 code — `SEK`, `USD`, `JMD`. */
    @SerialName("code") val code: String = "",
    /** Display name — `Swedish Krona`. */
    @SerialName("name") val name: String = "",
)

/**
 * The crypto tickers the platform has an exchange rate for, quoted against [quote].
 *
 * **Rate availability only.** A ticker here is one the platform can put a price on; it does
 * not mean the platform takes deposits, sweeps or payouts in it. That list is
 * [com.cryptochief.processing.services.BlockchainService.contractsAvailable], and it is far
 * shorter — [count] runs into the thousands.
 */
@Serializable
public data class CryptoCurrencies(
    /** Every ticker, deduplicated across the exchanges. */
    @SerialName("tickers") val tickers: List<String> = emptyList(),
    /**
     * The tickers each exchange carries, keyed by exchange name — `binance`, `bybit`…
     *
     * An exchange the platform has nothing for arrives as `"bybit": null`, and that reads
     * back as the empty list rather than failing the decode — see [NullSafeTickerList].
     */
    @SerialName("by_exchange")
    val byExchange: Map<String, @Serializable(with = NullSafeTickerList::class) List<String>> = emptyMap(),
    /** How many tickers [tickers] holds. */
    @SerialName("count") val count: Int = 0,
    /** The asset the rates are quoted against — `USDT`. */
    @SerialName("quote") val quote: String = "",
)

/**
 * A ticker list that reads a JSON `null` as no tickers.
 *
 * [CryptoCurrencies.byExchange] is a map, and a `null` *inside* a map is out of reach of the
 * coercion that fills a missing property in from its default — that only covers the property
 * itself, not a value nested under it. An exchange the platform currently has nothing for
 * arrives as `"bybit": null`, and without this the whole response would fail to decode over
 * one empty exchange.
 *
 * A method promising a list answers with an empty one; it does not throw.
 */
public object NullSafeTickerList : KSerializer<List<String>> {
    private val delegate: KSerializer<List<String>> = ListSerializer(String.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<String>) {
        delegate.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): List<String> =
        if (decoder.decodeNotNullMark()) {
            delegate.deserialize(decoder)
        } else {
            decoder.decodeNull()
            emptyList()
        }
}
