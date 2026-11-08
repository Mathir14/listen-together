package com.listentogether.playback

import com.listentogether.core.common.AppError
import com.listentogether.core.common.AppResult
import com.listentogether.core.common.Clock
import com.listentogether.core.model.PlaybackSnapshot
import com.listentogether.core.model.Track
import com.listentogether.music.api.ResolvedMedia
import com.listentogether.music.api.TrackResolver
import com.listentogether.sync.Origin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackApplierTest {
    private class FakeClock(var mono: Long = 1_000_000) : Clock {
        override fun nowMs(): Long = mono
        override fun monotonicMs(): Long = mono
    }

    private class FakeEngine : PlayerEngine {
        val stateFlow = kotlinx.coroutines.flow.MutableStateFlow(PlayerState())
        override val state: kotlinx.coroutines.flow.StateFlow<PlayerState> = stateFlow
        val prepares = mutableListOf<Prepare>()
        val seeks = mutableListOf<Long>()
        var playing = false
        var cleared = 0
        var released = 0
        private var position = 0L
        private var rate = 1f
        private var volume = 1f

        data class Prepare(val trackId: String, val positionMs: Long, val playWhenReady: Boolean)

        override fun prepare(track: Track, media: ResolvedMedia, startPositionMs: Long, playWhenReady: Boolean) {
            prepares += Prepare(track.providerTrackId, startPositionMs, playWhenReady)
            position = startPositionMs
            playing = playWhenReady
            stateFlow.value = PlayerState(trackId = track.providerTrackId, isReady = true, isPlaying = playWhenReady)
        }

        override fun play() {
            playing = true
            stateFlow.value = stateFlow.value.copy(isPlaying = true)
        }

        override fun pause() {
            playing = false
            stateFlow.value = stateFlow.value.copy(isPlaying = false)
        }

        override fun seekTo(positionMs: Long) {
            position = positionMs
            seeks += positionMs
        }

        override fun setRate(rate: Float) {
            this.rate = rate
        }

        override fun setVolume(volume: Float) {
            this.volume = volume
        }

        override fun positionMs(): Long = position
        override fun clear() {
            cleared++
            position = 0
            stateFlow.value = PlayerState()
        }

        override fun release() {
            released++
        }
    }

    private class FakeResolver : TrackResolver {
        val results = ArrayDeque<suspend (Track) -> AppResult<ResolvedMedia>>()
        var defaultResult: (Track) -> AppResult<ResolvedMedia> = { success(it) }
        val calls = mutableListOf<Pair<String, String>>()
        val invalidated = mutableListOf<String>()

        override suspend fun resolve(track: Track): AppResult<ResolvedMedia> {
            calls += "resolve" to track.providerTrackId
            return results.removeFirstOrNull()?.invoke(track) ?: defaultResult(track)
        }

        override suspend fun refresh(track: Track): AppResult<ResolvedMedia> {
            calls += "refresh" to track.providerTrackId
            return results.removeFirstOrNull()?.invoke(track) ?: defaultResult(track)
        }

        override fun invalidate(providerTrackId: String) {
            invalidated += providerTrackId
        }

        companion object {
            fun success(track: Track) = AppResult.Success(
                ResolvedMedia(
                    url = "https://stream/${track.providerTrackId}",
                    mimeType = "audio/mp4",
                    expiresAtEpochMs = Long.MAX_VALUE,
                ),
            )
        }
    }

    private fun TestScope.scope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

    private val trackA = Track(providerTrackId = "a", title = "A", artist = "X", durationMs = 180_000)
    private val trackB = Track(providerTrackId = "b", title = "B", artist = "Y", durationMs = 200_000)

    private fun snapshot(
        track: Track,
        revision: Long = 1,
        positionMs: Long = 10_000,
        updatedAt: Long = 1_000_000,
        isPlaying: Boolean = true,
        sessionId: String = "s1",
        itemId: String = "i1",
    ) = PlaybackSnapshot(
        sessionId = sessionId,
        revision = revision,
        currentItemId = itemId,
        track = track,
        isPlaying = isPlaying,
        positionMs = positionMs,
        updatedAt = updatedAt,
    )

    @Test
    fun `first snapshot resolves and prepares at projected position`() = runTest {
        val engine = FakeEngine()
        val resolver = FakeResolver()
        val clock = FakeClock()
        val applier = PlaybackApplier(resolver, engine, clock, scope(), { clock.nowMs() }, retryDelayMs = 0)

        applier.applyPlayback(snapshot(trackA, positionMs = 10_000, updatedAt = clock.nowMs()), Origin.REMOTE)
        advanceUntilIdle()

        assertEquals(1, engine.prepares.size)
        assertEquals("a", engine.prepares.single().trackId)
        assertEquals(10_000, engine.prepares.single().positionMs)
        assertTrue(engine.prepares.single().playWhenReady)
        assertEquals("a", applier.state.value.trackId)
    }

    @Test
    fun `recent remote seek is applied even below hard-seek threshold`() = runTest {
        val engine = FakeEngine()
        val resolver = FakeResolver()
        val clock = FakeClock()
        val applier = PlaybackApplier(resolver, engine, clock, scope(), { clock.nowMs() }, retryDelayMs = 0)
        applier.applyPlayback(snapshot(trackA, positionMs = 10_000, updatedAt = clock.nowMs()), Origin.REMOTE)
        advanceUntilIdle()
        engine.seeks.clear()

        applier.applyPlayback(snapshot(trackA, revision = 2, positionMs = 10_700, updatedAt = clock.nowMs()), Origin.REMOTE)
        advanceUntilIdle()

        assertEquals(listOf(10_700L), engine.seeks)
    }

    @Test
    fun `old snapshot divergence is left to drift corrector`() = runTest {
        val engine = FakeEngine()
        val resolver = FakeResolver()
        val clock = FakeClock(mono = 1_000_000)
        val applier = PlaybackApplier(resolver, engine, clock, scope(), { clock.nowMs() }, retryDelayMs = 0)
        applier.applyPlayback(snapshot(trackA, positionMs = 10_000, updatedAt = clock.nowMs()), Origin.REMOTE)
        advanceUntilIdle()
        engine.seeks.clear()

        // Stream position is 400 ms off, but the mutation is old: no hard seek.
        applier.applyPlayback(snapshot(trackA, revision = 2, positionMs = 10_400, updatedAt = clock.nowMs() - 10_000), Origin.REMOTE)
        advanceUntilIdle()

        assertTrue(engine.seeks.isEmpty())
    }

    @Test
    fun `expired stream refreshes without changing selected track`() = runTest {
        val engine = FakeEngine()
        val resolver = FakeResolver()
        val clock = FakeClock()
        resolver.results.add {
            AppResult.Success(ResolvedMedia("https://old", "audio/mp4", expiresAtEpochMs = clock.nowMs() - 1))
        }
        val applier = PlaybackApplier(resolver, engine, clock, scope(), { clock.nowMs() }, retryDelayMs = 0)

        applier.applyPlayback(snapshot(trackA, updatedAt = clock.nowMs()), Origin.REMOTE)
        advanceUntilIdle()

        assertEquals(listOf("resolve" to "a", "refresh" to "a"), resolver.calls)
        assertEquals(listOf("a"), resolver.invalidated)
        assertEquals("a", applier.state.value.trackId)
        assertEquals(10_000L, engine.positionMs())
    }

    @Test
    fun `resolution failure is represented and reported once`() = runTest {
        val engine = FakeEngine()
        val resolver = FakeResolver()
        resolver.defaultResult = { AppResult.Failure(AppError("PROVIDER_UNAVAILABLE", "nope")) }
        val clock = FakeClock()
        val applier = PlaybackApplier(resolver, engine, clock, scope(), { clock.nowMs() }, retryDelayMs = 0)

        applier.applyPlayback(snapshot(trackA, updatedAt = clock.nowMs()), Origin.REMOTE)
        advanceUntilIdle()

        assertNull(applier.state.value.trackId)
        assertEquals("PROVIDER_EXTRACTION_FAILED", applier.state.value.failure?.code)
        assertTrue(engine.prepares.isEmpty())
    }

    @Test
    fun `slow resolution for old track cannot overwrite newer selection`() = runTest {
        val engine = FakeEngine()
        val resolver = FakeResolver()
        val clock = FakeClock()
        val gate = CompletableDeferred<Unit>()
        resolver.results.add {
            gate.await()
            FakeResolver.success(it)
        }
        val applierScope = scope()
        val applier = PlaybackApplier(resolver, engine, clock, applierScope, { clock.nowMs() }, retryDelayMs = 0)

        val first = applierScope.async {
            applier.applyPlayback(snapshot(trackA, updatedAt = clock.nowMs()), Origin.REMOTE)
        }
        advanceUntilIdle()
        applier.applyPlayback(snapshot(trackB, itemId = "i2", updatedAt = clock.nowMs()), Origin.REMOTE)
        advanceUntilIdle()
        gate.complete(Unit)
        first.await()
        advanceUntilIdle()

        assertEquals(listOf("b"), engine.prepares.map(FakeEngine.Prepare::trackId))
        assertEquals("b", applier.state.value.trackId)
    }

    @Test
    fun `engine failure retries exactly once`() = runTest {
        val engine = FakeEngine()
        val resolver = FakeResolver()
        val clock = FakeClock()
        val applier = PlaybackApplier(resolver, engine, clock, scope(), { clock.nowMs() }, retryDelayMs = 0)
        applier.applyPlayback(snapshot(trackA, updatedAt = clock.nowMs()), Origin.REMOTE)
        advanceUntilIdle()
        val before = resolver.calls.size

        assertTrue(applier.recoverFromFailure())
        advanceUntilIdle()
        assertFalse(applier.recoverFromFailure())
        assertEquals(before + 1, resolver.calls.size) // one fresh resolve, no expired refresh
    }

    @Test
    fun `sequential track transitions play second track successfully without recreating applier`() = runTest {
        val engine = FakeEngine()
        val resolver = FakeResolver()
        val clock = FakeClock()
        val applier = PlaybackApplier(resolver, engine, clock, scope(), { clock.nowMs() }, retryDelayMs = 0)

        // Play track A
        applier.applyPlayback(snapshot(trackA, revision = 1, positionMs = 0, updatedAt = clock.nowMs()), Origin.REMOTE)
        advanceUntilIdle()
        assertEquals(1, engine.prepares.size)
        assertEquals("a", engine.prepares.last().trackId)
        assertEquals("a", applier.state.value.trackId)

        // Advance to track B (next track in queue)
        applier.applyPlayback(snapshot(trackB, revision = 2, positionMs = 0, updatedAt = clock.nowMs(), itemId = "i2"), Origin.REMOTE)
        advanceUntilIdle()
        assertEquals(2, engine.prepares.size)
        assertEquals("b", engine.prepares.last().trackId)
        assertEquals("b", applier.state.value.trackId)
        assertEquals(2, engine.cleared) // Each track clears previous media cleanly before preparation
    }
}
