package com.listentogether.ui

import com.listentogether.audio.AudioArbiter
import com.listentogether.audio.AudioPolicy
import com.listentogether.communication.ChatRepository
import com.listentogether.communication.TtsCoordinator
import com.listentogether.core.common.AppResult
import com.listentogether.core.data.AppSettings
import com.listentogether.core.data.SettingsRepository
import com.listentogether.core.model.ChatMessage
import com.listentogether.core.model.ConnectionState
import com.listentogether.core.model.PlaybackSnapshot
import com.listentogether.core.model.QueueItem
import com.listentogether.core.model.QueueState
import com.listentogether.core.model.RepeatMode
import com.listentogether.core.model.Role
import com.listentogether.core.model.RoomCredentials
import com.listentogether.core.model.RoomStatus
import com.listentogether.core.model.RoomView
import com.listentogether.core.model.SessionStatus
import com.listentogether.core.model.SessionView
import com.listentogether.core.model.Track
import com.listentogether.core.model.VoiceState
import com.listentogether.music.api.MusicCatalog
import com.listentogether.sync.MutableSessionStateStore
import com.listentogether.sync.SessionState
import com.listentogether.sync.SyncCommands
import com.listentogether.testing.FakeMusicCatalog
import com.listentogether.testing.FakeSessionStateStore
import com.listentogether.testing.InMemorySettingsRepository
import com.listentogether.testing.RecordingFocusController
import com.listentogether.testing.RecordingRouteController
import com.listentogether.ui.chat.ChatViewModel
import com.listentogether.ui.debug.DebugViewModel
import com.listentogether.ui.home.HomeViewModel
import com.listentogether.ui.nowplaying.NowPlayingViewModel
import com.listentogether.ui.queue.QueueViewModel
import com.listentogether.ui.room.RoomViewModel
import com.listentogether.ui.search.SearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UiViewModelsTest {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeConnector : SessionConnector {
        val connectionFlow = MutableStateFlow(ConnectionState.DISCONNECTED)
        val activeFlow = MutableStateFlow(false)
        var lastConnectedCredentials: RoomCredentials? = null
        var lastRecovery: Boolean = false
        var createResult: AppResult<RoomCredentials> = AppResult.Success(
            RoomCredentials("room-created-1", "user-speaker", "token-created-1", Role.SPEAKER, "secret-created-1")
        )
        var joinResult: AppResult<RoomCredentials> = AppResult.Success(
            RoomCredentials("room-join-1", "user-listener", "token-join-1", Role.LISTENER)
        )
        var savedCredentials: RoomCredentials? = RoomCredentials(
            "room-saved", "user-speaker", "token-saved", Role.SPEAKER
        )

        override val connectionState: StateFlow<ConnectionState> = connectionFlow
        override val isSessionActive: StateFlow<Boolean> = activeFlow

        override fun connect(credentials: RoomCredentials, recovery: Boolean) {
            lastConnectedCredentials = credentials
            lastRecovery = recovery
            connectionFlow.value = ConnectionState.CONNECTED
            activeFlow.value = true
        }

        override suspend fun createRoom(role: Role): AppResult<RoomCredentials> {
            val res = createResult
            if (res is AppResult.Success) {
                val creds = res.value.copy(role = role)
                connect(creds)
                return AppResult.Success(creds)
            }
            return res
        }

        override suspend fun joinRoom(roomId: String, inviteSecret: String): AppResult<RoomCredentials> {
            val res = joinResult
            if (res is AppResult.Success) {
                val creds = res.value.copy(roomId = roomId)
                connect(creds)
                return AppResult.Success(creds)
            }
            return res
        }

        override suspend fun loadSavedCredentials(): RoomCredentials? = savedCredentials
    }

    private class FakeVoiceCtrl : VoiceController {
        val voiceFlow = MutableStateFlow(VoiceState.IDLE)
        val micMutedFlow = MutableStateFlow(true)
        val micBlockedFlow = MutableStateFlow(false)
        val speakerMutedFlow = MutableStateFlow(false)

        override val voiceState: StateFlow<VoiceState> = voiceFlow
        override val micMuted: StateFlow<Boolean> = micMutedFlow
        override val micBlocked: StateFlow<Boolean> = micBlockedFlow
        override val speakerMuted: StateFlow<Boolean> = speakerMutedFlow

        override fun setMicMuted(muted: Boolean) {
            micMutedFlow.value = muted
        }

        override fun setSpeakerMuted(muted: Boolean) {
            speakerMutedFlow.value = muted
        }
    }

    private class FakeTtsCtrl : TtsController {
        var enabledValue: Boolean = true
        override fun setTtsEnabled(enabled: Boolean) {
            enabledValue = enabled
        }
    }

    private class RecordingSyncCommands : SyncCommands {
        var playCalled = false
        var pauseCalled = false
        var lastSeekMs: Long? = null
        var lastSelectedTrack: Track? = null
        var nextCalled = false
        var previousCalled = false
        var lastAddedTrack: Track? = null
        var lastRemovedItemId: String? = null
        var lastReorderIds: List<String>? = null
        var clearQueueCalled = false
        var lastRepeat: RepeatMode? = null
        var lastShuffle: Boolean? = null
        var leaveCalled = false

        override fun play() { playCalled = true }
        override fun pause() { pauseCalled = true }
        override fun seek(positionMs: Long) { lastSeekMs = positionMs }
        override fun selectTrack(track: Track) { lastSelectedTrack = track }
        override fun next() { nextCalled = true }
        override fun previous() { previousCalled = true }
        override fun addToQueue(track: Track, index: Int?) { lastAddedTrack = track }
        override fun removeFromQueue(itemId: String) { lastRemovedItemId = itemId }
        override fun reorderQueue(itemIds: List<String>) { lastReorderIds = itemIds }
        override fun clearQueue() { clearQueueCalled = true }
        override fun setRepeat(mode: RepeatMode) { lastRepeat = mode }
        override fun setShuffle(enabled: Boolean) { lastShuffle = enabled }
        override fun reportPlaybackIssue(trackId: String, state: com.listentogether.core.model.PlaybackIssueState, detail: String?) {}
        override fun leave() { leaveCalled = true }
    }

    @Test
    fun `HomeViewModel create, join, and rejoin`() = runTest {
        val connector = FakeConnector()
        val settings = InMemorySettingsRepository(
            AppSettings(lastRoomId = "room-saved", lastRole = Role.SPEAKER.name),
        )
        val vm = HomeViewModel(connector, settings)
        runCurrent()

        assertEquals("room-saved", vm.uiState.value.lastRoomId)

        // Create room
        vm.onRoleSelected(Role.LISTENER)
        var createdRoomId: String? = null
        vm.createRoom { createdRoomId = it }
        runCurrent()
        assertNotNull(createdRoomId)
        assertEquals(Role.LISTENER, connector.lastConnectedCredentials?.role)
        assertFalse(connector.lastRecovery)

        // Join room with empty input fails
        vm.onRoomIdChanged("")
        vm.joinRoom()
        runCurrent()
        assertNotNull(vm.uiState.value.errorMessage)

        // Join room missing secret fails
        vm.onRoomIdChanged("room-join-1")
        vm.onInviteSecretChanged("")
        vm.joinRoom()
        runCurrent()
        assertNotNull(vm.uiState.value.errorMessage)

        // Join room with valid input
        vm.onInviteSecretChanged("secret-valid")
        vm.joinRoom()
        runCurrent()
        assertEquals("room-join-1", connector.lastConnectedCredentials?.roomId)

        // Rejoin last room
        vm.rejoinLastRoom()
        runCurrent()
        assertEquals("room-saved", connector.lastConnectedCredentials?.roomId)
        assertTrue(connector.lastRecovery)
    }

    @Test
    fun `SearchViewModel search, play, and add to queue`() = runTest {
        val trackA = Track("youtube_music", "id-1", "Song 1", "Artist 1", durationMs = 180_000)
        val catalog = FakeMusicCatalog(initialTracks = listOf(trackA))
        val commands = RecordingSyncCommands()
        val vm = SearchViewModel(catalog, commands)

        vm.onQueryChanged("Song")
        vm.search()
        runCurrent()

        assertEquals(1, vm.uiState.value.results.size)
        assertEquals("Song 1", vm.uiState.value.results[0].title)

        // Play track
        vm.playTrack(trackA)
        assertEquals(trackA, commands.lastSelectedTrack)

        // Add to queue
        vm.addToQueue(trackA)
        assertEquals(trackA, commands.lastAddedTrack)
        assertEquals("Song 1", vm.uiState.value.addedTrackTitle)
    }

    @Test
    fun `NowPlayingViewModel controls and 1 Hz position polling`() = runTest {
        val trackA = Track("youtube_music", "id-1", "Song 1", "Artist 1", durationMs = 180_000)
        val playback = PlaybackSnapshot(
            sessionId = "session-1",
            revision = 1,
            track = trackA,
            isPlaying = true,
            positionMs = 10_000,
            updatedAt = 1000L,
        )
        val state = SessionState(playback = playback, role = Role.SPEAKER)
        val store = FakeSessionStateStore(state) { 1500L } // 500 ms elapsed
        val commands = RecordingSyncCommands()
        val voiceCtrl = FakeVoiceCtrl()
        val vm = NowPlayingViewModel(store, commands, voiceCtrl)
        runCurrent()

        assertEquals("Song 1", vm.uiState.value.track?.title)
        assertTrue(vm.uiState.value.isPlaying)

        // Check 1 Hz position update
        vm.updatePosition()
        assertEquals(10_500L, vm.uiState.value.positionMs)

        // Controls
        vm.togglePlayPause()
        assertTrue(commands.pauseCalled)

        vm.seekTo(25_000L)
        assertEquals(25_000L, commands.lastSeekMs)

        vm.next()
        assertTrue(commands.nextCalled)

        vm.previous()
        assertTrue(commands.previousCalled)

        vm.toggleRepeat()
        assertEquals(RepeatMode.ALL, commands.lastRepeat)

        vm.toggleShuffle()
        assertEquals(true, commands.lastShuffle)

        vm.toggleMic()
        assertFalse(voiceCtrl.micMutedFlow.value)
    }

    @Test
    fun `QueueViewModel reorder and remove`() = runTest {
        val trackA = Track("youtube_music", "id-1", "Song 1", "Artist 1", durationMs = 180_000)
        val trackB = Track("youtube_music", "id-2", "Song 2", "Artist 2", durationMs = 200_000)
        val items = listOf(
            QueueItem("item-1", trackA),
            QueueItem("item-2", trackB),
        )
        val queue = QueueState(items = items, currentItemId = "item-1")
        val store = FakeSessionStateStore(SessionState(queue = queue))
        val commands = RecordingSyncCommands()
        val vm = QueueViewModel(store, commands)
        runCurrent()

        assertEquals(2, vm.uiState.value.items.size)
        assertEquals("item-1", vm.uiState.value.currentItemId)

        // Move down
        vm.moveDown(0)
        assertEquals(listOf("item-2", "item-1"), commands.lastReorderIds)

        // Remove
        vm.removeItem("item-1")
        assertEquals("item-1", commands.lastRemovedItemId)

        // Clear
        vm.clearQueue()
        assertTrue(commands.clearQueueCalled)
    }

    @Test
    fun `RoomViewModel leave and settings`() = runTest {
        val store = FakeSessionStateStore(
            SessionState(
                room = RoomView("room-123", emptyList(), activeSessionId = "session-1", status = RoomStatus.ACTIVE),
                session = SessionView("session-1", "room-123", SessionStatus.ACTIVE, 1000L, emptyList()),
                role = Role.SPEAKER,
            ),
        )
        val commands = RecordingSyncCommands()
        val settings = InMemorySettingsRepository(AppSettings(ttsEnabled = true, ttsSpeechRate = 1.0f))
        val ttsCtrl = FakeTtsCtrl()
        val vm = RoomViewModel(store, commands, settings, ttsCtrl)
        runCurrent()

        assertEquals("room-123", vm.uiState.value.roomId)
        assertEquals(Role.SPEAKER, vm.uiState.value.role)

        vm.setTtsEnabled(false)
        assertFalse(ttsCtrl.enabledValue)

        vm.setTtsSpeechRate(1.5f)
        runCurrent()
        assertEquals(1.5f, settings.currentSettings.ttsSpeechRate)

        vm.leaveRoom()
        assertTrue(commands.leaveCalled)
    }

    @Test
    fun `DebugViewModel observes diagnostic state`() = runTest {
        val store = FakeSessionStateStore(
            SessionState(
                room = RoomView("room-dbg", emptyList(), activeSessionId = "session-dbg", status = RoomStatus.ACTIVE),
                revision = 42L,
                role = Role.SPEAKER,
            ),
        )
        val voiceCtrl = FakeVoiceCtrl()
        val arbiter = AudioArbiter(
            focusController = RecordingFocusController(),
            routeController = RecordingRouteController(),
            policy = AudioPolicy(),
            scope = backgroundScope,
        )
        val vm = DebugViewModel(store, voiceCtrl, arbiter)
        runCurrent()

        assertEquals("room-dbg", vm.uiState.value.roomId)
        assertEquals(42L, vm.uiState.value.revision)
        assertEquals(VoiceState.IDLE, vm.uiState.value.voiceState)
    }
}
