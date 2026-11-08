package com.listentogether.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

interface TtsCursorStore {
    suspend fun lastSpokenSequence(sessionId: String): Long
    suspend fun markSpoken(sessionId: String, sequence: Long)
    suspend fun purge()
}

class DataStoreTtsCursorStore(
    private val dataStore: DataStore<Preferences>,
) : TtsCursorStore {
    private object Keys {
        val sessionId = stringPreferencesKey("tts_cursor_session_id")
        val sequence = longPreferencesKey("tts_cursor_sequence")
    }

    override suspend fun lastSpokenSequence(sessionId: String): Long {
        val prefs = dataStore.data.first()
        if (prefs[Keys.sessionId] != sessionId) return 0
        return prefs[Keys.sequence] ?: 0
    }

    override suspend fun markSpoken(sessionId: String, sequence: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.sessionId] = sessionId
            prefs[Keys.sequence] = sequence
        }
    }

    override suspend fun purge() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.sessionId)
            prefs.remove(Keys.sequence)
        }
    }
}
