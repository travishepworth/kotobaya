package kotobaya.android.ankiapi.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotobaya.android.ankiapi.provider.AnkiContract
import kotobaya.android.ankiapi.provider.AnkiNote
import kotobaya.android.ankiapi.provider.NewModel
import kotobaya.android.ankiapi.provider.NewNote
import kotobaya.android.ankiapi.provider.NoteFilter
import kotobaya.android.dict.DictStore
import kotobaya.android.dict.Fx
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.dict.PackageFixtures
import kotobaya.android.notes.CreateResult
import kotobaya.android.notes.NoteRequest
import kotobaya.android.notes.cand
import kotobaya.android.notes.DICT
import kotobaya.android.notes.FakeSource
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AND-TAKO-PERF: the `csum` duplicate query answers exactly the notes whose first field (as the unfiltered
 * query renders it) has a requested checksum, without reading a dictionary entry per note.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DuplicateQueryCsumTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val mid = AnkiIds.TAKOBOTO_MODEL_ID
    private val dict = DICT + cand(1000001, "上手", "じょうず", listOf("skilful")) + cand(1000002, "上手", "うわて", listOf("upper part"))

    /** [FAKE_ENTRIES] over [dict], counting calls. */
    private class Counting(private val dict: List<kotobaya.android.notes.EntryCandidate>) : EntryLookup {
        var headwordCalls = 0
        var formsCalls = 0

        override fun headword(seq: Long): Headword? {
            headwordCalls++
            return dict.firstOrNull { it.seq == seq }?.let { Headword(it.headword, it.headwordReading, it.glosses.joinToString("; ")) }
        }

        override fun forms(seqs: Collection<Long>): Map<Long, HeadwordForms> {
            formsCalls++
            return seqs.mapNotNull { s -> dict.firstOrNull { it.seq == s }?.let { s to HeadwordForms(it.headword, it.headwordReading) } }.toMap()
        }
    }

    private fun csum(n: AnkiNote) = AnkiContract.fieldChecksum(n.fields[0])

    @Test
    fun csumQueryReturnsTheSameRowsAsFilteringTheUnfilteredQuery() = sameRows(indexed = false)

    /** AND-TAKO-PERF2: the same answers from the checksum index (the notes made outside the store are pending changes). */
    @Test
    fun indexedCsumQueryReturnsTheSameRowsAsFilteringTheUnfilteredQuery() = sameRows(indexed = true)

    private fun sameRows(indexed: Boolean) {
        val d = RoboDevice(app)
        val entries = Counting(dict)
        val s = if (indexed) {
            indexedStore(d, memoryMap(app), FakeSource(dict), entries = entries).also { it.warmIndexes() }
        } else {
            takobotoStore(d, memoryMap(app), FakeSource(dict), entries = entries)
        }
        // entry-linked notes made outside the API: no fields.expression, headword from the dictionary
        assertIs<CreateResult.Created>(noteCreator(d, FakeSource(dict), seed = 13).create(NoteRequest(word = "食べる", reading = "たべる")))
        assertIs<CreateResult.Created>(noteCreator(d, FakeSource(dict), seed = 14).create(NoteRequest(word = "上手", reading = "うわて")))
        // stored expression with HTML entities (the checksum strips/decodes it)
        assertIs<CreateResult.Created>(
            noteCreator(d, FakeSource(dict), seed = 15).create(
                NoteRequest(word = "食べ物", reading = "たべもの", fields = mapOf("expression" to "食べ物&nbsp;")),
            ),
        )
        // through the API (expression stored) and an unmatched word
        s.add(NewNote(mid, listOf("猫", "ねこ", "", "", ""), emptyList(), AnkiIds.INBOX_DECK_ID, "jp.takoboto"))
        s.add(NewNote(mid, listOf("ほげほげ", "", "", "", ""), emptyList(), AnkiIds.INBOX_DECK_ID, "jp.takoboto"))
        // a client model whose first field is the reading
        val m = s.addModel(NewModel("Reading first", listOf("Reading", "Japanese", "Meaning"), 1, "", null, 0, 0, null, null, null))
        s.add(NewNote(m.id, listOf("じょうず", "上手", ""), emptyList(), AnkiIds.INBOX_DECK_ID, "jp.takoboto"))

        for (modelId in listOf(mid, m.id, null)) {
            val all = s.findNotes(NoteFilter(modelId))
            assertEquals(6, all.size, "model $modelId: ${all.map { it.fields }}")
            val sets = all.map { setOf(csum(it)) } + listOf(all.map(::csum).toSet(), setOf(567_984_260L, 1L), setOf(1L), emptySet())
            for (set in sets) {
                val expected = all.filter { csum(it) in set }.map { it.id to it.fields }
                assertEquals(expected, s.findNotes(NoteFilter(modelId, set)).map { it.id to it.fields }, "model $modelId csums $set")
            }
        }
        assertEquals(listOf("食べる"), s.findNotes(NoteFilter(mid, setOf(567_984_260L))).map { it.fields[0] }, "Takoboto's own query")
        assertEquals(
            setOf("じょうず" to m.id, "上手" to mid),
            s.findNotes(NoteFilter(null, setOf(AnkiContract.fieldChecksum("じょうず"), AnkiContract.fieldChecksum("上手"))))
                .map { it.fields[0] to it.modelId }.toSet(),
            "without a model each note is checksummed through the model it was linked with",
        )

        entries.headwordCalls = 0
        entries.formsCalls = 0
        assertEquals(emptyList(), s.findNotes(NoteFilter(mid, setOf(1L))))
        assertEquals(0, entries.headwordCalls, "a miss reads no dictionary entry")
        assertEquals(1, entries.formsCalls, "headwords come from one batched lookup")
    }

    @Test
    fun installedLookupFormsMatchHeadwordAndSkipMissingEntries() {
        val dir = File.createTempFile("forms", "").also { it.delete(); it.mkdirs() }
        val store = DictStore(File(dir, "store"))
        val none = InstalledEntryLookup(InstalledDictionary(store))
        assertEquals(emptyMap(), none.forms(listOf(1L, 2L)), "no dictionary installed")

        val fx = (1L..1203L).map { Fx(it, "語$it", "ご$it", listOf("gloss $it"), it.toInt()) }
        PackageFixtures.create(File(dir, "p.db"), 1, "top60k", fx)
        File(dir, "p.db").copyTo(store.installed)
        val lookup = InstalledEntryLookup(InstalledDictionary(store))
        val asked = (1L..1203L).toList() + listOf(5_000_000L, 7L)
        val forms = lookup.forms(asked)
        assertEquals(1203, forms.size, "every present seq across chunks, missing ones left out")
        for (seq in listOf(1L, 500L, 501L, 1000L, 1203L)) {
            val h = lookup.headword(seq)!!
            assertEquals(HeadwordForms(h.word, h.reading), forms[seq])
        }
        assertTrue(lookup.forms(emptyList()).isEmpty())
    }
}
