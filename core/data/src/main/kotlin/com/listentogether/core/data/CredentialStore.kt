package com.listentogether.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.listentogether.core.model.Role
import com.listentogether.core.model.RoomCredentials
import kotlinx.coroutines.flow.first

interface CredentialStore {
    suspend fun save(credentials: RoomCredentials)
    suspend fun load(): RoomCredentials?
    suspend fun clear()
}

class DataStoreCredentialStore(
    private val dataStore: DataStore<Preferences>,
    private val vault: SecretVault,
) : CredentialStore {
    private object Keys {
        val roomId = stringPreferencesKey("cred_room_id")
        val participantId = stringPreferencesKey("cred_participant_id")
        val role = stringPreferencesKey("cred_role")
        val tokenCiphertext = stringPreferencesKey("cred_token")
        val inviteCiphertext = stringPreferencesKey("cred_invite")
    }

    override suspend fun save(credentials: RoomCredentials) {
        dataStore.edit { prefs ->
            prefs[Keys.roomId] = credentials.roomId
            prefs[Keys.participantId] = credentials.participantId
            prefs[Keys.role] = credentials.role.name
            prefs[Keys.tokenCiphertext] = vault.encrypt(credentials.participantToken)
            val inviteSecret = credentials.inviteSecret
            if (inviteSecret == null) {
                prefs.remove(Keys.inviteCiphertext)
            } else {
                prefs[Keys.inviteCiphertext] = vault.encrypt(inviteSecret)
            }
        }
    }

    override suspend fun load(): RoomCredentials? {
        val prefs = dataStore.data.first()
        val roomId = prefs[Keys.roomId] ?: return null
        val participantId = prefs[Keys.participantId] ?: return null
        val role = prefs[Keys.role]?.let { runCatching { Role.valueOf(it) }.getOrNull() } ?: return null
        val tokenCipher = prefs[Keys.tokenCiphertext] ?: return null
        val token = runCatching { vault.decrypt(tokenCipher) }.getOrNull() ?: return null
        val invite = prefs[Keys.inviteCiphertext]?.let { runCatching { vault.decrypt(it) }.getOrNull() }
        return RoomCredentials(
            roomId = roomId,
            participantId = participantId,
            participantToken = token,
            role = role,
            inviteSecret = invite,
        )
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.roomId)
            prefs.remove(Keys.participantId)
            prefs.remove(Keys.role)
            prefs.remove(Keys.tokenCiphertext)
            prefs.remove(Keys.inviteCiphertext)
        }
    }
}
