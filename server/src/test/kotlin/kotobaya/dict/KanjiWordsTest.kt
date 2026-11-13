package kotobaya.dict

import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import kotobaya.auth.AuthFixture
import kotobaya.auth.TokenKind
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FEAT-1: `listKanjiWords` ordering, filters, deck membership, paging and errors. */
class KanjiWordsTest : AuthFixture() {
    private val deckA = "01931f40-2000-7000-8000-00000000a001"
    private val deckB = "01931f40-2000-7000-8000-00000000a002"
    private val deckGone = "01931f40-2000-7000-8000-00000000a003"
    private val noteId = "01931f40-2000-7000-8000-00000000b001"

    private fun seed() = db.txBlocking { d ->
        d.execute("insert into ref.dict_release (id, version, built_at) values (1, 't', now())")
        // seq, freq_rank, kanji forms
        val entries = listOf(
            Triple(10L, 5, "成績"), Triple(11L, 2, "績"), Triple(12L, null, "業績"), Triple(13L, 9, "実績"),
            Triple(14L, 1, "功績"), Triple(15L, 3, "行"),
        )
        for ((seq, rank, hw) in entries) {
            d.execute(
                "insert into ref.dict_entry (seq, headword, headword_reading, readings, senses, freq_rank, added_release_id) " +
                    "values (?, ?, 'よみ', '[{\"text\":\"よみ\"}]', '[{\"gloss\":[{\"text\":\"g$seq\"}]}]', ?, 1)",
                seq, hw, rank,
            )
            for (ch in hw.toSet()) d.execute("insert into ref.entry_kanji (seq, literal) values (?, ?)", seq, ch.toString())
        }
        d.execute("update ref.dict_entry set retired_release_id = 1 where seq = 14")
        val dev = insertDevice()
        for ((id, name, deleted) in listOf(Triple(deckA, "B deck", false), Triple(deckB, "A deck", false), Triple(deckGone, "Gone", true))) {
            d.execute(
                "insert into app.deck (id, name, type, created_at, updated_at, deleted_at) values (?::uuid, ?, 'user', now(), now(), " +
                    (if (deleted) "now()" else "null") + ")",
                id, name,
            )
        }
        fun item(deck: String, seq: Long, occ: Int, deleted: Boolean = false) = d.execute(
            "insert into app.deck_item (deck_id, item_key, entry_seq, occurrence_count, added_at, deleted_at) " +
                "values (?::uuid, ?, ?, ?, now(), " + (if (deleted) "now()" else "null") + ")",
            deck, "e:$seq", seq, occ,
        )
        item(deckA, 10, 1); item(deckA, 13, 7); item(deckA, 12, 7)
        item(deckB, 13, 2); item(deckB, 11, 0, deleted = true)
        item(deckGone, 11, 99); item(deckGone, 10, 50)
        d.execute(
            "insert into app.note (id, note_type_id, type, entry_seq, created_by_device_id, created_at, updated_at) " +
                "values (?::uuid, '00000000-0000-7000-8000-000000000101', 'vocab', 11, ?, now(), now())",
            noteId, dev,
        )
    }

    private fun kw(block: suspend ApplicationTestBuilder.(String) -> Unit) = app(extraHandlers = dictHandlers(db)) { seed(); block(mint(TokenKind.PERSONAL, listOf("read")).second) }

    private suspend fun ApplicationTestBuilder.get(token: String, lit: String, query: String = ""): HttpResponse =
        client.call(HttpMethod.Get, "/kanji/${URLEncoder.encode(lit, Charsets.UTF_8)}/words$query", bearer = token)

    private fun HttpResponse.seqs() = json()["items"].map { it["seq"].asLong() }

    @Test
    fun ordersByFreqRankAndExcludesRetired() = kw { t ->
        val r = get(t, "績")
        assertEquals(200, r.status.value)
        assertEquals(listOf(11L, 10L, 13L, 12L), r.seqs()) // 14 retired; 12 has no rank, last
    }

    @Test
    fun mineAndDeckFiltersAndDeckMembership() = kw { t ->
        // live item of a live deck (10, 12, 13) or live vocab note (11); deleted deck/item alone do not count.
        assertEquals(listOf(11L, 10L, 13L, 12L), get(t, "績", "?mine=true").seqs())
        assertEquals(listOf(13L, 12L, 10L), get(t, "績", "?mine=true&deck_id=$deckA").seqs())
        // deck_id orders by occurrence_count desc, then freq_rank (nulls last)
        assertEquals(listOf(13L, 12L, 10L), get(t, "績", "?deck_id=$deckA").seqs())
        assertEquals(listOf(13L), get(t, "績", "?deck_id=$deckB").seqs())
        val items = get(t, "績").json()["items"].associateBy { it["seq"].asLong() }
        // 11: only a deleted item and a deleted deck's item -> no decks; but note overlay present
        assertEquals(0, items.getValue(11)["decks"].size())
        assertEquals(noteId, items.getValue(11)["note_id"].asText())
        // 10: deckA only (deleted deck excluded)
        assertEquals(listOf(deckA), items.getValue(10)["decks"].map { it["deck_id"].asText() })
        // 13: by name code-point order: "A deck" (deckB) before "B deck" (deckA)
        assertEquals(listOf(deckB, deckA), items.getValue(13)["decks"].map { it["deck_id"].asText() })
        assertEquals(listOf(2, 7), items.getValue(13)["decks"].map { it["occurrence_count"].asInt() })
    }

    @Test
    fun cursorPagingReturnsEveryEntryOnceAndIsBoundToItsQuery() = kw { t ->
        for (query in listOf("", "&mine=true", "&deck_id=$deckA")) {
            val all = get(t, "績", "?limit=200$query").seqs()
            val paged = mutableListOf<Long>()
            var cursor: String? = null
            do {
                val body = get(t, "績", "?limit=1$query" + (cursor?.let { "&cursor=$it" } ?: "")).json()
                paged += body["items"].map { it["seq"].asLong() }
                cursor = body["next_cursor"].takeUnless { it.isNull }?.asText()
            } while (cursor != null)
            assertEquals(all, paged, query)
        }
        val c = get(t, "績", "?limit=1").json()["next_cursor"].asText()
        assertEquals(422, get(t, "績", "?cursor=$c&mine=true").status.value)
        assertEquals(422, get(t, "行", "?cursor=$c").status.value)
        assertEquals(422, get(t, "績", "?cursor=$c&deck_id=$deckA").status.value)
    }

    @Test
    fun errors() = kw { t ->
        assertEquals(422, get(t, "成績").status.value)
        assertEquals(404, get(t, "績", "?deck_id=$deckGone").status.value)
        assertEquals(404, get(t, "績", "?deck_id=01931f40-2000-7000-8000-00000000ffff").status.value)
        assertEquals(200, get(t, "x").status.value)
        assertTrue(get(t, "猫").seqs().isEmpty())
        assertProblem(client.call(HttpMethod.Get, "/kanji/%E7%B8%BE/words"), 401, "unauthenticated")
    }
}
