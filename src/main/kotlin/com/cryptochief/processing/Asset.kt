package com.cryptochief.processing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One coin on one network. Empty fields match any. */
@Serializable
public data class Asset(
    @SerialName("network") public val network: Chain? = null,
    @SerialName("coin") public val coin: String? = null,
)

/** Allow / exclude filter over [Asset]. */
@Serializable
public data class AssetsPolicy(
    @SerialName("allow") public val allow: List<Asset> = emptyList(),
    @SerialName("exclude") public val exclude: List<Asset> = emptyList(),
)
