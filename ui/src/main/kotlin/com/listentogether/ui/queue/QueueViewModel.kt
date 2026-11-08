package com.listentogether.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.listentogether.core.model.QueueItem
import com.listentogether.core.model.Track
import com.listentogether.sync.SessionStateStore
import com.listentogether.sync.SyncCommands
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class QueueUiState(
    val items: List<QueueItem> = emptyList(),
    val currentItemId: String? = null,
    val queueRevision: Long = 0L,
)

class QueueViewModel(
    private val store: SessionStateStore,
    private val commands: SyncCommands,
) : ViewModel() {

    private val _uiState = MutableStateFlow(QueueUiState())
    val uiState: StateFlow<QueueUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            store.state.collect { session ->
                val queue = session.queue
                _uiState.value = _uiState.value.copy(
                    items = queue?.items ?: emptyList(),
                    currentItemId = queue?.currentItemId,
                    queueRevision = queue?.queueRevision ?: 0L,
                )
            }
        }
    }

    fun playTrack(track: Track) {
        commands.selectTrack(track)
    }

    fun removeItem(itemId: String) {
        commands.removeFromQueue(itemId)
    }

    fun clearQueue() {
        commands.clearQueue()
    }

    fun moveUp(index: Int) {
        if (index <= 0) return
        val current = _uiState.value.items.map { it.itemId }.toMutableList()
        val item = current.removeAt(index)
        current.add(index - 1, item)
        commands.reorderQueue(current)
    }

    fun moveDown(index: Int) {
        val current = _uiState.value.items.map { it.itemId }.toMutableList()
        if (index >= current.size - 1) return
        val item = current.removeAt(index)
        current.add(index + 1, item)
        commands.reorderQueue(current)
    }
}
