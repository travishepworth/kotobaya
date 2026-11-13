package kotobaya.drill

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import kotobaya.auth.TokenKind
import kotobaya.notes.NotesModule
import kotobaya.review.ReviewModule
import kotobaya.sync.SyncConfig
import kotobaya.sync.SyncFixture
import kotobaya.sync.merge.CoreCardCache
import kotlin.test.Test
import kotlin.test.assertEquals

/** T503: `createDrillSession` struggle sources over HTTP (selection itself is tested in core). */
class DrillSessionTest : SyncFixture() {
    override fun syncConfig() = SyncConfig(clock = { syncNow.get() }, cardCache = CoreCardCache())

    private val notes by lazy { NotesModule(db, sync.opApply) }
    private val review by lazy { ReviewModule(db, sync.opApply) }
    private val vocab = "00000000-0000-7000-8000-000000000101"

    private fun drillApp(block: suspend ApplicationTestBuilder.() -> Unit) = app(extraHandlers = notes.handlers + review.handlers, block = block)

    private fun token() = mint(TokenKind.PERSONAL, listOf("read", "write", "review")).second

    private suspend fun ApplicationTestBuilder.note(t: String, expression: String): JsonNode {
        val r = client.call(HttpMethod.Post, "/notes", bearer = t, body = mapOf("note_type_id" to vocab, "fields" to mapOf("expression" to expression)))
        assertEquals(201, r.status.value)
        return r.json()
    }

    private suspend fun ApplicationTestBuilder.session(t: String, body: Map<String, Any?>): List<String> {
        val r = client.call(HttpMethod.Post, "/drill/session", bearer = t, body = body)
        assertEquals(200, r.status.value)
        return r.json()["items"].map { it["card"]["id"].asText() }
    }

    @Test
    fun emptySourcesGiveAnEmptyListAndLeechesAndConfusablesAreServed() = drillApp {
        val t = token()
        val a = note(t, "a")
        val b = note(t, "b")
        for (source in listOf("hot_words", "leeches", "confusables")) {
            assertEquals(emptyList(), session(t, mapOf("source" to source)), source)
        }
        val aIds = a["cards"].map { it["id"].asText() }
        val bIds = b["cards"].map { it["id"].asText() }
        db.txBlocking { it.execute("update app.card set lapses = 9 where id = ?::uuid", aIds.first()) }
        assertEquals(listOf(aIds.first()), session(t, mapOf("source" to "leeches")))

        val (lo, hi) = listOf(a["id"].asText(), b["id"].asText()).sorted()
        val hlc = "000000000001-0000-00000000-0000-7000-8000-000000000001"
        db.txBlocking {
            it.execute(
                "insert into app.confusable (note_a_id, note_b_id, reason, status, hlc, created_at, updated_at) " +
                    "values (?::uuid, ?::uuid, 'homophone', 'suggested', ?, now(), now())",
                lo, hi, hlc,
            )
        }
        assertEquals(emptyList(), session(t, mapOf("source" to "confusables")), "suggested pairs are not drilled")
        db.txBlocking { it.execute("update app.confusable set status = 'confirmed'") }
        val out = session(t, mapOf("source" to "confusables"))
        assertEquals((aIds + bIds).toSet(), out.toSet())
        assertEquals(aIds.size + bIds.size, out.size)
    }
}
