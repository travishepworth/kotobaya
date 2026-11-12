package kotobaya.android.queue

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotobaya.android.db.createAppDatabase
import kotobaya.contracts.Reserved
import kotobaya.contracts.queue.AddKanjiMode
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.queue.DefaultQueueBuilder
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T304a: the Android loader against a fixture DB, the core builder and the server loader's rules. */
class LocalQueueLoaderTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val db = createAppDatabase(driver)
    private var now = NOW

    private fun loader() = LocalQueueLoader(db, { now })

    private fun exec(sql: String) {
        driver.execute(null, sql, 0)
    }

    private fun scalarLong(sql: String): Long =
        driver.executeQuery(null, sql, { c -> QueryResult.Value(if (c.next().value) c.getLong(0) else null) }, 0).value!!

    private fun uuid(kind: Int, n: Int) = "0193a1b2-%04d-7000-8000-%012d".format(kind, n)
    private fun note(n: Int) = uuid(1, n)
    private fun card(n: Int) = uuid(2, n)
    private fun deck(n: Int) = uuid(3, n)

    private fun setting(key: String, json: String) =
        exec("INSERT OR REPLACE INTO settings (key, value, hlc, updated_at) VALUES ('$key', '${json.replace("'", "''")}', 'h', 0)")

    private fun addNote(n: Int, entrySeq: Long? = null, expression: String = "", created: Long = n.toLong(), deleted: Boolean = false, mergedInto: Int? = null) {
        val fields = if (expression.isEmpty()) "{}" else """{"expression":"$expression"}"""
        exec(
            "INSERT INTO note (id, note_type_id, type, entry_seq, fields, created_by_device_id, created_at, updated_at, deleted_at, merged_into_id) " +
                "VALUES ('${note(n)}', '00000000-0000-7000-8000-000000000101', 'vocab', ${entrySeq ?: "NULL"}, '$fields', 'dev', $created, $created, " +
                "${if (deleted) "1" else "NULL"}, ${mergedInto?.let { "'${note(it)}'" } ?: "NULL"})",
        )
    }

    private fun addCard(
        n: Int, noteN: Int, kind: String = "meaning", state: String = "new", due: Long? = null,
        suspended: Boolean = false, buriedUntil: Long? = null, lastReviewId: String? = null,
    ) = exec(
        "INSERT INTO card (id, note_id, kind, state, due_at, suspended, buried_until, last_review_id, created_at, updated_at) VALUES " +
            "('${card(n)}', '${note(noteN)}', '$kind', '$state', ${due ?: "NULL"}, ${if (suspended) 1 else 0}, ${buriedUntil ?: "NULL"}, " +
            "${lastReviewId?.let { "'$it'" } ?: "NULL"}, 0, 0)",
    )

    private fun addDeck(n: Int, enabled: Boolean = true, deleted: Boolean = false, settings: String = "{}", type: String = "user") =
        exec(
            "INSERT INTO deck (id, name, type, enabled, settings, created_at, updated_at, deleted_at) VALUES " +
                "('${deck(n)}', 'd$n', '$type', ${if (enabled) 1 else 0}, '$settings', 0, 0, ${if (deleted) "1" else "NULL"})",
        )

    private fun addItem(deckN: Int, key: String, entrySeq: Long? = null, noteN: Int? = null, position: Long = 0) =
        exec(
            "INSERT INTO deck_item (deck_id, item_key, entry_seq, note_id, position, added_at) VALUES " +
                "('${deck(deckN)}', '$key', ${entrySeq ?: "NULL"}, ${noteN?.let { "'${note(it)}'" } ?: "NULL"}, $position, 0)",
        )

    private fun status(entry: Long, override: String?) =
        exec(
            "INSERT OR REPLACE INTO word_status (entry_seq, status, override_status, override_hlc, updated_at) VALUES " +
                "($entry, '${override ?: "new"}', ${override?.let { "'$it'" } ?: "NULL"}, ${if (override != null) "'h'" else "NULL"}, 0)",
        )

    private fun fixture() {
        setting("scheduler.timezone", "\"Asia/Tokyo\"")
        addDeck(1)
        addDeck(2, deleted = true)
        // N1 (entry 100, in deck 1): a NEW card; its sibling card of the same note is REVIEW due.
        addNote(1, entrySeq = 100)
        addCard(1, 1, "meaning")
        addCard(2, 1, "reading", state = "review", due = NOW - HOUR)
        addItem(1, "e:100", entrySeq = 100, position = 1)
        // N2L merged into N2: the deck item names the loser, the card is the survivor's.
        addNote(2, created = 2)
        addNote(3, created = 3, deleted = true, mergedInto = 2)
        addCard(3, 2)
        addItem(1, "n:${note(3)}", noteN = 3, position = 2)
        // N4: a REVIEW card due today, a learning card due in 10 minutes, a suspended and a buried card.
        addNote(4)
        addCard(4, 4, state = "review", due = NOW - 2 * HOUR)
        addNote(5)
        addCard(5, 5, state = "learning", due = NOW + 600_000)
        addNote(6)
        addCard(6, 6, suspended = true)
        addNote(7)
        addCard(7, 7, buriedUntil = NOW + HOUR)
        // never_forget (entry 200) and blacklisted (entry 300) words.
        addNote(8, entrySeq = 200)
        addCard(8, 8, state = "review", due = NOW - HOUR)
        status(200, "never_forget")
        addNote(9, entrySeq = 300)
        addCard(9, 9)
        status(300, "blacklisted")
        // Refused cache: NEW with the refused marker; must not be offered.
        addNote(10)
        addCard(10, 10, lastReviewId = Reserved.REPLAY_REFUSED_ID)
        // N3's only deck is deleted: the card falls back to the deckless source.
        addNote(11, created = 11)
        addCard(11, 11)
        addItem(2, "n:${note(11)}", noteN = 11)
    }

    @Test
    fun loaderQueueEqualsCoreBuilderAndFollowsTheLoaderRules() {
        fixture()
        val loaded = loader().load()
        assertEquals(DefaultQueueBuilder.build(loaded.input), loaded.queue)

        val ids = loaded.input.candidates.map { it.cardId }.toSet()
        // Not candidates: suspended (6) and refused (10). Never-forget (8) and blacklisted (9) are passed with their
        // override (as the server does) and the builder drops them; the buried card (7) is dropped by the builder too.
        assertEquals(setOf(1, 2, 3, 4, 5, 7, 8, 9, 11).map(::card).toSet(), ids)
        assertFalse(card(8) in loaded.queue.items.map { it.cardId } || card(9) in loaded.queue.items.map { it.cardId })
        assertFalse(card(10) in ids)
        // The refused card is still findable by a search-style lookup.
        assertEquals(1, scalarLong("SELECT COUNT(*) FROM card WHERE id = '${card(10)}'"))

        val byId = loaded.input.candidates.associateBy { it.cardId }
        // The merged-loser item resolves to the survivor; a membership of the deleted deck is dropped.
        assertEquals(listOf(deck(1)), byId.getValue(card(3)).memberships.map { it.deckId })
        assertEquals(2L, byId.getValue(card(3)).memberships.single().position)
        assertTrue(byId.getValue(card(11)).memberships.isEmpty())
        assertEquals(listOf(deck(1)), loaded.input.decks.map { it.id })

        val items = loaded.queue.items
        assertEquals(setOf(card(2), card(4), card(5)), items.filter { it.kind != QueueItemKind.NEW }.map { it.cardId }.toSet())
        // Sibling burying: N1's REVIEW card (2) is taken, so its NEW card (1) is skipped.
        assertFalse(card(1) in items.map { it.cardId })
        assertEquals(QueueCounts(due = 2, new = 2, learning = 1), loaded.counts)
        assertEquals(items.take(3), loaded.sessionPrefix(3))
    }

    @Test
    fun neverForgetAndBlacklistComeBackWhenTheStatusIsCleared() {
        fixture()
        assertFalse(card(8) in loader().load().queue.items.map { it.cardId })
        assertFalse(card(9) in loader().load().queue.items.map { it.cardId })
        status(200, null)
        status(300, null)
        val items = loader().load().queue.items.map { it.cardId }
        assertTrue(card(8) in items)
        assertTrue(card(9) in items)
    }

    @Test
    fun countsAndForecastAcrossATokyoDayBoundary() {
        setting("scheduler.timezone", "\"Asia/Tokyo\"")
        addNote(1)
        addCard(1, 1, state = "review", due = NOW + 2 * HOUR) // 03:00 JST, inside today's study day (ends 04:00)
        addNote(2)
        addCard(2, 2, state = "review", due = NOW + 4 * HOUR) // 05:00 JST, tomorrow's day
        addNote(3)
        addCard(3, 3, state = "review", due = NOW - 30 * HOUR) // overdue

        val loaderNow = loader().load()
        assertEquals(QueueCounts(due = 2, new = 0, learning = 0), loaderNow.counts)
        assertEquals(StudyDayOf(NOW), loaderNow.input.day.startMs to loaderNow.input.day.endMs)
        assertEquals(NOW, loader().nextDueInstant())
        val forecast = loader().forecast24h()
        assertEquals(24, forecast.size)
        assertEquals(1, forecast[0]) // overdue
        assertEquals(1, forecast[2])
        assertEquals(1, forecast[4])
        assertEquals(3, forecast.sum())

        // After the 04:00 JST rollover the later card (05:00) is due within the new study day.
        now = NOW + 3 * HOUR + 1
        assertEquals(QueueCounts(due = 3, new = 0, learning = 0), loader().load().counts)
    }

    @Test
    fun nextDueInstantWhenNothingIsDueNow() {
        addNote(1)
        addCard(1, 1, state = "review", due = NOW + 30 * HOUR)
        assertEquals(NOW + 30 * HOUR, loader().nextDueInstant())
    }

    @Test
    fun todayLimitsCountOnlyTheStudyDayInTheUsersZone() {
        setting("scheduler.new_per_day", "1.0")
        setting("scheduler.timezone", "\"Asia/Tokyo\"")
        addNote(1)
        addCard(1, 1)
        addNote(2)
        addCard(2, 2)
        // An answer that introduced card 1 before today's 04:00 JST start does not count against today's limit.
        exec("INSERT INTO review_log (id, card_id, device_id, hlc, reviewed_at, review_type, rating) VALUES ('${uuid(4, 1)}', '${card(1)}', 'd', 'h1', ${NOW - 30 * HOUR}, 'learning', 3)")
        assertEquals(1, loader().load().counts.new)
        // The same answer inside today's day uses the one daily slot.
        exec("UPDATE review_log SET reviewed_at = ${NOW - HOUR}")
        assertEquals(0, loader().load().counts.new)
    }

    @Test
    fun settingsIntsFollowTheBinary64ValidityTest() {
        fun newPerDay(json: String?): Int {
            exec("DELETE FROM settings")
            json?.let { setting("scheduler.new_per_day", it) }
            return QueueSettings.from(
                mapOf<String, String>().let { m -> if (json == null) m else m + ("scheduler.new_per_day" to json) },
            ).newPerDay
        }
        assertEquals(5, newPerDay("5.0"))
        assertEquals(100, newPerDay("1e2"))
        assertEquals(20, newPerDay("3.5"))
        assertEquals(20, newPerDay("1e400"))
        assertEquals(20, newPerDay("\"3\""))
        assertEquals(20, newPerDay("true"))
        assertEquals(20, newPerDay("null"))
        assertEquals(20, newPerDay("99999999999"))
        assertEquals(20, newPerDay(null))
        assertEquals(4, QueueSettings.from(mapOf("scheduler.day_rollover_hour" to "24")).dayRolloverHour)
    }

    @Test
    fun addKanjiCountsOnlyForTheExactStrings() {
        setting("scheduler.kanji_before_vocab", "true")
        addDeck(1, settings = """{"add_kanji":"off"}""")
        addDeck(2, settings = """{"add_kanji":"Before"}""")
        addDeck(3, settings = """{"add_kanji":"alongside"}""")
        addDeck(4, settings = """{"add_kanji":null}""")
        addDeck(5, settings = """{"add_kanji":1}""")
        val modes = loader().load().input.decks.associate { it.id to it.addKanji }
        assertEquals(AddKanjiMode.OFF, modes[deck(1)])
        assertEquals(AddKanjiMode.BEFORE, modes[deck(2)])
        assertEquals(AddKanjiMode.ALONGSIDE, modes[deck(3)])
        assertEquals(AddKanjiMode.BEFORE, modes[deck(4)])
        assertEquals(AddKanjiMode.BEFORE, modes[deck(5)])
        assertEquals(AddKanjiMode.BEFORE, loader().load().input.decklessAddKanji)
    }

    @Test
    fun studyDayAcrossDstChanges() {
        val ny = ZoneId.of("America/New_York")
        fun ms(zone: ZoneId, date: String, hour: Int) = LocalDate.parse(date).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        // Spring forward 2024-03-10: the day that started 03-09 04:00 lasts 23 hours.
        val spring = studyDayOf(ms(ny, "2024-03-10", 1), ny, 4).day
        assertEquals(ms(ny, "2024-03-09", 4), spring.startMs)
        assertEquals(ms(ny, "2024-03-10", 4), spring.endMs)
        assertEquals(23 * HOUR, spring.endMs - spring.startMs)
        // Fall back 2024-11-03: 25 hours.
        val fall = studyDayOf(ms(ny, "2024-11-03", 3), ny, 4).day
        assertEquals(25 * HOUR, fall.endMs - fall.startMs)
        // A rollover inside the gap (02:30 does not exist; hour 2 on 03-10) uses the first instant after the gap.
        val gap = studyDayOf(ms(ny, "2024-03-10", 12), ny, 2).day
        assertEquals(ms(ny, "2024-03-10", 3), gap.startMs)
        // Tokyo example of the StudyDay KDoc.
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(ms(tokyo, "2024-01-01", 4), studyDayOf(NOW, tokyo, 4).day.startMs)
    }

    @Test
    fun loaderCountsUseTheDstDayOfTheUsersZone() {
        setting("scheduler.timezone", "\"America/New_York\"")
        val ny = ZoneId.of("America/New_York")
        // now = 2024-03-10 00:30 EST; the study day ends 04:00 EDT = 08:00Z (23 h day).
        now = LocalDate.parse("2024-03-10").atTime(0, 30).atZone(ny).toInstant().toEpochMilli()
        val dayEnd = LocalDate.parse("2024-03-10").atTime(4, 0).atZone(ny).toInstant().toEpochMilli()
        addNote(1)
        addCard(1, 1, state = "review", due = dayEnd - 1)
        addNote(2)
        addCard(2, 2, state = "review", due = dayEnd)
        assertEquals(1, loader().load().counts.due)
        assertEquals(dayEnd, loader().load().input.day.endMs)
    }

    private fun StudyDayOf(at: Long): Pair<Long, Long> = studyDayOf(at, ZoneId.of("Asia/Tokyo"), 4).day.let { it.startMs to it.endMs }

    private companion object {
        const val NOW = 1_704_124_800_000L // 2024-01-02T01:00+09:00
        const val HOUR = 3_600_000L
    }
}
