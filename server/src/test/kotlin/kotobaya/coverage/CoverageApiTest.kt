package kotobaya.coverage

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import kotobaya.auth.AuthFixture
import kotobaya.auth.TokenKind
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T404c: coverage filters, curve, freshness, deck kinds and error cases over HTTP. */
class CoverageApiTest : AuthFixture() {
    private val data = CoverageData(db)
    private val coverage = CoverageModule(db)
    private val log = LoggerFactory.getLogger(CoverageApiTest::class.java)

    private val novel = "01931f40-3000-7000-8000-00000000cb01"
    private val novelSeg = "01931f40-3100-7000-8000-00000000cb10"
    private val show = "01931f40-3000-7000-8000-00000000ca01"
    private val showSeg1 = "01931f40-3100-7000-8000-00000000ca11"

    private fun covApp(block: suspend ApplicationTestBuilder.() -> Unit) = app(extraHandlers = coverage.handlers, block = block)

    private fun reader() = mint(TokenKind.PERSONAL, listOf("read")).second

    private suspend fun ApplicationTestBuilder.get(path: String, token: String): JsonNode {
        val r = client.call(HttpMethod.Get, path, bearer = token)
        val body = r.json()
        assertEquals(200, r.status.value, body.toString())
        return body
    }

    private fun counts(node: JsonNode, vararg keys: String) = keys.map { node[it].asInt() }

    @Test
    fun eachHideClassIsRemovedFromEveryNumber() = covApp {
        val t = reader()
        data.load("deck-b-novel.json")
        // names: 2006 (new, 7 tokens) and 2009 (known, 3 tokens) leave every number.
        val names = get("/media/$novel/coverage?hide=names", t)
        assertEquals(listOf("names"), names["hide"].map { it.asText() })
        assertEquals(listOf(8, 3, 1, 4), counts(names["unique"], "total", "known", "learning", "unknown"), "2008 ignored is unknown when ignored is not hidden")
        assertEquals(listOf(143, 110, 8, 21, 4), counts(names["tokens"], "total", "known", "learning", "unknown", "unmapped"))
        assertEquals(1, names["ignored_count"].asInt())
        assertEquals(listOf(8, 4, 143), counts(names["segments"][0], "unique_total", "unique_unknown", "token_total"))
        // particles: 2002 (known 50), 2010 (new 2).
        val particles = get("/media/$novel/coverage?hide=particles,ignored", t)
        assertEquals(listOf("particles", "ignored"), particles["hide"].map { it.asText() })
        assertEquals(listOf(7, 3, 1, 3), counts(particles["unique"], "total", "known", "learning", "unknown"))
        assertEquals(listOf(97, 63, 8, 22, 4), counts(particles["tokens"], "total", "known", "learning", "unknown", "unmapped"))
        assertEquals(64.95, particles["tokens"]["known_pct"].asDouble())
        // auxiliary: 2003 (never_forget 20).
        val aux = get("/media/$novel/coverage?hide=auxiliary,ignored", t)
        assertEquals(listOf(8, 3, 1, 4), counts(aux["unique"], "total", "known", "learning", "unknown"))
        assertEquals(listOf(129, 93, 8, 24, 4), counts(aux["tokens"], "total", "known", "learning", "unknown", "unmapped"))
        // unmapped: the 4 unmapped tokens leave the total and the unmapped field.
        val unmapped = get("/media/$novel/coverage?hide=unmapped,ignored", t)
        assertEquals(listOf(145, 113, 8, 24, 0), counts(unmapped["tokens"], "total", "known", "learning", "unknown", "unmapped"))
        assertEquals(145, unmapped["segments"][0]["token_total"].asInt())
        assertEquals(77.93, unmapped["tokens"]["known_pct"].asDouble())
        // everything at once
        val all = get("/media/$novel/coverage?hide=names,particles,auxiliary,ignored,unmapped", t)
        assertEquals(listOf("names", "particles", "auxiliary", "ignored", "unmapped"), all["hide"].map { it.asText() })
        assertEquals(listOf(4, 1, 1, 2), counts(all["unique"], "total", "known", "learning", "unknown"))
        assertEquals(listOf(63, 40, 8, 15, 0), counts(all["tokens"], "total", "known", "learning", "unknown", "unmapped"))
        assertEquals(1, all["ignored_count"].asInt(), "ignored entries are reported even when hidden")
    }

    @Test
    fun anEmptyHideHidesNothingAndIgnoredEntriesAreCountedEitherWay() = covApp {
        val t = reader()
        data.load("deck-a-show.json")
        val none = get("/media/$show/coverage?hide=", t)
        assertEquals(0, none["hide"].size())
        assertEquals(listOf(23, 5, 2, 16), counts(none["unique"], "total", "known", "learning", "unknown"))
        assertEquals(listOf(217, 135, 13, 69, 0), counts(none["tokens"], "total", "known", "learning", "unknown", "unmapped"))
        assertEquals(2, none["ignored_count"].asInt())
        assertEquals(16, none["curve"].last()["words"].asInt())
        val dflt = get("/media/$show/coverage", t)
        assertEquals(listOf("ignored"), dflt["hide"].map { it.asText() })
        assertEquals(2, dflt["ignored_count"].asInt())
        assertProblem(client.call(HttpMethod.Get, "/media/$show/coverage?hide=verbs", bearer = t), 422, "validation_failed")
    }

    @Test
    fun theCurveIsMonotoneHasTheContractPointsAndEndsAtAllMappedTokensWhenUnmappedIsHidden() = covApp {
        val t = reader()
        val media = "01931f40-3000-7000-8000-00000000cc01"
        val seg = "01931f40-3100-7000-8000-00000000cc10"
        data.media(media)
        // 600 unknown entries with counts 1..7 (ties broken by seq), 3 known entries, 50 unmapped tokens.
        var mapped = 0
        val rows = (1..600).map { i -> (5000L + i) to (i % 7 + 1) } + listOf(9001L to 100, 9002L to 80, 9003L to 60)
        mapped += rows.sumOf { it.second }
        data.segment(media, seg, 0, "", mapped + 50, 50)
        rows.forEach { (seq, c) -> data.occurrence(media, seg, seq, c) }
        listOf(9001L, 9002L, 9003L).forEach { data.status(it, "known") }

        val withUnmapped = get("/media/$media/coverage", t)
        assertEquals(listOf(0, 10, 20, 50, 100, 200, 500, 600), withUnmapped["curve"].map { it["words"].asInt() })
        val pcts = withUnmapped["curve"].map { it["known_pct"].asDouble() }
        assertTrue(pcts.zipWithNext().all { (a, b) -> a <= b }, "monotone: $pcts")
        assertEquals(withUnmapped["tokens"]["known_pct"], withUnmapped["curve"][0]["known_pct"])
        assertTrue(pcts.last() < 100.0, "unmapped tokens keep the last point below 100")
        // top 10 are the ten entries with count 7, lowest seqs first: exactly 70 tokens.
        val total = withUnmapped["tokens"]["total"].asInt()
        assertEquals(Math.round((240 + 70) * 10000.0 / total) / 100.0, withUnmapped["curve"][1]["known_pct"].asDouble())

        val hidden = get("/media/$media/coverage?hide=unmapped", t)
        assertEquals(100.0, hidden["curve"].last()["known_pct"].asDouble())
        assertEquals(600, hidden["curve"].last()["words"].asInt())

        // no unknown entries: a single point.
        (1..600).forEach { data.status(5000L + it, "known") }
        val done = get("/media/$media/coverage?hide=unmapped", t)
        assertEquals(1, done["curve"].size())
        assertEquals(0, done["curve"][0]["words"].asInt())
        assertEquals(100.0, done["curve"][0]["known_pct"].asDouble())
    }

    @Test
    fun aWordStatusChangeIsReflectedInTheNextResponse() = covApp {
        val t = reader()
        data.load("deck-b-novel.json")
        val before = get("/media/$novel/coverage", t)
        assertEquals(113, before["tokens"]["known"].asInt())
        data.status(2005, "known")
        val after = get("/media/$novel/coverage", t)
        assertEquals(123, after["tokens"]["known"].asInt())
        assertEquals(listOf(5, 3), counts(after["unique"], "known", "unknown"))
        data.status(2001, null) // row removed = new
        val again = get("/media/$novel/coverage", t)
        assertEquals(83, again["tokens"]["known"].asInt())
        assertEquals(4, again["unique"]["unknown"].asInt())
    }

    @Test
    fun aSegmentFilterGivesOneSegmentRowAndNoDifficulty() = covApp {
        val t = reader()
        data.load("deck-a-show.json")
        val r = get("/media/$show/coverage?segment_id=$showSeg1", t)
        assertEquals(showSeg1, r["segment_id"].asText())
        assertEquals(1, r["segments"].size())
        assertEquals(showSeg1, r["segments"][0]["segment_id"].asText())
        assertEquals(listOf(16, 4, 2, 10), counts(r["unique"], "total", "known", "learning", "unknown"))
        assertEquals(listOf(101, 58, 9, 34, 0), counts(r["tokens"], "total", "known", "learning", "unknown", "unmapped"))
        assertEquals(57.43, r["tokens"]["known_pct"].asDouble())
        assertEquals(1, r["ignored_count"].asInt(), "only 1008 occurs in segment 1")
        assertTrue(r["difficulty"].isNull)
        data.load("deck-b-novel.json")
        assertProblem(client.call(HttpMethod.Get, "/media/$show/coverage?segment_id=$novelSeg", bearer = t), 404, "not_found")
        assertProblem(client.call(HttpMethod.Get, "/media/$show/coverage?segment_id=01931f40-3100-7000-8000-00000000ffff", bearer = t), 404, "not_found")
    }

    @Test
    fun unparsedMediaIsAllZeros() = covApp {
        val t = reader()
        val media = "01931f40-3000-7000-8000-00000000cd01"
        data.media(media, contentVersion = 0)
        val r = get("/media/$media/coverage", t)
        assertEquals(listOf(0, 0, 0, 0), counts(r["unique"], "total", "known", "learning", "unknown"))
        assertEquals(listOf(0, 0, 0, 0, 0), counts(r["tokens"], "total", "known", "learning", "unknown", "unmapped"))
        assertEquals(0.0, r["tokens"]["known_pct"].asDouble())
        assertEquals(0, r["ignored_count"].asInt())
        assertEquals(1, r["curve"].size())
        assertEquals(0, r["curve"][0]["words"].asInt())
        assertEquals(0.0, r["curve"][0]["known_pct"].asDouble())
        assertEquals(0, r["segments"].size())
        assertTrue(r["difficulty"].isNull)
    }

    @Test
    fun missingAndDeletedMediaAndDecksAre404And410AndSmartDecksAre409() = covApp {
        val t = reader()
        val gone = "01931f40-3000-7000-8000-00000000ce01"
        data.media(gone, deleted = true)
        assertProblem(client.call(HttpMethod.Get, "/media/$gone/coverage", bearer = t), 410, "gone")
        assertProblem(client.call(HttpMethod.Get, "/media/01931f40-3000-7000-8000-00000000ffff/coverage", bearer = t), 404, "not_found")
        assertProblem(client.call(HttpMethod.Get, "/media/not-a-uuid/coverage", bearer = t), 404, "not_found")

        val smart = "01931f40-2000-7000-8000-00000000ce02"
        data.deck(smart, "smart")
        assertProblem(client.call(HttpMethod.Get, "/decks/$smart/coverage", bearer = t), 409, "not_applicable")
        val deletedDeck = "01931f40-2000-7000-8000-00000000ce03"
        data.deck(deletedDeck, "user", deleted = true)
        assertProblem(client.call(HttpMethod.Get, "/decks/$deletedDeck/coverage", bearer = t), 410, "gone")
        assertProblem(client.call(HttpMethod.Get, "/decks/01931f40-2000-7000-8000-00000000ffff/coverage", bearer = t), 404, "not_found")
    }

    @Test
    fun userAndListDecksCountEachItemOnceWithoutSegmentsOrDifficulty() = covApp {
        val t = reader()
        for ((deck, type) in listOf("01931f40-2000-7000-8000-00000000cf01" to "user", "01931f40-2000-7000-8000-00000000cf02" to "list")) {
            data.deck(deck, type)
            listOf(11L, 12L, 13L, 14L, 15L).forEach { data.deckItem(deck, it, occurrenceCount = if (type == "list") 0 else 9) }
            data.status(11, "known"); data.status(12, "never_forget"); data.status(13, "learning"); data.status(14, "ignored")
            val r = get("/decks/$deck/coverage", t)
            assertEquals(deck, r["deck_id"].asText())
            assertEquals(listOf(4, 2, 1, 1), counts(r["unique"], "total", "known", "learning", "unknown"), type)
            assertEquals(listOf(4, 2, 1, 1, 0), counts(r["tokens"], "total", "known", "learning", "unknown", "unmapped"), type)
            assertEquals(50.0, r["tokens"]["known_pct"].asDouble())
            assertEquals(1, r["ignored_count"].asInt())
            assertEquals(listOf(0, 1), r["curve"].map { it["words"].asInt() })
            assertEquals(75.0, r["curve"].last()["known_pct"].asDouble())
            assertEquals(0, r["segments"].size())
            assertTrue(r["difficulty"].isNull)
            val none = get("/decks/$deck/coverage?hide=", t)
            assertEquals(listOf(5, 2, 1, 2), counts(none["unique"], "total", "known", "learning", "unknown"))
        }
    }

    @Test
    fun aTenThousandEntryMediaAnswersQuickly() = covApp {
        val t = reader()
        val media = "01931f40-3000-7000-8000-00000000d001"
        data.media(media)
        val segs = (0 until 10).map { "01931f40-3100-7000-8000-%012x".format(0xd010 + it) }
        db.txBlocking { dsl ->
            segs.forEachIndexed { i, s ->
                dsl.execute(
                    "insert into app.media_segment (id, media_id, ordinal, kind, title, token_count, unmapped_token_count) values (?::uuid, ?::uuid, ?, 'episode', '', 100000, 3)",
                    s, media, i,
                )
            }
            dsl.execute(
                "insert into app.occurrence (segment_id, entry_seq, media_id, count) " +
                    "select (array[${segs.joinToString(",") { "'$it'::uuid" }}])[1 + (g % 10)], 100000 + g, ?::uuid, 1 + g % 13 from generate_series(1, 10000) g",
                media,
            )
            dsl.execute("insert into app.word_status (entry_seq, status, auto_status, updated_at) select 100000 + g, 'known', 'known', now() from generate_series(1, 10000, 3) g")
        }
        get("/media/$media/coverage", t) // warm-up
        val start = System.nanoTime()
        val r = get("/media/$media/coverage", t)
        val ms = (System.nanoTime() - start) / 1_000_000
        log.info("T404c: 10k-entry media coverage took {} ms", ms)
        assertEquals(10000, r["unique"]["total"].asInt())
        assertEquals(10, r["segments"].size())
    }
}
