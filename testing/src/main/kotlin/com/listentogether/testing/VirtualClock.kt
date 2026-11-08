package com.listentogether.testing

import com.listentogether.core.common.Clock

/**
 * Deterministic clock for tests and the two-client simulator. All time-dependent seams in the
 * app take [Clock], so tests never sleep on the wall clock.
 */
class VirtualClock(
    private var currentMs: Long = DEFAULT_START_MS,
) : Clock {
    /** Offset applied by fake transports when reporting server time. */
    var serverOffsetMs: Long = 0L

    override fun nowMs(): Long = currentMs
    override fun monotonicMs(): Long = currentMs

    fun serverNowMs(): Long = currentMs + serverOffsetMs

    fun advanceBy(deltaMs: Long) {
        require(deltaMs >= 0) { "Cannot move the virtual clock backwards" }
        currentMs += deltaMs
    }

    fun advanceTo(epochMs: Long) {
        require(epochMs >= currentMs) { "Cannot move the virtual clock backwards" }
        currentMs = epochMs
    }

    companion object {
        const val DEFAULT_START_MS = 1_000_000L
    }
}
