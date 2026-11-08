package com.listentogether.core.network

import com.listentogether.core.common.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClockSyncTest {
    private class FakeClock(var wall: Long = 1_000_000, var mono: Long = 5_000) : Clock {
        override fun nowMs(): Long = wall
        override fun monotonicMs(): Long = mono
    }

    @Test
    fun `offset is symmetric around the server timestamp`() {
        val sync = ClockSync(FakeClock())
        assertEquals(0, sync.computeOffset(t0 = 1_000, t1 = 1_100, t2 = 1_200))
        assertEquals(200, sync.computeOffset(t0 = 1_000, t1 = 1_300, t2 = 1_200))
        assertEquals(-200, sync.computeOffset(t0 = 1_000, t1 = 900, t2 = 1_200))
    }

    @Test
    fun `lowest rtt sample wins regardless of insertion order`() {
        val sync = ClockSync(FakeClock())
        sync.addSample(t0 = 0, t1 = 900, t2 = 1_000)
        sync.addSample(t0 = 0, t1 = 120, t2 = 200)
        assertEquals(20, sync.offsetMs)
        assertEquals(200, sync.bestSample()?.rttMs)
    }

    @Test
    fun `samples are capped and stale samples are evicted`() {
        val sync = ClockSync(FakeClock(), maxSamples = 2)
        sync.addSample(t0 = 0, t1 = 50, t2 = 100)
        sync.addSample(t0 = 0, t1 = 500, t2 = 800)
        sync.addSample(t0 = 0, t1 = 1_450, t2 = 900)
        assertEquals(800, sync.bestSample()?.rttMs)
        assertEquals(100, sync.offsetMs)
    }

    @Test
    fun `server now tracks monotonic time and ignores wall clock jumps`() {
        val clock = FakeClock()
        val sync = ClockSync(clock)
        sync.addSample(t0 = clock.mono, t1 = 1_000_100, t2 = clock.mono + 200)

        val before = sync.serverNowMs()
        clock.mono += 5_000
        clock.wall += 60 * 60 * 1000
        assertEquals(before + 5_000, sync.serverNowMs())
    }

    @Test
    fun `reset restores wall minus monotonic baseline`() {
        val clock = FakeClock()
        val sync = ClockSync(clock)
        sync.addSample(t0 = 0, t1 = 10_000, t2 = 100)
        sync.reset()
        assertNull(sync.bestSample())
        assertEquals(clock.nowMs() - clock.monotonicMs(), sync.offsetMs)
    }

    @Test
    fun `config maps base url to websocket url`() {
        assertEquals(
            "wss://listen-together-backend-2o5j.onrender.com/ws",
            NetworkConfig("https://listen-together-backend-2o5j.onrender.com/").webSocketUrl,
        )
        assertEquals(
            "ws://localhost:8080/ws",
            NetworkConfig("http://localhost:8080").webSocketUrl,
        )
    }
}
