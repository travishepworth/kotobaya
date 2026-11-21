package kotobaya.android.shell.api.mining

import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotobaya.android.dict.GapRef
import kotobaya.android.dict.GapRow
import kotobaya.android.shell.api.mining.MiningTestSupport.FakeServer
import kotobaya.android.shell.api.mining.MiningTestSupport.IN_PACKAGE
import kotobaya.android.shell.api.mining.MiningTestSupport.OUTSIDE
import kotobaya.android.shell.api.mining.MiningTestSupport.VOCAB
import kotobaya.android.shell.api.mining.MiningTestSupport.call
import kotobaya.android.shell.api.mining.MiningTestSupport.code
import kotobaya.android.shell.api.mining.MiningTestSupport.device
import kotobaya.android.shell.api.mining.MiningTestSupport.entryBody
import kotobaya.android.shell.api.mining.MiningTestSupport.fieldError
import kotobaya.android.shell.api.mining.MiningTestSupport.get
import kotobaya.android.shell.api.mining.MiningTestSupport.json
import kotobaya.android.shell.api.mining.MiningTestSupport.obj
import kotobaya.android.shell.api.mining.MiningTestSupport.seqs
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonString
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SHELL-L5b: the follow-ups of the SHELL-L5 review. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class MiningFollowUpsTest {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    @Test
    fun dictionaryAnswersAreShellOnesAndNeverForwarded() {
        val d = device(withDictionary = false)
        d.server = FakeServer { 200 to obj("items" to JsonArray(emptyList()), "next_cursor" to JsonNull) }
        val r = d.get("/api/v1/dict/search", "q=x")
        assertEquals(503, r.status)
        assertEquals("1", r.headers["kotobaya-local"])
        assertNotNull(r.headers["x-request-id"])
        assertTrue(d.forwarded.isEmpty())
    }

    @Test
    fun createNoteSourceIdsRejectJsonNull() {
        val d = device()
        for (key in listOf("source_sentence_id", "source_media_id")) {
            val r = d.call("POST", "/api/v1/notes", """{"note_type_id":"$VOCAB","entry_seq":$IN_PACKAGE,"$key":null}""")
            assertEquals(422, r.status, key)
            assertTrue(r.fieldError().startsWith("/$key "), r.fieldError())
        }
    }

    @Test
    fun searchOverThousandsOfCachedEntriesPagesCompleteAndScansTheTableOnce() {
        val d = device()
        val headword = ParityJson.str(d.get("/api/v1/dict/entries/$IN_PACKAGE").json().members["headword"])
        val base = OUTSIDE
        val n = 3000
        d.gapCache.put((0 until n).map { GapRow(GapRef.entry(base + it), 1, entryBody(d, base + it), 0) })
        val before = d.gapCache.scans
        val seen = ArrayList<Long>()
        var cursor: String? = null
        var pages = 0
        do {
            val r = d.get("/api/v1/dict/search", "q=${enc(headword)}&limit=500" + (cursor?.let { "&cursor=${enc(it)}" } ?: ""))
            assertEquals(200, r.status)
            assertEquals("1", r.headers["kotobaya-local"])
            seen += r.seqs()
            cursor = (r.json().members["next_cursor"] as? JsonString)?.value
            pages++
        } while (cursor != null && pages < 50)
        assertEquals(seen.size, seen.toSet().size, "no repeats across pages")
        assertTrue(seen.containsAll((0 until n).map { base + it }), "no skips: every cached entry once")
        assertTrue(pages >= 6, "paged ($pages)")
        assertEquals(1, d.gapCache.scans - before, "the cached rows are decoded once for all $pages pages")
        // a second search and a kanji-words read reuse the same index
        d.get("/api/v1/dict/search", "q=${enc(headword)}")
        d.get("/api/v1/kanji/${enc(headword.take(1))}/words")
        assertEquals(1, d.gapCache.scans - before)
        // a write invalidates it
        d.gapCache.put(listOf(GapRow(GapRef.entry(base + n), 1, entryBody(d, base + n), 0)))
        d.get("/api/v1/dict/search", "q=${enc(headword)}")
        assertEquals(2, d.gapCache.scans - before)
    }

    @Test
    fun mediaExamplesMatchTheSeqWhateverTheJsonSpacing() {
        val d = device()
        fun run(sql: String) = d.driver.execute(null, sql, 0)
        run("INSERT INTO media (id, title, kind, source, created_at, updated_at, installed_content_version) VALUES ('m1', 'T', 'anime', 'x', 1, 1, 1)")
        run("INSERT INTO media_segment (id, media_id, ordinal, kind) VALUES ('s1', 'm1', 0, 'episode')")
        fun sentence(id: String, ordinal: Int, tokens: String) = run(
            "INSERT INTO sentence (id, media_id, segment_id, ordinal, text, tokens, origin) VALUES ('$id', 'm1', 's1', $ordinal, 't$ordinal', '$tokens', 'media')",
        )
        sentence("a", 0, """[{"seq": $IN_PACKAGE, "class": "content"}]""")
        sentence("b", 1, """[{"seq":$IN_PACKAGE,"class":"content"}]""")
        sentence("c", 2, """[{"seq":${IN_PACKAGE}1,"class":"content"}]""")
        val r = d.get("/api/v1/dict/entries/$IN_PACKAGE/examples")
        assertEquals(200, r.status, r.code())
        val ids = (r.json().members["media"] as JsonArray).items.map { ParityJson.str(ParityJson.obj(it).members["id"]) }.sorted()
        assertEquals(listOf("a", "b"), ids)
    }
}
