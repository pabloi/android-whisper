package dev.pabloi.whisper.recording

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingsStoreTest {

    private fun fakeStore(): DataStore<Preferences> = object : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(state.value); state.value = next; return next
        }
    }

    private fun rec(id: String, started: Long) = Recording(
        id = id, displayName = "Rec $id", audioUri = "u://$id",
        transcriptUri = "", startedAt = started, durationMs = 1000,
        sampleRate = 48_000, routeLabel = "mic", state = Recording.State.FINALISED,
    )

    @Test fun addAndList() = runTest {
        val store = RecordingsStore(fakeStore())
        store.add(rec("a", 1))
        store.add(rec("b", 2))
        assertEquals(listOf("b", "a"), store.flow.first().map { it.id })  // newest first
    }

    @Test fun updateExisting() = runTest {
        val store = RecordingsStore(fakeStore())
        store.add(rec("a", 1))
        store.update("a") { it.copy(transcriptUri = "u://a.txt", state = Recording.State.TRANSCRIBED) }
        val r = store.find("a")!!
        assertEquals("u://a.txt", r.transcriptUri)
        assertEquals(Recording.State.TRANSCRIBED, r.state)
    }

    @Test fun capAt200OldestEvicted() = runTest {
        val store = RecordingsStore(fakeStore())
        for (i in 1..205) store.add(rec(i.toString(), i.toLong()))
        val ids = store.flow.first().map { it.id }
        assertEquals(200, ids.size)
        assertEquals("205", ids.first())
        assertEquals("6", ids.last())  // 1..5 evicted
    }

    @Test fun findUnknown() = runTest {
        val store = RecordingsStore(fakeStore())
        assertNull(store.find("nope"))
    }
}
