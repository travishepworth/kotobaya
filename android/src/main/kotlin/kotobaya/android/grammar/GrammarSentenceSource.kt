package kotobaya.android.grammar

import kotobaya.cards.grammar.GrammarCandidate
import kotobaya.cards.grammar.GrammarCardSentences
import kotobaya.contracts.render.ClozeSentence
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row

/**
 * Example sentence for a grammar card at review time, computed from the device replica (C-15 "Offline / Android"):
 * installed media snapshots, detected and synced manual spans, local word status and the local review log. Same
 * candidate rules and `lastShown` as the server; the choice is core's [GrammarCardSentences.pick]. A device that has
 * not installed the media snapshots (or has no span of the point) simply has no candidate, and the caller shows the notice.
 */
internal object GrammarSentenceSource {
    private fun str(v: JsonValue?): String? = (v as? JsonString)?.value
    private fun int(v: JsonValue?): Int? = (v as? JsonNumber)?.value?.toInt()
    private fun isNull(v: JsonValue?): Boolean = v == null || v == JsonNull

    /** Live spans of the point in live `origin = media` sentences of live media, with the sentence's unknown count. */
    fun candidates(t: LearningTables, grammarPointId: String): List<GrammarCandidate> {
        val point = t.get(LearningTable.GRAMMAR_POINT, listOf(JsonString(grammarPointId)))
        if (point == null || !isNull(point["deleted_at"])) return emptyList()
        val unknownBySentence = HashMap<String, Int>()
        val out = ArrayList<GrammarCandidate>()
        for (span in t.where(LearningTable.SENTENCE_GRAMMAR_SPAN, "grammar_point_id", JsonString(grammarPointId))) {
            if (!isNull(span["deleted_at"])) continue
            val sid = str(span["sentence_id"]) ?: continue
            val sentence = t.get(LearningTable.SENTENCE, listOf(JsonString(sid))) ?: continue
            if (str(sentence["origin"]) != "media") continue
            val media = t.get(LearningTable.MEDIA, listOf(JsonString(str(sentence["media_id"]) ?: continue))) ?: continue
            if (!isNull(media["deleted_at"])) continue
            val unknown = unknownBySentence.getOrPut(sid) { unknownCount(t, sentence) }
            out += GrammarCandidate(
                sid, str(sentence["text"]) ?: continue, int(span["start_offset"]) ?: continue, int(span["end_offset"]) ?: continue, unknown,
            )
        }
        return out
    }

    /** Distinct mapped content/other entries whose status is `new` (the server's definition; PARSER.md 2). */
    private fun unknownCount(t: LearningTables, sentence: Row): Int {
        val tokens = (sentence["tokens"] as? JsonArray)?.items.orEmpty()
        val seqs = HashSet<Long>()
        for (tok in tokens) {
            val m = (tok as? JsonObject)?.members ?: continue
            val seq = (m["seq"] as? JsonNumber)?.value?.toLong() ?: continue
            if (str(m["class"]) !in setOf("content", "other")) continue
            val status = str(t.get(LearningTable.WORD_STATUS, listOf(JsonNumber(seq.toDouble())))?.get("status")) ?: "new"
            if (status == "new") seqs += seq
        }
        return seqs.size
    }

    /** `sentence_id` of the card's latest live, non-manual review by (hlc, id), counting cards merged into it. */
    fun lastShown(t: LearningTables, cardId: String): String? {
        val ids = listOf(cardId) + t.where(LearningTable.CARD, "merged_into_id", JsonString(cardId)).mapNotNull { str(it["id"]) }
        return ids.flatMap { t.where(LearningTable.REVIEW_LOG, "card_id", JsonString(it)) }
            .filter { isNull(it["undone_at"]) && str(it["review_type"]) != "manual" }
            .maxWithOrNull(compareBy<Row>({ str(it["hlc"]) ?: "" }, { str(it["id"]) ?: "" }))
            ?.let { str(it["sentence_id"]) }
    }

    /** The cloze for the card's next showing, or null when the point has no usable local candidate. */
    fun pick(t: LearningTables, cardId: String, grammarPointId: String, reps: Int): ClozeSentence? {
        val picked = GrammarCardSentences.pick(candidates(t, grammarPointId), lastShown(t, cardId), reps) ?: return null
        return GrammarCardSentences.toCloze(picked)
    }
}
