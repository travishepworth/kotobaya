package kotobaya.android.dict

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotobaya.android.net.ApiError
import kotobaya.android.review.InstalledRenderData
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.db.app.AppDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A fake server for `getDictRelease` / `getDictEntry` / `getKanji`. */
class FakeGapRemote(var release: Long? = 1) : DictGapRemote {
    val entries = HashMap<Long, String>()
    val kanji = HashMap<String, String>()
    var offline = false
    val asked = ArrayList<String>()

    override suspend fun serverRelease(): Long? {
        if (offline) throw ApiError.Offline
        return release
    }

    override suspend fun entry(seq: Long): RemoteAnswer {
        if (offline) throw ApiError.Offline
        asked += "e:$seq"
        return entries[seq]?.let { RemoteAnswer.Found(it) } ?: RemoteAnswer.NotFound
    }

    override suspend fun kanji(literal: String): RemoteAnswer {
        if (offline) throw ApiError.Offline
        asked += "k:$literal"
        return kanji[literal]?.let { RemoteAnswer.Found(it) } ?: RemoteAnswer.NotFound
    }

    companion object {
        /** A `getDictEntry` body as the server sends it (overlay included). */
        fun entryBody(seq: Long, headword: String, reading: String, gloss: String, kanji: List<String> = emptyList()): String =
            JSONObject()
                .put("seq", seq).put("language", "ja").put("headword", headword).put("headword_reading", reading)
                .put("is_common", false)
                .put("kanji_forms", JSONArray().apply { if (headword != reading) put(JSONObject().put("text", headword).put("common", false).put("tags", JSONArray())) })
                .put("readings", JSONArray().put(JSONObject().put("text", reading).put("common", false).put("tags", JSONArray()).put("applies_to", JSONArray().put("*"))))
                .put(
                    "senses",
                    JSONArray().put(
                        JSONObject().put("pos", JSONArray().put("exp")).put("misc", JSONArray().put("col"))
                            .put("gloss", JSONArray().put(JSONObject().put("lang", "eng").put("text", gloss))),
                    ),
                )
                .put("pos", JSONArray().put("exp")).put("misc", JSONArray().put("col")).put("freq_rank", 71234).put("retired", false)
                .put("freqs", JSONArray().put(JSONObject().put("list_id", "jpdb").put("rank", 71234)))
                .put("pitch", JSONArray().put(JSONObject().put("reading", reading).put("positions", JSONArray().put(0))))
                .put("kanji", JSONArray(kanji))
                .put("status", "learning").put("note_id", "01931f40-1a2b-7c3d-8e4f-000000000a01").put("deck_ids", JSONArray())
                .toString()

        fun kanjiBody(literal: String, meaning: String): String = JSONObject()
            .put("literal", literal).put("on_readings", JSONArray().put("ビョウ")).put("kun_readings", JSONArray().put("ねこ"))
            .put("nanori", JSONArray()).put("meanings", JSONArray().put(meaning)).put("stroke_count", 11).put("grade", JSONObject.NULL)
            .put("jlpt", 1).put("freq_rank", 1702).put("radical", "犬").put("components", JSONArray().put("犭")).put("note_id", JSONObject.NULL)
            .toString()
    }
}

/**
 * SHELL-L9 (user report 2026-11-16): a word from a list deck outside the `top60k` package rendered with no dictionary
 * data. Gap fill puts it in the sidecar cache; review rendering reads it through the one lookup, offline afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class DictGapFillTest {
    private val dir = File.createTempFile("gapfill", "").also { it.delete(); it.mkdirs() }
    private val driver = AndroidSqliteDriver(AppDatabase.Schema, ApplicationProvider.getApplicationContext<Application>(), null)
    private val store = SqlDelightDeviceStore(driver)
    private val cache = DictGapCache(store, driver)
    private val dictStore = DictStore(File(dir, "store"))
    private val dict = InstalledDictionary(dictStore, cache = cache)
    private val remote = FakeGapRemote()

    private var notes = 0

    private fun installPackage(release: Long, scope: String, entries: List<Fx>) {
        val f = PackageFixtures.create(File(dir, "p$release.db"), release, scope, entries)
        f.copyTo(dictStore.installed, overwrite = true)
        dictStore.bump()
    }

    private fun exec(sql: String) = store.transaction { driver.execute(null, sql, 0) }

    private fun vocabNote(seq: Long) {
        notes++
        exec(
            "INSERT INTO note (id, note_type_id, type, entry_seq, fields, origin, created_by_device_id, created_at, updated_at) VALUES " +
                "('n$notes', '00000000-0000-7000-8000-000000000101', 'vocab', $seq, '{}', 'jpdb_import', 'dev', $notes, $notes)",
        )
    }

    private fun kanjiNote(literal: String) {
        notes++
        exec(
            "INSERT INTO note (id, note_type_id, type, kanji_literal, fields, origin, created_by_device_id, created_at, updated_at) VALUES " +
                "('n$notes', '00000000-0000-7000-8000-000000000102', 'kanji', '$literal', '{}', 'auto_kanji', 'dev', $notes, $notes)",
        )
    }

    private fun listDeck(id: String, seqs: List<Long>) {
        exec("INSERT INTO deck (id, name, type, list_key, created_at, updated_at) VALUES ('$id', 'conversation', 'list', 'freq-x-1000', 1, 1)")
        seqs.forEachIndexed { i, s ->
            exec("INSERT INTO deck_item (deck_id, item_key, entry_seq, position, added_at) VALUES ('$id', 'e:$s', $s, $i, 1)")
        }
    }

    private fun gapFill(budget: Int = 300) = DictGapFill(dict, cache, remote, budget = budget, pauseMs = 0, nowMs = { 1_000L })

    private val inTop60k = listOf(Fx(1, "食べる", "たべる", listOf("to eat"), 100))

    @Test
    fun aNoteWhoseEntryIsAbsentFromATop60kPackageRendersAfterOneGapFillRunAndOffline() = runBlocking {
        installPackage(1, "top60k", inTop60k)
        vocabNote(1)
        vocabNote(2_833_100) // the conversation word, rank 71234: outside the package
        remote.entries[2_833_100] = FakeGapRemote.entryBody(2_833_100, "マジで", "マジで", "seriously")
        val render = InstalledRenderData(dict)
        assertNull(render.lookup(2_833_100L), "before: the reported bug (no dictionary data)")

        val report = gapFill().run()
        assertEquals(setOf(GapRef.entry(2_833_100)), report.cached)
        assertEquals(listOf("e:2833100"), remote.asked, "the installed entry is never fetched")

        remote.offline = true
        val data = assertNotNull(render.lookup(2_833_100L))
        assertEquals("マジで", data.expression)
        assertEquals(listOf("seriously"), data.glosses)
        assertEquals(listOf(0), data.pitchDrops)
        // a fresh reader (app restart) over the same learning database, still offline
        val again = InstalledRenderData(InstalledDictionary(dictStore, cache = DictGapCache(store, driver)))
        assertEquals("マジで", again.lookup(2_833_100L)?.expression)
        val full = assertNotNull(dict.entry(2_833_100))
        assertEquals(listOf(FreqRank("jpdb", 71234)), full.freqs)
        // the per-user overlay is not cached
        val body = cache.row(GapRef.entry(2_833_100))?.body.orEmpty()
        assertFalse(body.contains("note_id") || body.contains("\"status\""), body)
        assertEquals(GapFillCounts(cached = 1, missing = 0), cache.counts())
    }

    @Test
    fun nothingReferencedIsSilentlyDroppedEveryReferenceIsInstalledCachedOrMissingWithAReason() = runBlocking {
        installPackage(1, "top60k", inTop60k)
        vocabNote(1)
        vocabNote(500)
        kanjiNote("猫") // the fixture package's kanji table has only 食
        kanjiNote("食")
        remote.entries[500] = FakeGapRemote.entryBody(500, "猫舌", "ねこじた", "sensitive to heat", listOf("猫", "舌"))
        remote.kanji["猫"] = FakeGapRemote.kanjiBody("猫", "cat")
        remote.kanji["舌"] = FakeGapRemote.kanjiBody("舌", "tongue")
        val deckSeqs = listOf(1L, 500L, 404L) + (600L..609L) // 404 is unknown to the server
        for (s in 600L..609L) remote.entries[s] = FakeGapRemote.entryBody(s, "語$s", "ご", "word $s")
        listDeck("d1", deckSeqs)

        val report = gapFill(budget = 5).run()
        val referenced = report.referenced.toSet()
        val expected = (listOf(1L, 500L, 404L) + (600L..609L)).map { GapRef.entry(it) }.toSet() +
            setOf(GapRef.kanji("猫"), GapRef.kanji("食"), GapRef.kanji("舌"))
        assertEquals(expected, referenced)
        // a partition: each reference in exactly one bucket
        assertEquals(referenced, report.installed + report.cached + report.missing.keys)
        assertEquals(referenced.size, report.installed.size + report.cached.size + report.missing.size)
        assertEquals(setOf(GapRef.entry(1), GapRef.kanji("食")), report.installed)
        assertEquals(MissingReason.SERVER_NOT_FOUND, report.missing[GapRef.entry(404)])
        // budget 5: 500, 404, 600, 601, 602 asked; the rest of the deck and the kanji wait for the next run
        assertEquals(setOf(GapRef.entry(500), GapRef.entry(600), GapRef.entry(601), GapRef.entry(602)), report.cached)
        for (s in 603L..609L) assertEquals(MissingReason.BUDGET, report.missing[GapRef.entry(s)])
        assertEquals(MissingReason.BUDGET, report.missing[GapRef.kanji("猫")])
        assertEquals(MissingReason.BUDGET, report.missing[GapRef.kanji("舌")])
        assertEquals(GapFillCounts(cached = 4, missing = 10), cache.counts())
        assertTrue(gapFill().isDue(), "work left over: the next sync runs again")

        // next run: the rest; the standing 404 is not asked again
        remote.asked.clear()
        val second = gapFill().run()
        assertFalse("e:404" in remote.asked)
        assertEquals(mapOf(GapRef.entry(404) to MissingReason.SERVER_NOT_FOUND), second.missing)
        assertEquals("cat", dict.kanji("猫")?.meanings?.single())
        assertEquals(GapFillCounts(cached = 13, missing = 1), cache.counts())
        assertFalse(gapFill().isDue(), "nothing changed and nothing left")
    }

    @Test
    fun offlineRunReportsEveryUnfetchedReferenceAsFetchFailed() = runBlocking {
        installPackage(1, "top60k", inTop60k)
        vocabNote(77)
        remote.offline = true
        val report = gapFill().run()
        assertEquals(mapOf(GapRef.entry(77) to MissingReason.FETCH_FAILED), report.missing)
        assertTrue(report.retryable)
    }

    @Test
    fun aCacheRowIsRemovedWhenTheInstalledPackageGainsTheEntry() = runBlocking {
        installPackage(1, "top60k", inTop60k)
        vocabNote(2_000)
        remote.entries[2_000] = FakeGapRemote.entryBody(2_000, "古語", "こご", "server copy")
        gapFill().run()
        assertNotNull(cache.row(GapRef.entry(2_000)))

        installPackage(2, "top60k", inTop60k + Fx(2_000, "古語", "こご", listOf("package copy"), 59_000))
        remote.release = 2
        val report = gapFill().run()
        assertNull(cache.row(GapRef.entry(2_000)), "the package now holds it: the cache row is dropped")
        assertEquals(1, report.pruned)
        assertEquals(setOf(GapRef.entry(2_000)), report.installed)
        assertEquals("package copy", InstalledRenderData(dict).lookup(2_000L)?.glosses?.single())
    }

    @Test
    fun aNegativeResultIsDroppedWhenTheServerReleaseChangesAndAskedAgain() = runBlocking {
        installPackage(1, "top60k", inTop60k)
        vocabNote(3_000)
        gapFill().run()
        assertEquals(MissingReason.SERVER_NOT_FOUND, gapFill().run().missing[GapRef.entry(3_000)])

        remote.release = 2
        remote.entries[3_000] = FakeGapRemote.entryBody(3_000, "新語", "しんご", "new in release 2")
        remote.asked.clear()
        val report = gapFill().run()
        assertEquals(listOf("e:3000"), remote.asked)
        assertEquals(setOf(GapRef.entry(3_000)), report.cached)
        assertEquals(2L, cache.serverReleaseId())
    }

    @Test
    fun cachedRowsOfAnOlderServerReleaseStayReadableAndAreRefreshed() = runBlocking {
        installPackage(1, "top60k", inTop60k)
        vocabNote(4_000)
        remote.entries[4_000] = FakeGapRemote.entryBody(4_000, "語", "ご", "old")
        gapFill().run()
        remote.release = 2
        remote.offline = true
        assertEquals("old", dict.entry(4_000)?.let { InstalledRenderData.dictionaryData(it).glosses.single() })
        remote.offline = false
        remote.entries[4_000] = FakeGapRemote.entryBody(4_000, "語", "ご", "new")
        val report = gapFill().run()
        assertEquals(setOf(GapRef.entry(4_000)), report.cached)
        assertEquals(2L, cache.row(GapRef.entry(4_000))?.releaseId)
        assertEquals("new", dict.entry(4_000)?.let { InstalledRenderData.dictionaryData(it).glosses.single() })
    }

    @Test
    fun theFullLookupAsksTheServerOnlyWhenNotAuthoritativeAndCachesTheAnswer() = runBlocking {
        installPackage(1, "top60k", inTop60k)
        val lookup = DictLookup(dict, cache, remote)
        remote.entries[5_000] = FakeGapRemote.entryBody(5_000, "語", "ご", "word")
        assertEquals(1L, assertIs<Resolved.Found<DictEntryDetail>>(lookup.resolveEntry(1)).value.seq) // step 1
        assertEquals("語", assertIs<Resolved.Found<DictEntryDetail>>(lookup.resolveEntry(5_000)).value.headword) // step 3
        remote.offline = true
        assertEquals("語", lookup.entry(5_000)?.headword) // step 2, offline
        assertEquals(Resolved.NotOnDevice, lookup.resolveEntry(6_000)) // top60k miss offline: 503
        remote.offline = false
        assertEquals(Resolved.NotFound, lookup.resolveEntry(6_000)) // server 404 -> negative result
        remote.offline = true
        assertEquals(Resolved.NotFound, lookup.resolveEntry(6_000)) // standing negative, offline
        assertFalse(lookup.isAuthoritative())

        installPackage(2, "all", inTop60k)
        cache.recordServerRelease(2)
        assertTrue(lookup.isAuthoritative())
        remote.asked.clear()
        remote.offline = false
        assertEquals(Resolved.NotFound, lookup.resolveEntry(7_000))
        assertTrue(remote.asked.isEmpty(), "an authoritative package answers 404 without asking")
        cache.recordServerRelease(3) // the server moved on: the package is no longer authoritative
        assertFalse(lookup.isAuthoritative())
    }

    @Test
    fun withoutAnyPackageEveryReferenceIsGapFilled() = runBlocking {
        vocabNote(1)
        remote.entries[1] = FakeGapRemote.entryBody(1, "食べる", "たべる", "to eat", listOf("食"))
        remote.kanji["食"] = FakeGapRemote.kanjiBody("食", "eat")
        val report = gapFill().run()
        assertEquals(setOf(GapRef.entry(1), GapRef.kanji("食")), report.cached)
        assertEquals("eat", dict.kanji("食")?.meanings?.single())
    }
}
