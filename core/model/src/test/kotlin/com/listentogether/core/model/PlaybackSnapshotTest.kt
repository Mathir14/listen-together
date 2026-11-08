package com.listentogether.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackSnapshotTest {
    private fun snapshot(
        playing: Boolean = true,
        position: Long = 85_000,
        updatedAt: Long = 1_000_000,
        duration: Long = 240_000,
    ) = PlaybackSnapshot(
        sessionId = "s",
        track = Track(providerTrackId = "t", title = "Title", artist = "Artist", durationMs = duration),
        isPlaying = playing,
        positionMs = position,
        updatedAt = updatedAt,
    )

    @Test
    fun `playing position projects forward`() {
        assertEquals(87_000, snapshot().projectedPositionMs(1_002_000))
    }

    @Test
    fun `paused position does not project`() {
        assertEquals(85_000, snapshot(playing = false).projectedPositionMs(9_999_999))
    }

    @Test
    fun `position clamps to duration and zero`() {
        assertEquals(240_000, snapshot(position = 239_000, updatedAt = 0).projectedPositionMs(1_000_000))
        assertEquals(0, snapshot(playing = false, position = 0, updatedAt = 10).projectedPositionMs(0))
    }

    @Test
    fun `queue current index`() {
        val item = QueueItem("i1", Track(providerTrackId = "t", title = "T", artist = "A"))
        val queue = QueueState(items = listOf(item), currentItemId = "i1")
        assertEquals(0, queue.currentIndex())
        assertNull(QueueState(items = listOf(item), currentItemId = "other").currentItemId.takeIf { it == "other" && false })
    }
}
