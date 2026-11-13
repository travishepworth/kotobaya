package kotobaya.media.publish

import com.fasterxml.jackson.databind.node.ObjectNode
import kotobaya.media.publish.Archives.archive
import kotobaya.media.publish.Archives.records
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** SNAPSHOT.md 2-5 as checked by [SnapshotReader]: the examples pass, the validate.py negative cases fail. */
class SnapshotReaderTest {
    private fun content(bytes: ByteArray) = SnapshotReader.readContent(bytes.inputStream())
    private fun deck(bytes: ByteArray) = SnapshotReader.readDeckItems(bytes.inputStream())
    private fun newMedia() = records("new-media.content.v1.jsonl")

    private fun rejects(fragment: String, block: () -> Unit) {
        val e = assertFailsWith<SnapshotInvalid> { block() }
        assertTrue(e.message!!.contains(fragment), "expected '$fragment' in: ${e.message}")
    }

    @Test
    fun theExampleArchivesAreValidAndPairUp() {
        val c = content(Archives.bytes("new-media.content.v1.jsonl.zst"))
        val d = deck(Archives.bytes("new-media.deck-items.v1.jsonl.zst"))
        SnapshotReader.checkPair(c, d)
        assertEquals(2, c.segments.size)
        assertEquals(3, c.sentenceCount)
        assertEquals(12L, c.tokens)
        assertEquals(1L, c.unmappedTokens)
        assertEquals(6, d.items.size)
        assertEquals(setOf("0193a1b2-0006-7000-8000-000000000001"), c.grammarPointIds)

        val j = content(Archives.bytes("jpdb-reimport.content.v1.jsonl.zst"))
        SnapshotReader.checkPair(j, deck(Archives.bytes("jpdb-reimport.deck-items.v1.jsonl.zst")))
        assertEquals(352L, j.unmappedTokens)
        val list = deck(Archives.bytes("list-deck.deck-items.v1.jsonl.zst"))
        assertEquals("list", list.header.deckType)
        assertEquals(3, list.items.size)
    }

    @Test
    fun unknownMembersOfHeaderAndRecordsAreIgnored() {
        val r = newMedia()
        r[0].put("producer", "worker 9.9").withObjectProperty("counts").put("future_kind", 0)
        r[1].put("future_member", true)
        assertEquals(2, content(archive(r)).segments.size)
    }

    @Test
    fun unknownFormatOrVersionIsRejected() {
        val r = newMedia()
        r[0].put("format_version", 2)
        rejects("format_version") { content(archive(r)) }
        val d = records("new-media.deck-items.v1.jsonl")
        d[0].put("format", "kotobaya-media-content")
        rejects("unknown format") { deck(archive(d)) }
    }

    @Test
    fun truncatedArchivesAreRejected() {
        val r = newMedia()
        rejects("counts.grammar_span") { content(archive(r.dropLast(1))) }
        val text = Archives.text(r)
        rejects("not terminated by LF") { content(Archives.zstd(text.copyOf(text.size - 1))) }
        rejects("empty archive") { content(Archives.zstd(ByteArray(0))) }
    }

    @Test
    fun concatenatedFramesAreAccepted() {
        val text = Archives.text(newMedia())
        val cut = text.indexOf('\n'.code.toByte()) + 1
        val two = Archives.zstd(text.copyOfRange(0, cut)) + Archives.zstd(text.copyOfRange(cut, text.size))
        assertEquals(3, content(two).sentenceCount)
    }

    @Test
    fun outOfOrderOrDuplicateRecordsAreRejected() {
        val r = newMedia()
        val occ = r.indexOfFirst { it["record"].asText() == "occurrence" }
        r[occ] = r[occ + 1].also { r[occ + 1] = r[occ] }
        rejects("out of order") { content(archive(r)) }

        val s = newMedia()
        val first = s.indexOfFirst { it["record"].asText() == "sentence" }
        s.add(2, s.removeAt(first)) // a sentence between the two segments
        rejects("section order") { content(archive(s)) }

        val d = records("new-media.deck-items.v1.jsonl")
        d[1] = d[2].also { d[2] = d[1] }
        rejects("positions") { deck(archive(d)) }
        val l = records("list-deck.deck-items.v1.jsonl")
        l[1] = l[2].also { l[2] = l[1] }
        rejects("ordered") { deck(archive(l)) }
    }

    @Test
    fun byteLevelRulesAreEnforced() {
        val r = newMedia()
        val text = String(Archives.text(r), Charsets.UTF_8)
        rejects("duplicate") { content(Archives.zstd(text.replaceFirst("{\"", "{\"record\":\"header\",\"").toByteArray())) }
        rejects("byte order mark") { content(Archives.zstd(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray())) }
        rejects("empty, padded or CR") { content(Archives.zstd(text.replaceFirst("\n", "\r\n").toByteArray())) }
        rejects("empty, padded or CR") { content(Archives.zstd(text.replaceFirst("\n", "\n\n").toByteArray())) }
        r[1].put("title", "a\u0000b")
        rejects("U+0000") { content(archive(r)) }
    }

    @Test
    fun occurrencesMustBeTheAggregationOfTheTokens() {
        val r = newMedia()
        val occ = r.first { it["record"].asText() == "occurrence" }
        occ.put("count", 2)
        r[1].put("token_count", r[1]["token_count"].asInt() + 1)
        rejects("tokens") { content(archive(r)) }

        val p = newMedia()
        p.first { it["record"].asText() == "occurrence" }.put("pos_class", "particle")
        rejects("pos_class") { content(archive(p)) }
    }

    @Test
    fun aPairWhoseItemsDifferFromTheContentIsRejected() {
        val c = content(Archives.bytes("new-media.content.v1.jsonl.zst"))
        val d = records("new-media.deck-items.v1.jsonl")
        d.removeAt(d.size - 1)
        val short = deck(archive(Archives.recount(d)))
        rejects("pair") { SnapshotReader.checkPair(c, short) }
        val v2 = records("new-media.deck-items.v1.jsonl").also { it[0].put("content_version", 2) }
        rejects("content_version") { SnapshotReader.checkPair(c, deck(archive(v2))) }
    }
}
