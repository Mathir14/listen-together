package com.listentogether.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FoundationTest {
    @Test
    fun `error results never throw and map`() {
        val result: AppResult<Int> = AppResult.Failure(AppError.network())
        assertEquals("NETWORK", result.errorOrNull()?.code)
        val mapped = result.map { it + 1 }
        assertTrue(mapped is AppResult.Failure)
    }

    @Test
    fun `backoff is bounded and jittered within band`() {
        val backoff = ExponentialBackoff(random = kotlin.random.Random(42))
        val first = backoff.delayForAttempt(0)
        assertTrue(first in 400..600, "first delay $first outside ±20% band")
        repeat(20) { attempt ->
            val delayed = backoff.delayForAttempt(attempt)
            assertTrue(delayed in 0..30_000)
        }
        assertEquals(30_000, backoff.delayForAttempt(40).coerceAtMost(30_000))
    }
}
