package com.cryptochief.processing

/** Root of every exception thrown by the SDK. */
public sealed class CryptoChiefException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Server returned a non-2xx response with a structured error envelope.
 *
 * A refusal arrives as `{"ok":false,"error":...,"msg":...}` in one of two shapes: the
 * gateway's own checks put the machine code in `error` and an English sentence in `msg`,
 * while refusals relayed from an upstream service put the generic `SERVICE_ERROR` in
 * `error` and the machine code in `msg`. The SDK folds both into [code], so callers
 * switch on one field and never have to test which shape they got.
 *
 * @property code the machine-readable code, and the one to branch on — `error` unless it
 *   is absent or `SERVICE_ERROR`, in which case `msg`; `HTTP_<status>` if the body carried
 *   neither. The constants in [ErrorCode] cover the codes the gateway itself raises.
 * @property status the HTTP status code.
 * @property description the human-readable half — the sentence from `msg` when the
 *   envelope carried one alongside a distinct code, otherwise the code itself. Display it;
 *   do not branch on it.
 * @property raw the response body, verbatim, truncated at 8 KiB.
 */
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

    /**
     * A wallet label over 255 characters. A real machine code from the gateway,
     * unlike the upstream refusals that arrive as SERVICE_ERROR with the detail
     * in the message.
     */
    public const val LABEL_TOO_LONG: String = "LABEL_TOO_LONG"
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

    /** The object does not exist OR is not this project's — deliberately indistinguishable. */
    public const val NOT_FOUND: String = "NOT_FOUND"
    /** Webhook resend: a newer event exists for the same object; only the latest may be resent. Permanent. */
    public const val DELIVERY_SUPERSEDED: String = "DELIVERY_SUPERSEDED"
    /** Webhook resend: a worker holds the delivery, or it is already scheduled for a retry. */
    public const val DELIVERY_IN_FLIGHT: String = "DELIVERY_IN_FLIGHT"
    /** Webhook resend: resent under a minute ago (HTTP 429, Retry-After). */
    public const val RESEND_TOO_SOON: String = "RESEND_TOO_SOON"
    /** Static-deposit resend: no webhook was ever queued — the wallet had no callback_url. */
    public const val NO_DELIVERIES: String = "NO_DELIVERIES"
}
