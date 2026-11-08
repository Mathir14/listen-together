package com.listentogether.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppSettings(
    val ttsEnabled: Boolean = true,
    val ttsSpeechRate: Float = 1.0f,
    val ttsMuteDuringVoice: Boolean = true,
    val micDefaultOn: Boolean = false,
    val lastRoomId: String? = null,
    val lastRole: String? = null,
)

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun setTtsEnabled(enabled: Boolean)
    suspend fun setTtsSpeechRate(rate: Float)
    suspend fun setTtsMuteDuringVoice(mute: Boolean)
    suspend fun setMicDefaultOn(enabled: Boolean)
    suspend fun setLastRoom(roomId: String?, role: String?)
}

class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {
    private object Keys {
        val ttsEnabled = booleanPreferencesKey("tts_enabled")
        val ttsRate = floatPreferencesKey("tts_speech_rate")
        val ttsMuteDuringVoice = booleanPreferencesKey("tts_mute_during_voice")
        val micDefaultOn = booleanPreferencesKey("mic_default_on")
        val lastRoomId = stringPreferencesKey("last_room_id")
        val lastRole = stringPreferencesKey("last_role")
    }

    override val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            ttsEnabled = prefs[Keys.ttsEnabled] ?: true,
            ttsSpeechRate = prefs[Keys.ttsRate] ?: 1.0f,
            ttsMuteDuringVoice = prefs[Keys.ttsMuteDuringVoice] ?: true,
            micDefaultOn = prefs[Keys.micDefaultOn] ?: false,
            lastRoomId = prefs[Keys.lastRoomId],
            lastRole = prefs[Keys.lastRole],
        )
    }

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun setTtsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.ttsEnabled] = enabled }
    }

    override suspend fun setTtsSpeechRate(rate: Float) {
        dataStore.edit { it[Keys.ttsRate] = rate.coerceIn(0.5f, 2.5f) }
    }

    override suspend fun setTtsMuteDuringVoice(mute: Boolean) {
        dataStore.edit { it[Keys.ttsMuteDuringVoice] = mute }
    }

    override suspend fun setMicDefaultOn(enabled: Boolean) {
        dataStore.edit { it[Keys.micDefaultOn] = enabled }
    }

    override suspend fun setLastRoom(roomId: String?, role: String?) {
        dataStore.edit { prefs ->
            if (roomId == null) prefs.remove(Keys.lastRoomId) else prefs[Keys.lastRoomId] = roomId
            if (role == null) prefs.remove(Keys.lastRole) else prefs[Keys.lastRole] = role
        }
    }

    companion object {
        fun from(context: Context): DataStoreSettingsRepository =
            DataStoreSettingsRepository(context.listenTogetherDataStore)
    }
}
