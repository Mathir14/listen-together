package com.listentogether

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.media3.exoplayer.ExoPlayer
import com.listentogether.audio.AndroidFocusController
import com.listentogether.audio.AndroidRouteController
import com.listentogether.audio.AudioArbiter
import com.listentogether.audio.AudioPolicy
import com.listentogether.communication.AndroidAnnouncer
import com.listentogether.communication.ChatRepository
import com.listentogether.communication.TtsCoordinator
import com.listentogether.core.common.AppResult
import com.listentogether.core.common.Clock
import com.listentogether.core.common.DefaultDispatchersProvider
import com.listentogether.core.common.DispatchersProvider
import com.listentogether.core.common.SystemClock
import com.listentogether.core.common.logging.Logger
import com.listentogether.core.common.logging.NoopLogger
import com.listentogether.core.data.CredentialStore
import com.listentogether.core.data.DataStoreCredentialStore
import com.listentogether.core.data.DataStoreSessionSnapshotCache
import com.listentogether.core.data.DataStoreSettingsRepository
import com.listentogether.core.data.DataStoreTtsCursorStore
import com.listentogether.core.data.KeystoreSecretVault
import com.listentogether.core.data.SecretVault
import com.listentogether.core.data.SessionSnapshotCache
import com.listentogether.core.data.SettingsRepository
import com.listentogether.core.data.TtsCursorStore
import com.listentogether.core.data.listenTogetherDataStore
import com.listentogether.core.model.ConnectionState
import com.listentogether.core.model.Role
import com.listentogether.core.model.RoomCredentials
import com.listentogether.core.model.VoiceState
import com.listentogether.core.network.KtorRoomApi
import com.listentogether.core.network.NetworkConfig
import com.listentogether.core.network.ProtocolChannel
import com.listentogether.core.network.RoomApi
import com.listentogether.core.network.RoomSocket
import com.listentogether.music.api.MusicCatalog
import com.listentogether.music.api.TrackResolver
import com.listentogether.music.youtube.YouTubeMusicCatalog
import com.listentogether.music.youtube.YouTubeMusicResolver
import com.listentogether.playback.ExoPlayerEngine
import com.listentogether.playback.PlaybackApplier
import com.listentogether.playback.RoomPlayer
import com.listentogether.service.SessionRuntime
import com.listentogether.service.SessionRuntimeHolder
import com.listentogether.sync.MutableSessionStateStore
import com.listentogether.sync.RoomSocketSessionTransport
import com.listentogether.sync.SessionTransport
import com.listentogether.ui.SessionConnector
import com.listentogether.ui.TtsController
import com.listentogether.ui.VoiceController
import com.listentogether.ui.chat.ChatViewModel
import com.listentogether.ui.debug.DebugViewModel
import com.listentogether.ui.home.HomeViewModel
import com.listentogether.ui.nowplaying.NowPlayingViewModel
import com.listentogether.ui.queue.QueueViewModel
import com.listentogether.ui.room.RoomViewModel
import com.listentogether.ui.search.SearchViewModel
import com.listentogether.voice.ProtocolChannelVoiceSignaling
import com.listentogether.voice.VoiceSession
import com.listentogether.voice.VoiceTransport
import com.listentogether.voice.WebRtcVoiceTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Dependency graph and composition root for the Listen Together application (architecture §3.1).
 * Owns process-scoped singletons and exposes capability facades to the UI layer.
 */
class AppGraph(
    val context: Context,
    val clock: Clock = SystemClock(),
    val dispatchers: DispatchersProvider = DefaultDispatchersProvider(),
    val logger: Logger = NoopLogger,
    val networkConfig: NetworkConfig = NetworkConfig(NetworkConfig.DEFAULT_BASE_URL),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : AutoCloseable {

    // Persistence
    val dataStore: DataStore<Preferences> = context.listenTogetherDataStore
    val secretVault: SecretVault = runCatching { KeystoreSecretVault() }.getOrElse {
        // Fallback for JVM tests or hardware keystore unavailability
        object : SecretVault {
            override fun encrypt(plaintext: String): String = plaintext
            override fun decrypt(ciphertext: String): String = ciphertext
        }
    }
    val credentialStore: CredentialStore = DataStoreCredentialStore(dataStore, secretVault)
    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(dataStore)
    val ttsCursorStore: TtsCursorStore = DataStoreTtsCursorStore(dataStore)
    val snapshotCache: SessionSnapshotCache = DataStoreSessionSnapshotCache(dataStore)

    // Networking & Protocol
    val roomApi: RoomApi = KtorRoomApi(networkConfig)
    val protocolChannel: ProtocolChannel = RoomSocket(
        config = networkConfig,
        clock = clock,
        scope = scope,
        logger = logger,
    )
    val sessionTransport: SessionTransport = RoomSocketSessionTransport(
        channel = protocolChannel,
        scope = scope,
    )

    // Audio Arbitration
    lateinit var audioArbiter: AudioArbiter
        private set

    init {
        val focusController = AndroidFocusController(context) { change ->
            if (::audioArbiter.isInitialized) {
                audioArbiter.onFocusChange(change)
            }
        }
        val routeController = AndroidRouteController(context)
        audioArbiter = AudioArbiter(
            focusController = focusController,
            routeController = routeController,
            policy = AudioPolicy(),
            scope = scope,
        )
    }

    // Media & Playback
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()
    val playerEngine: ExoPlayerEngine = ExoPlayerEngine(exoPlayer)
    val musicCatalog: MusicCatalog = YouTubeMusicCatalog()
    val trackResolver: TrackResolver = YouTubeMusicResolver(clock = clock)

    val playbackApplier: PlaybackApplier = PlaybackApplier(
        resolver = trackResolver,
        engine = playerEngine,
        clock = clock,
        scope = scope,
        serverNowMs = { sessionTransport.serverNowMs() },
        logger = logger,
    )

    val roomPlayer: RoomPlayer = RoomPlayer(
        applier = playbackApplier,
        engine = playerEngine,
        clock = clock,
        scope = scope,
        serverNowMs = { sessionTransport.serverNowMs() },
        audioArbiter = audioArbiter,
        logger = logger,
    ).apply { start() }

    // Session State Store
    val sessionStateStore: MutableSessionStateStore = MutableSessionStateStore {
        sessionTransport.serverNowMs()
    }

    // Communication (Chat & TTS)
    val chatRepository: ChatRepository = ChatRepository(
        transport = sessionTransport,
        stateStore = sessionStateStore,
        scope = scope,
    )

    val announcer: AndroidAnnouncer = AndroidAnnouncer(context)
    val ttsCoordinator: TtsCoordinator = TtsCoordinator(
        chat = chatRepository,
        announcer = announcer,
        audio = audioArbiter,
        stateStore = sessionStateStore,
        settingsRepository = settingsRepository,
        cursorStore = ttsCursorStore,
        scope = scope,
        logger = logger,
    )

    // Voice
    val voiceSignaling: ProtocolChannelVoiceSignaling = ProtocolChannelVoiceSignaling(
        channel = protocolChannel,
        roomId = { sessionRuntime.state.value.room?.roomId },
        sessionId = { sessionRuntime.state.value.session?.sessionId },
        scope = scope,
        logger = logger,
    )

    val voiceTransport: VoiceTransport = WebRtcVoiceTransport(context, logger)

    val voiceFactory: () -> VoiceSession = {
        VoiceSession(
            transport = voiceTransport,
            signaling = voiceSignaling,
            turnCredentials = {
                val creds = credentialStore.load()
                val roomId = creds?.roomId ?: sessionRuntime.state.value.room?.roomId.orEmpty()
                val token = creds?.participantToken.orEmpty()
                roomApi.turnCredentials(roomId, token)
            },
            clock = clock,
            scope = scope,
            logger = logger,
        )
    }

    // Session Runtime (process-scoped host)
    val sessionRuntime: SessionRuntime = SessionRuntime(
        scope = scope,
        settingsRepository = settingsRepository,
        transport = sessionTransport,
        probe = roomPlayer,
        sink = roomPlayer,
        audioArbiter = audioArbiter,
        chat = chatRepository,
        tts = ttsCoordinator,
        voiceFactory = voiceFactory,
        micPermissionGranted = {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        },
        mediaPlayer = playerEngine,
        logger = logger,
        store = sessionStateStore,
    )

    // UI Facades
    val sessionConnector: SessionConnector = object : SessionConnector {
        override val connectionState: StateFlow<ConnectionState> =
            sessionRuntime.state.map { it.connection }
                .stateIn(scope, SharingStarted.Eagerly, ConnectionState.DISCONNECTED)

        override val isSessionActive: StateFlow<Boolean> =
            sessionRuntime.state.map { it.isSessionActive }
                .stateIn(scope, SharingStarted.Eagerly, false)

        override fun connect(credentials: RoomCredentials, recovery: Boolean) {
            sessionRuntime.connect(credentials, recovery)
        }

        override suspend fun createRoom(role: Role): AppResult<RoomCredentials> {
            val result = roomApi.createRoom(role)
            if (result is AppResult.Success) {
                val creds = result.value
                credentialStore.save(creds)
                connect(creds)
            }
            return result
        }

        override suspend fun joinRoom(roomId: String, inviteSecret: String): AppResult<RoomCredentials> {
            val result = roomApi.joinRoom(roomId, inviteSecret)
            if (result is AppResult.Success) {
                val creds = result.value
                credentialStore.save(creds)
                connect(creds)
            }
            return result
        }

        override suspend fun loadSavedCredentials(): RoomCredentials? =
            credentialStore.load()
    }

    val voiceController: VoiceController = object : VoiceController {
        override val voiceState: StateFlow<VoiceState> = sessionRuntime.voiceState
        override val micMuted: StateFlow<Boolean> = sessionRuntime.micMuted
        override val micBlocked: StateFlow<Boolean> = sessionRuntime.micBlocked
        override val speakerMuted: StateFlow<Boolean> = sessionRuntime.speakerMuted

        override fun setMicMuted(muted: Boolean) {
            sessionRuntime.setMicMuted(muted)
        }

        override fun setSpeakerMuted(muted: Boolean) {
            sessionRuntime.setSpeakerMuted(muted)
        }
    }

    val ttsController: TtsController = object : TtsController {
        override fun setTtsEnabled(enabled: Boolean) {
            sessionRuntime.setTtsEnabled(enabled)
        }
    }

    init {
        // Publish to holder so SessionService can rebind
        SessionRuntimeHolder.set(sessionRuntime)
    }

    // Deep link join capability
    suspend fun joinWithDeepLink(request: DeepLinkJoinRequest): AppResult<RoomCredentials> {
        val result = roomApi.joinRoom(request.roomId, request.inviteSecret)
        if (result is AppResult.Success) {
            val credentials = result.value
            credentialStore.save(credentials)
            sessionConnector.connect(credentials)
        }
        return result
    }

    // ViewModels
    fun createHomeViewModel(): HomeViewModel =
        HomeViewModel(sessionConnector, settingsRepository)

    fun createSearchViewModel(): SearchViewModel =
        SearchViewModel(musicCatalog, sessionRuntime.engine)

    fun createNowPlayingViewModel(): NowPlayingViewModel =
        NowPlayingViewModel(sessionStateStore, sessionRuntime.engine, voiceController)

    fun createQueueViewModel(): QueueViewModel =
        QueueViewModel(sessionStateStore, sessionRuntime.engine)

    fun createChatViewModel(): ChatViewModel =
        ChatViewModel(chatRepository, sessionStateStore, ttsCoordinator, voiceController, ttsController)

    fun createRoomViewModel(): RoomViewModel =
        RoomViewModel(
            store = sessionStateStore,
            commands = sessionRuntime.engine,
            settingsRepository = settingsRepository,
            ttsController = ttsController,
            loadCredentials = { credentialStore.load() },
        )

    fun createDebugViewModel(): DebugViewModel =
        DebugViewModel(sessionStateStore, voiceController, audioArbiter)

    override fun close() {
        SessionRuntimeHolder.set(null)
        sessionRuntime.close()
        roomPlayer.close()
        ttsCoordinator.close()
        announcer.release()
        audioArbiter.release()
        exoPlayer.release()
        roomApi.close()
        scope.cancel()
    }
}
