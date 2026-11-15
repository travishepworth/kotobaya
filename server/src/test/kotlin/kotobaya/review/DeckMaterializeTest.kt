package kotobaya.review

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import kotobaya.auth.TokenKind
import kotobaya.contracts.sync.OpType
import kotobaya.notes.NotesModule
import kotobaya.server.Uuid7
import kotobaya.sync.SyncConfig
import kotobaya.sync.SyncFixture
import kotobaya.sync.merge.CoreCardCache
import kotobaya.sync.merge.DuplicateMerge
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C-27 (ADR-032): `GET /review/queue` materializes the deck entries the core builder draws (`toMaterialize`) as the
 * server device through the op-apply path, idempotently, and converges with a device that created the same entry.
 */
class DeckMaterializeTest : SyncFixture() {
    override fun syncConfig() = SyncConfig(clock = { syncNow.get() }, cardCache = CoreCardCache(), merge = DuplicateMerge())

    private val notes by lazy { NotesModule(db, sync.opApply) }
    private val review by lazy { ReviewModule(db, sync.opApply) }
    private val stats by lazy { kotobaya.stats.StatsModule(db, clock = { syncNow.get() }) }

    private fun reviewApp(block: suspend ApplicationTestBuilder.() -> Unit) =
        app(extraHandlers = notes.handlers + review.handlers + stats.handlers, block = block)

    private fun token(scopes: List<String> = listOf("read", "write", "review")) = mint(TokenKind.PERSONAL, scopes).second

    private fun setting(key: String, valueJson: String) {
        emit(OpType.SETTINGS_SET, """{"key":"$key","value":$valueJson}""")
    }

    /** A user deck holding `e:<seq>` for seq `base+1 .. base+n` (positions 1024, 2048, ...), each with a dictionary entry. */
    private fun deckWithEntries(n: Int, base: Long = 5000): String {
        val deck = UUID.randomUUID().toString()
        emit(OpType.DECK_CREATED, """{"id":"$deck","name":"D$base","type":"user"}""")
        db.txBlocking {
            it.execute("insert into ref.dict_release (id, version, built_at) values (1, 't', now()) on conflict do nothing")
            for (i in 1..n) {
                it.execute(
                    "insert into ref.dict_entry (seq, headword, headword_reading, readings, senses, added_release_id) " +
                        "values (?, 'たべる', 'たべる', '[{\"text\":\"たべる\"}]', '[]', 1) on conflict do nothing",
                    base + i,
                )
            }
        }
        for (i in 1..n) emit(OpType.DECK_ITEM_PUT, """{"deck_id":"$deck","item_key":"e:${base + i}","position":${i * 1024}}""")
        return deck
    }

    private suspend fun ApplicationTestBuilder.fetch(t: String, query: String = ""): JsonNode {
        val r = client.call(HttpMethod.Get, "/review/queue$query", bearer = t)
        assertEquals(200, r.status.value)
        return r.json()
    }

    private suspend fun ApplicationTestBuilder.good(t: String, card: String) {
        val body = mapOf("id" to Uuid7.generate().toString(), "card_id" to card, "rating" to 3)
        assertEquals(200, client.call(HttpMethod.Post, "/review/answer", bearer = t, body = body).status.value)
    }

    private fun liveNotes(): Long = count("select count(*) from app.note where deleted_at is null and entry_seq is not null")
    private fun changes(type: String): Long = count("select count(*) from app.change where op_type = ?", type)

    @Test
    fun deckEntriesWithoutNotesBecomeNewCardsOnceThroughOps() = reviewApp {
        val t = token()
        deckWithEntries(30)
        val q = fetch(t)
        // Default limits: new_per_session 10 bounds one session's build.
        assertEquals(10, q["items"].size())
        assertTrue(q["items"].all { it["is_new"].asBoolean() })
        assertEquals(10, q["items"].map { it["card"]["note_id"].asText() }.toSet().size, "one card per note (sibling burying)")
        assertEquals(10L, liveNotes())
        assertEquals(10L, count("select count(*) from app.note where origin = 'deck'"))
        assertEquals(10L, changes("note.created"))
        assertEquals(20L, changes("card.created"), "the vocab type's default kinds: meaning and reading")
        assertEquals(30L, count("select count(*) from app.change where op_type in ('note.created', 'card.created') and device_id = '00000000-0000-7000-8000-000000000001'::uuid"))
        assertEquals(0, q["to_materialize"].asInt())
        assertEquals(10, q["counts"]["new"].asInt())
        // The first entries in deck order.
        assertEquals((5001L..5010L).toSet(), db.txBlocking { d -> d.fetch("select entry_seq from app.note where deleted_at is null").map { (it.get(0) as Number).toLong() }.toSet() })

        // Repeated fetches create nothing more and return the same cards.
        val head = count("select max(seq) from app.change")
        val again = fetch(t)
        assertEquals(q["items"].map { it["card"]["id"] }, again["items"].map { it["card"]["id"] })
        assertEquals(head, count("select max(seq) from app.change"))
        assertEquals(10L, liveNotes())
        // The Today counts agree.
        assertEquals(10, client.call(HttpMethod.Get, "/stats/summary", bearer = t).json()["due"]["new"].asInt())
    }

    @Test
    fun newPerDayIsRespectedAndKeepGoingThenMaterializes() = reviewApp {
        val t = token()
        setting("scheduler.new_per_day", "3")
        deckWithEntries(20)
        val q = fetch(t)
        assertEquals(3, q["items"].size())
        assertEquals(3L, liveNotes())
        for (item in q["items"]) good(t, item["card"]["id"].asText())
        val done = fetch(t)
        assertEquals(0, done["items"].count { it["is_new"].asBoolean() })
        assertEquals(3L, liveNotes(), "the daily limit is used up: nothing more is created")
        assertEquals(0, done["to_materialize"].asInt())
        // Learning cards within learn-ahead keep the normal queue non-empty; once they are far enough away it is
        // "done for now" with keep going available because of the pending entries alone.
        syncNow.set(syncNow.get().plusSeconds(60))
        val kg = fetch(t, "?keep_going=true&limit=4")
        assertEquals(4, kg["items"].count { it["is_new"].asBoolean() })
        assertEquals(7L, liveNotes())
    }

    @Test
    fun keepGoingYieldsAFreshBatchOnEveryFetch() = reviewApp {
        val t = token()
        deckWithEntries(60)
        val seen = HashSet<String>()
        repeat(6) { round ->
            val q = fetch(t, "?keep_going=true&limit=5")
            val fresh = q["items"].filter { it["is_new"].asBoolean() }.map { it["card"]["id"].asText() }
            assertEquals(5, fresh.size, "round $round: a full batch of new cards")
            assertTrue(fresh.none { it in seen }, "round $round: never a card already shown")
            seen += fresh
            fresh.forEach { good(t, it) }
            assertEquals(5L * (round + 1), liveNotes())
        }
        assertEquals(30L, changes("note.created"))
    }

    @Test
    fun aReadOnlyCallerSeesPendingEntriesButCreatesNothing() = reviewApp {
        deckWithEntries(5)
        val q = fetch(token(listOf("read")))
        assertEquals(0, q["items"].size())
        assertEquals(5, q["to_materialize"].asInt())
        assertEquals(5, q["counts"]["new"].asInt())
        assertEquals(0L, liveNotes())
        assertEquals(0L, changes("note.created"))
        // A caller with `review` only (the CLI) materializes.
        assertEquals(5, fetch(token(listOf("read", "review")))["items"].size())
        assertEquals(5L, liveNotes())
    }

    @Test
    fun aDeletedMaterializedNoteIsNeverResurrected() = reviewApp {
        val t = token()
        deckWithEntries(3)
        val first = fetch(t)["items"][0]["card"]["note_id"].asText()
        assertEquals(204, client.call(HttpMethod.Delete, "/notes/$first", bearer = t).status.value)
        val q = fetch(t)
        assertTrue(q["items"].none { it["card"]["note_id"].asText() == first })
        assertEquals(0L, count("select count(*) from app.note where deleted_at is null and entry_seq = (select entry_seq from app.note where id = ?::uuid)", first))
        assertEquals(3L, changes("note.created"))
    }

    /**
     * SYNC.md 8.1 race: the server materialized `e:5001` while device A, offline, created its own note for 5001 and
     * reviewed it. A's push makes A's note the loser: one live note per entry, A's card merged into the server's,
     * A's review kept and replayed on the survivor. The other order (A first) never creates a second note.
     */
    @Test
    fun aDeviceNoteForTheSameEntryConvergesOnTheServerNote() = reviewApp {
        val t = token()
        deckWithEntries(2)
        val items = fetch(t)["items"]
        val serverNote = db.txBlocking { it.fetchValue("select id::text from app.note where entry_seq = 5001 and deleted_at is null") as String }
        val serverCard = db.txBlocking { it.fetchValue("select id::text from app.card where note_id = ?::uuid and kind = 'meaning'", serverNote) as String }
        assertTrue(items.any { it["card"]["note_id"].asText() == serverNote })

        val at = syncNow.get().toEpochMilli() + 1_000
        val deviceNote = Uuid7.generate(at - 500).toString()
        val deviceCard = Uuid7.generate(at - 400).toString()
        val reviewId = Uuid7.generate(at - 300).toString()
        val vocab = "00000000-0000-7000-8000-000000000101"
        assertAccepted(pushAsOwner(op(OpType.NOTE_CREATED, A, at - 500, """{"id":"$deviceNote","note_type_id":"$vocab","entry_seq":5001,"origin":"deck"}"""), at))
        assertAccepted(pushAsOwner(op(OpType.CARD_CREATED, A, at - 400, """{"id":"$deviceCard","note_id":"$deviceNote","kind":"meaning"}"""), at))
        assertAccepted(
            pushAsOwner(
                op(OpType.REVIEW_LOGGED, A, at - 300, """{"id":"$reviewId","card_id":"$deviceCard","reviewed_at":${at - 300},"utc_offset_min":0,"review_type":"learning","rating":3}"""),
                at,
            ),
        )

        assertEquals(1L, count("select count(*) from app.note where entry_seq = 5001 and deleted_at is null"))
        assertEquals(serverNote, db.txBlocking { it.fetchValue("select merged_into_id::text from app.note where id = ?::uuid", deviceNote) as String? })
        assertEquals(serverCard, db.txBlocking { it.fetchValue("select merged_into_id::text from app.card where id = ?::uuid", deviceCard) as String? })
        assertEquals(1L, count("select count(*) from app.card where note_id = ?::uuid and kind = 'meaning' and deleted_at is null", serverNote))
        assertEquals("learning", db.txBlocking { it.fetchValue("select state from app.card where id = ?::uuid", serverCard) as String }, "A's review replays on the survivor")
        assertEquals(1L, count("select count(*) from app.review_log where id = ?::uuid", reviewId))

        // Device first: A created the note for a fresh entry 7001 before any fetch drew it; the server never adds a second.
        val deck2 = deckWithEntries(1, base = 7000)
        val note7001 = Uuid7.generate(at + 100).toString()
        assertAccepted(pushAsOwner(op(OpType.NOTE_CREATED, A, at + 100, """{"id":"$note7001","note_type_id":"$vocab","entry_seq":7001,"origin":"deck"}"""), at + 200))
        fetch(t, "?deck_id=$deck2")
        fetch(t, "?keep_going=true")
        assertEquals(1L, count("select count(*) from app.note where entry_seq = 7001"))
    }
}
