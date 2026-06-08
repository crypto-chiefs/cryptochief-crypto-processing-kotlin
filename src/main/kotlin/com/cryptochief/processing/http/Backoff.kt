package com.cryptochief.processing.http

import java.time.Duration
import kotlin.random.Random

internal object Backoff {

    fun delay(attempt: Int, base: Duration, max: Duration, random: Random = Random.Default): Duration {
        val safeBase = if (base.isNegative || base.isZero) Duration.ofMillis(200) else base
        val safeMax = if (max.isNegative || max.isZero) Duration.ofSeconds(5) else max
        val shift = (attempt - 1).coerceIn(0, 30)
        val shifted = runCatching { safeBase.multipliedBy(1L shl shift) }
            .getOrDefault(safeMax)
        val ceiling = if (shifted > safeMax || shifted.isNegative) safeMax else shifted
        val ceilingMs = ceiling.toMillis().coerceAtLeast(0)
        val sampled = random.nextLong(0, ceilingMs + 1)
        return Duration.ofMillis(sampled)
    }
}
