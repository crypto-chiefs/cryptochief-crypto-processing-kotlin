package com.cryptochief.processing

/** Root of every exception thrown by the SDK. */
public sealed class CryptoChiefException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Server returned a non-2xx response with a structured error envelope. */
public class ApiException(
    public val code: String,
    public val status: Int,
    public val description: String,
    public val raw: String? = null,
) : CryptoChiefException(buildApiMessage(code, status, description)) {

    /** Retryable when the failure is 5xx or `NETWORK_ERROR`. */
    public val retryable: Boolean
        get() = status in 500..599 || code == ErrorCode.NETWORK_ERROR

    private companion object {
        private fun buildApiMessage(code: String, status: Int, description: String): String =
            when {
                status == 0 -> "cryptochief: $code"
                description.isNotEmpty() && description != code -> "cryptochief: $status $code: $description"
                else -> "cryptochief: $status $code"
            }
    }
}

/** Connection, DNS, TLS, timeout, or read failure. */
public class NetworkException(
    message: String,
    cause: Throwable? = null,
) : CryptoChiefException(message, cause)

/** Response was 2xx but the body did not parse against the expected schema. */
public class DecodeException(
    message: String,
    cause: Throwable? = null,
) : CryptoChiefException(message, cause)

/** Missing or malformed configuration: merchant ID, API key, RSA key. */
public class ConfigurationException(
    message: String,
    cause: Throwable? = null,
) : CryptoChiefException(message, cause)

/** Known stable error code strings used in [ApiException.code]. */
public object ErrorCode {
    public const val INSUFFICIENT_FUNDS: String = "INSUFFICIENT_FUNDS"
    public const val INSUFFICIENT_CREDITS: String = "INSUFFICIENT_CREDITS"
    public const val DEBT_LIMIT_EXCEEDED: String = "DEBT_LIMIT_EXCEEDED"
    public const val ASSET_NOT_ENABLED: String = "ASSET_NOT_ENABLED"
    public const val ORDER_ALREADY_EXIST: String = "ORDER_ALREADY_EXIST"
    public const val ORDER_CANNOT_CANCEL: String = "ORDER_CANNOT_CANCEL"
    public const val ORDER_NOT_LIVE: String = "ORDER_NOT_LIVE"
    public const val ASSET_ALREADY_SELECTED: String = "ASSET_ALREADY_SELECTED"
    public const val INVALID_PARAMS: String = "INVALID_PARAMS"
    public const val SERVICE_ERROR: String = "SERVICE_ERROR"
    public const val UNAUTHORIZED: String = "UNAUTHORIZED"
    public const val URL_CALLBACK_REQUIRED: String = "URL_CALLBACK_REQUIRED"
    public const val BATCH_EMPTY: String = "BATCH_EMPTY"
    public const val BATCH_TOO_LARGE: String = "BATCH_TOO_LARGE"
    public const val BATCH_DUPLICATE_ORDER_ID: String = "BATCH_DUPLICATE_ORDER_ID"
    public const val FROM_WALLET_NOT_OWNED: String = "FROM_WALLET_NOT_OWNED"
    public const val SIGNATURE_EXPIRED: String = "SIGNATURE_EXPIRED"
    public const val ALREADY_EXECUTED: String = "ALREADY_EXECUTED"
    public const val PREFLIGHT_FAILED: String = "PREFLIGHT_FAILED"
    public const val BROADCAST_FAILED: String = "BROADCAST_FAILED"
    public const val SIGNED_TX_MISMATCH: String = "SIGNED_TX_MISMATCH"
    public const val CONTRACT_REQUIRED_FOR_TOKEN: String = "CONTRACT_REQUIRED_FOR_TOKEN"
    public const val TRANSFER_FIELDS_NOT_ALLOWED_FOR_CONTRACT: String = "TRANSFER_FIELDS_NOT_ALLOWED_FOR_CONTRACT"
    public const val CALLS_REQUIRED: String = "CALLS_REQUIRED"
    public const val CALLS_NOT_ALLOWED_FOR_TRANSFER: String = "CALLS_NOT_ALLOWED_FOR_TRANSFER"
    public const val CONTRACT_CALLS_UNSUPPORTED_ON_NETWORK: String = "CONTRACT_CALLS_UNSUPPORTED_ON_NETWORK"
    public const val NETWORK_ERROR: String = "NETWORK_ERROR"
}
