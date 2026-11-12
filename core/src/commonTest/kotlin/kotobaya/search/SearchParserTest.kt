package kotobaya.search

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.WordStatus
import kotobaya.contracts.search.CompareOp
import kotobaya.contracts.search.CompletionKind
import kotobaya.contracts.search.IsValue
import kotobaya.contracts.search.SearchNode
import kotobaya.contracts.search.SearchPattern
import kotobaya.contracts.search.SearchProperty
import kotobaya.contracts.search.SearchTerm
import kotobaya.contracts.search.SearchVocabulary
import kotobaya.contracts.search.SourceSpan
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val P = DefaultSearchQueryParser
private val NO_SPAN = SourceSpan(0, 0)

private fun strip(n: SearchNode): SearchNode = when (n) {
    is SearchNode.All -> n.copy(span = NO_SPAN)
    is SearchNode.And -> SearchNode.And(n.children.map(::strip), NO_SPAN)
    is SearchNode.Or -> SearchNode.Or(n.children.map(::strip), NO_SPAN)
    is SearchNode.Not -> SearchNode.Not(strip(n.child), NO_SPAN)
    is SearchNode.Term -> n.copy(span = NO_SPAN)
}

private fun parseOk(q: String): SearchNode {
    val r = P.parse(q)
    assertEquals(emptyList(), r.errors, "errors for <$q>")
    return strip(assertNotNull(r.query))
}

private fun text(vararg segs: String) = SearchNode.Term(SearchTerm.Text(SearchPattern(segs.toList())), NO_SPAN)
private fun and(vararg k: SearchNode) = SearchNode.And(k.toList(), NO_SPAN)
private fun or(vararg k: SearchNode) = SearchNode.Or(k.toList(), NO_SPAN)
private fun not(k: SearchNode) = SearchNode.Not(k, NO_SPAN)

class SearchParserTest {
    @Test
    fun validExamplesParseFormatCanonicalAndRoundTrip() {
        for ((q, canonical) in SearchExamples.valid) {
            val tree = parseOk(q)
            val text = P.format(tree)
            assertEquals(canonical, text, "canonical text of <$q>")
            assertEquals(tree, parseOk(text), "round trip of <$q>")
            assertEquals(text, P.format(parseOk(text)), "fixed point of <$q>")
        }
    }

    @Test
    fun invalidExamplesYieldTheImpliedErrorCode() {
        for ((q, code) in SearchExamples.invalid) {
            val r = P.parse(q)
            assertEquals(null, r.query, "tree for <$q>")
            assertTrue(r.errors.isNotEmpty(), "no error for <$q>")
            assertTrue(r.errors.any { it.code == code }, "<$q> expected $code, got ${r.errors.map { it.code }}")
            assertTrue(r.errors.all { it.messageKey == "search.error." + it.code.name.lowercase() })
        }
    }

    @Test
    fun precedenceAndFlattening() {
        assertEquals(or(and(text("a"), text("b")), text("c")), parseOk("a b or c"))
        assertEquals(and(text("a"), text("b"), text("c")), parseOk("(a b) c"))
        assertEquals(or(text("a"), text("b"), text("c")), parseOk("(a or b) or c"))
        assertEquals(not(not(text("x"))), parseOk("--x"))
        assertEquals(not(and(text("a"), text("b"))), parseOk("-(a b)"))
        assertEquals(and(text("a"), text("b")), parseOk("a AND b"))
        assertEquals(SearchNode.All(NO_SPAN), parseOk("  　 "))
    }

    @Test
    fun spansAreUtf16OffsetsAndErrorsAreInSourceOrder() {
        val r = P.parse("a dek:x kanji:abc")
        assertEquals(listOf(SourceSpan(2, 7), SourceSpan(8, 17)), r.errors.map { it.span })
        val ok = P.parse("𠮷 is:new").query
        assertEquals(SourceSpan(0, 9), ok?.span)
    }

    @Test
    fun formatRejectsIllFormedTrees() {
        assertFailsWith<IllegalArgumentException> { P.format(and(text("a"))) }
        assertFailsWith<IllegalArgumentException> { P.format(and(and(text("a"), text("b")), text("c"))) }
        assertFailsWith<IllegalArgumentException> { P.format(not(SearchNode.All(NO_SPAN))) }
        assertFailsWith<IllegalArgumentException> { P.format(text("")) }
    }

    @Test
    fun numbersFormatWithoutExponent() {
        assertEquals("10.5", formatNumber(10.5))
        assertEquals("3", formatNumber(3.0))
        assertEquals("0.0000001", formatNumber(1e-7))
        assertEquals("-0", formatNumber(-0.0))
        assertEquals("10000000000000000000000", formatNumber(1e22))
        assertEquals("0.5", formatNumber(0.5))
        assertEquals("-12.25", formatNumber(-12.25))
    }

    // ---- property test

    private val awkwardTexts = listOf(
        listOf("or"), listOf("OR"), listOf("and"), listOf("-a"), listOf("a:b"), listOf("a b"), listOf("x=y"),
        listOf("a"), listOf("食べる"), listOf("a(b)"), listOf("say \"hi\""), listOf("back\\slash"),
        listOf("*"), listOf("", ""), listOf("a", ""), listOf("", "b"), listOf("a", "b"), listOf("lit*star", "x"),
        listOf("-"), listOf("a<b"), listOf("　"), listOf("Or"), listOf("deck:x"), listOf("a\tb"),
    )

    private fun genPattern(r: Random): SearchPattern = SearchPattern(awkwardTexts[r.nextInt(awkwardTexts.size)])

    private fun genDouble(r: Random): Double = when (r.nextInt(6)) {
        0 -> r.nextInt(-50, 50).toDouble()
        1 -> r.nextInt(0, 100000) / 100.0
        2 -> -0.0
        3 -> 1e-7
        4 -> r.nextDouble() * 1000
        else -> -r.nextDouble()
    }

    private fun genTerm(r: Random): SearchTerm = when (r.nextInt(18)) {
        0 -> SearchTerm.Text(genPattern(r))
        1 -> SearchTerm.Deck(genPattern(r))
        2 -> SearchTerm.Tag(genPattern(r))
        3 -> SearchTerm.Is(IsValue.entries[r.nextInt(IsValue.entries.size)])
        4 -> SearchTerm.Kind("kind_" + r.nextInt(5))
        5 -> SearchTerm.Type(NoteBaseType.entries[r.nextInt(NoteBaseType.entries.size)])
        6 -> SearchTerm.NoteType(genPattern(r))
        7 -> SearchTerm.Preset(genPattern(r))
        8 -> SearchTerm.Kanji(listOf("績", "責", "𠮷")[r.nextInt(3)])
        9 -> SearchTerm.Entry(r.nextLong(1, Long.MAX_VALUE))
        10 -> SearchTerm.Status(WordStatus.entries[r.nextInt(WordStatus.entries.size)])
        11 -> SearchTerm.Flag(r.nextInt(8))
        12 -> SearchTerm.Rated(r.nextInt(1, 36501), if (r.nextBoolean()) null else r.nextInt(1, 5))
        13 -> SearchTerm.Added(r.nextInt(1, 36501))
        14 -> SearchTerm.NoteId("0193a1b2-0001-7000-8000-00000000000" + r.nextInt(10))
        15 -> SearchTerm.Field("f" + r.nextInt(3), genPattern(r))
        16 -> SearchTerm.CardId("0193a1b2-0002-7000-8000-00000000000" + r.nextInt(10))
        else -> SearchTerm.Compare(
            SearchProperty.entries[r.nextInt(SearchProperty.entries.size)],
            CompareOp.entries[r.nextInt(CompareOp.entries.size)],
            genDouble(r),
        )
    }

    /** [parent]: 0 root/Not, 1 inside And, 2 inside Or (no directly nested same operator). */
    private fun genNode(r: Random, depth: Int, parent: Int): SearchNode {
        val leaf = depth <= 0 || r.nextInt(3) == 0
        if (leaf) return SearchNode.Term(genTerm(r), NO_SPAN)
        return when (r.nextInt(3)) {
            0 -> if (parent == 1) genNode(r, depth - 1, parent) else SearchNode.And(List(r.nextInt(2, 4)) { genNode(r, depth - 1, 1) }, NO_SPAN)
            1 -> if (parent == 2) genNode(r, depth - 1, parent) else SearchNode.Or(List(r.nextInt(2, 4)) { genNode(r, depth - 1, 2) }, NO_SPAN)
            else -> SearchNode.Not(genNode(r, depth - 1, 0), NO_SPAN)
        }
    }

    @Test
    fun randomWellFormedTreesRoundTrip() {
        val r = Random(20260101)
        repeat(3000) {
            val tree = genNode(r, 4, 0)
            val text = P.format(tree)
            val res = P.parse(text)
            assertEquals(emptyList(), res.errors, "errors parsing <$text>")
            assertEquals(tree, strip(assertNotNull(res.query)), "round trip of <$text>")
            assertEquals(text, P.format(assertNotNull(res.query)), "fixed point of <$text>")
        }
    }

    // ---- completion

    private val vocab = SearchVocabulary(
        deckNames = listOf("Frieren", "Anime: Frieren S1", "Anime", "frieren extra"),
        tags = listOf("jlpt5", "anime"),
        cardKinds = listOf("pitch", "custom_kind"),
        noteTypeNames = listOf("My cards"),
        presetNames = listOf("Hard words"),
        fieldKeys = listOf("meaning", "reading"),
    )

    @Test
    fun completesKeysByPrefixThenSubstring() {
        val r = P.complete("de", 2, SearchVocabulary.EMPTY, 10)
        assertEquals(listOf("deck:", "added:"), r.map { it.insert })
        assertEquals("deck:", r[0].insert)
        assertEquals(CompletionKind.KEY, r[0].kind)
        assertEquals(0, r[0].replaceStart)
        assertEquals(2, r[0].replaceEnd)
        // substring matches come after the prefix matches
        val t = P.complete("ted", 3, SearchVocabulary.EMPTY, 10).map { it.insert }
        assertEquals(listOf("rated:"), t)
        // deterministic
        assertEquals(P.complete("", 0, vocab, 50), P.complete("", 0, vocab, 50))
        assertEquals(3, P.complete("", 0, vocab, 3).size)
    }

    @Test
    fun completesOperatorsOnlyAfterAnOperand() {
        assertTrue(P.complete("a o", 3, SearchVocabulary.EMPTY, 20).any { it.kind == CompletionKind.OPERATOR && it.insert == "or" })
        assertTrue(P.complete("o", 1, SearchVocabulary.EMPTY, 20).none { it.kind == CompletionKind.OPERATOR })
        assertTrue(P.complete("-(", 2, SearchVocabulary.EMPTY, 40).none { it.kind == CompletionKind.OPERATOR })
        assertTrue(P.complete("(a) ", 4, SearchVocabulary.EMPTY, 40).any { it.insert == "and" })
    }

    @Test
    fun completesFixedAndVocabularyValues() {
        assertEquals(listOf("learning", "leech", "relearning"), P.complete("is:le", 5, vocab, 10).map { it.insert })
        assertEquals(listOf("kanji"), P.complete("type:ka", 7, vocab, 10).map { it.insert })
        val deck = P.complete("deck:fri", 8, vocab, 10)
        assertEquals(listOf("Frieren", "frieren extra", "Anime: Frieren S1"), deck.map { it.label })
        assertEquals(listOf("Frieren", "\"frieren extra\"", "\"Anime: Frieren S1\""), deck.map { it.insert })
        assertTrue(deck.all { it.kind == CompletionKind.VALUE && it.replaceStart == 5 && it.replaceEnd == 8 })
        // inside an open quote
        val open = P.complete("deck:\"Anime: F", 14, vocab, 10)
        assertEquals(listOf("\"Anime: Frieren S1\""), open.map { it.insert })
        assertEquals(5, open[0].replaceStart)
        assertEquals(14, open[0].replaceEnd)
        assertEquals(listOf("pitch"), P.complete("kind:pi", 7, vocab, 10).map { it.insert })
        assertTrue(P.complete("kind:cu", 7, vocab, 10).any { it.insert == "custom_kind" })
        assertEquals(listOf("meaning:"), P.complete("field:mea", 9, vocab, 10).map { it.insert })
        assertEquals(emptyList(), P.complete("nid:0", 5, vocab, 10))
        assertEquals(emptyList(), P.complete("kanji:", 6, vocab, 10))
        assertEquals(emptyList(), P.complete("added:", 6, vocab, 10))
        assertEquals(emptyList(), P.complete("lapses>", 7, vocab, 10))
        assertEquals(listOf("jlpt5"), P.complete("tag:5", 5, vocab, 10).map { it.insert })
    }

    @Test
    fun completeNeverThrows() {
        for (q in listOf("", "\"", "(((", "deck:\"a\\", "a\\", "field:", "field:x:", "rated:1:", "))))", "　")) {
            for (c in listOf(-5, 0, q.length / 2, q.length, q.length + 9)) {
                P.complete(q, c, vocab, 5)
            }
        }
        assertEquals(emptyList(), P.complete("is:", 3, vocab, 0))
    }
}
