package com.listentogether.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.listentogether.protocol.ProtocolJson
import com.listentogether.protocol.SnapshotPayload
import kotlinx.coroutines.flow.first

interface SessionSnapshotCache {
    suspend fun save(snapshot: SnapshotPayload)
    suspend fun load(): SnapshotPayload?
    suspend fun purge()
}

class DataStoreSessionSnapshotCache(
    private val dataStore: DataStore<Preferences>,
    private val maxBytes: Int = 64 * 1024,
) : SessionSnapshotCache {
    private val key = stringPreferencesKey("last_session_snapshot")

    override suspend fun save(snapshot: SnapshotPayload) {
        val encoded = ProtocolJson.encode(
            com.listentogether.protocol.eventFrame(
                type = com.listentogether.protocol.MessageType.SNAPSHOT,
                payload = snapshot,
                roomId = snapshot.room.roomId,
                sessionId = snapshot.session?.sessionId,
                revision = snapshot.revision,
            ),
        )
        if (encoded.toByteArray(Charsets.UTF_8).size > maxBytes) {
            // Recovery cache is best-effort; never allow it to grow unbounded.
            dataStore.edit { it.remove(key) }
            return
        }
        dataStore.edit { it[key] = encoded }
    }

    override suspend fun load(): SnapshotPayload? {
        val raw = dataStore.data.first()[key] ?: return null
        return runCatching {
            ProtocolJson.payload<SnapshotPayload>(ProtocolJson.decode(raw))
        }.getOrNull()
    }

    override suspend fun purge() {
        dataStore.edit { it.remove(key) }
    }
}
