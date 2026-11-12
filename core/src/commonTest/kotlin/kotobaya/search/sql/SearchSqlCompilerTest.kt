package kotobaya.search.sql

import kotobaya.contracts.queue.StudyDay
import kotobaya.contracts.search.SearchNode
import kotobaya.search.DefaultSearchQueryParser
import kotobaya.search.SearchExamples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val HOT = "0193a1b2-0001-7000-8000-0000000000aa"

private val CTX = SearchContext(StudyDay(1_700_000_000_000L, 1_700_086_400_000L), 1_700_040_000_000L, 8, setOf(HOT))

private fun parse(q: String): SearchNode {
    val r = DefaultSearchQueryParser.parse(q)
    assertEquals(emptyList(), r.errors, "errors for <$q>")
    return assertNotNull(r.query)
}

private fun compile(q: String, d: Dialect, ctx: SearchContext = CTX) = SearchSqlCompiler.compile(parse(q), d, ctx)

private fun row(
    tags: String = "[]",
    fields: String = "{}",
    mnemonic: String = "",
    source: String = "",
    noteId: String = "n1",
    type: String = "vocab",
    probes: List<Boolean> = emptyList(),
) = ResidualRow(noteId, type, tags, fields, mnemonic, source, probes)

class SearchSqlCompilerTest {
    private fun render(f: SqlFragment) = f.sql + " ## " + f.params.joinToString(" | ") { 
            when {
                it is Double && it == it.toLong().toDouble() -> "${it.toLong()}.0"
                it is Double -> it.toString().lowercase().replace(".0e", "e") // JVM 1.0E-7, JS 1e-7
                else -> it.toString()
            }
        }

    @Test
    fun everyPlusExampleCompilesInBothDialectsAndMatchesSnapshot() {
        assertEquals(SearchExamples.valid.size, SQL_SNAPSHOTS.size, "snapshot list out of date; regenerate")
        for ((i, ex) in SearchExamples.valid.withIndex()) {
            val (q, pg, lite) = SQL_SNAPSHOTS[i]
            assertEquals(ex.first, q)
            assertEquals(pg, render(compile(q, Dialect.POSTGRES).select()), "postgres <$q>")
            assertEquals(lite, render(compile(q, Dialect.SQLITE).select()), "sqlite <$q>")
        }
    }

    @Test
    fun placeholdersMatchParameterCount() {
        for ((q, _) in SearchExamples.valid) {
            for (d in Dialect.entries) {
                val f = compile(q, d).select()
                assertEquals(f.sql.count { it == '?' }, f.params.size, "<$q> $d")
            }
        }
    }

    @Test
    fun injectionShapedInputNeverReachesSqlText() {
        val payloads = listOf("x'; DROP TABLE card;--", "100%", "a_b", "back\\slash", "q\"uote", "%27%3B", "績'--")
        for (p in payloads) {
            val quoted = "\"" + p.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            val templates = listOf("%s", "deck:%s", "tag:%s", "field:k:%s", "notetype:%s", "preset:%s")
            for (t in templates) {
                val q = t.replace("%s", quoted)
                val node = parse(q)
                val benign = parse(t.replace("%s", "\"abc\""))
                for (d in Dialect.entries) {
                    val a = SearchSqlCompiler.compile(node, d, CTX).select()
                    val b = SearchSqlCompiler.compile(benign, d, CTX).select()
                    // identical shape: the payload changed nothing but the bound values
                    // postgres: identical shape, the payload changed nothing but the bound values
                    // (sqlite prefilters one group per space-free chunk, so only the payload-free check applies)
                    if (d == Dialect.POSTGRES) assertEquals(b.sql, a.sql, "<$q> $d")
                    assertFalse(a.sql.contains(p), q)
                    assertFalse(a.sql.contains("DROP"), q)
                }
            }
        }
        val f = compile("\"x'; DROP TABLE card;--\"", Dialect.POSTGRES).select()
        assertTrue(f.params.any { it == "%x'; drop table card;--%" }, f.params.toString())
        // LIKE metacharacters in the user text are escaped
        val g = compile("\"100%_\"", Dialect.SQLITE).select()
        assertTrue(g.params.contains("%100\\%\\_%"), g.params.toString())
    }

    @Test
    fun hotAndKanjiUserValuesAreBound() {
        val f = compile("kanji:績 kind:x", Dialect.POSTGRES).select()
        assertFalse(f.sql.contains("績"))
        assertTrue(f.params.contains("績"))
    }

    @Test
    fun postgresNeverNeedsResidual() {
        for ((q, _) in SearchExamples.valid) {
            assertFalse(compile(q, Dialect.POSTGRES).residual, q)
        }
    }

    @Test
    fun sqliteTagFieldTextKanjiHotAreResidual() {
        for (q in listOf("tag:a", "field:k:x", "abc", "kanji:績", "is:hot", "-is:hot", "-tag:a")) {
            assertTrue(compile(q, Dialect.SQLITE).residual, q)
        }
        for (q in listOf("is:due deck:x", "lapses>3 rated:2", "type:vocab kind:reading nid:0193a1b2-0001-7000-8000-000000000001")) {
            assertFalse(compile(q, Dialect.SQLITE).residual, q)
        }
    }

    @Test
    fun sqliteResidualDecidesTagWholeMatchCaseInsensitively() {
        val c = compile("tag:Anime", Dialect.SQLITE)
        assertTrue(c.accepts(row(tags = "[\"x\",\"anime\"]")))
        assertFalse(c.accepts(row(tags = "[\"animes\"]")))
        assertFalse(c.accepts(row(tags = "[]")))
        val w = compile("tag:jlpt*", Dialect.SQLITE)
        assertTrue(w.accepts(row(tags = "[\"jlpt_n5\"]")))
        assertTrue(w.accepts(row(tags = "[\"JLPT\"]")))
    }

    @Test
    fun sqliteNotOverResidualUsesWholeSubtreeResidual() {
        val c = compile("-tag:anime", Dialect.SQLITE)
        // the prefilter must not exclude anything
        assertTrue(c.where.sql.endsWith("n.deleted_at IS NULL"), c.where.sql)
        assertTrue(c.accepts(row(tags = "[\"x\"]", probes = listOf(true))))
        assertFalse(c.accepts(row(tags = "[\"anime\"]", probes = listOf(true))))
    }

    @Test
    fun sqliteOrCombinesSqlProbeAndResidual() {
        // tag:anime or is:new -- the SQL part is only visible to Kotlin through a probe column
        val c = compile("tag:anime or is:new", Dialect.SQLITE)
        assertEquals(2, c.probes.size)
        // probes: p0 = tags prefilter, p1 = state = new
        assertTrue(c.accepts(row(tags = "[\"anime\"]", probes = listOf(true, false))))
        assertFalse(c.accepts(row(tags = "[\"animeX\"]", probes = listOf(true, false))))
        assertTrue(c.accepts(row(tags = "[]", probes = listOf(false, true))))
        assertFalse(c.accepts(row(tags = "[]", probes = listOf(false, false))))
    }

    @Test
    fun sqliteTextAndFieldResidual() {
        val c = compile("EAT", Dialect.SQLITE)
        assertTrue(c.accepts(row(fields = "{\"meaning\":\"to eat\"}")))
        assertTrue(c.accepts(row(mnemonic = "Eaten")))
        assertFalse(c.accepts(row(fields = "{\"meaning\":\"drink\"}")))
        val f = compile("field:meaning:to*", Dialect.SQLITE)
        assertTrue(f.accepts(row(fields = "{\"meaning\":\"To eat\"}")))
        assertFalse(f.accepts(row(fields = "{\"meaning\":\"eat to\"}")))
        // a missing key is the empty string, which `*` matches
        assertTrue(compile("field:nope:*", Dialect.SQLITE).accepts(row(fields = "{}")))
        // no folding beyond ASCII
        assertFalse(compile("é", Dialect.SQLITE).accepts(row(mnemonic = "É")))
    }

    @Test
    fun sqliteTextPrefilterDoesNotUseChunksThatStraddleAJoiningSpace() {
        val c = compile("\"to eat\"", Dialect.SQLITE)
        // "to" and "eat" in different values: search_text joins them by a space and does match
        assertTrue(c.accepts(row(fields = "{\"a\":\"to\",\"b\":\"eat\"}")))
        assertEquals(6, c.where.params.size, "two chunks (to, eat), each probed on three columns")
    }

    @Test
    fun sqliteKanjiMatchesLiteralOrVocabExpression() {
        val c = compile("kanji:績", Dialect.SQLITE)
        // probes: p0 = kanji_literal match, p1 = type vocab & instr
        assertTrue(c.accepts(row(probes = listOf(true, false))))
        assertTrue(c.accepts(row(fields = "{\"expression\":\"績佐\"}", probes = listOf(false, true))))
        assertFalse(c.accepts(row(fields = "{\"expression\":\"責\"}", probes = listOf(false, true))))
    }

    @Test
    fun sqliteHotUsesTheCallersSet() {
        val c = compile("is:hot", Dialect.SQLITE)
        assertTrue(c.accepts(row(noteId = HOT)))
        assertFalse(c.accepts(row(noteId = "other")))
        val pg = compile("is:hot", Dialect.POSTGRES).select()
        assertTrue(pg.sql.contains("n.id IN (CAST(? AS uuid))"))
        assertEquals(listOf<Any>(HOT), pg.params.takeLast(1))
        assertTrue(compile("is:hot", Dialect.POSTGRES, CTX.copy(hotNoteIds = emptySet())).select().sql.contains("1 = 0"))
    }

    @Test
    fun leechIsAVirtualTag() {
        val f = compile("tag:leech", Dialect.POSTGRES).select()
        assertTrue(f.sql.contains("c.lapses >= ?"))
        assertTrue(f.params.contains(8L))
        assertFalse(compile("tag:lee", Dialect.POSTGRES).select().sql.contains("c.lapses"))
        assertTrue(compile("tag:lee*", Dialect.POSTGRES).select().sql.contains("c.lapses"))
    }

    @Test
    fun dayTermsUseTheStudyDay() {
        val day = 86_400_000L
        val start = CTX.studyDay.startMs
        assertTrue(compile("rated:1", Dialect.SQLITE).select().params.contains(start))
        assertTrue(compile("added:3", Dialect.SQLITE).select().params.contains(start - 2 * day))
        // due<=0: due_at < start + 1 day; due>=-3: due_at >= start - 3 days; due=1: [start+1d, start+2d)
        assertTrue(compile("due<=0", Dialect.SQLITE).select().params.contains(start + day))
        assertTrue(compile("due>=-3", Dialect.SQLITE).select().params.contains(start - 3 * day))
        val eq = compile("due=1", Dialect.SQLITE).select().params
        assertTrue(eq.contains(start + day) && eq.contains(start + 2 * day))
        assertTrue(compile("due=1.5", Dialect.SQLITE).select().sql.contains("1 = 0"))
    }

    @Test
    fun dayBoundariesComeFromTheCallerAcrossDst() {
        // fake zone: the clocks go forward at the start of day 2, so day 1 lasts 23 hours; days before 0 are 24 h
        val h = 3_600_000L
        val day = 86_400_000L
        val start = CTX.studyDay.startMs
        val fake: (Int) -> Long = { k -> start + k * day - (if (k >= 2) h else 0L) }
        val ctx = CTX.copy(dayStart = fake)
        // due=1 is [dayStart(1), dayStart(2)) = [start + 24h, start + 47h)
        val eq = compile("due=1", Dialect.SQLITE, ctx).select().params
        assertTrue(eq.containsAll(listOf(start + day, start + 2 * day - h)), eq.toString())
        assertFalse(eq.contains(start + 2 * day))
        // due>1 starts at day 2; due<=1 ends before it
        assertTrue(compile("due>1", Dialect.SQLITE, ctx).select().params.contains(start + 2 * day - h))
        assertTrue(compile("due<=1", Dialect.SQLITE, ctx).select().params.contains(start + 2 * day - h))
        // a day beyond the change shifts by the hour as well
        assertTrue(compile("due>=3", Dialect.SQLITE, ctx).select().params.contains(start + 3 * day - h))
        // rated/added use dayStart(-(N-1)); the default and the fake agree before the change
        assertTrue(compile("added:3", Dialect.SQLITE, ctx).select().params.contains(start - 2 * day))
        // the same numbers on Postgres
        assertTrue(compile("due=1", Dialect.POSTGRES, ctx).select().params.contains(start + 2 * day - h))
        // a change in the past: day -1 lasted 25 hours
        val back = CTX.copy(dayStart = { k -> start + k * day - (if (k <= -1) h else 0L) })
        assertTrue(compile("rated:2", Dialect.SQLITE, back).select().params.contains(start - day - h))
    }

    @Test
    fun nullablePropertiesNeverMatchWhenNull() {
        val f = compile("stability!=3", Dialect.SQLITE).select().sql
        assertTrue(f.contains("c.stability IS NOT NULL AND c.stability <> ?"), f)
    }

    @Test
    fun liveCardFilterAndMergeResolutionArePresent() {
        for (d in Dialect.entries) {
            val f = compile("nid:0193a1b2-0001-7000-8000-000000000001", d).select().sql
            assertTrue(f.contains("c.deleted_at IS NULL AND c.merged_into_id IS NULL AND n.deleted_at IS NULL"))
            assertTrue(f.contains("l.merged_into_id = n.id"))
            assertTrue(compile("rated:2", d).select().sql.contains("l.merged_into_id = c.id"))
            assertTrue(compile("cid:0193a1b2-0002-7000-8000-000000000001", d).select().sql.contains("l.merged_into_id = c.id"))
        }
    }

    @Test
    fun smartDeckMembershipIsItsQuery() {
        val smart = SmartDeckRef("Leeches", parse("is:leech"))
        val f = compile("deck:leeches", Dialect.POSTGRES, CTX.copy(smartDecks = listOf(smart))).select()
        assertTrue(f.sql.contains("c.lapses >= ?"), f.sql)
        val g = compile("deck:other", Dialect.POSTGRES, CTX.copy(smartDecks = listOf(smart))).select()
        assertFalse(g.sql.contains("c.lapses"))
    }

    @Test
    fun patternMatcher() {
        assertTrue(matchesWhole(listOf("abc"), "ABC"))
        assertFalse(matchesWhole(listOf("abc"), "abcd"))
        assertTrue(matchesWhole(listOf("a", "c"), "abc"))
        assertFalse(matchesWhole(listOf("ab", "bc"), "abc"))
        assertTrue(matchesWhole(listOf("", "b", ""), "abc"))
        assertTrue(matchesWhole(listOf("", ""), ""))
        assertFalse(matchesWhole(listOf("a", "b", "c"), "ac"))
    }
}
