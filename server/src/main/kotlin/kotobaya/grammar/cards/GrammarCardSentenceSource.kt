package kotobaya.grammar.cards

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotobaya.cards.grammar.GrammarCandidate
import kotobaya.cards.grammar.GrammarCardSentences
import kotobaya.grammar.UNKNOWN
import kotobaya.server.Database
import kotobaya.server.json
import org.jooq.DSLContext

/**
 * Example-sentence selection for a grammar card review (T507, F-25). Candidates are the user's mined sentences
 * (`origin = 'media'`, live media) carrying a live span of the point, detected or manual. Generated sentences are
 * never used (SPEC: kept separate), and Tatoeba rows carry no spans, so they only appear once detection tags them.
 * The choice itself (rotation, i+0 first) is `GrammarCardSentences.pick` in core, shared with the clients.
 * Grammar card creation needs no code here: `POST /notes` with `grammar_point_id` already mints the ops (rule 8).
 */
class GrammarCardSentenceSource(private val db: Database) {
    suspend fun candidates(grammarPointId: String): List<GrammarCandidate> = db.tx { dsl -> candidates(dsl, grammarPointId) }

    /** The sentence for the next review of [grammarPointId]: never [lastShown] when two or more exist; [rotation] is e.g. the card's review count. */
    suspend fun next(grammarPointId: String, lastShown: String?, rotation: Int): GrammarCandidate? =
        GrammarCardSentences.pick(candidates(grammarPointId), lastShown, rotation)

    companion object {
        /** Live spans of a live point in live `origin = media` sentences, ordered (sentence id, start, end) for a deterministic pick. */
        fun candidates(dsl: DSLContext, grammarPointId: String): List<GrammarCandidate> = dsl.fetch(
            "select s.id::text, s.text, gs.start_offset, gs.end_offset, $UNKNOWN::int " +
                "from app.sentence_grammar_span gs " +
                "join app.grammar_point p on p.id = gs.grammar_point_id and p.deleted_at is null " +
                "join app.sentence s on s.id = gs.sentence_id and s.origin = 'media' " +
                "join app.media m on m.id = s.media_id and m.deleted_at is null " +
                "where gs.grammar_point_id = ?::uuid and gs.deleted_at is null " +
                "order by s.id, gs.start_offset, gs.end_offset",
            grammarPointId,
        ).map {
            GrammarCandidate(it.get(0, String::class.java), it.get(1, String::class.java), it.get(2, Int::class.java), it.get(3, Int::class.java), it.get(4, Int::class.java))
        }

        /**
         * `lastShown` of a card: `sentence_id` of its latest (by hlc) live, non-manual review, counting cards merged into it;
         * null when none or when that row has no sentence.
         */
        fun lastShown(dsl: DSLContext, cardId: String): String? =
            dsl.fetch(
                "select sentence_id::text from app.review_log where (card_id = ?::uuid or card_id in " +
                    "(select id from app.card where merged_into_id = ?::uuid)) and undone_at is null and review_type <> 'manual' " +
                    "order by hlc desc, id desc limit 1",
                cardId, cardId,
            ).firstOrNull()?.get(0, String::class.java)

        /**
         * `(Sentence, GrammarCardSentence)` JSON for the card's next showing (C-15), or null when the point has no candidate.
         * Pure function of the replica state: refetching or undoing shows the same sentence.
         */
        fun itemFor(dsl: DSLContext, cardId: String, grammarPointId: String, reps: Int): Pair<ObjectNode, ObjectNode>? {
            val last = lastShown(dsl, cardId)
            val pick = GrammarCardSentences.pick(candidates(dsl, grammarPointId), last, reps) ?: return null
            val r = dsl.fetch(
                "select s.id::text, s.media_id::text, s.segment_id::text, s.ordinal, s.text, s.tokens::text, s.origin, " +
                    "s.start_ms, s.end_ms, s.audio_blob_hash, s.image_blob_hash, s.location::text, m.title, g.title " +
                    "from app.sentence s join app.media m on m.id = s.media_id join app.media_segment g on g.id = s.segment_id " +
                    "where s.id = ?::uuid",
                pick.sentenceId,
            ).firstOrNull() ?: return null
            val spans = dsl.fetch(
                "select gs.grammar_point_id::text, gs.start_offset, gs.end_offset, gs.origin " +
                    "from app.sentence_grammar_span gs join app.grammar_point p on p.id = gs.grammar_point_id and p.deleted_at is null " +
                    "where gs.sentence_id = ?::uuid and gs.deleted_at is null " +
                    "order by gs.start_offset, gs.end_offset, gs.grammar_point_id, gs.origin",
                pick.sentenceId,
            )
            val location = r.get(11, String::class.java)?.let { json.readTree(it) }
            val sentence = json.createObjectNode()
            sentence.put("id", r.get(0, String::class.java))
            sentence.put("media_id", r.get(1, String::class.java))
            sentence.put("segment_id", r.get(2, String::class.java))
            sentence.put("ordinal", r.get(3, Int::class.java))
            sentence.put("text", r.get(4, String::class.java))
            sentence.set<JsonNode>("tokens", json.readTree(r.get(5, String::class.java)))
            sentence.put("origin", r.get(6, String::class.java))
            sentence.put("start_ms", (r.get(7) as Number?)?.toInt())
            sentence.put("end_ms", (r.get(8) as Number?)?.toInt())
            sentence.put("audio_blob_hash", r.get(9, String::class.java))
            sentence.put("image_blob_hash", r.get(10, String::class.java))
            sentence.set<JsonNode>("location", location ?: json.nullNode())
            sentence.put("unknown_count", pick.unknownCount)
            val arr = sentence.putArray("grammar_spans")
            for (sp in spans) {
                arr.addObject().put("grammar_point_id", sp.get(0, String::class.java)).put("start_offset", sp.get(1, Int::class.java))
                    .put("end_offset", sp.get(2, Int::class.java)).put("origin", sp.get(3, String::class.java))
            }
            val gs = json.createObjectNode()
            gs.put("sentence_id", pick.sentenceId)
            gs.put("text", pick.text)
            gs.put("start_offset", pick.start)
            gs.put("end_offset", pick.end)
            gs.putNull("translation")
            gs.put("unknown_count", pick.unknownCount)
            gs.put("rotation", reps)
            gs.put("last_shown_sentence_id", last)
            val src = gs.putObject("source")
            src.put("media_id", r.get(1, String::class.java))
            src.put("media_title", r.get(12, String::class.java))
            src.put("segment_id", r.get(2, String::class.java))
            src.put("segment_title", r.get(13, String::class.java))
            src.put("start_ms", (r.get(7) as Number?)?.toInt())
            src.set<JsonNode>("location", location ?: json.nullNode())
            return sentence to gs
        }
    }
}
