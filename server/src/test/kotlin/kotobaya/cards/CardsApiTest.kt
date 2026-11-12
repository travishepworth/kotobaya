package kotobaya.cards

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import kotobaya.auth.TokenKind
import kotobaya.contracts.sync.OpType
import kotobaya.notes.NotesModule
import kotobaya.review.ReviewModule
import kotobaya.search.sql.Dialect
import kotobaya.server.Uuid7
import kotobaya.sync.SyncConfig
import kotobaya.sync.SyncFixture
import kotobaya.sync.merge.CoreCardCache
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T210a: cards endpoints and bulk operations over HTTP with the real core replay as the card cache. */
class CardsApiTest : SyncFixture() {
    override fun syncConfig() = SyncConfig(clock = { syncNow.get() }, cardCache = CoreCardCache())

    private val handlers by lazy { NotesModule(db, sync.opApply).handlers + ReviewModule(db, sync.opApply).handlers + CardsModule(db, sync.opApply).handlers }
    private val vocab = "00000000-0000-7000-8000-000000000101"

    private fun cardsApp(block: suspend ApplicationTestBuilder.() -> Unit) = app(extraHandlers = handlers, block = block)

    private fun token() = mint(TokenKind.PERSONAL, listOf("read", "write", "review")).second

    private suspend fun ApplicationTestBuilder.note(t: String, expression: String, tags: List<String> = emptyList(), deck: String? = null): JsonNode {
        val body = mutableMapOf<String, Any?>("note_type_id" to vocab, "fields" to mapOf("expression" to expression), "tags" to tags)
        deck?.let { body["deck_id"] = it }
        val r = client.call(HttpMethod.Post, "/notes", bearer = t, body = body)
        assertEquals(201, r.status.value)
        return r.json()
    }

    private fun cardOf(note: JsonNode, kind: String): String = note["cards"].first { it["kind"].asText() == kind }["id"].asText()

    private fun head(): Long = count("select coalesce(max(seq), 0) from app.change")

    private fun opsSince(after: Long): List<String> =
        db.txBlocking { dsl -> dsl.fetch("select op_type from app.change where seq > ? order by seq", after).map { it.get(0, String::class.java) } }

    private fun userDeck(name: String = "Mining"): String {
        val id = UUID.randomUUID().toString()
        emit(OpType.DECK_CREATED, """{"id":"$id","name":"$name","type":"user"}""")
        return id
    }

    private fun cache(card: String): Map<String, Any?> = db.txBlocking { dsl ->
        dsl.fetchOne("select state, due_at::text, reps from app.card where id = ?::uuid", card)!!.intoMap()
    }

    private suspend fun ApplicationTestBuilder.bulk(t: String, body: Map<String, Any?>, path: String = "/cards/bulk"): HttpResponse =
        client.call(HttpMethod.Post, path, bearer = t, body = body)

    @Test
    fun bulkActionsOverAQueueEmitExactlyOneOpPerMatchedCardAndDryRunChangesNothing() = cardsApp {
        val t = token()
        val a = note(t, "a", listOf("x"))
        val b = note(t, "b", listOf("x"))
        note(t, "c")
        // 2 notes x 2 default cards match tag:x; the third note does not
        val matched = listOf(a, b).flatMap { n -> n["cards"].map { it["id"].asText() } }.toSet()

        val before = head()
        val dry = bulk(t, mapOf("query" to "tag:x", "action" to mapOf("type" to "suspend"), "dry_run" to true))
        assertEquals(200, dry.status.value)
        assertEquals(4, dry.json()["matched"].asInt())
        assertEquals(0, dry.json()["ops_emitted"].asInt())
        assertTrue(dry.json()["dry_run"].asBoolean())
        assertEquals(before, head())

        val r = bulk(t, mapOf("query" to "tag:x", "action" to mapOf("type" to "suspend")))
        assertEquals(200, r.status.value)
        assertEquals(4, r.json()["changed"].asInt())
        assertEquals(4, r.json()["ops_emitted"].asInt())
        assertEquals(List(4) { "card.suspended" }, opsSince(before))
        // one transaction: the ops hold consecutive seqs and the header names the last
        assertEquals(head(), r.headers["Kotobaya-Seq"]?.toLong())
        val suspended = db.txBlocking { dsl -> dsl.fetch("select id::text from app.card where suspended").map { it.get(0, String::class.java) }.toSet() }
        assertEquals(matched, suspended)

        // tags: one note.updated per distinct note, not per card; a no-op set is skipped
        var h = head()
        val tag = bulk(t, mapOf("query" to "tag:x", "action" to mapOf("type" to "add_tags", "tags" to listOf("y"))))
        assertEquals(200, tag.status.value)
        assertEquals(listOf("note.updated", "note.updated"), opsSince(h))
        assertEquals(2, tag.json()["changed"].asInt())
        h = head()
        val again = bulk(t, mapOf("query" to "tag:x", "action" to mapOf("type" to "add_tags", "tags" to listOf("y"))))
        assertEquals(0, again.json()["ops_emitted"].asInt())
        assertEquals(2, again.json()["skipped"].asInt())
        assertEquals(h, head())

        // flag and unsuspend, then move into a deck (per distinct note), then delete
        assertEquals(4, bulk(t, mapOf("query" to "tag:x", "action" to mapOf("type" to "flag", "flags" to 3))).json()["ops_emitted"].asInt())
        assertEquals(4L, count("select count(*) from app.card where flags = 3"))
        assertEquals(4, bulk(t, mapOf("query" to "tag:x", "action" to mapOf("type" to "unsuspend"))).json()["ops_emitted"].asInt())
        val deck = userDeck()
        h = head()
        val mv = bulk(t, mapOf("query" to "tag:x", "action" to mapOf("type" to "move", "to_deck_id" to deck)))
        assertEquals(200, mv.status.value)
        assertEquals(listOf("deck.item_put", "deck.item_put"), opsSince(h))
        assertEquals(2L, count("select count(*) from app.deck_item where deck_id = ?::uuid and deleted_at is null", deck))
        val del = bulk(t, mapOf("query" to "tag:x", "action" to mapOf("type" to "delete")))
        assertEquals(4, del.json()["ops_emitted"].asInt())
        assertEquals(4L, count("select count(*) from app.card where deleted_at is not null"))
        // deleted cards no longer match, and ids that were deleted are skipped
        val ids = bulk(t, mapOf("card_ids" to matched.toList(), "action" to mapOf("type" to "delete")))
        assertEquals(0, ids.json()["matched"].asInt())
        assertEquals(4, ids.json()["skipped"].asInt())
    }

    @Test
    fun aSelectorMatchingMoreThan5000CardsIs422AndNothingIsApplied() = cardsApp {
        val t = token()
        val n = note(t, "big")
        db.txBlocking {
            it.execute(
                "insert into app.card (id, note_id, kind, created_at, updated_at) " +
                    "select gen_random_uuid(), ?::uuid, 'k' || g, now(), now() from generate_series(1, 5001) g",
                n["id"].asText(),
            )
        }
        val before = head()
        val r = bulk(t, mapOf("query" to "is:new", "action" to mapOf("type" to "suspend")))
        assertProblem(r, 422, "bulk_too_large")
        assertEquals(before, head())
        assertEquals(0L, count("select count(*) from app.card where suspended"))
        // an explicit id list is capped by the contract, not by this check: a narrower query still works
        val ok = bulk(t, mapOf("query" to "kind:k1", "action" to mapOf("type" to "suspend")))
        assertEquals(1, ok.json()["ops_emitted"].asInt())
    }

    @Test
    fun aQueryNeverActsOnARowTheSearchRejects() = cardsApp {
        val t = token()
        val keep = note(t, "keep", listOf("a"))
        note(t, "other", listOf("a", "b"))
        note(t, "none")
        val wanted = setOf(cardOf(keep, "meaning"))
        // the Postgres dialect decides every term in SQL (no residual to apply)
        val compiled = db.txBlocking { CardSearch.compile(it, "tag:a -tag:b kind:meaning", syncNow.get().toEpochMilli()) }
        assertEquals(Dialect.POSTGRES, compiled.dialect)
        assertFalse(compiled.residual)

        val r = bulk(t, mapOf("query" to "tag:a -tag:b kind:meaning", "action" to mapOf("type" to "flag", "flags" to 5)))
        assertEquals(1, r.json()["matched"].asInt())
        val flagged = db.txBlocking { dsl -> dsl.fetch("select id::text from app.card where flags = 5").map { it.get(0, String::class.java) }.toSet() }
        assertEquals(wanted, flagged)
        assertProblem(bulk(t, mapOf("query" to "lapse:3", "action" to mapOf("type" to "suspend"))), 422, "invalid_query")
    }

    @Test
    fun scheduleActionsRewriteTheReplayedCacheAndNeedTheReviewScope() = cardsApp {
        val t = token()
        val n = note(t, "sched")
        val card = cardOf(n, "meaning")
        val fresh = cardOf(n, "reading")
        val answered = client.call(HttpMethod.Post, "/review/answer", bearer = t, body = mapOf("id" to Uuid7.generate().toString(), "card_id" to card, "rating" to 3))
        assertEquals(200, answered.status.value)
        assertEquals("learning", cache(card)["state"])

        // the write-only token is refused
        val writer = mint(TokenKind.PERSONAL, listOf("read", "write")).second
        val refused = bulk(writer, mapOf("card_ids" to listOf(card), "action" to mapOf("type" to "reset")), "/cards/bulk/schedule")
        assertEquals(403, refused.status.value)

        // a new card in the selection refuses reschedule for the whole call, nothing applied
        val h0 = head()
        val due = "2030-01-01T00:00:00.000Z"
        val mixed = bulk(t, mapOf("card_ids" to listOf(card, fresh), "action" to mapOf("type" to "reschedule", "due_at" to due)), "/cards/bulk/schedule")
        assertEquals(422, mixed.status.value)
        assertEquals(h0, head())

        val dry = bulk(t, mapOf("card_ids" to listOf(card), "action" to mapOf("type" to "reschedule", "due_at" to due), "dry_run" to true), "/cards/bulk/schedule")
        assertEquals(1, dry.json()["changed"].asInt())
        assertEquals(h0, head())

        val r = bulk(t, mapOf("card_ids" to listOf(card), "action" to mapOf("type" to "reschedule", "due_at" to due)), "/cards/bulk/schedule")
        assertEquals(200, r.status.value)
        assertEquals(listOf("review.logged"), opsSince(h0))
        assertEquals("2030-01-01 00:00:00+00", cache(card)["due_at"])
        assertEquals("learning", cache(card)["state"])
        assertEquals(1L, count("select count(*) from app.review_log where card_id = ?::uuid and review_type = 'manual' and manual_action = 'set_due'", card))

        val reset = bulk(t, mapOf("card_ids" to listOf(card, fresh), "action" to mapOf("type" to "reset")), "/cards/bulk/schedule")
        assertEquals(200, reset.status.value)
        assertEquals(1, reset.json()["changed"].asInt())
        assertEquals(1, reset.json()["skipped"].asInt())
        assertEquals("new", cache(card)["state"])
    }

    @Test
    fun cardEndpointsListGetPatchCreateDeleteAndShowHistory() = cardsApp {
        val t = token()
        val n = note(t, "crud", listOf("t"))
        val card = cardOf(n, "meaning")

        val list = client.call(HttpMethod.Get, "/cards?query=tag:t&sort=created_at&limit=1", bearer = t).json()
        assertEquals(1, list["items"].size())
        assertEquals("crud", list["items"][0]["note"]["fields"]["expression"].asText())
        val page2 = client.call(HttpMethod.Get, "/cards?query=tag:t&sort=created_at&limit=1&cursor=${list["next_cursor"].asText()}", bearer = t).json()
        assertEquals(1, page2["items"].size())
        assertTrue(page2["next_cursor"].isNull)
        assertTrue(list["items"][0]["id"].asText() != page2["items"][0]["id"].asText())
        assertEquals(422, client.call(HttpMethod.Get, "/cards?sort=nope", bearer = t).status.value)

        val before = head()
        val patch = client.call(HttpMethod.Patch, "/cards/$card", bearer = t, body = mapOf("flags" to 2, "suspended" to true))
        assertEquals(200, patch.status.value)
        assertEquals(listOf("card.suspended", "card.flag_set"), opsSince(before))
        assertTrue(patch.json()["suspended"].asBoolean())
        assertEquals(422, client.call(HttpMethod.Patch, "/cards/$card", bearer = t, body = emptyMap<String, Any>()).status.value)

        // optional kind: created once, then returned unchanged
        val created = client.call(HttpMethod.Post, "/cards", bearer = t, body = mapOf("note_id" to n["id"].asText(), "kind" to "listening"))
        assertEquals(201, created.status.value)
        val h = head()
        val again = client.call(HttpMethod.Post, "/cards", bearer = t, body = mapOf("note_id" to n["id"].asText(), "kind" to "listening"))
        assertEquals(200, again.status.value)
        assertEquals(created.json()["id"].asText(), again.json()["id"].asText())
        assertEquals(h, head())
        assertEquals(422, client.call(HttpMethod.Post, "/cards", bearer = t, body = mapOf("note_id" to n["id"].asText(), "kind" to "bogus")).status.value)

        assertEquals(200, client.call(HttpMethod.Post, "/review/answer", bearer = t, body = mapOf("id" to Uuid7.generate().toString(), "card_id" to created.json()["id"].asText(), "rating" to 3)).status.value)
        val history = client.call(HttpMethod.Get, "/cards/${created.json()["id"].asText()}/reviews", bearer = t).json()
        assertEquals(1, history["items"].size())

        assertEquals(204, client.call(HttpMethod.Delete, "/cards/$card", bearer = t).status.value)
        val h2 = head()
        assertEquals(204, client.call(HttpMethod.Delete, "/cards/$card", bearer = t).status.value)
        assertEquals(h2, head())
        assertEquals(410, client.call(HttpMethod.Get, "/cards/$card", bearer = t).status.value)
        assertEquals(200, client.call(HttpMethod.Get, "/cards/$card?include_deleted=true", bearer = t).status.value)
    }
}
