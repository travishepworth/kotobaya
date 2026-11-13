package kotobaya.android.grammar

import kotobaya.cards.grammar.GrammarCandidate
import kotobaya.cards.grammar.GrammarCardSentences
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.client.apply.InMemoryLearningTables
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.Row
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GrammarSentenceSourceTest {
    private val point = "p1"
    private val t = InMemoryLearningTables()

    private fun n(v: Int): JsonValue = JsonNumber(v.toDouble())

    private fun put(table: LearningTable, vararg p: Pair<String, JsonValue>) = t.put(table, linkedMapOf(*p) as Row)

    private fun sentence(id: String, text: String, seq: Int?, origin: String = "media") {
        val tokens = if (seq == null) JsonArray(emptyList()) else
            JsonArray(listOf(JsonObject(linkedMapOf("seq" to n(seq), "class" to JsonString("content")))))
        put(
            LearningTable.SENTENCE, "id" to JsonString(id), "media_id" to JsonString("m1"), "text" to JsonString(text),
            "tokens" to tokens, "origin" to JsonString(origin),
        )
    }

    private fun span(sid: String, s: Int, e: Int, origin: String = "detected", deleted: Boolean = false) = put(
        LearningTable.SENTENCE_GRAMMAR_SPAN, "sentence_id" to JsonString(sid), "grammar_point_id" to JsonString(point),
        "start_offset" to n(s), "end_offset" to n(e), "origin" to JsonString(origin),
        "deleted_at" to if (deleted) n(1) else JsonNull,
    )

    private fun review(id: String, hlc: String, sentenceId: String?, type: String = "scheduled", undone: Boolean = false) = put(
        LearningTable.REVIEW_LOG, "id" to JsonString(id), "card_id" to JsonString("c1"), "hlc" to JsonString(hlc),
        "review_type" to JsonString(type), "sentence_id" to (sentenceId?.let { JsonString(it) } ?: JsonNull),
        "undone_at" to if (undone) n(1) else JsonNull,
    )

    private fun seed() {
        put(LearningTable.GRAMMAR_POINT, "id" to JsonString(point), "deleted_at" to JsonNull)
        put(LearningTable.MEDIA, "id" to JsonString("m1"), "deleted_at" to JsonNull)
        put(LearningTable.WORD_STATUS, "entry_seq" to n(100), "status" to JsonString("known"))
        sentence("s-a", "食べざるを得ない", 100) // i+0
        sentence("s-b", "飲まざるを得ない", 200) // 1 unknown
        sentence("s-c", "行かざるを得ない", 100) // i+0
        sentence("s-gen", "生成ざるを得ない", 100, origin = "generated")
        span("s-a", 2, 7)
        span("s-b", 2, 7)
        span("s-c", 2, 7)
        span("s-gen", 2, 7)
        span("s-c", 0, 1, deleted = true)
    }

    @Test
    fun candidatesMatchServerRules() {
        seed()
        val got = GrammarSentenceSource.candidates(t, point).sortedBy { it.sentenceId }
        assertEquals(
            listOf(
                GrammarCandidate("s-a", "食べざるを得ない", 2, 7, 0),
                GrammarCandidate("s-b", "飲まざるを得ない", 2, 7, 1),
                GrammarCandidate("s-c", "行かざるを得ない", 2, 7, 0),
            ),
            got,
        )
    }

    @Test
    fun samePickAsTheServerForTheFixture() {
        seed()
        val server = listOf(
            GrammarCandidate("s-a", "食べざるを得ない", 2, 7, 0),
            GrammarCandidate("s-b", "飲まざるを得ない", 2, 7, 1),
            GrammarCandidate("s-c", "行かざるを得ない", 2, 7, 0),
        )
        // No review yet: rotation indexes the i+0 tier; then the latest review's sentence steps the tier on.
        assertEquals(GrammarCardSentences.toCloze(GrammarCardSentences.pick(server, null, 0)!!), GrammarSentenceSource.pick(t, "c1", point, 0))
        assertEquals("s-a", GrammarSentenceSource.pick(t, "c1", point, 0)?.sentenceId)
        review("r1", "h1", "s-a")
        assertEquals(GrammarCardSentences.toCloze(GrammarCardSentences.pick(server, "s-a", 1)!!), GrammarSentenceSource.pick(t, "c1", point, 1))
        assertEquals("s-c", GrammarSentenceSource.pick(t, "c1", point, 1)?.sentenceId)
        // Undone and manual rows are ignored; the later live one wins by hlc.
        review("r2", "h2", "s-c", undone = true)
        review("r3", "h3", "s-c", type = "manual")
        assertEquals("s-c", GrammarSentenceSource.pick(t, "c1", point, 1)?.sentenceId)
        review("r4", "h4", "s-c")
        assertEquals("s-a", GrammarSentenceSource.pick(t, "c1", point, 2)?.sentenceId)
    }

    @Test
    fun noLocalCandidateGivesNull() {
        put(LearningTable.GRAMMAR_POINT, "id" to JsonString(point), "deleted_at" to JsonNull)
        assertNull(GrammarSentenceSource.pick(t, "c1", point, 0))
    }
}
