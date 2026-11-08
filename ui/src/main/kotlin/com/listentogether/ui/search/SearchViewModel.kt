package com.listentogether.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.listentogether.core.common.AppResult
import com.listentogether.core.model.Track
import com.listentogether.music.api.MusicCatalog
import com.listentogether.sync.SyncCommands
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val results: List<Track> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val addedTrackTitle: String? = null,
)

class SearchViewModel(
    private val catalog: MusicCatalog,
    private val commands: SyncCommands,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(query = query, errorMessage = null)
        searchJob?.cancel()
        if (query.trim().length >= 2) {
            searchJob = viewModelScope.launch {
                delay(300L) // Debounce
                performSearch(query.trim())
            }
        } else if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(results = emptyList(), isLoading = false)
        }
    }

    fun search() {
        val query = _uiState.value.query.trim()
        if (query.isNotBlank()) {
            searchJob?.cancel()
            viewModelScope.launch { performSearch(query) }
        }
    }

    private suspend fun performSearch(query: String) {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        when (val result = catalog.search(query)) {
            is AppResult.Success -> {
                _uiState.value = _uiState.value.copy(
                    results = result.value,
                    isLoading = false,
                )
            }
            is AppResult.Failure -> {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = result.error.message,
                )
            }
        }
    }

    fun playTrack(track: Track) {
        commands.selectTrack(track)
    }

    fun addToQueue(track: Track) {
        commands.addToQueue(track)
        _uiState.value = _uiState.value.copy(addedTrackTitle = track.title)
    }

    fun clearAddedNotification() {
        _uiState.value = _uiState.value.copy(addedTrackTitle = null)
    }
}
