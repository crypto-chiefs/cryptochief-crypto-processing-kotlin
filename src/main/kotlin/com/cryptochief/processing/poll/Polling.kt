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
import java.time.Duration

/**
 * On timeout returns the last snapshot rather than throwing: check [PayoutInfo.isTerminal].
 *
 * Default timeout is 90 minutes ([PollOptions.PAYOUT_TIMEOUT]), also when [options] leaves
 * `timeout` unset: a payout stays `confirm_check` until every source reaches
 * [PayoutInfo.requiredConfirmations].
 */
public suspend fun CryptoChiefClient.waitForPayout(
    uuid: String,
    options: PollOptions = PollOptions(),
): PayoutInfo = pollPayout(options) { payouts.info(uuid) }

/**
 * On timeout returns the last snapshot rather than throwing: check [TransactionInfo.isTerminal].
 *
 * Default timeout is 10 minutes ([PollOptions.DEFAULT_TIMEOUT]).
 */
public suspend fun CryptoChiefClient.waitForTransaction(
    uuid: String,
    options: PollOptions = PollOptions(),
): TransactionInfo = pollTransaction(options) { transactions.info(uuid) }

/**
 * On timeout returns the last snapshot rather than throwing: check [PayIn.isTerminal].
 *
 * Default timeout is 10 minutes ([PollOptions.DEFAULT_TIMEOUT]).
 */
public suspend fun CryptoChiefClient.waitForPayIn(
    uuid: String,
    options: PollOptions = PollOptions(),
): PayIn = pollPayIn(options) { payIns.info(uuid) }

internal suspend fun pollPayout(options: PollOptions, fetch: suspend () -> PayoutInfo): PayoutInfo =
    pollUntilTerminal(options, PollOptions.PAYOUT_TIMEOUT, fetch) { it.isTerminal }

internal suspend fun pollTransaction(options: PollOptions, fetch: suspend () -> TransactionInfo): TransactionInfo =
    pollUntilTerminal(options, PollOptions.DEFAULT_TIMEOUT, fetch) { it.isTerminal }

internal suspend fun pollPayIn(options: PollOptions, fetch: suspend () -> PayIn): PayIn =
    pollUntilTerminal(options, PollOptions.DEFAULT_TIMEOUT, fetch) { it.isTerminal }

private suspend inline fun <T> pollUntilTerminal(
    options: PollOptions,
    defaultTimeout: Duration,
    crossinline fetch: suspend () -> T,
    crossinline isTerminal: (T) -> Boolean,
): T {
    val timeoutMs = (options.timeout ?: defaultTimeout).toMillis()
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
