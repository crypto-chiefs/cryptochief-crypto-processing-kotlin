package com.cryptochief.processing

import java.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Tuning for the `waitFor*` polling helpers. */
public data class PollOptions(
    val interval: Duration = 5.seconds.toJavaDuration(),
    val timeout: Duration = 10.minutes.toJavaDuration(),
)
