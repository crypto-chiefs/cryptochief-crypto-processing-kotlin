package com.cryptochief.processing.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Delivery statuses in [WebhookDelivery.status]. */
public object WebhookDeliveryStatus {
    /** Queued, not yet attempted (or waiting for a retry). */
    public const val PENDING: String = "pending"
    /** A worker holds it right now. */
    public const val IN_PROGRESS: String = "in_progress"
    /** Your endpoint answered 2xx. */
    public const val DELIVERED: String = "delivered"
    /** Every attempt so far was refused or timed out. */
    public const val FAILED: String = "failed"
    /** Superseded by a newer event before it was ever sent. */
    public const val CANCELLED: String = "cancelled"
}

/** One POST the platform made to your endpoint. Newest first in [WebhookDelivery.attemptHistory]. */
@Serializable
public data class WebhookAttempt(
    @SerialName("attempt") val attempt: Int = 0,
    /** `null` when nothing answered (DNS, connect, TLS, timeout); [error] then holds the transport error. */
    @SerialName("http_status") val httpStatus: Int? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("target_url") val targetUrl: String = "",
    /** `null` for attempts recorded before the platform kept the time. */
    @SerialName("created_at") val createdAt: String? = null,
    /** What your endpoint answered, as the platform saw it. Capped; see [responseTruncated]. */
    @SerialName("response_body") val responseBody: String? = null,
    @SerialName("response_content_type") val responseContentType: String? = null,
    @SerialName("response_truncated") val responseTruncated: Boolean = false,
)

/** The body the platform sent. [bytes] is the whole size even when [body] was cut. */
@Serializable
public data class WebhookPayload(
    @SerialName("body") val body: String = "",
    @SerialName("bytes") val bytes: Int = 0,
    @SerialName("truncated") val truncated: Boolean = false,
)

/**
 * One outbound webhook, with every attempt the platform made and the body it sent.
 * `null` means "not recorded", distinct from zero or empty.
 */
@Serializable
public data class WebhookDelivery(
    @SerialName("uuid") val uuid: String,
    @SerialName("event_type") val eventType: String = "",
    /** The object the event was about — the order or static deposit uuid you already hold. */
    @SerialName("reference") val reference: String = "",
    @SerialName("target_url") val targetUrl: String = "",
    @SerialName("status") val status: String = "",
    @SerialName("attempts") val attempts: Int = 0,
    @SerialName("max_attempts") val maxAttempts: Int = 0,
    /** How many times a resend was asked for, by API or from the dashboard. */
    @SerialName("resend_count") val resendCount: Int = 0,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("last_http_status") val lastHttpStatus: Int? = null,
    @SerialName("next_attempt_at") val nextAttemptAt: String? = null,
    @SerialName("delivered_at") val deliveredAt: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    /**
     * The NEWER event for the same object, when there is one. A superseded delivery cannot
     * be resent — resend the latest event instead.
     */
    @SerialName("superseded_by") val supersededBy: String? = null,
    @SerialName("attempt_history") val attemptHistory: List<WebhookAttempt> = emptyList(),
    @SerialName("payload") val payload: WebhookPayload = WebhookPayload(),
)

/**
 * What a resend did. On this platform a resend is synchronous: the POST to your endpoint
 * happens before the answer comes back, so `queued = true` arrives with [status] already
 * `delivered` or `failed` for that attempt.
 */
@Serializable
public data class WebhookResendResult(
    @SerialName("uuid") val uuid: String,
    @SerialName("event_type") val eventType: String = "",
    @SerialName("reference") val reference: String = "",
    @SerialName("status") val status: String = "",
    @SerialName("queued") val queued: Boolean = false,
    @SerialName("attempts") val attempts: Int = 0,
    @SerialName("resend_count") val resendCount: Int = 0,
    /** Set when [queued] is false: one of the `ErrorCode.DELIVERY_*` / `RESEND_TOO_SOON` codes. */
    @SerialName("reason") val reason: String? = null,
    @SerialName("superseded_by") val supersededBy: String? = null,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Int? = null,
)

/**
 * The resend of a static deposit's webhook. [deliveries] has one entry — the newest
 * delivery for the deposit — kept as a list so the shape matches the white-label platform,
 * which may requeue several.
 */
@Serializable
public data class StaticDepositResendResult(
    @SerialName("uuid") val uuid: String,
    @SerialName("deliveries") val deliveries: List<WebhookResendResult> = emptyList(),
    @SerialName("queued") val queued: Int = 0,
    @SerialName("total") val total: Int = 0,
)
