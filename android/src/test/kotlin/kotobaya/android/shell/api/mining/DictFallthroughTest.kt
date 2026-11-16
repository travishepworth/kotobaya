package kotobaya.android.shell.api.mining

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotobaya.android.dict.GapRef
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.api.mining.MiningTestSupport.FakeRemote
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
import kotobaya.android.shell.api.mining.MiningTestSupport.serverReleaseAhead
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SHELL-L5 acceptance `DictFallthroughTest` (SHELL.md L-7, 4.3): without a dictionary and offline every dictionary
 * read and `createNote` with an `entry_seq` is `503 shell_dict_missing` (never `404` / `422 not_found`: the device
 * cannot say the word does not exist); online the request reaches the server (forwarded, or fetched by the lookup and
 * cached), and `createNote` then mints the note locally. A package that is not authoritative behaves the same for what
 * it lacks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class DictFallthroughTest {
    private fun newNote(seq: Long, id: String = "0193b000-0001-7000-8000-0000000000a1") =
        """{"id":"$id","note_type_id":"$VOCAB","entry_seq":$seq}"""

    @Test
    fun noDictionaryOfflineEveryDictionaryReadIs503DictMissing() {
        val d = device(withDictionary = false)
        val reads = listOf(
            "/api/v1/dict/search" to "q=%E3%81%AD%E3%81%93",
            "/api/v1/dict/entries/$IN_PACKAGE" to "",
            "/api/v1/dict/entries/$IN_PACKAGE/examples" to "",
            "/api/v1/dict/headwords" to "seq=$IN_PACKAGE",
            "/api/v1/dict/release" to "",
            "/api/v1/kanji/%E5%AD%A6" to "",
        )
        for ((path, query) in reads) {
            val r = d.get(path, query)
            assertEquals(503, r.status, path)
            assertEquals("shell_dict_missing", r.code(), path)
            assertEquals("1", r.headers["kotobaya-local"], path)
        }
        val create = d.call("POST", "/api/v1/notes", newNote(IN_PACKAGE))
        assertEquals(503, create.status)
        assertEquals("shell_dict_missing", create.code())
        assertTrue(d.minted().isEmpty(), "nothing minted")
    }

    @Test
    fun noDictionaryOnlineReadsReachTheServer() {
        val remote = FakeRemote()
        val d = device(remote = remote, withDictionary = false)
        remote.entries[IN_PACKAGE] = entryBody(device(), IN_PACKAGE)
        val page = obj(
            "items" to JsonArray(
                listOf(
                    obj(
                        "seq" to JsonNumber(IN_PACKAGE.toDouble()), "headword" to JsonString("x"), "headword_reading" to JsonString("x"),
                        "is_common" to JsonBool(true), "glosses" to JsonArray(emptyList()), "pos" to JsonArray(emptyList()),
                        "freq_rank" to JsonNull, "retired" to JsonBool(false), "status" to JsonString("known"),
                        "note_id" to JsonString("0193ffff-0000-7000-8000-000000000001"), "deck_ids" to JsonArray(emptyList()),
                    ),
                ),
            ),
            "next_cursor" to JsonString("server-cursor"),
        )
        val server = FakeServer { op -> if (op == "searchDictionary") 200 to page else 200 to obj("id" to JsonNumber(7.0)) }
        d.server = server

        // searchDictionary: forwarded, the server's page and cursor, overlay recomputed from the replica
        val s = d.get("/api/v1/dict/search", "q=x")
        assertEquals(200, s.status)
        assertEquals(listOf(IN_PACKAGE), s.seqs())
        val item = ParityJson.obj(ParityJson.arr(s.json().members["items"]).single())
        assertEquals(JsonString("new"), item.members["status"], "status from the replica, not the server's")
        assertEquals(JsonNull, item.members["note_id"])
        assertEquals(JsonString("server-cursor"), s.json().members["next_cursor"])
        // a server cursor goes back to the server
        assertEquals(200, d.get("/api/v1/dict/search", "q=x&cursor=server-cursor").status)
        assertEquals(listOf("searchDictionary", "searchDictionary"), server.seen)

        // getDictRelease: forwarded; the answer is the last known server release
        assertEquals(200, d.get("/api/v1/dict/release").status)
        assertEquals(7L, d.gapCache.serverReleaseId())

        // getDictEntry: fetched by the lookup (step 3) and cached; offline afterwards it is answered from the cache
        val e = d.get("/api/v1/dict/entries/$IN_PACKAGE")
        assertEquals(200, e.status)
        assertEquals(listOf("entry $IN_PACKAGE"), remote.asked)
        assertNotNull(d.gapCache.row(GapRef.entry(IN_PACKAGE))?.body)
        remote.online = false
        d.server = null
        assertEquals(200, d.get("/api/v1/dict/entries/$IN_PACKAGE").status)
        assertEquals(listOf("entry $IN_PACKAGE"), remote.asked, "the cached entry needs no network")

        // createNote: the entry is cached, the note is created locally
        val c = d.call("POST", "/api/v1/notes", newNote(IN_PACKAGE))
        assertEquals(201, c.status)
        assertEquals(listOf("note.created", "card.created"), d.minted().map { ParityJson.str(it.members["op_type"]) })
    }

    @Test
    fun createNoteOnlineFetchesAMissingEntryThenMintsLocally() {
        val remote = FakeRemote()
        val d = device(remote = remote)
        d.serverReleaseAhead() // the server has a newer release than the phone's package
        remote.entries[OUTSIDE] = entryBody(d, OUTSIDE, headword = "話し相手")
        val c = d.call("POST", "/api/v1/notes", newNote(OUTSIDE))
        assertEquals(201, c.status)
        assertEquals(JsonNumber(OUTSIDE.toDouble()), c.json().members["entry_seq"])
        assertEquals(listOf("entry $OUTSIDE"), remote.asked)
        assertTrue(d.forwarded.isEmpty(), "createNote is never forwarded")
        // now cached: found offline, searchable by its form, readable
        remote.online = false
        assertEquals(200, d.get("/api/v1/dict/entries/$OUTSIDE").status)
        assertEquals("話し相手", ParityJson.str(d.get("/api/v1/dict/entries/$OUTSIDE").json().members["headword"]))
    }

    @Test
    fun aNonAuthoritativePackageOfflineAnswers503NeverNotFound() {
        val remote = FakeRemote(online = false)
        val d = device(remote = remote)
        d.serverReleaseAhead()
        val create = d.call("POST", "/api/v1/notes", newNote(OUTSIDE))
        assertEquals(503, create.status)
        assertEquals("shell_dict_missing", create.code())
        assertEquals(503, d.get("/api/v1/dict/entries/$OUTSIDE").status)
        assertEquals(503, d.get("/api/v1/dict/headwords", "seq=$IN_PACKAGE,$OUTSIDE").status)
        // what the package has is still answered locally, also a search (offline: local)
        assertEquals(200, d.get("/api/v1/dict/entries/$IN_PACKAGE").status)
        assertTrue(d.get("/api/v1/dict/search", "q=%E3%81%AD%E3%81%93").seqs().isNotEmpty())
        assertTrue(d.minted().isEmpty())
    }

    @Test
    fun anAuthoritativePackageOrAServer404IsNotFound() {
        val remote = FakeRemote()
        val d = device(remote = remote)
        // authoritative (scope all, current release): no network, 404 / 422 not_found
        assertEquals(404, d.get("/api/v1/dict/entries/$OUTSIDE").status)
        val create = d.call("POST", "/api/v1/notes", newNote(OUTSIDE))
        assertEquals("/entry_seq not_found", create.fieldError())
        assertTrue(remote.asked.isEmpty())
        assertEquals(listOf(IN_PACKAGE), d.get("/api/v1/dict/headwords", "seq=$OUTSIDE,$IN_PACKAGE").seqs())

        // not authoritative, the server says 404: a standing negative result, asked once
        d.serverReleaseAhead()
        assertEquals(404, d.get("/api/v1/dict/entries/$OUTSIDE").status)
        assertEquals("/entry_seq not_found", d.call("POST", "/api/v1/notes", newNote(OUTSIDE)).fieldError())
        assertEquals(listOf("entry $OUTSIDE"), remote.asked)
        assertTrue(d.minted().isEmpty())
    }
}
