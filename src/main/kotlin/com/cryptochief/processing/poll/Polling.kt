package com.cryptochief.processing.poll

import com.cryptochief.processing.ApiException
import com.cryptochief.processing.CryptoChiefClient
import com.cryptochief.processing.PollOptions
import com.cryptochief.processing.models.PayIn
import com.cryptochief.processing.models.PayoutInfo
import com.cryptochief.processing.models.TransactionInfo
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

public suspend fun CryptoChiefClient.waitForPayout(
    uuid: String,
    options: PollOptions = PollOptions(),
): PayoutInfo = pollUntilTerminal(options,
    fetch = { payouts.info(uuid) },
    isTerminal = { it.isTerminal })

public suspend fun CryptoChiefClient.waitForTransaction(
    uuid: String,
    options: PollOptions = PollOptions(),
): TransactionInfo = pollUntilTerminal(options,
    fetch = { transactions.info(uuid) },
    isTerminal = { it.isTerminal })

public suspend fun CryptoChiefClient.waitForPayIn(
    uuid: String,
    options: PollOptions = PollOptions(),
): PayIn = pollUntilTerminal(options,
    fetch = { payIns.info(uuid) },
    isTerminal = { it.isTerminal })

private suspend inline fun <T> pollUntilTerminal(
    options: PollOptions,
    crossinline fetch: suspend () -> T,
    crossinline isTerminal: (T) -> Boolean,
): T {
    val timeoutMs = options.timeout.toMillis()
    val intervalMs = options.interval.toMillis()
    var last: T? = null
    return try {
        withTimeout(timeoutMs) {
            while (true) {
                val obj = try {
                    fetch()
                } catch (e: ApiException) {
                    if (!e.retryable) throw e
                    null
                }
                if (obj != null) {
                    last = obj
                    if (isTerminal(obj)) return@withTimeout obj
                }
                delay(intervalMs)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    } catch (e: TimeoutCancellationException) {
        last ?: throw e
    }
}
