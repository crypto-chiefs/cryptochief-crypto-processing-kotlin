package com.cryptochief.processing

import java.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Tuning for the `waitFor*` polling helpers.
 *
 * [timeout] `null` applies the helper's default: [PAYOUT_TIMEOUT] for `waitForPayout`,
 * [DEFAULT_TIMEOUT] for the others.
 */
public data class PollOptions(
    val interval: Duration = 5.seconds.toJavaDuration(),
    val timeout: Duration? = null,
) {
    public companion object {
        /** Default timeout of `waitForTransaction` and `waitForPayIn`. */
        public val DEFAULT_TIMEOUT: Duration = 10.minutes.toJavaDuration()

        /** Default timeout of `waitForPayout`. */
        public val PAYOUT_TIMEOUT: Duration = 90.minutes.toJavaDuration()
    }
}
