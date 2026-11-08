package com.listentogether.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.listentogether.core.model.ParticipantView
import com.listentogether.core.model.PresenceStatus
import com.listentogether.core.model.Role
import com.listentogether.core.model.RoomCredentials
import com.listentogether.core.model.RoomStatus
import com.listentogether.core.model.RoomView
import com.listentogether.protocol.SnapshotPayload
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalPersistenceTest {
    private fun TestScope.dataStore(): DataStore<Preferences> {
        val file = File.createTempFile("listen-together-test", ".preferences_pb").apply { delete() }
        file.deleteOnExit()
        return PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
    }

    @Test
    fun `settings default and update`() = runTest {
        val store = dataStore()
        val repo = DataStoreSettingsRepository(store)
        val defaults = repo.current()
        assertTrue(defaults.ttsEnabled)
        assertEquals(1.0f, defaults.ttsSpeechRate)

        repo.setTtsEnabled(false)
        repo.setTtsSpeechRate(1.4f)
        repo.setLastRoom("room-1", "SPEAKER")
        val updated = repo.settings.first()
        assertFalse(updated.ttsEnabled)
        assertEquals(1.4f, updated.ttsSpeechRate)
        assertEquals("room-1", updated.lastRoomId)
    }

    @Test
    fun `participant token is not stored as plaintext`() = runTest {
        val store = dataStore()
        val credentials = RoomCredentials(
            roomId = "room-1",
            participantId = "user-1",
            participantToken = "super-secret-token",
            role = Role.SPEAKER,
            inviteSecret = "invite-secret",
        )
        val credentialStore = DataStoreCredentialStore(store, InMemorySecretVault())
        credentialStore.save(credentials)

        assertEquals(credentials, credentialStore.load())
        val raw = store.data.first().toString()
        assertFalse(raw.contains("super-secret-token"))
        assertFalse(raw.contains("invite-secret"))

        credentialStore.clear()
        assertNull(credentialStore.load())
    }

    @Test
    fun `snapshot cache enforces size cap`() = runTest {
        val store = dataStore()
        val cache = DataStoreSessionSnapshotCache(store, maxBytes = 64)
        val payload = SnapshotPayload(
            room = RoomView(
                roomId = "room-1",
                participants = listOf(ParticipantView("user-1", Role.SPEAKER, PresenceStatus.CONNECTED)),
                status = RoomStatus.WAITING,
            ),
            yourRole = Role.SPEAKER,
            serverTime = 1,
        )
        cache.save(payload)
        assertNull(cache.load())

        val roomy = DataStoreSessionSnapshotCache(store, maxBytes = 64 * 1024)
        roomy.save(payload)
        assertEquals(payload, roomy.load())
        roomy.purge()
        assertNull(roomy.load())
    }

    @Test
    fun `tts cursor is scoped to session and purgeable`() = runTest {
        val store = dataStore()
        val cursor = DataStoreTtsCursorStore(store)
        assertEquals(0, cursor.lastSpokenSequence("s1"))
        cursor.markSpoken("s1", 4)
        assertEquals(4, cursor.lastSpokenSequence("s1"))
        assertEquals(0, cursor.lastSpokenSequence("s2"))
        cursor.purge()
        assertEquals(0, cursor.lastSpokenSequence("s1"))
    }
}
