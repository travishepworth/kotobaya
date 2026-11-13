package kotobaya.confusables

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import kotobaya.auth.TokenKind
import kotobaya.notes.NotesModule
import kotobaya.sync.SyncFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T502: suggestion minting is idempotent and never overrides a decision; list/set over HTTP incl. scope. */
class ConfusableApiTest : SyncFixture() {
    private val handlers by lazy { NotesModule(db, sync.opApply).handlers + ConfusableModule(db, sync.opApply).handlers }
    private val vocab = "00000000-0000-7000-8000-000000000101"
    private val kanjiType = "00000000-0000-7000-8000-000000000102"

    private fun confApp(block: suspend ApplicationTestBuilder.() -> Unit) = app(extraHandlers = handlers, block = block)

    private val writer by lazy { mint(TokenKind.PERSONAL, listOf("read", "write")).second }
    private val reader by lazy { mint(TokenKind.PERSONAL, listOf("read")).second }

    private fun seed() {
        db.txBlocking { d ->
            d.execute("insert into ref.dict_release (id, version, built_at) values (1, 't', now()) on conflict do nothing")
            val entries = listOf(
                Triple(1L, "待つ" to "まつ", "to wait"),
                Triple(2L, "持つ" to "もつ", "to hold"),
                Triple(3L, "松" to "まつ", "pine tree"),
                Triple(4L, "控える" to "ひかえる", "to wait"),
            )
            for ((seq, hr, gloss) in entries) {
                d.execute(
                    "insert into ref.dict_entry (seq, headword, headword_reading, readings, senses, added_release_id) values (?, ?, ?, ?::jsonb, ?::jsonb, 1)",
                    seq, hr.first, hr.second, """[{"text":"${hr.second}"}]""", """[{"gloss":[{"lang":"eng","text":"$gloss"}]}]""",
                )
            }
            val k = mapOf("待" to "{彳,土,寸}", "持" to "{扌,土,寸}", "特" to "{牛,土,寸}")
            for ((lit, c) in k) d.execute("insert into ref.kanji (literal, components) values (?, ?::text[])", lit, c)
        }
    }

    private suspend fun ApplicationTestBuilder.vocabNote(seq: Long): String {
        val r = client.call(HttpMethod.Post, "/notes", bearer = writer, body = mapOf("note_type_id" to vocab, "entry_seq" to seq))
        assertEquals(201, r.status.value, r.json().toString())
        return r.json()["id"].asText()
    }

    private suspend fun ApplicationTestBuilder.kanjiNote(lit: String): String {
        val r = client.call(HttpMethod.Post, "/notes", bearer = writer, body = mapOf("note_type_id" to kanjiType, "kanji_literal" to lit, "fields" to mapOf("expression" to lit)))
        assertEquals(201, r.status.value, r.json().toString())
        return r.json()["id"].asText()
    }

    private fun statusOf(items: JsonNode, reason: String) = items.filter { it["reason"].asText() == reason }.map { it["status"].asText() }

    @Test
    fun suggestionsAreIdempotentAndNeverFlipADecisionBack() = confApp {
        seed()
        val machi = vocabNote(1)
        val motsu = vocabNote(2)
        val matsu = vocabNote(3)
        vocabNote(4)
        kanjiNote("待"); kanjiNote("持"); kanjiNote("特")

        val suggester = ConfusableSuggester(db, sync.opApply)
        // homophone 1; look-alike vocab 1 + kanji 3; meaning 1
        assertEquals(6, suggester.runOnce())
        assertEquals(0, suggester.runOnce(), "re-running adds nothing")
        assertEquals(6L, count("select count(*) from app.confusable where status = 'suggested'"))

        val all = client.call(HttpMethod.Get, "/confusables", bearer = reader).json()["items"]
        assertEquals(6, all.size())
        assertEquals(listOf("suggested"), statusOf(all, "homophone"))
        assertTrue(all.all { it["note_a_id"].asText() < it["note_b_id"].asText() })

        val (a, b) = listOf(machi, matsu).sorted()
        // The ids may be given in either order.
        val rej = client.call(HttpMethod.Put, "/confusables/$b/$a/homophone", bearer = writer, body = mapOf("status" to "rejected"))
        assertEquals(200, rej.status.value, rej.json().toString())
        assertEquals("rejected", rej.json()["status"].asText())
        assertEquals(a, rej.json()["note_a_id"].asText())

        val (c, d) = listOf(machi, motsu).sorted()
        assertEquals("confirmed", client.call(HttpMethod.Put, "/confusables/$c/$d/look_alike", bearer = writer, body = mapOf("status" to "confirmed")).json()["status"].asText())

        assertEquals(0, suggester.runOnce(), "decisions are kept, the pairs are not re-suggested")
        assertEquals("rejected", row("confusable", "note_a_id" to a, "note_b_id" to b, "reason" to "homophone")!!["status"].asText())
        assertEquals("confirmed", row("confusable", "note_a_id" to c, "note_b_id" to d, "reason" to "look_alike")!!["status"].asText())

        // Default list hides rejected; status filter shows it.
        val forWord = client.call(HttpMethod.Get, "/words/1/confusables", bearer = reader).json()["items"]
        assertEquals("confirmed", forWord[0]["status"].asText(), "confirmed first")
        assertEquals(2, forWord.size())
        assertTrue(forWord.none { it["status"].asText() == "rejected" })
        assertEquals(1, client.call(HttpMethod.Get, "/words/1/confusables?status=rejected", bearer = reader).json()["items"].size())
        assertEquals(0, client.call(HttpMethod.Get, "/words/999/confusables", bearer = reader).json()["items"].size())

        val page = client.call(HttpMethod.Get, "/confusables?limit=4", bearer = reader).json()
        assertEquals(4, page["items"].size())
        val next = client.call(HttpMethod.Get, "/confusables?limit=4&cursor=${page["next_cursor"].asText()}", bearer = reader).json()
        assertEquals(2, next["items"].size())
        assertEquals(1, client.call(HttpMethod.Get, "/confusables?status=confirmed", bearer = reader).json()["items"].size())
        assertEquals(3, client.call(HttpMethod.Get, "/confusables?note_id=$machi", bearer = reader).json()["items"].size())
    }

    @Test
    fun aHandMadePairIsConfirmedAndBadInputIsRefused() = confApp {
        seed()
        val x = vocabNote(1)
        val y = vocabNote(4)
        val (a, b) = listOf(x, y).sorted()
        val r = client.call(HttpMethod.Put, "/confusables/$a/$b/meaning", bearer = writer, body = mapOf("status" to "confirmed"))
        assertEquals(200, r.status.value)
        assertEquals("confirmed", r.json()["status"].asText())
        assertEquals(1L, count("select count(*) from app.change where op_type = 'confusable.confirmed'"))

        assertEquals(422, client.call(HttpMethod.Put, "/confusables/$a/$a/meaning", bearer = writer, body = mapOf("status" to "confirmed")).status.value)
        assertEquals(422, client.call(HttpMethod.Put, "/confusables/$a/$b/bogus", bearer = writer, body = mapOf("status" to "confirmed")).status.value)
        assertEquals(422, client.call(HttpMethod.Put, "/confusables/$a/$b/meaning", bearer = writer, body = mapOf("status" to "suggested")).status.value)
        assertEquals(404, client.call(HttpMethod.Put, "/confusables/$a/00000000-0000-7000-8000-00000000dead/meaning", bearer = writer, body = mapOf("status" to "confirmed")).status.value)
    }

    @Test
    fun readScopeCannotDecideAndListsNeedAToken() = confApp {
        seed()
        val x = vocabNote(1)
        val y = vocabNote(4)
        val (a, b) = listOf(x, y).sorted()
        assertEquals(403, client.call(HttpMethod.Put, "/confusables/$a/$b/meaning", bearer = reader, body = mapOf("status" to "confirmed")).status.value)
        assertEquals(401, client.call(HttpMethod.Get, "/confusables").status.value)
        assertEquals(200, client.call(HttpMethod.Get, "/confusables", bearer = reader).status.value)
    }
}
