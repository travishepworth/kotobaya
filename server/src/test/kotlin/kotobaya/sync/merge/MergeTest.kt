package kotobaya.sync.merge

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.OpType
import kotobaya.contracts.sync.OpType.CARD_CREATED
import kotobaya.contracts.sync.OpType.NOTE_CREATED
import kotobaya.contracts.sync.OpType.NOTE_UPDATED
import kotobaya.contracts.sync.OpType.REVIEW_LOGGED
import kotobaya.sync.SyncJson
import kotobaya.sync.fuzz.RealSyncFixture
import kotobaya.sync.fuzz.SimDevice
import kotobaya.sync.wall
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** SYNC.md 8.1 detection beyond the scenario files: concurrent adds, imports vs phones, re-matches, the REST path. */
class MergeTest : RealSyncFixture() {
    private val base = 0x0193a1b2c000L

    private fun at(ms: Long, vararg devices: SimDevice) {
        server.pt = ms
        devices.forEach { it.pt = ms }
    }

    private fun vocab(d: SimDevice, seq: Long, fields: Map<String, String> = emptyMap(), kinds: List<String> = listOf("meaning")): Pair<String, List<String>> {
        val note = d.newId(1)
        d.make(NOTE_CREATED, mapOf("id" to note, "note_type_id" to Reserved.NOTE_TYPE_VOCAB, "entry_seq" to seq, "fields" to fields))
        val cards = kinds.map { k -> d.newId(2).also { d.make(CARD_CREATED, mapOf("id" to it, "note_id" to note, "kind" to k)) } }
        return note to cards
    }

    private fun review(d: SimDevice, card: String) = d.make(
        REVIEW_LOGGED,
        mapOf("id" to d.newId(3), "card_id" to card, "reviewed_at" to d.pt, "utc_offset_min" to 540, "review_type" to "learning", "rating" to 3, "button" to "good"),
    )

    private fun liveNotes(where: String, vararg args: Any?) = count("select count(*) from app.note where deleted_at is null and $where", *args)

    @Test
    fun concurrentSameWordAddOnTwoDevicesMergesToOneNote() {
        val a = SimDevice(A)
        val b = SimDevice(B)
        at(base, a, b)
        val (na, ca) = vocab(a, 1358280, mapOf("meaning" to "to eat"), listOf("meaning", "reading"))
        at(base + 10, a, b)
        val (nb, cb) = vocab(b, 1358280, mapOf("sentence" to "パンを食べる"), listOf("meaning", "listening"))
        at(base + 20, a, b)
        review(a, ca[0]); review(b, cb[0])
        at(base + 100, a, b)
        b.sync(server) // B first: B's note survives
        a.sync(server)
        b.sync(server)
        assertEquals(1, liveNotes("entry_seq = 1358280"))
        val loser = row("note", "id" to na)!!
        assertEquals(nb, loser["merged_into_id"].asText())
        // A's cards: meaning merged into B's meaning; reading had no counterpart, so the server created one on B's note.
        assertEquals(cb[0], row("card", "id" to ca[0])!!["merged_into_id"].asText())
        val reading = row("card", "id" to ca[1])!!["merged_into_id"].asText()
        assertEquals(nb, row("card", "id" to reading)!!["note_id"].asText())
        // Both meaning reviews feed the survivor card; both fields reached the survivor.
        assertEquals(2, count("select count(*) from app.review_log r join app.card c on c.id = r.card_id where coalesce(c.merged_into_id, c.id) = ?::uuid", cb[0]))
        val w = row("note", "id" to nb)!!
        assertEquals("to eat", w["fields"]["meaning"].asText())
        assertEquals("パンを食べる", w["fields"]["sentence"].asText())
        assertConverged("same word", a, b)
    }

    /** FOLLOWUPS "Freeze A → T202 tests": a jpdb-import kanji note (server-minted) vs a phone's offline add. */
    @Test
    fun jpdbImportKanjiDedupeVersusOfflinePhoneAddMerges() {
        val phone = SimDevice(A)
        at(base, phone)
        // The import job runs first (server-minted, dedupe finds no live 食 note).
        val imported = "0193a1b2-1001-7000-8000-000000000001"
        emit(NOTE_CREATED, """{"id":"$imported","note_type_id":"${Reserved.NOTE_TYPE_KANJI}","kanji_literal":"食","origin":"jpdb_import"}""")
        emit(CARD_CREATED, """{"id":"0193a1b2-1002-7000-8000-000000000001","note_id":"$imported","kind":"meaning"}""")
        // The phone, offline, had added 食 too, with a card and a review.
        at(base + 50, phone)
        val note = phone.newId(1)
        phone.make(NOTE_CREATED, mapOf("id" to note, "note_type_id" to Reserved.NOTE_TYPE_KANJI, "kanji_literal" to "食", "mnemonic" to "a person eating"))
        val card = phone.newId(2)
        phone.make(CARD_CREATED, mapOf("id" to card, "note_id" to note, "kind" to "meaning"))
        review(phone, card)
        at(base + 100, phone)
        phone.sync(server)
        assertEquals(1, liveNotes("kanji_literal = '食'"))
        assertEquals(imported, row("note", "id" to note)!!["merged_into_id"].asText())
        assertEquals("a person eating", row("note", "id" to imported)!!["mnemonic"].asText())
        assertEquals("0193a1b2-1002-7000-8000-000000000001", row("card", "id" to card)!!["merged_into_id"].asText())
        assertConverged("import first", phone)
    }

    @Test
    fun jpdbImportArrivingAfterThePhonesNoteIsMergedIntoItOnTheRestPath() {
        val phone = SimDevice(A)
        at(base, phone)
        val note = phone.newId(1)
        phone.make(NOTE_CREATED, mapOf("id" to note, "note_type_id" to Reserved.NOTE_TYPE_KANJI, "kanji_literal" to "食"))
        phone.sync(server)
        // An import that minted without seeing it (no 503: the REST/job path merges like a push).
        at(base + 100, phone)
        val imported = "0193a1b2-1001-7000-8000-000000000002"
        emit(NOTE_CREATED, """{"id":"$imported","note_type_id":"${Reserved.NOTE_TYPE_KANJI}","kanji_literal":"食","origin":"jpdb_import"}""")
        emit(CARD_CREATED, """{"id":"0193a1b2-1002-7000-8000-000000000002","note_id":"$imported","kind":"meaning"}""")
        assertEquals(note, row("note", "id" to imported)!!["merged_into_id"].asText())
        // The import's card was created on the merged note, so it was merged too: the server made the survivor's card.
        val survivorCard = row("card", "id" to "0193a1b2-1002-7000-8000-000000000002")!!["merged_into_id"].asText()
        assertEquals(note, row("card", "id" to survivorCard)!!["note_id"].asText())
        phone.sync(server)
        assertConverged("phone first", phone)
    }

    /** 8.1 via `note.updated`: a re-matched note with cards; the survivor lacks one kind. */
    @Test
    fun reMatchingANoteWithCardsMergesItAndItsCards() {
        val a = SimDevice(A)
        at(base, a)
        val (w, wc) = vocab(a, 100, kinds = listOf("meaning"))
        val (x, xc) = vocab(a, 200, kinds = listOf("reading", "meaning"))
        at(base + 10, a)
        a.make(OpType.CARD_DELETED, mapOf("id" to a.newId(2).also { a.make(CARD_CREATED, mapOf("id" to it, "note_id" to x, "kind" to "writing")) }))
        review(a, xc[1])
        a.make(NOTE_UPDATED, mapOf("id" to x, "set" to mapOf("entry_seq" to 100)))
        at(base + 20, a)
        a.sync(server)
        val emitted = db.txBlocking { dsl ->
            dsl.fetch("select op_type, payload->>'kind' from app.change where caused_by_op_id is not null order by seq").map { "${it.get(0)}:${it.get(1) ?: ""}" }
        }
        // note.merged; then (kind, id) order: meaning → W's meaning; reading → new card on W, merged. The deleted writing card is left alone.
        assertEquals(listOf("note.merged:", "card.merged:", "card.created:reading", "card.merged:"), emitted)
        assertEquals(w, row("note", "id" to x)!!["merged_into_id"].asText())
        assertEquals(wc[0], row("card", "id" to xc[1])!!["merged_into_id"].asText())
        assertEquals(0, count("select count(*) from app.card where note_id = ?::uuid and kind = 'writing'", w))
        assertConverged("re-match", a)
    }

    /** The loser's server-side deleted_at is wall(merge hlc) even when the clock moves between mints (8.2 step 2, 11). */
    @Test
    fun loserDeletedAtIsWallOfTheMergeOpWithATickingClock() {
        val tick = AtomicLong(base + 1000)
        val a = SimDevice(A)
        val b = SimDevice(B)
        at(base, a, b)
        vocab(a, 42)
        val (l, _) = vocab(b, 42)
        a.sync(server)
        // Every read of the server clock advances it by 7 ms from here on.
        syncNow.set(Instant.ofEpochMilli(tick.get()))
        val ticking = kotobaya.sync.SyncModule(db, syncConfig().copy(clock = { Instant.ofEpochMilli(tick.getAndAdd(7)) }))
        server.module = ticking
        b.push(server)
        val mergeHlc = db.txBlocking { it.fetchValue("select hlc::text from app.change where op_type = 'note.merged'") as String }
        assertEquals(wall(mergeHlc), SyncJson.ms(row("note", "id" to l)!!["deleted_at"]))
        a.pt = tick.get(); b.pt = tick.get()
        a.sync(server); b.sync(server)
        assertConverged("ticking", a, b)
    }

    @Test
    fun aCardCollisionOnOneNoteMergesTheNewcomer() {
        val a = SimDevice(A)
        val b = SimDevice(B)
        at(base, a, b)
        val (n, c) = vocab(a, 7)
        a.sync(server); b.sync(server)
        // Both phones add a "reading" card to the same note offline.
        val ra = a.newId(2); a.make(CARD_CREATED, mapOf("id" to ra, "note_id" to n, "kind" to "reading"))
        val rb = b.newId(2); b.make(CARD_CREATED, mapOf("id" to rb, "note_id" to n, "kind" to "reading"))
        review(b, rb)
        at(base + 50, a, b)
        a.sync(server); b.sync(server); a.sync(server)
        assertEquals(ra, row("card", "id" to rb)!!["merged_into_id"].asText())
        assertNotNull(row("card", "id" to c[0]))
        assertEquals(1, count("select count(*) from app.card where note_id = ?::uuid and kind = 'reading' and deleted_at is null", n))
        assertConverged("card collision", a, b)
    }
}
