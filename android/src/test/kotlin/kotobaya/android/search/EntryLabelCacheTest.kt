package kotobaya.android.search

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** AND-DECK-LABELS: batched online fetch, persistence, and labeler order. */
class EntryLabelCacheTest {
    private val calls = ArrayList<List<Long>>()

    /** Knows every seq except multiples of 7 (omitted, as the server omits unknown ones). */
    private val fetcher = HeadwordFetcher { seqs ->
        calls.add(seqs)
        seqs.filter { it % 7 != 0L }.associateWith { EntryLabel("w$it", "r$it", "g$it") }
    }

    @Test
    fun fetchesInBatchesOfAtMost500CachesAndDoesNotRefetch() = runBlocking {
        val file = File.createTempFile("labels", ".json").also { it.deleteOnExit() }
        val cache = EntryLabelCache(file, fetcher)
        assertTrue(cache.fetchMissing((1L..1200L).toList()))
        assertEquals(listOf(500, 500, 200), calls.map { it.size })
        assertEquals("w1", cache.labels(listOf(1L))[1L]?.headword)
        assertTrue(7L !in cache.labels(listOf(7L)))
        // Second load: cached seqs make no request.
        calls.clear()
        cache.fetchMissing((1L..6L).toList())
        assertTrue(calls.isEmpty())
        // A new instance reads the persisted file.
        val again = EntryLabelCache(file, fetcher)
        assertEquals(EntryLabel("w1199", "r1199", "g1199"), again.labels(listOf(1199L))[1199L])
        again.fetchMissing((1L..6L).toList())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun failedBatchKeepsThePlaceholderAndRetriesNextTime() = runBlocking {
        var fail = true
        val cache = EntryLabelCache(null) { seqs -> if (fail) error("offline") else seqs.associateWith { EntryLabel("w", "r", "g") } }
        assertEquals(false, cache.fetchMissing(listOf(1L, 2L)))
        assertTrue(cache.labels(listOf(1L)).isEmpty())
        fail = false
        assertTrue(cache.fetchMissing(listOf(1L, 2L)))
        assertEquals(2, cache.labels(listOf(1L, 2L)).size)
    }

    @Test
    fun installedDictionaryBeatsCacheAndOmittedSeqsStayAbsent() = runBlocking {
        val cache = EntryLabelCache(null, fetcher)
        cache.fetchMissing(listOf(1L, 2L, 7L))
        val installed = EntryLabeler { s -> s.filter { it == 1L }.associateWith { EntryLabel("local", "l", "l") } }
        val got = installed.then(cache).labels(listOf(1L, 2L, 7L))
        assertEquals("local", got[1L]?.headword)
        assertEquals("w2", got[2L]?.headword)
        assertTrue(7L !in got)
    }
}
