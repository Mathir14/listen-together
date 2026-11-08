package com.listentogether.core.common

/**
 * Injectable time source. Domain and synchronisation code must never call
 * [System.currentTimeMillis] directly so that timing behaviour is testable.
 */
interface Clock {
    /** Wall-clock UTC epoch milliseconds. Used only when exchanging/timestamps with the server. */
    fun nowMs(): Long

    /** Monotonic milliseconds; unaffected by wall-clock changes. Used for projection and timeouts. */
    fun monotonicMs(): Long
}

class SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun monotonicMs(): Long = System.nanoTime() / 1_000_000L
}
