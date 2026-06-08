package com.cryptochief.processing.models

import com.cryptochief.processing.Chain
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class HistoryQuery(
    @SerialName("page") public val page: Int? = null,
    @SerialName("page_size") public val pageSize: Int? = null,
    @SerialName("status") public val status: String? = null,
    @SerialName("coin") public val coin: String? = null,
    @SerialName("network") public val network: Chain? = null,
    @SerialName("date_from") public val dateFrom: String? = null,
    @SerialName("date_to") public val dateTo: String? = null,
)

@Serializable
public data class HistoryMeta(
    @SerialName("page") public val page: Int = 0,
    @SerialName("page_size") public val pageSize: Int = 0,
    @SerialName("total") public val total: Int = 0,
    @SerialName("total_pages") public val totalPages: Int? = null,
)
