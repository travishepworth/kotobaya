package kotobaya.android.review

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotobaya.db.app.AppDatabase
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

import kotobaya.android.dict.DictStore
import kotobaya.android.dict.Fx
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.dict.PackageFixtures
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.queue.QueueItem
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.ReviewQueue
import kotobaya.sync.client.apply.DeviceOpApplier
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.UuidV7Source
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AND-CARDHTML regression. On the phone every due card belongs to a note made from a dictionary entry: vocab notes
 * (type ...101) with `fields = {}` and an `entry_seq`, kanji notes (type ...102) with only `literal`. The renderer
 * fills those empty fields from dictionary data the caller passes in, but production's [ReviewBackend] was built
 * without a [DictionaryLookup] (the default returned null), so the front rendered as an empty
 * `<div class="kb-expression"></div>`. Anonymized fixture of that shape; real on-device dictionary package.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class CardHtmlRegressionTest {
    private val driver = AndroidSqliteDriver(AppDatabase.Schema, ApplicationProvider.getApplicationContext<Application>(), null)
    private val db = AppDatabase(driver)
    private val store = SqlDelightDeviceStore(driver, db)
    private val minter = OpMinter(store, DeviceOpApplier<SqlLearningTables>(), Clock { NOW }, UuidV7Source(Random(3)))

    init {
        db.transaction {
            exec(
                "INSERT INTO note (id, note_type_id, type, entry_seq, fields, origin, created_by_device_id, created_at, updated_at) VALUES " +
                    "('$VOCAB_NOTE', '00000000-0000-7000-8000-000000000101', 'vocab', $SEQ, '{}', 'jpdb_import', 'dev', 1, 1)",
            )
            exec(
                "INSERT INTO note (id, note_type_id, type, kanji_literal, fields, origin, created_by_device_id, created_at, updated_at) VALUES " +
                    "('$KANJI_NOTE', '00000000-0000-7000-8000-000000000102', 'kanji', '食', '{\"literal\":\"食\"}', 'auto_kanji', 'dev', 1, 1)",
            )
            card(VOCAB_CARD, VOCAB_NOTE, "meaning")
            card(KANJI_CARD, KANJI_NOTE, "keyword")
        }
    }

    private fun exec(sql: String) {
        driver.execute(null, sql, 0)
    }

    private fun card(id: String, note: String, kind: String) = exec(
        "INSERT INTO card (id, note_id, kind, state, due_at, suspended, stability, difficulty, interval_days, last_review_at, reps, " +
            "created_at, updated_at) VALUES ('$id', '$note', '$kind', 'review', ${NOW - 60_000L}, 0, 10.0, 5.0, 10, ${NOW - 10 * 86_400_000L}, 3, 0, 0)",
    )

    private fun installedDictionary(): InstalledDictionary {
        val dir = File.createTempFile("cardhtml", "").also { it.delete(); it.mkdirs() }
        val store = DictStore(File(dir, "store"))
        PackageFixtures.create(File(dir, "p.db"), 1, "top60k", listOf(Fx(SEQ, "食べる", "たべる", listOf("to eat"), 100)))
        File(dir, "p.db").copyTo(store.installed)
        return InstalledDictionary(store)
    }

    private fun backend(dictionary: DictionaryLookup, kanji: KanjiLookup, cardId: String) = ReviewBackend(
        store = store,
        minter = minter,
        queue = QueueSource { ReviewQueue(listOf(QueueItem(cardId, QueueItemKind.REVIEW, null)), emptyList(), null) },
        prefs = { ReviewPrefs(furigana = false) },
        dictionary = dictionary,
        kanji = kanji,
        clock = { NOW },
    )

    private fun shown(b: ReviewBackend<*>): ShownCard {
        val s = b.newSession()
        s.start()
        return assertIs<ReviewState.Showing>(s.state.value).card
    }

    private fun recordingPayloads(sink: MutableList<String>) = CardPayloads(CardFailures(log = {}, report = { sink += it }))

    @Test
    fun aDictionaryVocabCardRendersItsExpressionFromTheInstalledDictionary() {
        val data = InstalledRenderData(installedDictionary())
        val card = shown(backend(data, data, VOCAB_CARD))
        assertTrue("食べる" in card.front.html, card.front.html)
        assertTrue("to eat" in card.back.html, card.back.html)

        val reports = mutableListOf<String>()
        val page = assertIs<CardPayload.Page>(recordingPayloads(reports).of(card.front, dark = false))
        assertTrue("食べる" in page.html && page.html.startsWith("<!doctype html>"))
        assertEquals(emptyList(), reports)
    }

    @Test
    fun aKanjiCardGetsItsKeywordAndReadingsFromTheInstalledDictionary() {
        val data = InstalledRenderData(installedDictionary())
        val card = shown(backend(data, data, KANJI_CARD))
        assertTrue("食" in card.front.html, card.front.html)
        assertTrue("eat" in card.back.html && "ショク" in card.back.html, card.back.html)
    }

    /** The pre-fix production wiring (no dictionary): an empty card, which must become the error state, not a page. */
    @Test
    fun withoutDictionaryDataTheBlankCardIsAnErrorStateAndAReport() {
        val card = shown(backend(DictionaryLookup { null }, KanjiLookup { null }, VOCAB_CARD))
        assertEquals("""<div class="kb-card kb-vocab kb-kind-meaning kb-front"><div class="kb-expression"></div></div>""", card.front.html)
        val reports = mutableListOf<String>()
        val failed = assertIs<CardPayload.Failed>(recordingPayloads(reports).of(card.front, dark = true))
        assertEquals("blank render vocab/meaning front", failed.reason)
        assertEquals(listOf("blank render vocab/meaning front"), reports)
    }

    /** No dictionary installed at all (fresh install): lookups are null, nothing throws. */
    @Test
    fun noInstalledDictionaryMeansNoDataNotACrash() {
        val dir = File.createTempFile("nodict", "").also { it.delete(); it.mkdirs() }
        val data = InstalledRenderData(InstalledDictionary(DictStore(File(dir, "store"))))
        assertEquals(null, data.lookup(SEQ))
        assertEquals(null, data.lookup("食"))
    }

    private companion object {
        const val NOW = 1_794_636_600_000L
        const val SEQ = 1_358_280L
        const val VOCAB_NOTE = "0193a1b2-0001-7000-8000-000000000001"
        const val KANJI_NOTE = "0193a1b2-0001-7000-8000-000000000002"
        const val VOCAB_CARD = "0193a1b2-0002-7000-8000-000000000001"
        const val KANJI_CARD = "0193a1b2-0002-7000-8000-000000000002"
    }
}
