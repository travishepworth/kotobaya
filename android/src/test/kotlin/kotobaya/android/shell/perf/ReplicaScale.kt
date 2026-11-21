package kotobaya.android.shell.perf

import app.cash.sqldelight.db.SqlDriver
import kotlin.random.Random
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.sync.client.store.bookkeeping

/**
 * PERF-LF: a synthetic replica at the size of a real user (several thousand notes and cards, tens of thousands of
 * review_log rows over two years, list decks with thousands of items, a mined deck of `n:` items, recent failures for
 * the hot lists). Written with plain SQL in one transaction (test data only; production writes go through ops).
 * Deterministic for a seed.
 */
internal object ReplicaScale {
    const val DAY = 86_400_000L
    private const val VOCAB_TYPE = "00000000-0000-7000-8000-000000000101"
    private const val KANJI_TYPE = "00000000-0000-7000-8000-000000000102"
    private const val SERVER = "00000000-0000-7000-8000-000000000001"

    /**
     * Defaults: the row counts of the user's real replica (first sync of the 0.1.31 backup, 2026-11-21): 9 842 notes
     * (7 621 vocab, 2 221 kanji), 10 950 cards (~90% started, ~8% due today), 81 640 reviews, 46 778 deck items (four list
     * decks of 8-11k items), ~300 cards failed in the last 14 days.
     */
    data class Size(
        val vocabNotes: Int = 7_600,
        val kanjiNotes: Int = 2_200,
        val reviewsPerStartedCard: Int = 8,
        val listDecks: Int = 4,
        val listDeckItems: Int = 11_000,
        val minedDeckItems: Int = 1_500,
    )

    class Seeded(val notes: Int, val cards: Int, val reviews: Int, val deckItems: Int)

    /** Row counts of an existing replica. */
    fun counts(driver: SqlDriver): Seeded {
        fun n(table: String): Int = driver.executeQuery(null, "SELECT COUNT(*) FROM $table", { c: app.cash.sqldelight.db.SqlCursor -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0)!!.toInt()) }, 0).value
        return Seeded(n("note"), n("card"), n("review_log"), n("deck_item"))
    }

    private fun uuid(kind: Int, n: Int): String = "%08x-0000-7000-8000-%012x".format(kind, n)

    /**
     * [dictSeqs]: entries the device's dictionary holds; every fifth item of a list deck is one of them (no note yet), so
     * the queue finds deck entries it can materialize near the top of each deck, as with the real dictionary.
     */
    fun seed(driver: SqlDriver, store: SqlDelightDeviceStore, now: Long, size: Size = Size(), seed: Int = 42, dictSeqs: List<Long> = emptyList()): Seeded {
        val rnd = Random(seed)
        var notes = 0
        var cards = 0
        var reviews = 0
        var items = 0
        var hlc = 0L
        fun nextHlc(at: Long): String = "%013d-%06d-%s".format(at, hlc++ % 1_000_000, SERVER)
        val kanji = (0 until size.kanjiNotes).map { (0x4E00 + it * 7).toChar().toString() }
        store.transaction {
            fun note(id: String, typeId: String, type: String, seq: Long?, literal: String?, fields: String, createdAt: Long) {
                driver.execute(
                    null,
                    "INSERT INTO note (id, note_type_id, type, entry_seq, kanji_literal, fields, origin, created_by_device_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'manual', ?, ?, ?)",
                    9,
                ) {
                    bindString(0, id); bindString(1, typeId); bindString(2, type); bindLong(3, seq); bindString(4, literal)
                    bindString(5, fields); bindString(6, SERVER); bindLong(7, createdAt); bindLong(8, createdAt)
                }
                notes++
            }

            /** A card; started cards get a history of reviews (the last one sets its schedule). */
            fun card(id: String, noteId: String, kind: String, createdAt: Long, started: Boolean, failRecently: Boolean) {
                var lastId: String? = null
                var lastAt: Long? = null
                var lapses = 0
                var reps = 0
                if (started) {
                    val n = 1 + rnd.nextInt(size.reviewsPerStartedCard * 2)
                    var at = createdAt + rnd.nextLong(DAY)
                    for (i in 0 until n) {
                        if (at >= now) break
                        val rating = if (rnd.nextInt(10) == 0) 1 else 3
                        val type = if (i == 0) "learning" else "scheduled"
                        val rid = uuid(0x30, reviews)
                        driver.execute(
                            null,
                            "INSERT INTO review_log (id, card_id, device_id, hlc, reviewed_at, utc_offset_min, review_type, rating, button, duration_ms) VALUES (?, ?, ?, ?, ?, 540, ?, ?, ?, ?)",
                            9,
                        ) {
                            bindString(0, rid); bindString(1, id); bindString(2, SERVER); bindString(3, nextHlc(at)); bindLong(4, at)
                            bindString(5, type); bindLong(6, rating.toLong()); bindString(7, if (rating == 1) "again" else "good"); bindLong(8, 4_000L + rnd.nextInt(8_000))
                        }
                        reviews++
                        reps++
                        if (rating == 1) lapses++
                        lastId = rid
                        lastAt = at
                        at += DAY * (1 + rnd.nextInt(1 + i * 6))
                    }
                    if (failRecently) {
                        for (k in 0 until 2 + rnd.nextInt(3)) {
                            val fat = now - rnd.nextLong(13 * DAY)
                            val rid = uuid(0x30, reviews)
                            driver.execute(
                                null,
                                "INSERT INTO review_log (id, card_id, device_id, hlc, reviewed_at, utc_offset_min, review_type, rating, button, duration_ms) VALUES (?, ?, ?, ?, ?, 540, 'scheduled', 1, 'again', 9000)",
                                5,
                            ) { bindString(0, rid); bindString(1, id); bindString(2, SERVER); bindString(3, nextHlc(fat)); bindLong(4, fat) }
                            reviews++
                            reps++
                            lapses++
                            if (lastAt == null || fat > lastAt!!) {
                                lastAt = fat
                                lastId = rid
                            }
                        }
                    }
                }
                val isStarted = lastId != null
                // due: spread from 30 days overdue-ish to 120 days ahead; ~3% due today
                val due = if (!isStarted) null else now + (rnd.nextLong(150 * DAY) - 30 * DAY).let { if (rnd.nextInt(12) == 0) -rnd.nextLong(DAY) else it.coerceAtLeast(DAY / 2) }
                driver.execute(
                    null,
                    "INSERT INTO card (id, note_id, kind, state, stability, difficulty, due_at, interval_days, last_review_at, last_review_id, reps, lapses, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    14,
                ) {
                    bindString(0, id); bindString(1, noteId); bindString(2, kind); bindString(3, if (isStarted) "review" else "new")
                    bindDouble(4, if (isStarted) 1.0 + rnd.nextDouble(100.0) else null); bindDouble(5, if (isStarted) 1.0 + rnd.nextDouble(9.0) else null)
                    bindLong(6, due); bindLong(7, if (isStarted) 1L + rnd.nextInt(90) else null); bindLong(8, lastAt); bindString(9, lastId)
                    bindLong(10, reps.toLong()); bindLong(11, lapses.toLong()); bindLong(12, createdAt); bindLong(13, createdAt)
                }
                cards++
            }

            val start = now - 730 * DAY
            for (i in kanji.indices) {
                val id = uuid(0x20, i)
                val created = start + rnd.nextLong(700 * DAY)
                note(id, KANJI_TYPE, "kanji", null, kanji[i], """{"literal":"${kanji[i]}","keyword":"k$i"}""", created)
                card(uuid(0x21, i), id, "keyword", created, started = rnd.nextInt(10) < 9, failRecently = false)
            }
            for (i in 0 until size.vocabNotes) {
                val id = uuid(0x10, i)
                val created = start + rnd.nextLong(720 * DAY)
                val expr = kanji[rnd.nextInt(kanji.size)] + kanji[rnd.nextInt(kanji.size)]
                note(id, VOCAB_TYPE, "vocab", 1_500_000L + i, null, """{"expression":"$expr","reading":"よみ$i","meaning":"meaning $i"}""", created)
                // ~90% of vocab notes started; recent failures on ~4% of them (the hot lists)
                val started = rnd.nextInt(10) < 9
                val hot = started && rnd.nextInt(25) == 0
                card(uuid(0x11, i), id, "meaning", created, started, hot)
                if (rnd.nextInt(20) == 0) card(uuid(0x12, i), id, "reading", created, started, hot && rnd.nextBoolean())
            }
            fun deck(id: String, type: String, name: String, priority: Int) {
                driver.execute(
                    null,
                    "INSERT INTO deck (id, name, type, priority, list_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    7,
                ) { bindString(0, id); bindString(1, name); bindString(2, type); bindLong(3, priority.toLong()); bindString(4, if (type == "list") "list-$priority" else null); bindLong(5, start); bindLong(6, start) }
            }
            fun item(deck: String, key: String, seq: Long?, noteId: String?, position: Int) {
                driver.execute(
                    null,
                    "INSERT OR IGNORE INTO deck_item (deck_id, item_key, entry_seq, note_id, position, added_at) VALUES (?, ?, ?, ?, ?, ?)",
                    6,
                ) { bindString(0, deck); bindString(1, key); bindLong(2, seq); bindString(3, noteId); bindLong(4, position.toLong()); bindLong(5, start) }
                items++
            }
            for (d in 0 until size.listDecks) {
                val id = uuid(0x40, d)
                deck(id, "list", "List $d", d)
                // overlapping windows of the vocab entries plus entries with no note yet
                for (p in 0 until size.listDeckItems) {
                    val seq = if (p % 5 == 0 && p / 5 < dictSeqs.size) dictSeqs[p / 5] else 1_500_000L + d * 1_000 + p
                    item(id, "e:$seq", seq, null, p)
                }
            }
            val mined = uuid(0x40, 99)
            deck(mined, "user", "Mined", 10)
            for (p in 0 until size.minedDeckItems) {
                val n = rnd.nextInt(size.vocabNotes)
                item(mined, "n:${uuid(0x10, n)}", null, uuid(0x10, n), p)
            }
            it.bookkeeping.lastSyncAt = now - 60_000
        }
        return Seeded(notes, cards, reviews, items)
    }
}
