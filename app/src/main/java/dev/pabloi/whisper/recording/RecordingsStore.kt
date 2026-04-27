package dev.pabloi.whisper.recording

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.recordingsDataStore by preferencesDataStore(name = "whispr_recordings")

class RecordingsStore(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.recordingsDataStore)

    private val key = stringPreferencesKey("recordings_index_json")
    private val json = Json { ignoreUnknownKeys = true }

    val flow: Flow<List<Recording>> = store.data.map { prefs ->
        val raw = prefs[key].orEmpty()
        if (raw.isEmpty()) emptyList()
        else runCatching { json.decodeFromString(SER, raw) }.getOrDefault(emptyList())
    }

    suspend fun snapshot(): List<Recording> = flow.first()

    suspend fun find(id: String): Recording? = snapshot().firstOrNull { it.id == id }

    suspend fun add(rec: Recording) = mutate { current ->
        // newest first; cap at MAX_ENTRIES
        (listOf(rec) + current.filter { it.id != rec.id }).take(MAX_ENTRIES)
    }

    suspend fun update(id: String, mapper: (Recording) -> Recording) = mutate { current ->
        current.map { if (it.id == id) mapper(it) else it }
    }

    suspend fun remove(id: String) = mutate { current -> current.filter { it.id != id } }

    suspend fun firstNonFinalised(): Recording? =
        snapshot().firstOrNull { it.state == Recording.State.RECORDING || it.state == Recording.State.ORPHANED }

    private suspend fun mutate(transform: (List<Recording>) -> List<Recording>) {
        store.edit { prefs ->
            val current = prefs[key].orEmpty().let { raw ->
                if (raw.isEmpty()) emptyList()
                else runCatching { json.decodeFromString(SER, raw) }.getOrDefault(emptyList())
            }
            prefs[key] = json.encodeToString(SER, transform(current))
        }
    }

    companion object {
        const val MAX_ENTRIES = 200
        private val SER = kotlinx.serialization.builtins.ListSerializer(Recording.serializer())
    }
}
