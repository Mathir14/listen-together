package com.listentogether.playback

import com.listentogether.core.common.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriftCorrectorTest {
    private class FakeClock(var mono: Long = 1_000_000) : Clock {
        override fun nowMs(): Long = mono
        override fun monotonicMs(): Long = mono
    }

    private fun evaluate(
        corrector: DriftCorrector,
        skewMs: Long,
        nowMs: Long = 1_000_000,
        isPlaying: Boolean = true,
        isReady: Boolean = true,
        isBuffering: Boolean = false,
        localBase: Long = 60_000,
    ) = corrector.evaluate(
        localPositionMs = localBase + skewMs,
        authoritativePositionMs = localBase,
        isPlaying = isPlaying,
        isReady = isReady,
        isBuffering = isBuffering,
        nowMs = nowMs,
    )

    @Test
    fun `skew below 300 ms is ignored`() {
        val corrector = DriftCorrector(FakeClock())
        assertNull(evaluate(corrector, 299))
        assertNull(evaluate(corrector, -299))
    }

    @Test
    fun `boundary 300 ms starts a rate nudge`() {
        val corrector = DriftCorrector(FakeClock())
        val fast = assertIs<DriftAction.RateNudge>(evaluate(corrector, 300))
        assertEquals(0.98f, fast.rate)
        assertEquals(-1, fast.direction)

        val slowCorrector = DriftCorrector(FakeClock())
        val slow = assertIs<DriftAction.RateNudge>(evaluate(slowCorrector, -300))
        assertEquals(1.02f, slow.rate)
        assertEquals(1, slow.direction)
    }

    @Test
    fun `1500 ms remains a nudge and 1501 ms hard seeks`() {
        val corrector = DriftCorrector(FakeClock())
        assertIs<DriftAction.RateNudge>(evaluate(corrector, 1_500))

        val other = DriftCorrector(FakeClock())
        val seek = assertIs<DriftAction.HardSeek>(evaluate(other, 1_501))
        assertEquals(60_000, seek.positionMs)
    }

    @Test
    fun `active nudge continues then restores rate when settled`() {
        val clock = FakeClock()
        val corrector = DriftCorrector(clock)
        assertIs<DriftAction.RateNudge>(evaluate(corrector, 500, nowMs = clock.mono))

        // Same large skew: cooldown would normally suppress, but the active nudge continues.
        clock.mono += 2_000
        assertIs<DriftAction.RateNudge>(evaluate(corrector, 400, nowMs = clock.mono))

        clock.mono += 1_000
        assertEquals(DriftAction.RestoreRate, evaluate(corrector, 50, nowMs = clock.mono))
        assertTrue(!corrector.isNudging())
    }

    @Test
    fun `nudge expires after eight seconds`() {
        val clock = FakeClock()
        val corrector = DriftCorrector(clock)
        assertIs<DriftAction.RateNudge>(evaluate(corrector, 600, nowMs = clock.mono))
        clock.mono += 8_001
        assertEquals(DriftAction.RestoreRate, evaluate(corrector, 600, nowMs = clock.mono))
    }

    @Test
    fun `cooldown suppresses corrections except extreme skew`() {
        val clock = FakeClock()
        val corrector = DriftCorrector(clock)
        assertIs<DriftAction.HardSeek>(evaluate(corrector, 2_000, nowMs = clock.mono))

        clock.mono += 1_000
        assertNull(evaluate(corrector, 1_000, nowMs = clock.mono))

        val extreme = assertIs<DriftAction.HardSeek>(evaluate(corrector, 3_500, nowMs = clock.mono))
        assertEquals(60_000, extreme.positionMs)
    }

    @Test
    fun `never corrects while buffering or not ready`() {
        val corrector = DriftCorrector(FakeClock())
        assertNull(evaluate(corrector, 2_000, isReady = false))
        assertNull(evaluate(corrector, 2_000, isBuffering = true))
        assertNull(evaluate(corrector, 2_000, isPlaying = false))
    }

    @Test
    fun `suppresses for one second after a remote seek`() {
        val clock = FakeClock()
        val corrector = DriftCorrector(clock)
        corrector.noteRemoteSeek(clock.mono)
        assertNull(evaluate(corrector, 2_000, nowMs = clock.mono + 900))
        assertIs<DriftAction.HardSeek>(evaluate(corrector, 2_000, nowMs = clock.mono + 1_001))
    }

    @Test
    fun `suppresses until 500 ms after track ready`() {
        val clock = FakeClock()
        val corrector = DriftCorrector(clock)
        corrector.noteTrackChanged(clock.mono)
        corrector.noteTrackReady(clock.mono + 100)
        assertNull(evaluate(corrector, 2_000, nowMs = clock.mono + 550))
        assertIs<DriftAction.HardSeek>(evaluate(corrector, 2_000, nowMs = clock.mono + 601))
    }
}
