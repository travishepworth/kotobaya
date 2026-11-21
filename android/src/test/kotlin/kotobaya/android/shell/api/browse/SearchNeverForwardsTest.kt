package kotobaya.android.shell.api.browse

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.dict.GapRef
import kotobaya.android.dict.GapRow
import kotobaya.android.shell.api.actions.ActionsTestSupport
import kotobaya.android.shell.api.actions.ActionsTestSupport.C1
import kotobaya.android.shell.api.actions.ActionsTestSupport.SEQ1
import kotobaya.android.shell.api.actions.ActionsTestSupport.json
import kotobaya.android.shell.api.actions.CardQueryResult
import kotobaya.android.shell.api.actions.LocalCardQuery
import kotobaya.android.shell.api.browse.BrowseTestSupport.ids
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonString
import kotobaya.search.DefaultSearchQueryParser
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LF-A5 (C-31, SHELL.md 4.3 `listCards` with `query`): every search string is answered on the device, with an
 * [kotobaya.android.shell.api.Upstream] that fails the test when called. The server's selection is core's compiler
 * over the same rows; its SQLite form without any size guard ([LocalCardQuery] with an unbounded cap) is the reference.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class SearchNeverForwardsTest {
    private fun rows(): List<Pair<Char, String>> = File("../contracts/core/search-examples.tsv").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line -> line.split('\t').let { it[0][0] to (ParityJson.parse(it[1]) as JsonString).value } }

    private fun device(withDictionary: Boolean = true): ParityDevice {
        Class.forName("org.sqlite.JDBC")
        return ParityDevice(ActionsTestSupport.X, ActionsTestSupport.T0, routes, withDictionary = withDictionary)
            .also { it.pull(ActionsTestSupport.seed(), "seed"); it.clock.now = ActionsTestSupport.T0 + 10_000 }
    }

    @Test
    fun everySearchExampleIsAnsweredOnTheDeviceWithTheServersIds() {
        val d = device()
        val api = LocalSearchApi(d)
        val reference = LocalCardQuery(d.driver, d.database, d.installedDictionary, dictFormsMaxSeqs = Int.MAX_VALUE)
        var answered = 0
        for ((expect, q) in rows()) {
            val res = api.cards(q, "sort=created_at&limit=500")
            if (expect == '-') {
                assertEquals(422, res.status, q)
                val first = ParityJson.obj((res.json().members["errors"] as JsonArray).items[0])
                assertEquals(DefaultSearchQueryParser.parse(q).errors.first().span.start.toLong(), ParityJson.long(first.members["position"]), q)
                continue
            }
            val expected = reference.select(q, d.clock.now)
            assertTrue(expected is CardQueryResult.Ids, "$q: $expected")
            assertEquals(200, res.status, "$q: ${res.body}")
            assertEquals(expected.ids, ids(res).sorted(), q)
            answered++
        }
        assertEquals(rows().count { it.first == '+' }, answered)
    }

    @Test
    fun aDictionaryFormTermBeyondTheOldGuardIsAnsweredNotForwarded() {
        val d = device()
        // the guard of the legacy selector forwarded this; the device evaluator answers it
        assertTrue(LocalCardQuery(d.driver, d.database, d.installedDictionary, dictFormsMaxSeqs = 0).select("うっかり", d.clock.now) is CardQueryResult.Forward)
        val res = LocalSearchApi(d).cards("うっかり")
        assertEquals(200, res.status, res.body)
        assertEquals(listOf(C1), ids(res), "the dictionary form finds note 1 (its fields are empty)")
    }

    /**
     * Missing package (SHELL.md 4.3, 10.7 a): dictionary terms use the sidecar cache alone; a note whose entry is in
     * neither store does not match through the dictionary (its own text still does). A local `200`, never a forward.
     */
    @Test
    fun withoutADictionaryPackageTheSidecarCacheAloneAnswersLocally() {
        val d = device(withDictionary = false)
        assertTrue(!d.installedDictionary.isAvailable())
        val api = LocalSearchApi(d)
        val none = api.cards("うっかり")
        assertEquals(200, none.status, none.body)
        assertEquals(emptyList(), ids(none), "entry in neither store: no dictionary match")
        assertEquals(2, ids(api.cards("")).size, "other terms are unaffected")

        val body = """{"seq":$SEQ1,"headword":"うっかり","headword_reading":"うっかり","kanji_forms":[],""" +
            """"readings":[{"text":"ウッカリ"}],"senses":[]}"""
        d.gapCache.put(listOf(GapRow(GapRef.entry(SEQ1), 1, body, 0L)))
        val cached = api.cards("うっかり")
        assertEquals(200, cached.status, cached.body)
        assertEquals(listOf(C1), ids(cached), "found through the cached entry's reading (katakana folded to its norm)")
    }

    @Test
    fun withNeitherPackageNorCacheTextTermsMatchNoteTextOnly() {
        val d = device(withDictionary = false)
        val res = LocalSearchApi(d, dictionary = null).cards("うっかり")
        assertEquals(200, res.status, res.body)
        assertEquals(emptyList(), ids(res))
    }
}
