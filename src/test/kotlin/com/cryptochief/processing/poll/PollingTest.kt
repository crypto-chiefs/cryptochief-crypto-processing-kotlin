package com.cryptochief.processing.poll

import com.cryptochief.processing.Chain
import com.cryptochief.processing.PollOptions
import com.cryptochief.processing.models.PayIn
import com.cryptochief.processing.models.PayInStatus
import com.cryptochief.processing.models.PayoutInfo
import com.cryptochief.processing.models.PayoutStatus
import com.cryptochief.processing.models.TransactionInfo
import com.cryptochief.processing.models.TxStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class PollingTest {

    private val intervalOnly = PollOptions(interval = Duration.ofSeconds(10))

    @Test
    fun `PollOptions leaves the timeout to the helper by default`() {
        assertNull(PollOptions().timeout)
        assertNull(intervalOnly.timeout)
    }

    @Test
    fun `waitForPayout applies 90 minutes when options set only the interval`() = runTest {
        val last = pollPayout(intervalOnly) { PayoutInfo(uuid = "p-1", status = PayoutStatus.CONFIRM_CHECK) }

        assertEquals(Duration.ofMinutes(90).toMillis(), testScheduler.currentTime)
        assertFalse(last.isTerminal)
    }

    @Test
    fun `waitForPayout applies 90 minutes with default options`() = runTest {
        pollPayout(PollOptions()) { PayoutInfo(uuid = "p-1", status = PayoutStatus.CONFIRM_CHECK) }

        assertEquals(Duration.ofMinutes(90).toMillis(), testScheduler.currentTime)
    }

    @Test
    fun `waitForPayout honours an explicit timeout`() = runTest {
        pollPayout(PollOptions(interval = Duration.ofSeconds(10), timeout = Duration.ofMinutes(15))) {
            PayoutInfo(uuid = "p-1", status = PayoutStatus.CONFIRM_CHECK)
        }

        assertEquals(Duration.ofMinutes(15).toMillis(), testScheduler.currentTime)
    }

    @Test
    fun `waitForTransaction applies 10 minutes when options set only the interval`() = runTest {
        val last = pollTransaction(intervalOnly) {
            TransactionInfo(uuid = "t-1", status = TxStatus.BROADCASTED, network = Chain.ETH_MAINNET, fromAddress = "0xfrom")
        }

        assertEquals(Duration.ofMinutes(10).toMillis(), testScheduler.currentTime)
        assertFalse(last.isTerminal)
    }

    @Test
    fun `waitForPayIn applies 10 minutes when options set only the interval`() = runTest {
        val last = pollPayIn(intervalOnly) {
            PayIn(uuid = "o-1", orderId = "order-1", status = PayInStatus.PENDING)
        }

        assertEquals(Duration.ofMinutes(10).toMillis(), testScheduler.currentTime)
        assertFalse(last.isTerminal)
    }

    @Test
    fun `a terminal snapshot returns before the timeout`() = runTest {
        var calls = 0
        val last = pollPayout(intervalOnly) {
            calls++
            PayoutInfo(uuid = "p-1", status = if (calls < 3) PayoutStatus.CONFIRM_CHECK else PayoutStatus.PAID)
        }

        assertEquals(PayoutStatus.PAID, last.status)
        assertEquals(Duration.ofSeconds(20).toMillis(), testScheduler.currentTime)
    }
}
