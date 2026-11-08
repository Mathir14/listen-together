package com.listentogether.backend.transport

import com.listentogether.core.common.Clock

/**
 * Fixed-window rate limiter. One instance per connection and concern.
 */
class WindowRateLimiter(
    private val limit: Int,
    private val windowMs: Long,
    private val clock: Clock,
) {
    private var windowStartMs: Long = -1
    private var count: Int = 0

    fun tryAcquire(): Boolean {
        val now = clock.nowMs()
        if (windowStartMs < 0 || now - windowStartMs >= windowMs) {
            windowStartMs = now
            count = 0
        }
        if (count >= limit) return false
        count += 1
        return true
    }
}
