package kotobaya.media.publish

import kotobaya.sync.DefaultCanonicalJson
import org.jooq.DSLContext
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import java.time.OffsetDateTime
import java.util.UUID

/**
 * SNAPSHOT.md 7 steps 3-4 on the server, inside the publish transaction: replace every row of media [mediaId]
 * (segments, sentences, occurrences, detected spans; manual spans untouched) and of its media deck's `deck_item`s
 * with the archives' records. The archives were fully validated before the transaction; this pass re-streams them
 * (never holding the decompressed text) and re-runs the same checks.
 */
internal class SnapshotInstall(private val dsl: DSLContext, private val mediaId: String) {
    private val media = UUID.fromString(mediaId)

    fun install(contentFile: Path, deckFile: Path) {
        dsl.execute("delete from app.occurrence where media_id = ?::uuid", mediaId)
        dsl.execute("delete from app.sentence where media_id = ?::uuid", mediaId)
        dsl.execute("delete from app.media_segment where media_id = ?::uuid", mediaId)
        dsl.execute("delete from app.sentence_grammar_span where media_id = ?::uuid and origin = 'detected'", mediaId)
        dsl.execute("delete from app.deck_item where deck_id = ?::uuid", mediaId)
        // SNAPSHOT.md 4.3: added_at = the deck's own created_at, equal on every replica.
        val addedAt = dsl.fetchValue("select created_at from app.deck where id = ?::uuid", mediaId)
            ?.let { it as OffsetDateTime } ?: error("media deck $mediaId vanished inside the publish transaction")
        dsl.connection { c ->
            val content = ContentWriter(c)
            Files.newInputStream(contentFile).buffered().use { SnapshotReader.readContent(it, content) }
            content.finish()
            val deck = DeckWriter(c, addedAt)
            Files.newInputStream(deckFile).buffered().use { SnapshotReader.readDeckItems(it, deck) }
            deck.finish()
        }
    }

    private class Batch(private val ps: PreparedStatement) {
        private var pending = 0

        fun add(bind: PreparedStatement.() -> Unit) {
            ps.bind()
            ps.addBatch()
            if (++pending >= 1000) flush()
        }

        fun flush() {
            if (pending > 0) ps.executeBatch()
            pending = 0
        }

        fun close() {
            flush()
            ps.close()
        }
    }

    private inner class ContentWriter(private val c: Connection) : ContentSink {
        private val segments = Batch(
            c.prepareStatement(
                "insert into app.media_segment (id, media_id, ordinal, kind, title, sentence_count, token_count, unmapped_token_count) " +
                    "values (?, ?, ?, ?, ?, ?, ?, ?)",
            ),
        )
        private val sentences = Batch(
            c.prepareStatement(
                "insert into app.sentence (id, media_id, segment_id, ordinal, text, tokens, origin, start_ms, end_ms, audio_blob_hash, " +
                    "image_blob_hash, location) values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb)",
            ),
        )
        private val occurrences = Batch(
            c.prepareStatement(
                "insert into app.occurrence (segment_id, entry_seq, media_id, count, pos_class, sentence_ids) values (?, ?, ?, ?, ?, ?)",
            ),
        )
        private val spans = Batch(
            c.prepareStatement(
                "insert into app.sentence_grammar_span (sentence_id, grammar_point_id, start_offset, end_offset, origin, media_id, rule_index) " +
                    "values (?, ?, ?, ?, 'detected', ?, ?)",
            ),
        )
        private var stage = 0

        /** Flushes the batches of earlier record kinds before the first record of a later one (foreign keys). */
        private fun enter(s: Int) {
            if (s == stage) return
            stage = s
            segments.flush()
            sentences.flush()
            occurrences.flush()
        }

        override fun segment(r: SegmentRecord) = segments.add {
            setObject(1, UUID.fromString(r.id))
            setObject(2, media)
            setInt(3, r.ordinal)
            setString(4, r.kind)
            setString(5, r.title)
            setInt(6, r.sentenceCount)
            setInt(7, r.tokenCount)
            setInt(8, r.unmappedTokenCount)
        }

        override fun sentence(r: SentenceRecord) {
            enter(2)
            sentences.add {
                setObject(1, UUID.fromString(r.id))
                setObject(2, media)
                setObject(3, UUID.fromString(r.segmentId))
                setInt(4, r.ordinal)
                setString(5, r.text)
                setString(6, DefaultCanonicalJson.encode(r.tokens))
                setString(7, r.origin)
                nullableInt(8, r.startMs)
                nullableInt(9, r.endMs)
                setString(10, r.audioBlobHash)
                setString(11, r.imageBlobHash)
                setString(12, r.location?.let(DefaultCanonicalJson::encode))
            }
        }

        override fun occurrence(r: OccurrenceRecord) {
            enter(3)
            occurrences.add {
                setObject(1, UUID.fromString(r.segmentId))
                setLong(2, r.entrySeq)
                setObject(3, media)
                setInt(4, r.count)
                setString(5, r.posClass)
                setArray(6, c.createArrayOf("uuid", r.sentenceIds.map(UUID::fromString).toTypedArray()))
            }
        }

        override fun grammarSpan(r: GrammarSpanRecord) {
            enter(4)
            spans.add {
                setObject(1, UUID.fromString(r.sentenceId))
                setObject(2, UUID.fromString(r.grammarPointId))
                setInt(3, r.startOffset)
                setInt(4, r.endOffset)
                setObject(5, media)
                setShort(6, r.ruleIndex.toShort())
            }
        }

        /** After the whole archive passed: flush everything. */
        fun finish() {
            segments.close()
            sentences.close()
            occurrences.close()
            spans.close()
        }
    }

    private inner class DeckWriter(c: Connection, private val addedAt: OffsetDateTime) : DeckItemSink {
        private val items = Batch(
            c.prepareStatement(
                "insert into app.deck_item (deck_id, item_key, entry_seq, note_id, position, occurrence_count, added_at, deleted_at, hlc) " +
                    "values (?, ?, ?, null, ?, ?, ?, null, null)",
            ),
        )

        override fun item(r: DeckItemRecord) = items.add {
            setObject(1, media)
            setString(2, "e:${r.entrySeq}")
            setLong(3, r.entrySeq)
            setLong(4, r.position)
            setInt(5, r.occurrenceCount)
            setObject(6, addedAt)
        }

        fun finish() = items.close()
    }

    private fun PreparedStatement.nullableInt(i: Int, v: Int?) = if (v == null) setNull(i, Types.INTEGER) else setInt(i, v)
}
