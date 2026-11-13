package kotobaya.grammar

import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotobaya.auth.TokenKind
import kotobaya.sync.SyncFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** T505b: grammar CRUD over HTTP, every write arriving as an op; seed validity and idempotence. */
class GrammarApiTest : SyncFixture() {
    private val grammar by lazy { GrammarModule(db, sync.opApply, FakeRuleTester()) }

    private fun opTypes(): List<String> =
        db.txBlocking { dsl -> dsl.fetch("select op_type from app.change order by seq").map { it.get(0, String::class.java) } }

    @Test
    fun crudRoundTripGoesThroughOpsAndScopesAreEnforced() = app(extraHandlers = grammar.handlers) {
        val writer = mint(TokenKind.PERSONAL, listOf("read", "write")).second
        val reader = mint(TokenKind.PERSONAL, listOf("read")).second

        val created = client.call(HttpMethod.Post, "/grammar", bearer = writer, body = mapOf("title" to "cannot help but", "pattern" to "〜ざるを得ない", "jlpt" to 2))
        assertEquals(201, created.status.value, created.json().toString())
        val id = created.json()["id"].asText()
        assertEquals(true, created.json()["note_id"].isNull)
        assertEquals(0, created.json()["example_count"].asInt())

        assertProblem(client.call(HttpMethod.Post, "/grammar", bearer = reader, body = mapOf("title" to "x")), 403, "insufficient_scope")
        assertProblem(client.call(HttpMethod.Post, "/grammar", bearer = writer, body = mapOf("pattern" to "x")), 422, "validation_failed")
        assertProblem(client.call(HttpMethod.Patch, "/grammar/$id", bearer = writer, body = emptyMap<String, Any>()), 422, "validation_failed")
        assertProblem(client.call(HttpMethod.Patch, "/grammar/$id", bearer = reader, body = mapOf("meaning" to "x")), 403, "insufficient_scope")
        assertProblem(client.call(HttpMethod.Delete, "/grammar/$id", bearer = reader), 403, "insufficient_scope")

        // idempotent create on id
        val again = client.call(HttpMethod.Post, "/grammar", bearer = writer, body = mapOf("id" to id, "title" to "other"))
        assertEquals(200, again.status.value)
        assertEquals("cannot help but", again.json()["title"].asText())

        client.call(HttpMethod.Post, "/grammar", bearer = writer, body = mapOf("title" to "easy one", "jlpt" to 5))
        val list = client.call(HttpMethod.Get, "/grammar", bearer = reader).json()["items"].map { it["title"].asText() }
        assertEquals(listOf("easy one", "cannot help but"), list)
        assertEquals(1, client.call(HttpMethod.Get, "/grammar?jlpt=2", bearer = reader).json()["items"].size())
        assertEquals(1, client.call(HttpMethod.Get, "/grammar?q=%E3%81%96%E3%82%8B", bearer = reader).json()["items"].size())
        assertEquals(0, client.call(HttpMethod.Get, "/grammar?q=zzz", bearer = reader).json()["items"].size())
        assertEquals("cannot help but", client.call(HttpMethod.Get, "/grammar/$id", bearer = reader).json()["title"].asText())

        val updated = client.call(
            HttpMethod.Patch, "/grammar/$id", bearer = writer,
            body = mapOf("explanation" to "Formal.", "is_stub" to false, "links" to listOf(mapOf("title" to "DoJG", "url" to "https://example.com"))),
        )
        assertEquals(200, updated.status.value, updated.json().toString())
        assertEquals("Formal.", updated.json()["explanation"].asText())
        assertEquals(1, updated.json()["links"].size())

        assertEquals(204, client.call(HttpMethod.Delete, "/grammar/$id", bearer = writer).status.value)
        assertEquals(204, client.call(HttpMethod.Delete, "/grammar/$id", bearer = writer).status.value)
        assertProblem(client.call(HttpMethod.Get, "/grammar/$id", bearer = reader), 410, "gone")
        assertEquals(1, client.call(HttpMethod.Get, "/grammar", bearer = reader).json()["items"].size())
        assertEquals(2, client.call(HttpMethod.Get, "/grammar?include_deleted=true", bearer = reader).json()["items"].size())

        assertEquals(listOf("grammar.created", "grammar.created", "grammar.updated", "grammar.deleted"), opTypes())
    }

    @Test
    fun seedIsValidAndSeedingTwiceIsIdempotent() {
        val stubs = GrammarSeeder.load()
        assertTrue(stubs.size >= 250)
        assertEquals(stubs.size, stubs.map { it.id }.toSet().size)
        assertEquals(stubs.size, stubs.map { it.jlpt to it.pattern }.toSet().size, "unique patterns per level")
        assertEquals(stubs.size, stubs.map { it.jlpt to it.title.trim().lowercase() }.toSet().size, "seed titles are unique per JLPT level")

        val first = runBlocking { GrammarSeeder.seed(sync.opApply) }
        assertEquals(stubs.size, first)
        assertEquals(0, runBlocking { GrammarSeeder.seed(sync.opApply) })
        assertEquals(stubs.size.toLong(), count("select count(*) from app.grammar_point where is_stub"))
        assertEquals(stubs.size.toLong(), count("select count(*) from app.change where op_type = 'grammar.created'"))
        val row = assertNotNull(db.txBlocking { it.fetchOne("select explanation, meaning from app.grammar_point limit 1") })
        assertEquals("", row.get(0, String::class.java) + row.get(1, String::class.java))
    }
}
