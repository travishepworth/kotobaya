package kotobaya.android.shell.api.mining

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotobaya.android.shell.api.Upstream
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.api.mining.MiningTestSupport.IN_PACKAGE
import kotobaya.android.shell.api.mining.MiningTestSupport.OUTSIDE
import kotobaya.android.shell.api.mining.MiningTestSupport.VOCAB
import kotobaya.android.shell.api.mining.MiningTestSupport.call
import kotobaya.android.shell.api.mining.MiningTestSupport.code
import kotobaya.android.shell.api.mining.MiningTestSupport.device
import kotobaya.android.shell.api.mining.MiningTestSupport.fieldError
import kotobaya.android.shell.api.mining.MiningTestSupport.get
import kotobaya.android.shell.api.mining.MiningTestSupport.seqs
import kotobaya.android.shell.api.mining.MiningTestSupport.serverReleaseAhead
import kotobaya.android.shell.parity.ParityDevice
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SHELL.md L-7, C-31 (LF-A6): dictionary reads and `createNote` never reach the server. Every device here has an
 * [Upstream] that fails the test when it is used; each operation answers for a hit and for a miss (`404` / `422` for a
 * known miss, `503 shell_dict_missing` at once for any other miss, an empty page for a search).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class DictFallthroughTest {
    private fun newNote(seq: Long, id: String = "0193b000-0001-7000-8000-0000000000a1") =
        """{"id":"$id","note_type_id":"$VOCAB","entry_seq":$seq}"""

    private fun failingUpstream(): ParityDevice.() -> Unit = { server = Upstream { _, op, _ -> error("forwarded ${op.operationId}") } }

    private fun noForward(d: ParityDevice) = assertTrue(d.forwarded.isEmpty(), "forwarded: ${d.forwarded}")

    @Test
    fun noDictionaryEveryDictionaryReadIs503DictMissingAndNothingIsForwarded() {
        val d = device(withDictionary = false).apply(failingUpstream())
        val reads = listOf(
            "/api/v1/dict/search" to "q=%E3%81%AD%E3%81%93",
            "/api/v1/dict/entries/$IN_PACKAGE" to "",
            "/api/v1/dict/entries/$IN_PACKAGE/examples" to "",
            "/api/v1/dict/release" to "",
            "/api/v1/kanji/%E5%AD%A6" to "",
            "/api/v1/kanji/%E5%AD%A6/words" to "",
        )
        for ((path, query) in reads) {
            val r = d.get(path, query)
            assertEquals(503, r.status, path)
            assertEquals("shell_dict_missing", r.code(), path)
            assertEquals("1", r.headers["kotobaya-local"], path)
        }
        // a list of words never fails for a missing entry: 200, partial
        val h = d.get("/api/v1/dict/headwords", "seq=$IN_PACKAGE")
        assertEquals(200, h.status)
        assertEquals("1", h.headers["kotobaya-shell-partial"])
        assertTrue(h.seqs().isEmpty())
        val create = d.call("POST", "/api/v1/notes", newNote(IN_PACKAGE))
        assertEquals(503, create.status)
        assertEquals("shell_dict_missing", create.code())
        assertTrue(d.minted().isEmpty(), "nothing minted")
        noForward(d)
    }

    @Test
    fun anAuthoritativePackageAnswersHitsAndKnownMissesLocally() {
        val d = device().apply(failingUpstream())
        // hit
        assertEquals(200, d.get("/api/v1/dict/entries/$IN_PACKAGE").status)
        assertEquals(200, d.get("/api/v1/dict/entries/$IN_PACKAGE/examples").status)
        assertEquals(200, d.get("/api/v1/dict/release").status)
        assertEquals(listOf(IN_PACKAGE), d.get("/api/v1/dict/headwords", "seq=$IN_PACKAGE").seqs())
        assertTrue(d.get("/api/v1/dict/search", "q=%E3%81%AD%E3%81%93").seqs().isNotEmpty())
        assertEquals(201, d.call("POST", "/api/v1/notes", newNote(IN_PACKAGE)).status)
        // known miss
        assertEquals(404, d.get("/api/v1/dict/entries/$OUTSIDE").status)
        assertEquals(404, d.get("/api/v1/dict/entries/$OUTSIDE/examples").status)
        val h = d.get("/api/v1/dict/headwords", "seq=$OUTSIDE,$IN_PACKAGE")
        assertEquals(listOf(IN_PACKAGE), h.seqs())
        assertNull(h.headers["kotobaya-shell-partial"], "every missing seq is a known miss")
        assertEquals(200, d.get("/api/v1/dict/search", "q=zzzzqqqq").status)
        assertTrue(d.get("/api/v1/dict/search", "q=zzzzqqqq").seqs().isEmpty(), "a search miss is an empty page")
        assertEquals("/entry_seq not_found", d.call("POST", "/api/v1/notes", newNote(OUTSIDE, "0193b000-0001-7000-8000-0000000000a2")).fieldError())
        noForward(d)
    }

    @Test
    fun aNonAuthoritativePackageAnswersAMissWith503AtOnceAndNeverForwards() {
        val d = device().apply(failingUpstream())
        d.serverReleaseAhead() // the server has a newer release than the phone's package
        for (path in listOf("/api/v1/dict/entries/$OUTSIDE", "/api/v1/dict/entries/$OUTSIDE/examples")) {
            val r = d.get(path)
            assertEquals(503, r.status, path)
            assertEquals("shell_dict_missing", r.code(), path)
        }
        val create = d.call("POST", "/api/v1/notes", newNote(OUTSIDE))
        assertEquals(503, create.status)
        assertEquals("shell_dict_missing", create.code())
        val h = d.get("/api/v1/dict/headwords", "seq=$IN_PACKAGE,$OUTSIDE")
        assertEquals(200, h.status)
        assertEquals(listOf(IN_PACKAGE), h.seqs())
        assertEquals("1", h.headers["kotobaya-shell-partial"], "an unknown miss is partial")
        // what the package has is still answered locally
        assertEquals(200, d.get("/api/v1/dict/entries/$IN_PACKAGE").status)
        assertTrue(d.get("/api/v1/dict/search", "q=%E3%81%AD%E3%81%93").seqs().isNotEmpty())
        // a cursor that is not the device's own is refused, never passed on
        val bad = d.get("/api/v1/dict/search", "q=%E3%81%AD%E3%81%93&cursor=server-cursor")
        assertEquals(422, bad.status)
        assertEquals("/cursor invalid_value", bad.fieldError())
        assertTrue(d.minted().isEmpty())
        noForward(d)
    }
}
