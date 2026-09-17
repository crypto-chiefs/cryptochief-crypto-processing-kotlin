package com.cryptochief.processing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The `Idempotency-Key` header of every call made in this coroutine context. Add it with
 * [withIdempotencyKey], or to a context directly:
 *
 * ```
 * withContext(IdempotencyKey("payout-2026-09-16-0001")) {
 *     client.payouts.execute(request)
 * }
 * ```
 *
 * The header is part of the HMAC v1 string to sign, so it has to be set before the client signs:
 * an interceptor that adds it afterwards is not covered by the signature and the server answers
 * 401 `INVALID_SIGNATURE`.
 *
 * The platform keeps the value in the billing record of the call, up to 255 bytes. It does not
 * deduplicate payouts — [com.cryptochief.processing.models.ExecutePayoutRequest.orderId] does
 * that.
 *
 * @throws IllegalArgumentException if [value] is empty, holds a character outside printable ASCII
 * (including a tab), or begins or ends with a space. The server trims spaces and tabs before it
 * verifies the signature, so an untrimmed value would be signed in a form the server never sees.
 */
public class IdempotencyKey(
    public val value: String,
) : AbstractCoroutineContextElement(IdempotencyKey) {

    init {
        require(isValid(value)) {
            "idempotency key must be printable ASCII without a leading or trailing space: \"$value\""
        }
    }

    override fun toString(): String = "IdempotencyKey($value)"

    public companion object Key : CoroutineContext.Key<IdempotencyKey> {
        internal fun isValid(value: String): Boolean {
            if (value.isEmpty() || value.first() == ' ' || value.last() == ' ') return false
            return value.all { it in ' '..'~' }
        }
    }
}

/**
 * Runs [block] with [key] as the `Idempotency-Key` of every call it makes.
 *
 * ```
 * val payout = withIdempotencyKey("payout-2026-09-16-0001") {
 *     client.payouts.execute(request)
 * }
 * ```
 *
 * @throws IllegalArgumentException if [key] is not a valid [IdempotencyKey].
 */
public suspend fun <T> withIdempotencyKey(key: String, block: suspend CoroutineScope.() -> T): T =
    withContext(IdempotencyKey(key), block)
