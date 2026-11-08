package dev.pntome.app.net.garmin

import kotlinx.coroutines.delay

/**
 * Reusable exponential-backoff retry for outgoing control-channel sends (design §5.3, Req 10.2).
 *
 * The Connect IQ Mobile SDK message channel is routed through Garmin Connect Mobile and can be
 * *temporarily* unavailable (BT hiccup, app backgrounded, momentary service error). Rather than
 * surfacing the first transient failure to the user, [retryWithBackoff] re-attempts a suspend
 * send operation up to [RetryPolicy.maxAttempts] times with exponentially growing delays, and
 * only reports [Result.failure] once the budget is exhausted — at which point the caller can
 * inform the user that the channel is down (Req 10.2).
 *
 * The logic is pure coroutine code with an **injectable delay function** so unit tests can
 * record the backoff schedule and run instantly without sleeping (and so cancellation is
 * honoured through the injected [kotlinx.coroutines.delay] by default).
 */
object OutgoingRetry {

    /**
     * Backoff configuration for [retryWithBackoff].
     *
     * The delay before attempt *n* (1-based, no delay before the first attempt) is:
     * `min(initialDelayMillis * backoffFactor^(n-2), maxDelayMillis)`.
     *
     * @property maxAttempts total number of attempts including the first (must be >= 1).
     * @property initialDelayMillis delay before the *second* attempt (the first retry).
     * @property backoffFactor multiplier applied to the delay after each failed attempt.
     * @property maxDelayMillis upper bound so backoff does not grow without limit.
     */
    data class RetryPolicy(
        val maxAttempts: Int = 4,
        val initialDelayMillis: Long = 200L,
        val backoffFactor: Double = 2.0,
        val maxDelayMillis: Long = 5_000L,
    ) {
        init {
            require(maxAttempts >= 1) { "maxAttempts must be >= 1, was $maxAttempts" }
            require(initialDelayMillis >= 0) { "initialDelayMillis must be >= 0" }
            require(backoffFactor >= 1.0) { "backoffFactor must be >= 1.0" }
            require(maxDelayMillis >= 0) { "maxDelayMillis must be >= 0" }
        }
    }

    /**
     * Invoke [block] (a suspend send returning `Result<T>`) and retry on failure using [policy].
     *
     * Semantics:
     * - Attempt 1 runs immediately (no delay). On [Result.isSuccess] it is returned at once.
     * - On failure, if attempts remain, wait the computed backoff via [delayFn] then retry.
     * - After [RetryPolicy.maxAttempts] failures the **last** failure is returned so the caller
     *   can surface the real cause to the user (Req 10.2).
     * - If [block] throws (rather than returning [Result.failure]), the throw propagates
     *   unchanged — callers are expected to model transport failures as [Result.failure]
     *   (as [ControlChannel.send] does). [kotlinx.coroutines.CancellationException] always
     *   propagates so structured-concurrency cancellation is respected.
     *
     * @param policy the backoff schedule and attempt budget.
     * @param delayFn suspend delay used between attempts; defaults to [kotlinx.coroutines.delay].
     *   Injected so tests can record the schedule and avoid real sleeping while still
     *   honouring cancellation.
     * @param block the send operation to attempt; must return a [Result].
     * @return the first [Result.success], or the final [Result.failure] when the budget runs out.
     */
    suspend fun <T> retryWithBackoff(
        policy: RetryPolicy = RetryPolicy(),
        delayFn: suspend (Long) -> Unit = { delay(it) },
        block: suspend () -> Result<T>,
    ): Result<T> {
        var lastFailure: Result<T>? = null
        var currentDelay = policy.initialDelayMillis

        for (attempt in 1..policy.maxAttempts) {
            val result = block()
            if (result.isSuccess) {
                return result
            }
            lastFailure = result

            // No delay after the final attempt — we're about to give up.
            if (attempt < policy.maxAttempts) {
                delayFn(currentDelay)
                currentDelay = nextDelay(currentDelay, policy)
            }
        }

        // maxAttempts >= 1 guarantees the loop ran at least once, so lastFailure is set.
        return lastFailure!!
    }

    /** Grow the delay by [RetryPolicy.backoffFactor], clamped to [RetryPolicy.maxDelayMillis]. */
    private fun nextDelay(current: Long, policy: RetryPolicy): Long {
        val grown = (current * policy.backoffFactor).toLong()
        return grown.coerceAtMost(policy.maxDelayMillis)
    }
}
