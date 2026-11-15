package kotobaya.android.decks

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotobaya.android.db.createAppDatabase
import kotobaya.android.queue.LocalQueueLoader
import kotobaya.android.search.EntryLabel
import kotobaya.android.search.EntryLabeler
import kotobaya.android.review.asQueueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AND-DECKS: per-deck counts equal the loader's deck-filtered counts; a deck review loads only that deck. */
class DeckRepositoryTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val db = createAppDatabase(driver)
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val loader = LocalQueueLoader(db, { now })

    private fun exec(sql: String) {
        driver.execute(null, sql, 0)
    }

    private fun id(kind: Int, n: Int) = "0193a1b2-%04d-7000-8000-%012d".format(kind, n)

    private fun deck(n: Int, name: String) = exec(
        "INSERT INTO deck (id, name, type, created_at, updated_at) VALUES ('${id(3, n)}', '$name', 'user', 0, 0)",
    )

    /** A vocab note with one card (new or due review), put into [deckN]. */
    private fun word(n: Int, deckN: Int, expression: String, review: Boolean) {
        exec(
            "INSERT INTO note (id, note_type_id, type, entry_seq, fields, created_by_device_id, created_at, updated_at) VALUES " +
                "('${id(1, n)}', '00000000-0000-7000-8000-000000000101', 'vocab', ${100 + n}, '{\"expression\":\"$expression\"}', 'dev', $n, $n)",
        )
        val state = if (review) "'review', ${now - hour}" else "'new', NULL"
        exec("INSERT INTO card (id, note_id, kind, state, due_at, created_at, updated_at) VALUES ('${id(2, n)}', '${id(1, n)}', 'meaning', $state, 0, 0)")
        exec("INSERT INTO deck_item (deck_id, item_key, entry_seq, position, added_at) VALUES ('${id(3, deckN)}', 'e:${100 + n}', ${100 + n}, $n, 0)")
    }

    private fun fixture() {
        exec("INSERT OR REPLACE INTO settings (key, value, hlc, updated_at) VALUES ('scheduler.timezone', '\"Asia/Tokyo\"', 'h', 0)")
        deck(1, "Alpha")
        deck(2, "Beta")
        word(1, 1, "猫", review = true)
        word(2, 1, "犬", review = false)
        word(3, 2, "鳥", review = true)
        word(4, 2, "魚", review = false)
        word(5, 2, "虫", review = false)
    }

    @Test
    fun deckCountsEqualTheFilteredLoaderAndStudyLoadsOnlyThatDeck() {
        fixture()
        val repo = DeckRepository(driver, loader)
        val decks = repo.decks().associateBy { it.id }
        assertEquals(2, decks.size)
        for ((deckId, summary) in decks) {
            assertEquals(loader.load(deckFilter = deckId).counts, summary.counts)
        }
        assertEquals(2, decks.getValue(id(3, 1)).totalCards)
        assertEquals(3, decks.getValue(id(3, 2)).totalCards)
        assertEquals(1, decks.getValue(id(3, 1)).counts.due)
        assertEquals(1, decks.getValue(id(3, 1)).counts.new)

        val studied = loader.asQueueSource().forDeck(id(3, 1)).load(0).items.map { it.cardId }.toSet()
        assertEquals(setOf(id(2, 1), id(2, 2)), studied)
        // No deck argument: the unfiltered queue holds cards of both decks.
        val all = loader.asQueueSource().load(0).items.map { it.cardId }.toSet()
        assertTrue(id(2, 3) in all && id(2, 1) in all)
    }

    @Test
    fun deckCountsStillMatchTheFilteredLoaderWhenALimitBinds() {
        fixture()
        exec("INSERT OR REPLACE INTO settings (key, value, hlc, updated_at) VALUES ('scheduler.new_per_day', '1', 'h', 0)")
        val repo = DeckRepository(driver, loader)
        for (d in repo.decks()) assertEquals(loader.load(deckFilter = d.id).counts, d.counts)
        assertEquals(1, repo.deck(id(3, 2))?.counts?.new)
    }

    private fun status(seq: Int, auto: String?, override: String?) = exec(
        "INSERT INTO word_status (entry_seq, status, auto_status, override_status, override_hlc, updated_at) VALUES " +
            "($seq, '${override ?: auto ?: "new"}', ${auto?.let { "'$it'" }}, ${override?.let { "'$it'" }}, ${override?.let { "'h'" }}, 0)",
    )

    @Test
    fun wordsCarryEffectiveStatusAndFiltersGroupIt() {
        fixture()
        status(102, auto = "learning", override = null) // 犬: learning
        status(103, auto = "learning", override = "never_forget") // 鳥: override beats auto
        status(104, auto = null, override = "blacklisted") // 魚
        // 虫 (105) has no row: new; 猫 (101) too
        val repo = DeckRepository(driver, loader)
        val beta = id(3, 2)
        assertEquals(
            mapOf("鳥" to "never_forget", "魚" to "blacklisted", "虫" to "new"),
            repo.words(beta, 10, 0).associate { it.label to it.status },
        )
        val counts = repo.statusCounts(beta)
        assertEquals(mapOf(DeckFilter.ALL to 3, DeckFilter.NEW to 1, DeckFilter.LEARNING to 0, DeckFilter.KNOWN to 1, DeckFilter.IGNORED to 1), counts)
        assertEquals(listOf("鳥"), repo.words(beta, 10, 0, DeckFilter.KNOWN).map { it.label })
        assertEquals(listOf("魚"), repo.words(beta, 10, 0, DeckFilter.IGNORED).map { it.label })
        assertEquals(listOf("虫"), repo.words(beta, 10, 0, DeckFilter.NEW).map { it.label })
        assertEquals(listOf(102L), repo.entrySeqs(id(3, 1), DeckFilter.LEARNING))
        assertEquals(listOf(101L, 102L), repo.entrySeqs(id(3, 1), DeckFilter.ALL))
    }

    @Test
    fun anEntryItemWithoutANoteShowsTheDictionaryHeadwordNeverTheRawKey() {
        deck(1, "Alpha")
        exec("INSERT INTO deck_item (deck_id, item_key, entry_seq, position, added_at) VALUES ('${id(3, 1)}', 'e:900', 900, 1, 0)")
        exec("INSERT INTO deck_item (deck_id, item_key, entry_seq, position, added_at) VALUES ('${id(3, 1)}', 'e:901', 901, 2, 0)")
        val labeler = EntryLabeler { seqs -> seqs.filter { it == 900L }.associateWith { EntryLabel("桜", "さくら", "cherry") } }
        val words = DeckRepository(driver, loader, labeler).words(id(3, 1), 10, 0)
        assertEquals("桜", words[0].label)
        assertEquals("さくら · cherry", words[0].detail)
        assertEquals(false, words[0].hasNote)
        assertEquals("", words[1].label) // not in the dictionary: the UI shows a placeholder, not "e:901"
    }

    @Test
    fun wordsArePagedInDeckOrder() {
        fixture()
        val repo = DeckRepository(driver, loader)
        assertEquals(listOf("鳥", "魚"), repo.words(id(3, 2), 2, 0).map { it.label })
        assertEquals(listOf("虫"), repo.words(id(3, 2), 2, 2).map { it.label })
    }
}
