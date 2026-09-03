package com.cryptochief.processing.services

import com.cryptochief.processing.http.HttpTransport
import com.cryptochief.processing.models.StaticDepositResendResult
import com.cryptochief.processing.models.UuidRequest
import com.cryptochief.processing.models.WebhookDelivery
import com.cryptochief.processing.models.WebhookResendResult
import kotlinx.serialization.serializer

/**
 * Reads and re-fires the platform's OUTBOUND webhooks — the deliveries made to your
 * endpoint. (Verifying INCOMING webhooks is `com.cryptochief.processing.webhook`.)
 *
 * A delivery is named by the uuid the platform put on it in the `X-Webhook-Delivery`
 * header ([com.cryptochief.processing.webhook.WEBHOOK_DELIVERY_HEADER]). It is the same
 * across every attempt and resend of that delivery — the natural idempotency key for your
 * receiver — and it is the only handle there is: the API has no listing of deliveries, and
 * the payload names the order, not the delivery. Keep it when you log an incoming webhook.
 */
public class WebhooksService internal constructor(private val transport: HttpTransport) {

    /**
     * One delivery by the uuid from its `X-Webhook-Delivery` header. A delivery that is not
     * this project's is `NOT_FOUND`, the same as one that does not exist.
     */
    public suspend fun info(deliveryUuid: String): WebhookDelivery =
        transport.send(
            path = "/v1/webhooks/info",
            requestSerializer = serializer<UuidRequest>(),
            responseSerializer = serializer(),
            body = UuidRequest(deliveryUuid),
        )

    /**
     * Send one delivery to your endpoint again, right now.
     *
     * Refused with an [com.cryptochief.processing.ApiException] whose `code` is:
     * - `DELIVERY_SUPERSEDED` (409) — a newer event exists for the same object. Re-sending
     *   `invoice.in_mempool` after `invoice.paid` would tell your system the order went
     *   backwards, so only the latest event may be resent. Permanent; the newer event's name
     *   is in the message.
     * - `DELIVERY_IN_FLIGHT` (409) — a worker is delivering it right now, or it is already
     *   scheduled for an automatic retry. Try again in a moment.
     * - `RESEND_TOO_SOON` (429) — resent under a minute ago; `Retry-After` is set.
     *
     * A successful manual delivery is billed as `/v1/webhook/resend`; a refused one is not.
     */
    public suspend fun resend(deliveryUuid: String): WebhookResendResult =
        transport.send(
            path = "/v1/webhooks/resend",
            requestSerializer = serializer<UuidRequest>(),
            responseSerializer = serializer(),
            body = UuidRequest(deliveryUuid),
        )

    /**
     * Re-fire the NEWEST webhook of one static deposit, named by the deposit's own uuid — for
     * when you have the deposit and not the delivery. Older events of the deposit are
     * superseded and are not resent.
     *
     * Refused with `NO_DELIVERIES` (409) when the deposit is yours but no webhook was ever
     * queued for it: it arrived on a static wallet with no `callback_url`. The per-delivery
     * refusals of [resend] apply as well.
     */
    public suspend fun resendStaticDeposit(depositUuid: String): StaticDepositResendResult =
        transport.send(
            path = "/v1/static-deposits/resend",
            requestSerializer = serializer<UuidRequest>(),
            responseSerializer = serializer(),
            body = UuidRequest(depositUuid),
        )
}
