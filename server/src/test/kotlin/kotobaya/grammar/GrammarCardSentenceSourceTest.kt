package kotobaya.grammar

import kotlinx.coroutines.runBlocking
import kotobaya.grammar.cards.GrammarCardSentenceSource
import kotobaya.sync.SyncFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T507: rotation per review and i+0 preference over real rows. */
class GrammarCardSentenceSourceTest : SyncFixture() {
    private val gid = "01931f40-7000-7000-8000-000000000001"
    private val media = "01931f40-3000-7000-8000-000000000f01"
    private val seg = "01931f40-3100-7000-8000-000000000f11"

    private fun sid(n: Int) = "01931f40-5000-7000-8000-00000000080$n"

    private fun tokens(vararg seqs: Long): String =
        seqs.joinToString(",", "[", "]") { """{"seq":$it,"class":"content"}""" }

    private fun setup(sentences: List<Triple<Int, String, String>>) = db.txBlocking {
        it.execute("insert into app.grammar_point (id, title, created_at, updated_at) values (?::uuid, 'g', now(), now())", gid)
        it.execute("insert into app.media (id, title, kind, source, parse_status, content_version, created_at, updated_at) values (?::uuid, 'm', 'anime', 'file', 'parsed', 1, now(), now())", media)
        it.execute("insert into app.media_segment (id, media_id, ordinal, kind) values (?::uuid, ?::uuid, 0, 'episode')", seg, media)
        it.execute("insert into app.word_status (entry_seq, status, auto_status, updated_at) values (1, 'known', 'known', now())")
        for ((n, text, toks) in sentences) {
            it.execute("insert into app.sentence (id, media_id, segment_id, ordinal, text, tokens, origin) values (?::uuid, ?::uuid, ?::uuid, ?, ?, ?::jsonb, 'media')", sid(n), media, seg, n, text, toks)
            it.execute(
                "insert into app.sentence_grammar_span (sentence_id, grammar_point_id, start_offset, end_offset, origin, media_id, rule_index) values (?::uuid, ?::uuid, 0, 2, 'detected', ?::uuid, 0)",
                sid(n), gid, media,
            )
        }
    }

    @Test
    fun rotatesEachReviewAndPrefersIPlusZero() {
        // 1 and 2 are i+0 (entry 1 is known); 3 has an unknown word (entry 2).
        setup(listOf(Triple(1, "ざるを得ない", tokens(1)), Triple(2, "ざるを得ないよ", tokens(1)), Triple(3, "ざるを得ないね", tokens(1, 2))))
        val src = GrammarCardSentenceSource(db)
        var last: String? = null
        repeat(6) { n ->
            val p = runBlocking { src.next(gid, last, n) }!!
            assertTrue(p.sentenceId != last, "review $n repeated the last sentence")
            assertEquals(0, p.unknownCount, "i+0 preferred")
            last = p.sentenceId
        }
        assertNull(runBlocking { src.next("01931f40-7000-7000-8000-0000000000ff", null, 0) })
    }
}
