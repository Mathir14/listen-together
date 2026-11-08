package com.listentogether.core.common

import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential backoff with symmetric jitter, capped. Pure and deterministic when a [Random]
 * implementation with a fixed seed is injected.
 */
class ExponentialBackoff(
    private val initialMs: Long = 500,
    private val maxMs: Long = 30_000,
    private val multiplier: Double = 2.0,
    private val jitterFraction: Double = 0.20,
    private val random: Random = Random.Default,
) {
    fun delayForAttempt(attempt: Int): Long {
        require(attempt >= 0) { "attempt must be >= 0" }
        val base = initialMs * multiplier.pow(attempt.toDouble())
        val capped = base.coerceAtMost(maxMs.toDouble())
        val jitter = capped * jitterFraction * (random.nextDouble() * 2.0 - 1.0)
        return (capped + jitter).toLong().coerceIn(0, maxMs)
    }
}
