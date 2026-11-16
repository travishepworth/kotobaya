package kotobaya.android.shell.api.mining

import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotobaya.android.dict.GapRef
import kotobaya.android.dict.GapRow
import kotobaya.android.shell.api.mining.MiningTestSupport.FakeServer
import kotobaya.android.shell.api.mining.MiningTestSupport.OUTSIDE
import kotobaya.android.shell.api.mining.MiningTestSupport.RETIRED
import kotobaya.android.shell.api.mining.MiningTestSupport.VOCAB
import kotobaya.android.shell.api.mining.MiningTestSupport.call
import kotobaya.android.shell.api.mining.MiningTestSupport.device
import kotobaya.android.shell.api.mining.MiningTestSupport.entryBody
import kotobaya.android.shell.api.mining.MiningTestSupport.fieldError
import kotobaya.android.shell.api.mining.MiningTestSupport.get
import kotobaya.android.shell.api.mining.MiningTestSupport.installServerTestDictionary
import kotobaya.android.shell.api.mining.MiningTestSupport.json
import kotobaya.android.shell.api.mining.MiningTestSupport.obj
import kotobaya.android.shell.api.mining.MiningTestSupport.queriesFile
import kotobaya.android.shell.api.mining.MiningTestSupport.seqs
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonString
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SHELL-L5: `searchDictionary` answered locally agrees with the server. The oracle is the server's own fixture test
 * (`server/src/test/resources/dict/queries.json`: 50 kana, katakana, kanji, romaji and English queries with the
 * expected first result) over the same `ref` fixture prepared the same way. Plus paging, validation, the sidecar cache
 * in local results, and the overlay from the replica.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class DictSearchTest {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun serverLike() = device().also { installServerTestDictionary(it) }

    private fun queries(kinds: Set<String>?): List<Triple<String, String, Long?>> {
        val spec = ParityJson.obj(ParityJson.parse(queriesFile.readText()))
        return ParityJson.arr(spec.members["queries"]).map { ParityJson.obj(it) }.map { q ->
            Triple(ParityJson.str(q.members["kind"]), ParityJson.str(q.members["q"]), (q.members["expect"]).takeIf { it != JsonNull }?.let { ParityJson.long(it) })
        }.filter { kinds == null || it.first in kinds }
    }

    private fun assertTopResults(kinds: Set<String>?) {
        val d = serverLike()
        val cases = queries(kinds)
        assertTrue(cases.isNotEmpty())
        val failures = ArrayList<String>()
        for ((kind, q, want) in cases) {
            val r = d.get("/api/v1/dict/search", "q=${enc(q)}")
            val got = if (r.status == 200) r.seqs() else emptyList()
            if (r.status != 200 || got.firstOrNull() != want) failures += "$q ($kind): expected $want, got ${got.firstOrNull()} (HTTP ${r.status})"
            if (got.any { it in RETIRED }) failures += "$q: retired entry returned"
            if (r.headers["kotobaya-local"] != "1") failures += "$q: not answered locally"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        // L-7: an authoritative package answers locally; only an empty first page asks the server (offline here: local)
        assertEquals(cases.count { it.third == null }, d.forwarded.size, "forwarded: ${d.forwarded}")
    }

    @Test
    fun kanaQueriesMatchTheServer() = assertTopResults(setOf("kana", "katakana", "prefix-1char", "prefix-2char", "substring", "no-deinflection"))

    @Test
    fun kanjiQueriesMatchTheServer() = assertTopResults(setOf("kanji"))

    @Test
    fun romajiQueriesMatchTheServer() = assertTopResults(setOf("romaji", "english"))

    @Test
    fun everyServerFixtureQueryMatches() = assertTopResults(null)

    @Test
    fun pagingWalksEveryResultOnceWithLocalCursors() {
        val d = serverLike()
        val all = d.get("/api/v1/dict/search", "q=${enc("お")}&limit=500").seqs()
        assertTrue(all.size > 25)
        val walked = ArrayList<Long>()
        var cursor: String? = null
        do {
            val r = d.get("/api/v1/dict/search", "q=${enc("お")}&limit=10" + (cursor?.let { "&cursor=${enc(it)}" } ?: ""))
            assertEquals(200, r.status)
            walked += r.seqs()
            cursor = (r.json().members["next_cursor"] as? JsonString)?.value
            cursor?.let { assertTrue(it.startsWith("l."), it) }
        } while (cursor != null)
        assertEquals(all, walked)
        // a cursor of another query is refused
        val first = (d.get("/api/v1/dict/search", "q=${enc("お")}&limit=10").json().members["next_cursor"] as JsonString).value
        assertEquals("/cursor invalid_value", d.get("/api/v1/dict/search", "q=${enc("か")}&limit=10&cursor=${enc(first)}").fieldError())
    }

    @Test
    fun validationAsTheServer() {
        val d = device()
        assertEquals("/q required", d.get("/api/v1/dict/search").fieldError())
        assertEquals("/q invalid_value", d.get("/api/v1/dict/search", "q=%20%20").fieldError())
        assertEquals("/q invalid_value", d.get("/api/v1/dict/search", "q=${"あ".repeat(101).let(::enc)}").fieldError())
        assertEquals("/limit invalid_value", d.get("/api/v1/dict/search", "q=a&limit=0").fieldError())
        assertEquals("/common_only invalid_value", d.get("/api/v1/dict/search", "q=a&common_only=yes").fieldError())
        assertEquals("/colour unknown_field", d.get("/api/v1/dict/search", "q=a&colour=red").fieldError())
        val none = d.get("/api/v1/dict/search", "q=zzzzqqq")
        assertEquals(200, none.status)
        assertEquals(emptyList(), none.seqs())
        assertEquals(JsonNull, none.json().members["next_cursor"])
    }

    @Test
    fun aGapFilledEntryIsFoundLocallyAndTheOverlayIsTheReplicas() {
        val d = device()
        d.gapCache.put(listOf(GapRow(GapRef.entry(OUTSIDE), d.dictionary.releaseId, entryBody(d, OUTSIDE, headword = "話し相手"), 0L)))
        // the renumbered copy of 1000220 matches whatever 1000220 matches; by its reading it is found next to it
        val reading = ParityJson.str(d.get("/api/v1/dict/entries/1000220").json().members["headword_reading"])
        val r = d.get("/api/v1/dict/search", "q=${enc(reading)}&limit=500")
        assertEquals(200, r.status)
        assertTrue(OUTSIDE in r.seqs(), "a cached entry is searchable offline")
        assertTrue(1000220L in r.seqs())

        assertEquals(201, d.call("POST", "/api/v1/notes", """{"note_type_id":"$VOCAB","entry_seq":$OUTSIDE}""").status)
        val item = ParityJson.arr(d.get("/api/v1/dict/search", "q=${enc(reading)}&limit=500").json().members["items"])
            .map { ParityJson.obj(it) }.single { ParityJson.long(it.members["seq"]) == OUTSIDE }
        assertNotNull(item.members["note_id"] as? JsonString, "read-your-writes: the new note shows")
    }

    @Test
    fun anAuthoritativeEmptyPageIsForwardedWhenOnline() {
        val d = device()
        val server = FakeServer { 200 to obj("items" to JsonArray(emptyList()), "next_cursor" to JsonNull) }
        d.server = server
        assertEquals(200, d.get("/api/v1/dict/search", "q=zzzzqqq").status)
        assertEquals(listOf("searchDictionary"), server.seen)
        // a page with results is answered locally
        assertEquals(200, d.get("/api/v1/dict/search", "q=${enc("ねこ")}").status)
        assertEquals(listOf("searchDictionary"), server.seen)
    }

    @Test
    fun kanjiWordsAndKanjiAreLocal() {
        val d = device()
        val k = d.get("/api/v1/kanji/${enc("学")}")
        assertEquals(200, k.status)
        assertEquals(JsonString("学"), k.json().members["literal"])
        val words = d.get("/api/v1/kanji/${enc("学")}/words", "limit=5")
        assertEquals(200, words.status)
        assertTrue(words.seqs().isNotEmpty())
        assertEquals("/literal invalid_value", d.get("/api/v1/kanji/${enc("学校")}").fieldError())
        assertEquals(404, d.get("/api/v1/kanji/${enc("学")}/words", "deck_id=0193ffff-0000-7000-8000-000000000001").status)
        // paging over local cursors returns every word once
        val all = d.get("/api/v1/kanji/${enc("学")}/words", "limit=200").seqs()
        val walked = ArrayList<Long>()
        var cursor: String? = null
        do {
            val r = d.get("/api/v1/kanji/${enc("学")}/words", "limit=2" + (cursor?.let { "&cursor=${enc(it)}" } ?: ""))
            walked += r.seqs()
            cursor = (r.json().members["next_cursor"] as? JsonString)?.value
        } while (cursor != null)
        assertEquals(all, walked)
        // an entry the user mined shows with mine=true
        assertEquals(201, d.call("POST", "/api/v1/notes", """{"note_type_id":"$VOCAB","entry_seq":${all.first()}}""").status)
        assertEquals(listOf(all.first()), d.get("/api/v1/kanji/${enc("学")}/words", "mine=true").seqs())
    }
}
