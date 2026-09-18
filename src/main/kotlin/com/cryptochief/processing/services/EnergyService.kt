package com.cryptochief.processing.services

import com.cryptochief.processing.ApiException
import com.cryptochief.processing.IdempotencyKey
import com.cryptochief.processing.http.HttpTransport
import com.cryptochief.processing.models.EnergyOrder
import com.cryptochief.processing.models.EnergyOrderRequest
import com.cryptochief.processing.models.EnergyQuote
import com.cryptochief.processing.models.EnergyQuoteRequest
import com.cryptochief.processing.models.EnergyRentRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** TRON energy rental. Rents are billed to the same credits balance as the rest of the API. */
public class EnergyService internal constructor(private val transport: HttpTransport) {

    /**
     * Price of renting energy for one transfer against burning TRX for it. Free — no paid call,
     * nothing billed.
     *
     * [EnergyQuoteRequest.receiveAddress] is the sender of the planned transfer: the address the
     * rented energy would be delegated to.
     */
    public suspend fun quote(request: EnergyQuoteRequest): EnergyQuote =
        transport.send(
            path = "/v1/energy/quote",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = request,
        )

    /**
     * Rent energy for the sender of a planned transfer. Synchronous: by the time this returns,
     * the energy is delegated to [EnergyRentRequest.receiveAddress] or the refusal is known.
     *
     * The `Idempotency-Key` is required — the server refuses the call with 400 without one, and
     * the SDK refuses it earlier. Set it as for a payout execute:
     *
     * ```
     * val order = withIdempotencyKey("energy-2026-09-18-0001") {
     *     client.energy.rent(request)
     * }
     * ```
     *
     * The key deduplicates the rent: a repeat with the same key reads the same order back instead
     * of billing twice, so a retry after a network failure or a 502 is safe.
     *
     * The answer is always an [EnergyOrder], even on a non-2xx reply: the API answers a business
     * outcome with the order itself as the body, and this method returns it — `delivered` (200),
     * `refused` (502, or 402 when the credits balance is short: the order's
     * [EnergyOrder.errorCode] is `INSUFFICIENT_CREDITS` and nothing was billed), or `unresolved`
     * (409, [EnergyOrder.needsAttention] — do NOT retry: the energy may already be delegated;
     * follow the order with [order] until it settles). On a `refused` order [EnergyOrder.error]
     * says why in human text and [EnergyOrder.errorCode] carries the machine code.
     *
     * Errors with no order to report (a `{"ok":false,...}` error envelope — quote failures,
     * gateway errors, ...) surface as a regular [ApiException].
     *
     * @throws IllegalArgumentException if no [IdempotencyKey] is set in the coroutine context.
     * @throws com.cryptochief.processing.ApiException
     */
    public suspend fun rent(request: EnergyRentRequest): EnergyOrder {
        requireNotNull(currentCoroutineContext()[IdempotencyKey]) {
            "energy.rent requires an Idempotency-Key: wrap the call in withIdempotencyKey(\"energy-...\") { ... }"
        }
        try {
            return transport.send(
                path = "/v1/energy/rent",
                requestSerializer = serializer(),
                responseSerializer = serializer(),
                body = request,
            )
        } catch (err: ApiException) {
            return orderFromError(err) ?: throw err
        }
    }

    /** The order a rent was placed as, read back by its idempotency key. */
    public suspend fun order(key: String): EnergyOrder =
        transport.send(
            path = "/v1/energy/order",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = EnergyOrderRequest(key),
        )

    /**
     * A non-2xx reply whose body is the order itself (`id` + `status` present) is a business
     * outcome, not a transport failure — recover it. Anything else (a `{"ok":false,...}`
     * envelope, a gateway error page) is rethrown by the caller.
     */
    private fun orderFromError(err: ApiException): EnergyOrder? {
        val raw = err.raw?.takeIf { it.isNotEmpty() } ?: return null
        val obj = try {
            transport.json.parseToJsonElement(raw) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        if ("id" !in obj || "status" !in obj) return null
        return try {
            transport.json.decodeFromString<EnergyOrder>(raw)
        } catch (_: SerializationException) {
            null
        }
    }
}
