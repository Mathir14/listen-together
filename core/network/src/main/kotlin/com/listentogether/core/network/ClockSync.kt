package com.listentogether.core.network

import com.listentogether.core.common.Clock

data class ClockSample(val rttMs: Long, val offsetMs: Long)

/**
 * NTP-style clock synchronisation. [t0] and [t2] are client monotonic timestamps; [t1] is the
 * server epoch timestamp. The resulting offset is added to monotonic time to estimate server
 * epoch time without depending on wall-clock changes.
 */
class ClockSync(
    private val clock: Clock,
    private val maxSamples: Int = 5,
) {
    private val samples = ArrayDeque<ClockSample>()
    private var best: ClockSample? = null

    var offsetMs: Long = clock.nowMs() - clock.monotonicMs()
        private set

    fun computeOffset(t0: Long, t1: Long, t2: Long): Long = t1 - (t0 + t2) / 2

    fun addSample(t0: Long, t1: Long, t2: Long): ClockSample {
        val rtt = (t2 - t0).coerceAtLeast(0)
        val sample = ClockSample(rttMs = rtt, offsetMs = computeOffset(t0, t1, t2))
        samples.addLast(sample)
        while (samples.size > maxSamples) samples.removeFirst()
        val bestSample = samples.minByOrNull { it.rttMs } ?: sample
        best = bestSample
        offsetMs = bestSample.offsetMs
        return sample
    }

    fun bestSample(): ClockSample? = best

    fun serverNowMs(): Long = clock.monotonicMs() + offsetMs

    fun reset() {
        samples.clear()
        best = null
        offsetMs = clock.nowMs() - clock.monotonicMs()
    }
}
