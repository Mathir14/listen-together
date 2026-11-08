package com.listentogether

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.listentogether.core.designsystem.ConnectionBanner
import com.listentogether.core.designsystem.MiniPlayer
import com.listentogether.ui.chat.ChatScreen
import com.listentogether.ui.chat.ChatViewModel
import com.listentogether.ui.debug.DebugScreen
import com.listentogether.ui.debug.DebugViewModel
import com.listentogether.ui.home.HomeScreen
import com.listentogether.ui.home.HomeViewModel
import com.listentogether.ui.nowplaying.NowPlayingScreen
import com.listentogether.ui.nowplaying.NowPlayingViewModel
import com.listentogether.ui.queue.QueueScreen
import com.listentogether.ui.queue.QueueViewModel
import com.listentogether.ui.room.RoomScreen
import com.listentogether.ui.room.RoomViewModel
import com.listentogether.ui.search.SearchScreen
import com.listentogether.ui.search.SearchViewModel

enum class NavigationTab(val label: String) {
    HOME("Home"),
    PLAYING("Playing"),
    SEARCH("Search"),
    QUEUE("Queue"),
    CHAT("Chat"),
    ROOM("Room"),
    DEBUG("Debug"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavigationHost(
    appGraph: AppGraph,
    modifier: Modifier = Modifier,
    initialTab: NavigationTab = NavigationTab.HOME,
) {
    val sessionState by appGraph.sessionRuntime.state.collectAsState()
    var currentTab by remember {
        mutableStateOf(
            if (sessionState.isSessionActive || sessionState.room != null) NavigationTab.PLAYING else initialTab
        )
    }
    val snackbarHostState = remember { SnackbarHostState() }

    // ViewModels scoped to this graph
    val homeViewModel: HomeViewModel = viewModel { appGraph.createHomeViewModel() }
    val searchViewModel: SearchViewModel = viewModel { appGraph.createSearchViewModel() }
    val nowPlayingViewModel: NowPlayingViewModel = viewModel { appGraph.createNowPlayingViewModel() }
    val queueViewModel: QueueViewModel = viewModel { appGraph.createQueueViewModel() }
    val chatViewModel: ChatViewModel = viewModel { appGraph.createChatViewModel() }
    val roomViewModel: RoomViewModel = viewModel { appGraph.createRoomViewModel() }
    val debugViewModel: DebugViewModel = viewModel { appGraph.createDebugViewModel() }

    val isConnected = sessionState.room != null || sessionState.isSessionActive
    val activeTrack = sessionState.playback?.track
    val isPlaying = sessionState.playback?.isPlaying == true

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (currentTab) {
                            NavigationTab.HOME -> "Listen Together"
                            NavigationTab.PLAYING -> "Now Playing"
                            NavigationTab.SEARCH -> "Music Search"
                            NavigationTab.QUEUE -> "Queue"
                            NavigationTab.CHAT -> "Session Chat"
                            NavigationTab.ROOM -> "Room Settings"
                            NavigationTab.DEBUG -> "Diagnostics"
                        }
                    )
                },
                navigationIcon = {
                    if (currentTab == NavigationTab.DEBUG) {
                        IconButton(onClick = {
                            currentTab = if (isConnected) NavigationTab.PLAYING else NavigationTab.HOME
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (currentTab != NavigationTab.DEBUG) {
                        IconButton(onClick = { currentTab = NavigationTab.DEBUG }) {
                            Icon(Icons.Default.BugReport, contentDescription = "Diagnostics")
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                // Persistent MiniPlayer when active and not on Playing screen
                if (currentTab != NavigationTab.PLAYING && currentTab != NavigationTab.HOME && activeTrack != null) {
                    MiniPlayer(
                        track = activeTrack,
                        isPlaying = isPlaying,
                        onPlayPauseClick = {
                            if (isPlaying) {
                                appGraph.sessionRuntime.pause()
                            } else {
                                appGraph.sessionRuntime.play()
                            }
                        },
                        onNextClick = {
                            appGraph.sessionRuntime.next()
                        },
                        onClick = {
                            currentTab = NavigationTab.PLAYING
                        },
                    )
                }

                // Show NavigationBar when connected or inside a room session
                if (isConnected && currentTab != NavigationTab.DEBUG) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = currentTab == NavigationTab.PLAYING,
                            onClick = { currentTab = NavigationTab.PLAYING },
                            icon = { Icon(Icons.Default.MusicNote, contentDescription = "Playing") },
                            label = { Text("Playing") },
                        )
                        NavigationBarItem(
                            selected = currentTab == NavigationTab.SEARCH,
                            onClick = { currentTab = NavigationTab.SEARCH },
                            icon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                            label = { Text("Search") },
                        )
                        NavigationBarItem(
                            selected = currentTab == NavigationTab.QUEUE,
                            onClick = { currentTab = NavigationTab.QUEUE },
                            icon = { Icon(Icons.Default.List, contentDescription = "Queue") },
                            label = { Text("Queue") },
                        )
                        NavigationBarItem(
                            selected = currentTab == NavigationTab.CHAT,
                            onClick = { currentTab = NavigationTab.CHAT },
                            icon = { Icon(Icons.Default.Chat, contentDescription = "Chat") },
                            label = { Text("Chat") },
                        )
                        NavigationBarItem(
                            selected = currentTab == NavigationTab.ROOM,
                            onClick = { currentTab = NavigationTab.ROOM },
                            icon = { Icon(Icons.Default.Person, contentDescription = "Room") },
                            label = { Text("Room") },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            ConnectionBanner(connectionState = sessionState.connection)

            Box(modifier = Modifier.fillMaxSize()) {
                when (currentTab) {
                    NavigationTab.HOME -> {
                        HomeScreen(
                            viewModel = homeViewModel,
                            onNavigateToNowPlaying = {
                                currentTab = NavigationTab.PLAYING
                            },
                        )
                    }
                    NavigationTab.PLAYING -> {
                        NowPlayingScreen(
                            viewModel = nowPlayingViewModel,
                            onNavigateToQueue = {
                                currentTab = NavigationTab.QUEUE
                            },
                        )
                    }
                    NavigationTab.SEARCH -> {
                        SearchScreen(
                            viewModel = searchViewModel,
                            onNavigateToNowPlaying = {
                                currentTab = NavigationTab.PLAYING
                            },
                        )
                    }
                    NavigationTab.QUEUE -> {
                        QueueScreen(
                            viewModel = queueViewModel,
                            onNavigateToNowPlaying = {
                                currentTab = NavigationTab.PLAYING
                            },
                        )
                    }
                    NavigationTab.CHAT -> {
                        ChatScreen(viewModel = chatViewModel)
                    }
                    NavigationTab.ROOM -> {
                        RoomScreen(
                            viewModel = roomViewModel,
                            onLeftRoom = {
                                currentTab = NavigationTab.HOME
                            },
                        )
                    }
                    NavigationTab.DEBUG -> {
                        DebugScreen(viewModel = debugViewModel)
                    }
                }
            }
        }
    }
}
