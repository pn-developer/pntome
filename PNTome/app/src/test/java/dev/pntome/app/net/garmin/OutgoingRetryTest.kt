package dev.pntome.app.net.garmin

import dev.pntome.app.net.garmin.OutgoingRetry.RetryPolicy
import dev.pntome.app.net.garmin.messages.Ping
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OutgoingRetry] (Req 10.2) and its wiring through a [FakeControlChannel].
 *
 * Covers: success on first try (no delay); success after N transient failures; giving up after
 * the attempt budget and returning the final failure; the exact exponential backoff schedule
 * via an injected delay recorder; and cancellation propagation.
 */
class OutgoingRetryTest {

    /** A delay function that records each requested delay instead of actually sleeping. */
    private class DelayRecorder {
        val delays = mutableListOf<Long>()
        val fn: suspend (Long) -> Unit = { delays += it }
    }

    private val policy = RetryPolicy(
        maxAttempts = 4,
        initialDelayMillis = 100L,
        backoffFactor = 2.0,
        maxDelayMillis = 5_000L,
    )

    // --- success paths -----------------------------------------------------------------------

    @Test
    fun `succeeds on first try without delaying`() = runTest {
        val recorder = DelayRecorder()
        var attempts = 0

        val result = OutgoingRetry.retryWithBackoff(policy, recorder.fn) {
            attempts++
            Result.success(Unit)
        }

        assertTrue(result.isSuccess)
        assertEquals(1, attempts)
        assertTrue("no delays expected on first-try success", recorder.delays.isEmpty())
    }

    @Test
    fun `succeeds after transient failures`() = runTest {
        val recorder = DelayRecorder()
        var attempts = 0

        val result = OutgoingRetry.retryWithBackoff(policy, recorder.fn) {
            attempts++
            if (attempts < 3) Result.failure(RuntimeException("transient #$attempts"))
            else Result.success(Unit)
        }

        assertTrue(result.isSuccess)
        assertEquals(3, attempts)
        // Two retries → two delays following the backoff schedule.
        assertEquals(listOf(100L, 200L), recorder.delays)
    }

    // --- give up -----------------------------------------------------------------------------

    @Test
    fun `gives up after max attempts and returns the final failure`() = runTest {
        val recorder = DelayRecorder()
        var attempts = 0
        val finalError = IllegalStateException("down")

        val result = OutgoingRetry.retryWithBackoff<Unit>(policy, recorder.fn) {
            attempts++
            if (attempts < policy.maxAttempts) Result.failure(RuntimeException("transient"))
            else Result.failure(finalError)
        }

        assertTrue(result.isFailure)
        assertEquals(finalError, result.exceptionOrNull())
        assertEquals(policy.maxAttempts, attempts)
        // 4 attempts → 3 delays (no delay after the final, failing attempt).
        assertEquals(listOf(100L, 200L, 400L), recorder.delays)
    }

    @Test
    fun `single-attempt policy never delays`() = runTest {
        val recorder = DelayRecorder()
        var attempts = 0

        val result = OutgoingRetry.retryWithBackoff<Unit>(RetryPolicy(maxAttempts = 1), recorder.fn) {
            attempts++
            Result.failure(RuntimeException("boom"))
        }

        assertTrue(result.isFailure)
        assertEquals(1, attempts)
        assertTrue(recorder.delays.isEmpty())
    }

    // --- backoff schedule --------------------------------------------------------------------

    @Test
    fun `backoff grows exponentially and is clamped to maxDelay`() = runTest {
        val recorder = DelayRecorder()
        val clampingPolicy = RetryPolicy(
            maxAttempts = 6,
            initialDelayMillis = 1_000L,
            backoffFactor = 3.0,
            maxDelayMillis = 5_000L,
        )

        OutgoingRetry.retryWithBackoff<Unit>(clampingPolicy, recorder.fn) {
            Result.failure(RuntimeException("always"))
        }

        // 6 attempts → 5 delays: 1000, 3000, min(9000,5000)=5000, 5000, 5000.
        assertEquals(listOf(1_000L, 3_000L, 5_000L, 5_000L, 5_000L), recorder.delays)
    }

    // --- cancellation ------------------------------------------------------------------------

    @Test
    fun `respects cancellation during backoff`() = runTest {
        var attempts = 0
        val started = CompletableDeferred<Unit>()

        // Use the real suspending delay so cancellation interrupts the wait.
        val job = async {
            OutgoingRetry.retryWithBackoff<Unit>(
                policy = RetryPolicy(maxAttempts = 5, initialDelayMillis = 10_000L),
                delayFn = { ms ->
                    started.complete(Unit)
                    delay(ms)
                },
            ) {
                attempts++
                Result.failure(RuntimeException("transient"))
            }
        }

        started.await() // first attempt failed; we're now parked in the backoff delay.
        job.cancel()
        job.join()

        assertTrue("job should be cancelled", job.isCancelled)
        assertEquals("should not have retried past the cancelled delay", 1, attempts)
    }

    // --- wiring through FakeControlChannel ---------------------------------------------------

    @Test
    fun `retry wraps a fake channel send that fails then succeeds`() = runTest {
        val recorder = DelayRecorder()
        val channel = FakeControlChannel()
        // Script: two transport failures, then success.
        channel.sendResults.addAll(
            listOf(
                Result.failure(RuntimeException("bt hiccup")),
                Result.failure(RuntimeException("bt hiccup")),
                Result.success(Unit),
            ),
        )
        val message = Ping()

        val result = OutgoingRetry.retryWithBackoff(policy, recorder.fn) {
            channel.send(message)
        }

        assertTrue(result.isSuccess)
        // The same message was attempted three times and all were recorded.
        assertEquals(3, channel.sent.size)
        assertTrue(channel.sent.all { it == message })
        assertEquals(listOf(100L, 200L), recorder.delays)
    }

    @Test
    fun `retry surfaces failure when fake channel stays down`() = runTest {
        val recorder = DelayRecorder()
        val channel = FakeControlChannel(defaultSendResult = Result.failure(RuntimeException("down")))

        val result = OutgoingRetry.retryWithBackoff(policy, recorder.fn) {
            channel.send(Ping())
        }

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
        assertEquals(policy.maxAttempts, channel.sent.size)
        assertFalse(recorder.delays.isEmpty())
    }
}
