package kotobaya.grammar.cards

import kotobaya.cards.grammar.GrammarCandidate
import kotobaya.cards.grammar.GrammarCardSentences
import kotobaya.grammar.UNKNOWN
import kotobaya.server.Database

/**
 * Example-sentence selection for a grammar card review (T507). Candidates are the user's mined sentences
 * (`origin = 'media'`, live media) carrying a live span of the point, detected or manual. Generated sentences are
 * never used (SPEC: kept separate), and Tatoeba rows carry no spans, so they only appear once detection tags them.
 * The choice itself (rotation, i+0 first) is `GrammarCardSentences.pick` in core, shared with the clients.
 * Grammar card creation needs no code here: `POST /notes` with `grammar_point_id` already mints the ops (rule 8).
 */
class GrammarCardSentenceSource(private val db: Database) {
    suspend fun candidates(grammarPointId: String): List<GrammarCandidate> = db.tx { dsl ->
        dsl.fetch(
            "select s.id::text, s.text, gs.start_offset, gs.end_offset, $UNKNOWN::int " +
                "from app.sentence_grammar_span gs " +
                "join app.sentence s on s.id = gs.sentence_id and s.origin = 'media' " +
                "join app.media m on m.id = s.media_id and m.deleted_at is null " +
                "where gs.grammar_point_id = ?::uuid and gs.deleted_at is null",
            grammarPointId,
        ).map {
            GrammarCandidate(it.get(0, String::class.java), it.get(1, String::class.java), it.get(2, Int::class.java), it.get(3, Int::class.java), it.get(4, Int::class.java))
        }
    }

    /** The sentence for the next review of [grammarPointId]: never [lastShown] when two or more exist; [rotation] is e.g. the card's review count. */
    suspend fun next(grammarPointId: String, lastShown: String?, rotation: Int): GrammarCandidate? =
        GrammarCardSentences.pick(candidates(grammarPointId), lastShown, rotation)
}
