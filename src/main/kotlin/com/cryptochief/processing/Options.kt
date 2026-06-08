package com.cryptochief.processing

import okhttp3.OkHttpClient
import java.security.interfaces.RSAPrivateKey
import java.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

public const val DEFAULT_BASE_URL: String = "https://api-processing.crypto-chief.com"

public const val DEFAULT_TON_RPC_BASE_URL: String = "https://rpc.crypto-chief.com"

/** Immutable configuration for [CryptoChiefClient]. */
public class Options private constructor(
    public val merchantId: String,
    public val apiKey: String,
    public val baseUrl: String,
    public val tonRpcBaseUrl: String,
    public val userAgent: String,
    public val requestTimeout: Duration,
    public val maxRetries: Int,
    public val initialRetryDelay: Duration,
    public val maxRetryDelay: Duration,
    public val rsaPrivateKey: RSAPrivateKey?,
    public val httpClient: OkHttpClient?,
) {
    init {
        require(merchantId.isNotBlank()) { "merchantId is required" }
        require(apiKey.isNotBlank()) { "apiKey is required" }
        require(baseUrl.isNotBlank()) { "baseUrl is required" }
        require(maxRetries >= 0) { "maxRetries cannot be negative" }
    }

    public class Builder {
        public var merchantId: String = ""
        public var apiKey: String = ""
        public var baseUrl: String = DEFAULT_BASE_URL
        public var tonRpcBaseUrl: String = DEFAULT_TON_RPC_BASE_URL
        public var userAgent: String = "cryptochief-kotlin/${BuildInfo.VERSION}"
        public var requestTimeout: Duration = 60.seconds.toJavaDuration()
        public var maxRetries: Int = 3
        public var initialRetryDelay: Duration = 200.milliseconds.toJavaDuration()
        public var maxRetryDelay: Duration = 5.seconds.toJavaDuration()
        public var rsaPrivateKey: RSAPrivateKey? = null
        public var httpClient: OkHttpClient? = null

        public fun build(): Options = Options(
            merchantId = merchantId,
            apiKey = apiKey,
            baseUrl = baseUrl.trimEnd('/'),
            tonRpcBaseUrl = tonRpcBaseUrl.trimEnd('/'),
            userAgent = userAgent,
            requestTimeout = requestTimeout,
            maxRetries = maxRetries,
            initialRetryDelay = initialRetryDelay,
            maxRetryDelay = maxRetryDelay,
            rsaPrivateKey = rsaPrivateKey,
            httpClient = httpClient,
        )
    }

    public companion object {
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}

public object BuildInfo {
    public const val VERSION: String = "0.1.0"
}
