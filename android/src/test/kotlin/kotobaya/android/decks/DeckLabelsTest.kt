package kotobaya.android.decks

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotobaya.android.db.createAppDatabase
import kotobaya.android.queue.LocalQueueLoader
import kotobaya.android.search.EntryLabel
import kotobaya.android.search.EntryLabeler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** AND-LABELS: a deck item is labelled by a word, not its key: fields, then dictionary headword, then the key. */
class DeckLabelsTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val db = createAppDatabase(driver)
    private val loader = LocalQueueLoader(db, { 1_800_000_000_000L })
    private val dict = EntryLabeler { seqs ->
        mapOf(1358280L to EntryLabel("猫", "ねこ", "cat"), 1L to EntryLabel("犬", "いぬ", "dog")).filterKeys { it in seqs }
    }

    private fun exec(sql: String) {
        driver.execute(null, sql, 0)
    }

    private fun fixture() {
        exec("INSERT INTO deck (id, name, type, created_at, updated_at) VALUES ('d1', 'Test', 'user', 0, 0)")
        // a note with empty fields (the Takoboto shape), one with an expression, and items with no note at all
        exec(
            "INSERT INTO note (id, note_type_id, type, entry_seq, fields, created_by_device_id, created_at, updated_at) VALUES " +
                "('n1', '00000000-0000-7000-8000-000000000101', 'vocab', 1, '{}', 'dev', 1, 1), " +
                "('n2', '00000000-0000-7000-8000-000000000101', 'vocab', 2, '{\"expression\":\"鳥\",\"reading\":\"とり\",\"meaning\":\"bird\"}', 'dev', 2, 2)",
        )
        exec("INSERT INTO deck_item (deck_id, item_key, entry_seq, position, added_at) VALUES ('d1', 'e:1358280', 1358280, 0, 0)")
        exec("INSERT INTO deck_item (deck_id, item_key, entry_seq, position, added_at) VALUES ('d1', 'e:1', 1, 1, 0)")
        exec("INSERT INTO deck_item (deck_id, item_key, entry_seq, position, added_at) VALUES ('d1', 'e:2', 2, 2, 0)")
        exec("INSERT INTO deck_item (deck_id, item_key, entry_seq, position, added_at) VALUES ('d1', 'e:999', 999, 3, 0)")
    }

    @Test
    fun entryItemsShowTheHeadwordNotTheKeyWhenTheDictionaryHasThem() {
        fixture()
        val words = DeckRepository(driver, loader, dict).words("d1", 50, 0)
        assertEquals(listOf("猫", "犬", "鳥", "e:999"), words.map { it.label })
        assertEquals("ねこ · cat", words[0].detail)
        assertEquals("とり · bird", words[2].detail)
        assertFalse(words.take(3).any { it.label.startsWith("e:") })
    }

    @Test
    fun withoutADictionaryTheKeyIsTheLastResort() {
        fixture()
        val words = DeckRepository(driver, loader, null).words("d1", 50, 0)
        assertEquals(listOf("e:1358280", "e:1", "鳥", "e:999"), words.map { it.label })
    }
}
