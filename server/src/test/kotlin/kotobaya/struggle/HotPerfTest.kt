package kotobaya.struggle

import io.ktor.http.HttpMethod
import kotobaya.auth.AuthFixture
import kotobaya.auth.TokenKind
import kotobaya.dict.dictHandlers
import kotobaya.server.StartupSequence
import kotobaya.stats.StatsSeed
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BUG-10: `listHotWords`, `listHotKanji` and `listKanjiWords` answer fast on live-sized data (~90k reviews over ~10k cards,
 * 200k-entry dictionary).
 */
class HotPerfTest : AuthFixture() {
    private val endMs = now.get().toEpochMilli()

    private fun seed() {
        StartupSequence(db).runOnce()
        val dev = insertDevice()
        db.txBlocking { d ->
            d.execute("insert into ref.dict_release (id, version, built_at) values (1, 'perf', now())")
            // 200k entries; 1..4750 are the seeded notes' entries. Kanji drawn from a 2,000-character pool.
            d.execute(
                "insert into ref.dict_entry (seq, headword, headword_reading, readings, senses, freq_rank, is_common, added_release_id) " +
                    "select g, chr(19968 + g % 2000) || chr(19968 + (g * 7) % 2000), 'よみ', '[{\"text\":\"よみ\"}]', " +
                    "('[{\"gloss\":[{\"text\":\"gloss ' || g || '\"}]}]')::jsonb, case when g % 3 = 0 then g end, g % 3 = 0, 1 " +
                    "from generate_series(1, 200000) g",
            )
            d.execute(
                "insert into ref.entry_kanji (seq, literal) select distinct g, chr(19968 + (g * m) % 2000) " +
                    "from generate_series(1, 200000) g cross join generate_series(1, 4) m",
            )
            // One very common kanji (U+4E00) in every 4th entry, like 日 or 人 in JMdict.
            d.execute("insert into ref.entry_kanji (seq, literal) select g, chr(19968) from generate_series(1, 200000, 4) g on conflict do nothing")
            d.execute("insert into ref.kanji (literal, meanings) select chr(19968 + g), array['m' || g] from generate_series(0, 1999) g")
            d.execute("analyze ref.dict_entry")
            d.execute("analyze ref.entry_kanji")
        }
        // 4750 notes x 2 live cards + 475 losers = 9975 cards x 9 reviews, over the last 120 days.
        db.txBlocking { StatsSeed.seed(it, dev.toString(), notes = 4750, perCard = 9, spanDays = 120, endMs = endMs) }
    }

    @Test
    fun hotAndKanjiWordsEndpointsAreFast() {
        seed()
        val lit = URLEncoder.encode(String(Character.toChars(19968)), "UTF-8")
        val paths = listOf("/words/hot", "/words/hot?limit=200", "/kanji/hot", "/kanji/hot?limit=200", "/kanji/$lit/words")
        val timings = LinkedHashMap<String, MutableList<Long>>()
        app(extraHandlers = StruggleModule(db) { now.get() }.handlers + dictHandlers(db)) {
            val reader = mint(TokenKind.PERSONAL, listOf("read")).second
            repeat(2) {
                for (p in paths) {
                    val t0 = System.nanoTime()
                    val r = client.call(HttpMethod.Get, p, bearer = reader)
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    assertEquals(200, r.status.value, p)
                    timings.getOrPut(p) { mutableListOf() } += ms
                }
            }
        }
        println("BUG-10 timings (ms, cold then warm):")
        timings.forEach { (p, t) -> println("  $p ${t.joinToString(" / ")}") }
        for ((p, t) in timings) assertTrue(t.all { it < 1000 }, "$p took $t ms")
    }

    /** The rewritten history loader returns exactly the rows (and survivor mapping) the old `coalesce(...) = any(...)` join did. */
    @Test
    fun historyLoaderMatchesTheLegacyJoin() {
        seed()
        db.txBlocking { dsl ->
            val survivors = dsl.fetch("select id::text from app.card where deleted_at is null and merged_into_id is null order by id limit 3000")
                .map { it.get(0, String::class.java) }
            val legacy = dsl.fetch(
                "select coalesce(c.merged_into_id, c.id)::text, r.id::text, r.card_id::text, r.hlc::text, " +
                    "(extract(epoch from r.reviewed_at) * 1000)::bigint, r.rating, r.review_type " +
                    "from app.review_log r join app.card c on c.id = r.card_id " +
                    "where r.undone_at is null and coalesce(c.merged_into_id, c.id) = any(?::uuid[])",
                survivors.toTypedArray(),
            ).groupBy({ it.get(0, String::class.java) }) {
                listOf(it.get(1, String::class.java), it.get(3, String::class.java), (it.get(4) as Number).toLong(), (it.get(5) as Number?)?.toInt(), it.get(6, String::class.java))
            }.mapValues { (_, v) -> v.map { it.toString() }.sorted() }
            val now = HotWords(dsl, endMs).loadHistory(survivors).mapValues { (s, rows) ->
                rows.map { assertEquals(s, it.cardId); listOf(it.id, it.hlc, it.reviewedAt, it.rating?.value, it.reviewType.wire).toString() }.sorted()
            }
            assertTrue(legacy.size > 1000 && legacy.values.sumOf { it.size } > 20_000, "seeded ${legacy.size} survivors")
            assertEquals(legacy, now)
        }
    }
}
