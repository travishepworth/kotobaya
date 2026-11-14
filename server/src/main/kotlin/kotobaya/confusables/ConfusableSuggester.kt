package kotobaya.confusables

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.OpType
import kotobaya.notes.payload
import kotobaya.server.Database
import kotobaya.server.json
import kotobaya.sync.OpApply
import org.slf4j.LoggerFactory

/**
 * Server-minted `confusable.suggested` ops (T502). Scores the user's live notes ([ConfusableScoring]) and mints one op
 * per pair+reason that has no `app.confusable` row yet, through the shared [OpApply] (CLAUDE.md rule 8).
 *
 * Idempotent: any existing row (suggested, confirmed or rejected) suppresses the pair, so a re-run emits nothing and
 * a user decision is never touched. Runs at start and every [intervalMs]; [runOnce] is callable directly.
 */
open class ConfusableSuggester(
    private val db: Database,
    private val opApply: OpApply,
    private val intervalMs: Long = 6 * 3_600_000L,
    private val batch: Int = 200,
) {
    private val log = LoggerFactory.getLogger("kotobaya.confusables")

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            try {
                val n = runOnce()
                if (n > 0) log.info("confusables: {} suggestion(s)", n)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("confusable suggestions failed", e)
            }
            delay(intervalMs)
        }
    }

    /** Returns the number of suggestions minted. */
    open fun runOnce(): Int {
        val (candidates, existing) = db.txBlocking { dsl ->
            val vocab = dsl.fetch(
                "select n.id::text, n.entry_seq, e.headword, e.headword_reading, e.senses::text " +
                    "from app.note n join ref.dict_entry e on e.seq = n.entry_seq " +
                    "where n.type = 'vocab' and n.deleted_at is null and n.merged_into_id is null",
            ).map { r ->
                val senses = json.readTree(r.get(4, String::class.java)).map { s ->
                    s.path("gloss").filter { it.path("lang").asText("eng") == "eng" }.map { it.path("text").asText() }
                }
                VocabNote(r.get(0, String::class.java), (r.get(1) as Number).toLong(), r.get(2, String::class.java), r.get(3, String::class.java), senses)
            }
            val kanji = dsl.fetch(
                "select id::text, kanji_literal from app.note " +
                    "where type = 'kanji' and kanji_literal is not null and deleted_at is null and merged_into_id is null",
            ).map { KanjiNote(it.get(0, String::class.java), it.get(1, String::class.java)) }
            val literals = (kanji.map { it.literal } + vocab.flatMap { v -> v.headword.codePoints().toArray().map { String(Character.toChars(it)) } }).distinct()
            val components: Map<String, Set<String>> = if (literals.isEmpty()) {
                emptyMap()
            } else {
                dsl.fetch(
                    "select literal, components from ref.kanji where literal = any(?::text[]) and cardinality(components) > 0",
                    literals.toTypedArray(),
                ).associate { it.get(0, String::class.java) to (it.get(1) as Array<*>).map { c -> c.toString() }.toSet() }
            }
            val have = dsl.fetch("select note_a_id::text, note_b_id::text, reason from app.confusable")
                .map { r -> Triple(r.get(0, String::class.java), r.get(1, String::class.java), r.get(2, String::class.java)) }.toSet()
            ConfusableScoring.suggest(vocab, kanji, components) to have
        }
        val fresh = candidates.filter { Triple(it.a, it.b, it.reason.wire) !in existing }
            .sortedWith(compareBy({ it.a }, { it.b }, { it.reason.wire }))
        var minted = 0
        for (chunk in fresh.chunked(batch)) {
            minted += opApply.transactionBlocking { tx ->
                var n = 0
                for (p in chunk) {
                    // Re-check inside the transaction: a user decision may have landed since the scan.
                    val exists = tx.dsl.fetchExists(
                        tx.dsl.selectOne().from("app.confusable").where("note_a_id = ?::uuid and note_b_id = ?::uuid and reason = ?", p.a, p.b, p.reason.wire),
                    )
                    if (!exists) {
                        tx.emit(OpType.CONFUSABLE_SUGGESTED, pairPayload(p.a, p.b, p.reason.wire))
                        n++
                    }
                }
                n
            }
        }
        return minted
    }
}

internal fun pairPayload(a: String, b: String, reason: String): JsonObject =
    payload(json.createObjectNode().put("note_a_id", a).put("note_b_id", b).put("reason", reason))
