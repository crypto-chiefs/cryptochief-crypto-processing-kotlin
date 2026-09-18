package com.cryptochief.processing.services

import com.cryptochief.processing.ApiException
import com.cryptochief.processing.IdempotencyKey
import com.cryptochief.processing.http.HttpTransport
import com.cryptochief.processing.models.NativeBuyRequest
import com.cryptochief.processing.models.NativeOrder
import com.cryptochief.processing.models.NativeOrderRequest
import com.cryptochief.processing.models.NativeQuote
import com.cryptochief.processing.models.NativeQuoteRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * Buying the native coin of a network (TRX, ETH, BNB, SOL, TON, ...) from the platform's
 * liquidity. Buys are billed to the same credits balance as the rest of the API; the fee of the
 * platform's own transfer to the receiving address is already priced in.
 */
public class NativeService internal constructor(private val transport: HttpTransport) {

    /**
     * Price of buying [NativeQuoteRequest.amount] of the network's native coin. Free — no paid
     * call, nothing billed. The price is the coins at the market rate plus the fee of the
     * platform's own transfer to the receiving address; [NativeQuote.totalUsd] is the full sale
     * price and [NativeQuote.credits] the exact amount the buy bills.
     *
     * [NativeQuoteRequest.receiveAddress] can be any address — the merchant pays the transfer
     * fee. The quote expires in about 90 seconds and is single-use; pass [NativeQuote.ref] to
     * [buy] as [NativeBuyRequest.quoteRef] to buy at this price.
     */
    public suspend fun quote(request: NativeQuoteRequest): NativeQuote =
        transport.send(
            path = "/v1/native/quote",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = request,
        )

    /**
     * Buy native coin from the platform's liquidity, either by [NativeBuyRequest.network],
     * [NativeBuyRequest.receiveAddress] and [NativeBuyRequest.amount], or by
     * [NativeBuyRequest.quoteRef] of an unexpired quote. Synchronous: by the time this returns,
     * the coins are sent to the receiving address or the refusal is known.
     *
     * The `Idempotency-Key` is required — the server refuses the call with 400 without one, and
     * the SDK refuses it earlier. Set it as for an energy rent:
     *
     * ```
     * val order = withIdempotencyKey("native-2026-09-18-0001") {
     *     client.native.buy(request)
     * }
     * ```
     *
     * The key deduplicates the buy: a repeat with the same key reads the same order back instead
     * of billing twice, so a retry after a network failure or a 502 is safe.
     *
     * The answer is always a [NativeOrder], even on a non-2xx reply: the API answers a business
     * outcome with the order itself as the body, and this method returns it — `delivered` (200),
     * `refused` (502, or 402 when the credits balance is short: the order's
     * [NativeOrder.errorCode] is `INSUFFICIENT_CREDITS` and nothing was billed), or `unresolved`
     * (409, [NativeOrder.needsAttention] — do NOT retry: the coins may already be sent; follow
     * the order with [order] until it settles). On a `refused` order [NativeOrder.error] says why
     * in human text and [NativeOrder.errorCode] carries the machine code.
     *
     * Errors with no order to report (a `{"ok":false,...}` error envelope — 409 `QUOTE_EXPIRED`
     * / `QUOTE_ALREADY_USED`, gateway errors, ...) surface as a regular [ApiException].
     *
     * @throws IllegalArgumentException if no [IdempotencyKey] is set in the coroutine context.
     * @throws com.cryptochief.processing.ApiException
     */
    public suspend fun buy(request: NativeBuyRequest): NativeOrder {
        requireNotNull(currentCoroutineContext()[IdempotencyKey]) {
            "native.buy requires an Idempotency-Key: wrap the call in withIdempotencyKey(\"native-...\") { ... }"
        }
        try {
            return transport.send(
                path = "/v1/native/buy",
                requestSerializer = serializer(),
                responseSerializer = serializer(),
                body = request,
            )
        } catch (err: ApiException) {
            return orderFromError(err) ?: throw err
        }
    }

    /** The order a buy was placed as, read back by its idempotency key. */
    public suspend fun order(key: String): NativeOrder =
        transport.send(
            path = "/v1/native/order",
            requestSerializer = serializer(),
            responseSerializer = serializer(),
            body = NativeOrderRequest(key),
        )

    /**
     * A non-2xx reply whose body is the order itself (`id` + `status` present) is a business
     * outcome, not a transport failure — recover it. Anything else (a `{"ok":false,...}`
     * envelope, a gateway error page) is rethrown by the caller.
     */
    private fun orderFromError(err: ApiException): NativeOrder? {
        val raw = err.raw?.takeIf { it.isNotEmpty() } ?: return null
        val obj = try {
            transport.json.parseToJsonElement(raw) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        if ("id" !in obj || "status" !in obj) return null
        return try {
            transport.json.decodeFromString<NativeOrder>(raw)
        } catch (_: SerializationException) {
            null
        }
    }
}
