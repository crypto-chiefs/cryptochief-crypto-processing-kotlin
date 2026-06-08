package com.cryptochief.processing.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
