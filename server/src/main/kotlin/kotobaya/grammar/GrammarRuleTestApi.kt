package kotobaya.grammar

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.response.header
import kotobaya.notes.Errors
import kotobaya.notes.gone
import kotobaya.notes.jsonBody
import kotobaya.notes.pathId
import kotobaya.parse.ParserUnavailable
import kotobaya.server.Database
import kotobaya.server.Problems
import kotobaya.server.json
import kotobaya.server.requestId
import kotobaya.server.respondJson
import org.jooq.DSLContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("kotobaya.grammar.RuleTester")

/** worker-parse.yaml limits for the tokenised form. */
private const val MAX_TEXT_CODE_POINTS = 20_000
private const val MAX_TOKENS = 20_000
private const val BATCH_SENTENCES = 100
private const val BATCH_BYTES = 2 * 1024 * 1024
private const val SCAN_CAP = 200_000

private fun unavailable(call: ApplicationCall, x: ParserUnavailable): Nothing {
    log.warn("rule tester unavailable: {}", x.message)
    if (x.retryAfter) call.response.header("Retry-After", "5")
    throw Problems.unavailable("The rule tester is not available right now. Try again shortly.")
}

/** Validate-only use of the worker for `createGrammarPoint` / `updateGrammarPoint` (worker-parse.yaml section 5, fail closed). */
internal class RuleValidator(private val tester: RuleTesterClient) {
    /** 422 per invalid rule; 503 when the worker cannot answer. No call when [rules] is absent or empty. */
    suspend fun check(call: ApplicationCall, rules: JsonNode?) {
        if (rules == null || !rules.isArray || rules.isEmpty) return
        val texts = rules.map { it["rule"].asText() }
        throwErrors(call, texts, "/detection_rules")
    }

    /** Validate-only on [texts]; throws 422 with pointer `$base/{i}/rule` for each error. */
    suspend fun throwErrors(call: ApplicationCall, texts: List<String>, base: String) {
        val res = try {
            tester.test(texts, emptyList(), call.requestId)
        } catch (x: ParserUnavailable) {
            unavailable(call, x)
        }
        report(res.errors, base)
    }

    fun report(errors: List<RuleError>, base: String) {
        if (errors.isEmpty()) return
        val e = Errors()
        errors.forEach { e.add("$base/${it.ruleIndex}/rule", "invalid_value", "column ${it.column}: ${it.message}") }
        e.throwIfAny()
    }
}

/** `testGrammarRules`: stored sentences (media newest first, in order) through the worker's rule matcher; writes nothing. */
internal class GrammarRuleTestApi(private val db: Database, private val tester: RuleTesterClient, private val validator: RuleValidator) {
    private val examples = GrammarExamplesApi(db)

    private class Row(val id: String, val text: String, val tokens: String, val segOrdinal: Int, val segmentId: String, val ordinal: Int)

    suspend fun test(call: ApplicationCall) {
        val id = call.pathId("grammar_point_id")
        val body = if (call.request.contentType() == ContentType.Any || call.request.headers["Content-Length"] == "0") json.createObjectNode() else call.jsonBody()
        val e = Errors()
        e.unknownKeys(body, setOf("rules", "media_id", "limit"))
        val ownRules = body.get("rules")?.let { parseRules(it, e) }
        val mediaId = e.uuid(body, "media_id")
        val limit = body.get("limit")?.let { v ->
            if (v.isIntegralNumber && v.asInt() in 1..200) v.asInt() else null.also { e.add("/limit", "invalid_value", "Must be 1 to 200.") }
        } ?: 50
        e.throwIfAny()
        val stored = db.tx { dsl ->
            val r = dsl.fetchOptional(
                "select detection_rules::text, to_char(deleted_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"') from app.grammar_point where id = ?::uuid", id,
            ).orElse(null) ?: throw Problems.notFound("No such grammar point.")
            r.get(1, String::class.java)?.let { throw gone("grammar point", it) }
            json.readTree(r.get(0, String::class.java)).map { it["rule"].asText() }
        }
        val rules = ownRules ?: stored
        if (rules.isEmpty()) return call.respondJson(mapOf("scanned" to 0, "truncated" to false, "matches" to emptyList<Any>()))
        // Fail on a bad rule even when there is nothing to scan; the pointer is into the request's rules (or the stored ones).
        validator.throwErrors(call, rules, "/rules")
        call.respondJson(scan(call, rules, mediaId, limit))
    }

    private fun parseRules(v: JsonNode, e: Errors): List<String>? {
        if (!v.isArray || v.size() < 1 || v.size() > 20) {
            e.add("/rules", "invalid_value", "An array of 1 to 20 rules.")
            return null
        }
        val out = mutableListOf<String>()
        v.forEachIndexed { i, r ->
            val at = "/rules/$i"
            if (r !is ObjectNode) return@forEachIndexed e.add(at, "invalid_type", "Must be an object.")
            val text = r.get("rule")?.let { e.stringValue(it, "$at/rule", 1, 1000) } ?: run { if (!r.has("rule")) e.add("$at/rule", "required", "rule is required."); null }
            val draft = r.get("draft")
            if (draft != null && !draft.isBoolean) e.add("$at/draft", "invalid_type", "Must be a boolean.")
            r.fieldNames().asSequence().filter { it != "rule" && it != "draft" }.forEach { e.add("$at/$it", "unknown_field", "Unknown property $it.") }
            if (text != null) out += text
        }
        return if (e.any) null else out
    }

    /** Sentences in scan order, one page at a time; keyset paging within a media, media newest first. */
    private inner class Pager(private val mediaFilter: String?) {
        private var media: ArrayDeque<String>? = null
        private var last: Row? = null

        suspend fun next(): List<Row> {
            val queue = media ?: ArrayDeque(
                db.tx { dsl ->
                    dsl.fetch(
                        "select id::text from app.media where deleted_at is null and (?::uuid is null or id = ?::uuid) order by created_at desc, id desc",
                        mediaFilter, mediaFilter,
                    ).map { it.get(0, String::class.java) }
                },
            ).also { media = it }
            while (queue.isNotEmpty()) {
                val rows = page(queue.first(), last)
                if (rows.isNotEmpty()) {
                    last = rows.last()
                    return rows
                }
                queue.removeFirst()
                last = null
            }
            return emptyList()
        }

        private suspend fun page(mediaId: String, after: Row?): List<Row> = db.tx { dsl ->
            val seek = if (after == null) "" else " and (g.ordinal, s.segment_id, s.ordinal) > (?, ?::uuid, ?)"
            val args = mutableListOf<Any>(mediaId)
            if (after != null) args.addAll(listOf(after.segOrdinal, after.segmentId, after.ordinal))
            dsl.fetch(
                "select s.id::text, s.text, s.tokens::text, g.ordinal, s.segment_id::text, s.ordinal from app.sentence s " +
                    "join app.media_segment g on g.id = s.segment_id where s.media_id = ?::uuid$seek " +
                    "order by g.ordinal, s.segment_id, s.ordinal limit $BATCH_SENTENCES",
                *args.toTypedArray(),
            ).map { Row(it.get(0, String::class.java), it.get(1, String::class.java), it.get(2, String::class.java), it.get(3, Int::class.java), it.get(4, String::class.java), it.get(5, Int::class.java)) }
        }
    }

    private suspend fun scan(call: ApplicationCall, rules: List<String>, mediaId: String?, limit: Int): Map<String, Any?> {
        val pager = Pager(mediaId)
        val found = mutableListOf<Pair<Row, List<RuleMatch>>>()
        var scanned = 0
        var truncated = false
        val pending = ArrayDeque<Row>()
        while (true) {
            if (scanned >= SCAN_CAP) {
                truncated = pending.isNotEmpty() || pager.next().isNotEmpty()
                break
            }
            if (pending.isEmpty()) {
                pending.addAll(pager.next())
                if (pending.isEmpty()) break
            }
            // Take a batch by count and bytes; an unsendable sentence alone counts as scanned without a match.
            val batch = mutableListOf<Row>()
            var bytes = 0
            while (pending.isNotEmpty() && batch.size < BATCH_SENTENCES && scanned + batch.size < SCAN_CAP) {
                val r = pending.first()
                val size = r.text.length + r.tokens.length
                if (!sendable(r)) {
                    if (batch.isNotEmpty()) break
                    pending.removeFirst()
                    scanned++
                    continue
                }
                if (batch.isNotEmpty() && bytes + size > BATCH_BYTES) break
                batch += pending.removeFirst()
                bytes += size
            }
            if (batch.isEmpty()) continue
            val res = try {
                tester.test(rules, batch.map { RuleSentence(it.text, json.readTree(it.tokens)) }, call.requestId)
            } catch (x: ParserUnavailable) {
                unavailable(call, x)
            }
            validator.report(res.errors, "/rules")
            // A truncated worker answer may have cut its last sentence: use the complete prefix and re-send the rest.
            var usable = batch.size
            if (res.truncated) {
                val cut = res.matches.lastOrNull()?.sentenceIndex ?: 0
                usable = if (cut == 0) 1 else cut
                for (r in batch.subList(usable, batch.size).asReversed()) pending.addFirst(r)
            }
            val bySentence = res.matches.groupBy { it.sentenceIndex }
            for (k in 0 until usable) {
                scanned++
                val ms = bySentence[k]
                if (ms != null) found += batch[k] to ms
                if (found.size >= limit) {
                    for (r in batch.subList(k + 1, usable).asReversed()) pending.addFirst(r)
                    truncated = pending.isNotEmpty() || pager.next().isNotEmpty()
                    return result(found, scanned, truncated)
                }
            }
        }
        return result(found, scanned, truncated)
    }

    private fun sendable(r: Row): Boolean {
        if (r.text.codePointCount(0, r.text.length) > MAX_TEXT_CODE_POINTS || r.text.indexOf('\u0000') >= 0) return false
        val tokens = json.readTree(r.tokens)
        return tokens.isArray && tokens.size() <= MAX_TOKENS
    }

    private suspend fun result(found: List<Pair<Row, List<RuleMatch>>>, scanned: Int, truncated: Boolean): Map<String, Any?> {
        val ids = found.map { it.first.id }
        val sentences = if (ids.isEmpty()) emptyMap() else db.tx { dsl -> sentencesById(dsl, ids) }
        return linkedMapOf(
            "scanned" to scanned,
            "truncated" to truncated,
            "matches" to found.mapNotNull { (row, ms) ->
                val s = sentences[row.id] ?: return@mapNotNull null // deleted between scan and render
                linkedMapOf(
                    "sentence" to s,
                    "spans" to ms.sortedWith(compareBy({ it.startOffset }, { it.ruleIndex }, { it.endOffset }))
                        .map { linkedMapOf("start_offset" to it.startOffset, "end_offset" to it.endOffset, "rule_index" to it.ruleIndex) },
                )
            },
        )
    }

    private fun sentencesById(dsl: DSLContext, ids: List<String>): Map<String, Map<String, Any?>> {
        val rows = dsl.fetch("select $COLUMNS from app.sentence s where s.id = any(?::uuid[])", ids.toTypedArray())
        val spans = examples.spans(dsl, ids)
        return examples.render(rows, spans).associateBy { it["id"] as String }
    }
}
