package kotobaya.search.sql

import kotobaya.contracts.InstantMs
import kotobaya.contracts.Uuid
import kotobaya.contracts.queue.StudyDay
import kotobaya.contracts.search.CompareOp
import kotobaya.contracts.search.IsValue
import kotobaya.contracts.search.SearchNode
import kotobaya.contracts.search.SearchPattern
import kotobaya.contracts.search.SearchProperty
import kotobaya.contracts.search.SearchTerm
import kotlin.math.ceil
import kotlin.math.floor

/** SQL flavour to emit. */
public enum class Dialect {
    /** Server: PostgreSQL, schema `app`, `search_text`, `jsonb`, `text[]`, `timestamptz`. */
    POSTGRES,

    /** Device: SQLite 3.18 (no JSON functions, no `search_text`), epoch-millisecond integers. */
    SQLITE,
}

/** A live smart deck: `deck:` terms whose pattern matches [name] also select the cards matching [query]. */
public data class SmartDeckRef(
    /** `deck.name`. */
    public val name: String,
    /** The parsed `deck.query`. */
    public val query: SearchNode,
)

/** The evaluation inputs of Search.kt "Evaluation (C-2)" besides the tree. */
public data class SearchContext(
    /** Current study day, for day-based terms. */
    public val studyDay: StudyDay,
    /** The evaluation instant (UTC ms). */
    public val nowMs: InstantMs,
    /** `scheduler.leech_threshold`. */
    public val leechThreshold: Int,
    /** Hot note ids at [nowMs] (for `is:hot`). */
    public val hotNoteIds: Set<Uuid> = emptySet(),
    /** Live smart decks (their membership is the result of their query). Nested smart-deck references are resolved without the deck itself (no cycles). */
    public val smartDecks: List<SmartDeckRef> = emptyList(),
    /**
     * First instant of the study day [offset] days from today (`0` = [studyDay]`.startMs`, `-1` = yesterday,
     * `1` = [studyDay]`.endMs`), computed by the caller's platform code with the user's time zone and
     * rollover hour (core has no zone database). Must be strictly increasing in `offset`. Called with
     * offsets in `-36500..100000`. The default assumes 24-hour days, which is an hour off across a DST change.
     */
    public val dayStart: (offset: Int) -> InstantMs = { offset -> studyDay.startMs + offset.toLong() * 86_400_000L },
)

/** SQL text with positional `?` placeholders and the values to bind, in order. */
public data class SqlFragment(
    /** SQL text; `?` placeholders only, no user text. */
    public val sql: String,
    /** Bind values (String, Long or Double; never Int, so JS and JVM agree). */
    public val params: List<Any>,
)

/**
 * One row returned by [CompiledSearch.select], for [CompiledSearch.accepts]. Device only; `tags`
 * and `fields` are the raw JSON column texts.
 */
public class ResidualRow(
    /** `note.id`. */
    public val noteId: Uuid,
    /** `note.type`. */
    public val noteType: String,
    /** `note.tags` (JSON array text). */
    public val tagsJson: String,
    /** `note.fields` (JSON object text). */
    public val fieldsJson: String,
    /** `note.mnemonic`. */
    public val mnemonic: String,
    /** `note.source_sentence`. */
    public val sourceSentence: String,
    /** The probe columns, in order (SQLite `0`/`1` mapped to booleans). */
    public val probes: List<Boolean>,
) {
    internal val tags: List<String> by lazy { MiniJson(tagsJson).stringArray() }
    internal val fields: Map<String, String> by lazy { MiniJson(fieldsJson).objectText() }

    /** The device equivalent of `note.search_text`: values in key order, mnemonic, source sentence, joined by a space. */
    internal val searchText: String by lazy {
        fields.entries.sortedBy { it.key }.joinToString(" ") { it.value } + " " + mnemonic + " " + sourceSentence
    }
}

/**
 * A compiled query. Statement shape: `SELECT c.id [, residual columns] FROM card c JOIN note n ON n.id = c.note_id WHERE ...`.
 *
 * - [POSTGRES][Dialect.POSTGRES]: every term is decided in SQL; [residual] is false; `accepts` is not needed.
 * - [SQLITE][Dialect.SQLITE]: the SQL is a prefilter that never excludes a matching card. If [residual] is true,
 *   [select] also returns the columns of [ResidualRow] (in its constructor order, after `c.id`, then one 0/1 column
 *   per probe) and the caller MUST keep exactly the rows for which [accepts] is true.
 *
 * Contract addition (T108b): the frozen contract has no SQL compiler interface; this is it.
 */
public class CompiledSearch internal constructor(
    /** The dialect. */
    public val dialect: Dialect,
    /** The `WHERE` condition over aliases `c` (card) and `n` (note), live-card filter included. */
    public val where: SqlFragment,
    /** SQL booleans the residual needs per row (selected as columns `p0`, `p1`, ...). */
    public val probes: List<SqlFragment>,
    private val pred: ((ResidualRow) -> Boolean)?,
) {
    /** True iff rows must be filtered with [accepts]. */
    public val residual: Boolean get() = pred != null

    /** Exact decision for a row returned by [select] (true when there is no residual). */
    public fun accepts(row: ResidualRow): Boolean = pred?.invoke(row) ?: true

    /** The full statement; add `ORDER BY` / `LIMIT` (only when not [residual]) after it. */
    public fun select(): SqlFragment {
        val t = if (dialect == Dialect.POSTGRES) "app." else ""
        val sb = StringBuilder("SELECT c.id")
        val params = ArrayList<Any>()
        if (residual) {
            sb.append(", n.id, n.type, n.tags, n.fields, n.mnemonic, n.source_sentence")
            probes.forEachIndexed { i, p ->
                sb.append(", (").append(p.sql).append(") AS p").append(i)
                params.addAll(p.params)
            }
        }
        sb.append(" FROM ${t}card c JOIN ${t}note n ON n.id = c.note_id WHERE ").append(where.sql)
        params.addAll(where.params)
        return SqlFragment(sb.toString(), params)
    }
}

/** Compiles a search tree (T108a parser output) to a `WHERE` condition with bind parameters. Pure. */
public object SearchSqlCompiler {
    /** Compiles [node] for [dialect]. User text only ever appears in [SqlFragment.params]. */
    public fun compile(node: SearchNode, dialect: Dialect, context: SearchContext): CompiledSearch {
        val probes = ArrayList<SqlFragment>()
        val b = Builder(dialect, probes)
        val c = b.node(node, context)
        val base = "c.deleted_at IS NULL AND c.merged_into_id IS NULL AND n.deleted_at IS NULL"
        val where = if (c.sql == "TRUE") base else "$base AND ${c.sql}"
        return CompiledSearch(dialect, SqlFragment(where, c.params), probes, c.pred)
    }
}

private typealias Pred = (ResidualRow) -> Boolean

/** `node` is equivalent to `sql AND pred` (pred null: exactly `sql`); `sql` may over-select only when pred is non-null. */
private class C(val sql: String, val params: List<Any> = emptyList(), val pred: Pred? = null)

private const val UPPER = "'ABCDEFGHIJKLMNOPQRSTUVWXYZ'"
private const val LOWER = "'abcdefghijklmnopqrstuvwxyz'"
private val TRUE_C = C("TRUE")
private val FALSE_C = C("1 = 0")

private class Builder(val d: Dialect, val probes: MutableList<SqlFragment>) {
    val pg = d == Dialect.POSTGRES
    val tp = if (pg) "app." else ""

    fun node(n: SearchNode, ctx: SearchContext): C = when (n) {
        is SearchNode.All -> TRUE_C
        is SearchNode.And -> and(n.children.map { node(it, ctx) })
        is SearchNode.Or -> or(n.children.map { node(it, ctx) })
        is SearchNode.Not -> not(node(n.child, ctx))
        is SearchNode.Term -> term(n.term, ctx)
    }

    // ---- boolean combinators ----

    fun and(cs: List<C>): C {
        val parts = cs.filter { it.sql != "TRUE" }
        val preds = cs.mapNotNull { it.pred }
        val pred: Pred? = if (preds.isEmpty()) null else { r -> preds.all { it(r) } }
        val sql = when (parts.size) {
            0 -> "TRUE"
            1 -> parts[0].sql
            else -> parts.joinToString(" AND ", "(", ")") { it.sql }
        }
        return C(sql, parts.flatMap { it.params }, pred)
    }

    fun or(cs: List<C>): C {
        if (cs.any { it.sql == "TRUE" && it.pred == null }) return TRUE_C
        val sql = cs.joinToString(" OR ", "(", ")") { it.sql }
        val params = cs.flatMap { it.params }
        if (cs.all { it.pred == null }) return C(sql, params)
        val truths = cs.map { truth(it) }
        return C(sql, params, { r -> truths.any { it(r) } })
    }

    fun not(c: C): C {
        if (c.pred == null) return C("NOT (${c.sql})", c.params)
        val t = truth(c)
        return C("TRUE", emptyList(), { r -> !t(r) })
    }

    /** The exact truth of [c] for a row, using a probe column for the SQL part. */
    fun truth(c: C): Pred {
        val pred = c.pred
        if (c.sql == "TRUE") return pred ?: { true }
        val idx = probes.size
        probes.add(SqlFragment(c.sql, c.params))
        return if (pred == null) { r -> r.probes[idx] } else { r -> r.probes[idx] && pred(r) }
    }

    // ---- dialect helpers ----

    fun ts(ms: Long): C = if (pg) C("to_timestamp(CAST(? AS double precision) / 1000.0)", listOf(ms)) else C("?", listOf(ms))

    fun fold(col: String): String = if (pg) "translate($col, $UPPER, $LOWER)" else "lower($col)"

    fun tsCmp(col: String, op: String, ms: Long): C {
        val t = ts(ms)
        return C("$col $op ${t.sql}", t.params)
    }

    /** Whole-value ASCII-case-insensitive pattern match on [col] (a non-null text expression). */
    fun likeWhole(col: String, p: SearchPattern): C =
        C("${fold(col)} LIKE ? ESCAPE '\\'", listOf(likePattern(p.segments, contains = false, fold = true)))

    fun table(name: String) = "$tp$name"

    // ---- terms ----

    fun term(t: SearchTerm, ctx: SearchContext): C = when (t) {
        is SearchTerm.Text -> text(t.pattern)
        is SearchTerm.Deck -> deck(t.pattern, ctx)
        is SearchTerm.Tag -> tag(t.pattern, ctx)
        is SearchTerm.Is -> isTerm(t.value, ctx)
        is SearchTerm.Kind -> C("c.kind = ?", listOf(t.kind))
        is SearchTerm.Type -> C("n.type = ?", listOf(t.baseType.wire))
        is SearchTerm.NoteType -> {
            val w = likeWhole("nt.name", t.pattern)
            C("EXISTS (SELECT 1 FROM ${table("note_type")} nt WHERE nt.id = n.note_type_id AND ${w.sql})", w.params)
        }
        is SearchTerm.Preset -> {
            val w = likeWhole("p.name", t.pattern)
            C("EXISTS (SELECT 1 FROM ${table("fsrs_params")} p WHERE p.id = c.preset_id AND ${w.sql})", w.params)
        }
        is SearchTerm.Kanji -> kanji(t.literal)
        is SearchTerm.Entry -> C("(n.entry_seq IS NOT NULL AND n.entry_seq = ?)", listOf(t.entrySeq))
        is SearchTerm.Status -> C(
            "(n.entry_seq IS NOT NULL AND coalesce((SELECT ws.status FROM ${table("word_status")} ws WHERE ws.entry_seq = n.entry_seq), 'new') = ?)",
            listOf(t.status.wire),
        )
        is SearchTerm.Flag -> C("c.flags = ?", listOf(t.flag.toLong()))
        is SearchTerm.Rated -> rated(t.days, t.rating, ctx)
        is SearchTerm.Added -> {
            val from = ctx.dayStart(-(t.days - 1))
            tsCmp("c.created_at", ">=", from)
        }
        is SearchTerm.NoteId -> C(
            "(n.id = ${uuidParam()} OR EXISTS (SELECT 1 FROM ${table("note")} l WHERE l.id = ${uuidParam()} AND l.merged_into_id = n.id))",
            listOf(t.id, t.id),
        )
        is SearchTerm.CardId -> C(
            "(c.id = ${uuidParam()} OR EXISTS (SELECT 1 FROM ${table("card")} l WHERE l.id = ${uuidParam()} AND l.merged_into_id = c.id))",
            listOf(t.id, t.id),
        )
        is SearchTerm.Field -> field(t.key, t.pattern)
        is SearchTerm.Compare -> compare(t.property, t.op, t.value, ctx)
    }

    fun text(p: SearchPattern): C {
        if (pg) {
            val ilike = likePattern(p.segments, contains = true, fold = false)
            val exact = likePattern(p.segments, contains = true, fold = true)
            return C(
                "(n.search_text ILIKE ? ESCAPE '\\' AND ${fold("n.search_text")} LIKE ? ESCAPE '\\')",
                listOf(ilike, exact),
            )
        }
        val chunks = safeChunks(p.segments, splitOnSpace = true)
        val sql = chunks.joinToString(" AND ") { "(lower(n.fields) LIKE ? ESCAPE '\\' OR lower(n.mnemonic) LIKE ? ESCAPE '\\' OR lower(n.source_sentence) LIKE ? ESCAPE '\\')" }
            .ifEmpty { "TRUE" }
        val params = chunks.flatMap { c -> val l = likePattern(listOf(c), contains = true, fold = true); listOf(l, l, l) }
        return C(if (chunks.size > 1) "($sql)" else sql, params) { r -> matchesContains(p, r.searchText) }
    }

    fun tag(p: SearchPattern, ctx: SearchContext): C {
        val real: C = if (pg) {
            val w = likeWhole("t.v", p)
            C("EXISTS (SELECT 1 FROM unnest(n.tags) AS t(v) WHERE ${w.sql})", w.params)
        } else {
            val chunks = safeChunks(p.segments, splitOnSpace = false)
            val sql = chunks.joinToString(" AND ") { "lower(n.tags) LIKE ? ESCAPE '\\'" }.ifEmpty { "TRUE" }
            C(if (chunks.size > 1) "($sql)" else sql, chunks.map { likePattern(listOf(it), contains = true, fold = true) }) { r ->
                r.tags.any { matchesWhole(p.segments, it) }
            }
        }
        // virtual tag `leech`
        return if (matchesWhole(p.segments, "leech")) or(listOf(real, C("c.lapses >= ?", listOf(ctx.leechThreshold.toLong())))) else real
    }

    fun field(key: String, p: SearchPattern): C {
        if (pg) {
            val w = likeWhole("coalesce(n.fields ->> ?, '')", p)
            return C(w.sql, listOf<Any>(key) + w.params)
        }
        val chunks = safeChunks(p.segments, splitOnSpace = false)
        val sql = chunks.joinToString(" AND ") { "lower(n.fields) LIKE ? ESCAPE '\\'" }.ifEmpty { "TRUE" }
        return C(if (chunks.size > 1) "($sql)" else sql, chunks.map { likePattern(listOf(it), contains = true, fold = true) }) { r ->
            matchesWhole(p.segments, r.fields[key] ?: "")
        }
    }

    fun kanji(k: String): C {
        val literal = C("(n.kanji_literal IS NOT NULL AND n.kanji_literal = ?)", listOf(k))
        if (pg) {
            return C(
                "((n.kanji_literal IS NOT NULL AND n.kanji_literal = ?) OR (n.type = 'vocab' AND strpos(coalesce(n.fields ->> 'expression', ''), ?) > 0))",
                listOf(k, k),
            )
        }
        val vocab = and(listOf(C("n.type = 'vocab'"), C("instr(n.fields, ?) > 0", listOf(k)) { r -> (r.fields["expression"] ?: "").contains(k) }))
        return or(listOf(literal, vocab))
    }

    fun isTerm(v: IsValue, ctx: SearchContext): C = when (v) {
        IsValue.NEW, IsValue.LEARNING, IsValue.RELEARNING, IsValue.REVIEW -> C("c.state = ?", listOf(v.wire))
        IsValue.DUE -> {
            val buried = tsCmp("c.buried_until", ">", ctx.nowMs)
            val due = tsCmp("c.due_at", "<", ctx.studyDay.endMs)
            C(
                "(${boolFalse("c.suspended")} AND NOT (c.buried_until IS NOT NULL AND ${buried.sql}) AND c.state <> 'new' AND c.due_at IS NOT NULL AND ${due.sql})",
                buried.params + due.params,
            )
        }
        IsValue.SUSPENDED -> C(boolTrue("c.suspended"))
        IsValue.BURIED -> {
            val buried = tsCmp("c.buried_until", ">", ctx.nowMs)
            C("(c.buried_until IS NOT NULL AND ${buried.sql})", buried.params)
        }
        IsValue.LEECH -> C("c.lapses >= ?", listOf(ctx.leechThreshold.toLong()))
        IsValue.HOT -> hot(ctx.hotNoteIds)
        IsValue.FLAGGED -> C("c.flags <> 0")
    }

    /** A bound uuid: Postgres needs the cast (JDBC binds text), SQLite stores uuids as text. */
    fun uuidParam() = if (pg) "CAST(? AS uuid)" else "?"

    fun boolTrue(col: String) = if (pg) col else "$col = 1"

    fun boolFalse(col: String) = if (pg) "NOT $col" else "$col = 0"

    fun hot(ids: Set<Uuid>): C {
        if (!pg) return C("TRUE", emptyList()) { r -> r.noteId in ids }
        if (ids.isEmpty()) return FALSE_C
        val sorted = ids.sorted()
        return C(sorted.joinToString(",", "n.id IN (", ")") { uuidParam() }, sorted)
    }

    fun deck(p: SearchPattern, ctx: SearchContext): C {
        val w = likeWhole("d.name", p)
        val member = "(di.entry_seq = n.entry_seq OR di.note_id = n.id OR " +
            "di.note_id IN (SELECT l.id FROM ${table("note")} l WHERE l.merged_into_id = n.id))"
        val regular = C(
            "EXISTS (SELECT 1 FROM ${table("deck")} d JOIN ${table("deck_item")} di ON di.deck_id = d.id " +
                "WHERE d.deleted_at IS NULL AND d.type <> 'smart' AND di.deleted_at IS NULL AND ${w.sql} AND $member)",
            w.params,
        )
        val smart = ctx.smartDecks.filter { matchesWhole(p.segments, it.name) }.map { s ->
            node(s.query, ctx.copy(smartDecks = ctx.smartDecks.filter { it !== s }))
        }
        return or(listOf(regular) + smart)
    }

    fun rated(days: Int, rating: Int?, ctx: SearchContext): C {
        val from = tsCmp("rl.reviewed_at", ">=", ctx.dayStart(-(days - 1)))
        val ratingSql = if (rating != null) " AND rl.rating = ?" else ""
        val params = from.params + (if (rating != null) listOf<Any>(rating.toLong()) else emptyList())
        return C(
            "EXISTS (SELECT 1 FROM ${table("review_log")} rl WHERE " +
                "(rl.card_id = c.id OR rl.card_id IN (SELECT l.id FROM ${table("card")} l WHERE l.merged_into_id = c.id)) " +
                "AND rl.undone_at IS NULL AND rl.review_type IN ('scheduled', 'learning') AND ${from.sql}$ratingSql)",
            params,
        )
    }

    fun compare(prop: SearchProperty, op: CompareOp, v: Double, ctx: SearchContext): C {
        if (prop == SearchProperty.DUE) return dueCompare(op, v, ctx)
        val col = when (prop) {
            SearchProperty.LAPSES -> "c.lapses"
            SearchProperty.REPS -> "c.reps"
            SearchProperty.INTERVAL -> "c.interval_days"
            SearchProperty.STABILITY -> "c.stability"
            SearchProperty.DIFFICULTY -> "c.difficulty"
            SearchProperty.DUE -> error("handled above")
        }
        return C("($col IS NOT NULL AND $col ${sqlOp(op)} ?)", listOf(v))
    }

    fun sqlOp(op: CompareOp): String = if (op == CompareOp.NE) "<>" else op.wire

    /**
     * `due<op>v`: the study-day index of `due_at` is `floor((due_at - start) / 1 day)`. The comparison is
     * rewritten as a range test on `due_at` itself, against the caller-supplied day boundaries
     * ([SearchContext.dayStart]), so 23/25 h DST days are exact.
     */
    fun dueCompare(op: CompareOp, v: Double, ctx: SearchContext): C {
        fun at(k: Double): Long = ctx.dayStart(k.coerceIn(-100_000.0, 100_000.0).toInt())
        fun cmp(o: String, k: Double): C = tsCmp("c.due_at", o, at(k))
        val notNull = "c.due_at IS NOT NULL"
        fun wrap(vararg cs: C): C {
            val a = and(cs.toList())
            return C("($notNull AND ${a.sql})", a.params)
        }
        val isInt = v == floor(v)
        return when (op) {
            CompareOp.LT -> wrap(cmp("<", ceil(v)))
            CompareOp.LE -> wrap(cmp("<", floor(v) + 1))
            CompareOp.GT -> wrap(cmp(">=", floor(v) + 1))
            CompareOp.GE -> wrap(cmp(">=", ceil(v)))
            CompareOp.EQ -> if (isInt) wrap(cmp(">=", v), cmp("<", v + 1)) else FALSE_C
            CompareOp.NE -> if (isInt) wrap(or(listOf(cmp("<", v), cmp(">=", v + 1)))) else C(notNull)
        }
    }
}
