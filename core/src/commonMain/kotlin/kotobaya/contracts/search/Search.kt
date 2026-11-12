package kotobaya.contracts.search

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.Uuid
import kotobaya.contracts.WordStatus

/**
 * The card search language (SPEC "Public API › Card search language"), shared by the API
 * (`GET /cards?query=`, smart decks' `deck.query`), the web app and the phone. Grammar:
 * `contracts/core/search.ebnf` (normative for syntax); this file is normative for meaning.
 *
 * Example: `deck:"Frieren" is:due tag:leech lapses>3 kanji:績 rated:7:1 kind:reading`.
 *
 * **What matches.** A query selects **cards**. Only live cards are ever candidates: card and note
 * not tombstoned, card not a merge loser. The empty query (only whitespace) matches every live card.
 * Juxtaposition and `and`/`AND` mean AND; `or`/`OR` means OR and binds weaker; `-` negates the
 * following term or group; parentheses group. Example: `a b or c` = `(a AND b) OR c`.
 *
 * **Time.** Day-based terms use the current [kotobaya.contracts.queue.StudyDay] `[start, end)`:
 * "within the last N days" means `>= start − (N − 1) days` (so `rated:1` = since the start of today).
 *
 * **Text matching.** All string comparisons are case-insensitive for ASCII letters only (`A`–`Z`
 * ≡ `a`–`z`); no other folding (kana/width/NFKC are distinct). A [SearchPattern] is a sequence of
 * literal [SearchPattern.segments] separated by wildcards (`*` = any run of characters, possibly
 * empty).
 *
 * **Evaluation (C-2).** This file defines *which* cards match, not how a host finds them. Whatever
 * compiles a tree into a query (server: Postgres SQL; device: SQLite; T108/T2xx own the compilers)
 * takes exactly these inputs besides the tree, and its result MUST equal the meaning given here:
 * - the current [kotobaya.contracts.queue.StudyDay] (`start`, `end`, zone) for day-based terms;
 * - `now`, the evaluation instant (UTC ms), for `is:buried` / `is:due` and other instant checks;
 * - `scheduler.leech_threshold` (settings) for `is:leech` and the virtual tag `leech`;
 * - the set of hot note ids ([kotobaya.contracts.hot.HotScorer], computed by the caller at `now`)
 *   for `is:hot`;
 * - merge resolution (`merged_into_id` chains, followed to the survivor) for `nid:`, `cid:`,
 *   `entry:`, `rated:` and "only live cards".
 *
 * A compiler MAY split a tree into a **SQL prefilter plus a residual Kotlin predicate**, evaluated
 * on the rows the prefilter returns, provided the prefilter never excludes a matching card (it may
 * over-select) and the residual is applied to every returned row. This is the expected shape on
 * the device's SQLite, which has no JSON functions guaranteed (SQLite 3.18, SCHEMA.md 5) and no
 * `note.search_text` (SCHEMA.md 5.2): `tag:` (whole-tag match on the JSON `tags` array), `field:`
 * (one key of the JSON `fields` object), text terms (over field values, mnemonic and source
 * sentence), `kanji:` and `is:hot` are then residual (a `LIKE` on the JSON text MAY prefilter
 * them, never decide them). The server can decide every term in SQL (`search_text`, `jsonb`).
 * A `not` over a residual term makes its whole subtree residual (the prefilter for it is `TRUE`).
 */
public sealed interface SearchNode {
    /** UTF-16 offsets `[start, end)` of this node in the query string. */
    public val span: SourceSpan

    /** Matches every live card. Only produced for an empty query. */
    public data class All(
        override val span: SourceSpan,
    ) : SearchNode

    /** Every child matches. At least two children; never nested directly in another [And]. */
    public data class And(
        /** Children in source order. */
        public val children: List<SearchNode>,
        override val span: SourceSpan,
    ) : SearchNode

    /** At least one child matches. At least two children; never nested directly in another [Or]. */
    public data class Or(
        /** Children in source order. */
        public val children: List<SearchNode>,
        override val span: SourceSpan,
    ) : SearchNode

    /** The child does not match. `--x` is `Not(Not(x))` (kept; not simplified). */
    public data class Not(
        /** The negated node. */
        public val child: SearchNode,
        override val span: SourceSpan,
    ) : SearchNode

    /** A leaf. Parenthesised groups do not appear in the tree; a group's node is its content. */
    public data class Term(
        /** The term. */
        public val term: SearchTerm,
        override val span: SourceSpan,
    ) : SearchNode
}

/** UTF-16 offsets `[start, end)` into the query string. */
public data class SourceSpan(
    /** Start offset, inclusive. */
    public val start: Int,
    /** End offset, exclusive; `>= start`. */
    public val end: Int,
)

/**
 * A text pattern from a bare value or a quoted string, after unescaping (`\"`, `\\`, `\*`, and in
 * bare values also `\(`, `\)`). An unescaped `*` splits segments.
 *
 * Examples: `Frieren` → `["Frieren"]`; `jlpt*` → `["jlpt", ""]`; `"a\*b"` → `["a*b"]`;
 * `*eat*` → `["", "eat", ""]`.
 */
public data class SearchPattern(
    /** Literal segments; wildcard between consecutive segments. Never empty; a segment may be `""`. */
    public val segments: List<String>,
) {
    /** True iff the pattern contains a wildcard. */
    public val hasWildcard: Boolean get() = segments.size > 1
}

/** Value of `is:`. */
public enum class IsValue(
    /** Keyword after `is:`. */
    public val wire: String,
) {
    /** `card.state = new`. */
    NEW("new"),

    /** `card.state = learning`. */
    LEARNING("learning"),

    /** `card.state = relearning`. */
    RELEARNING("relearning"),

    /** `card.state = review`. */
    REVIEW("review"),

    /** Not suspended, not manually buried at the evaluation instant, state ≠ new, `due_at < day.end`. (Sibling burying is ignored.) */
    DUE("due"),

    /** `card.suspended`. */
    SUSPENDED("suspended"),

    /** Manually buried: `buried_until > now`. */
    BURIED("buried"),

    /** `card.lapses >= scheduler.leech_threshold` (the virtual leech status). */
    LEECH("leech"),

    /** The card's note is hot ([kotobaya.contracts.hot.HotScorer]). */
    HOT("hot"),

    /** `card.flags != 0`. */
    FLAGGED("flagged"),
}

/** A numeric card property usable with a comparison operator. A card whose value is null never matches (also not with `!=`). */
public enum class SearchProperty(
    /** Keyword. */
    public val wire: String,
) {
    /** `card.lapses`. */
    LAPSES("lapses"),

    /** `card.reps`. */
    REPS("reps"),

    /** `card.interval_days` (null for new cards). */
    INTERVAL("interval"),

    /** Study days from today to the day containing `due_at`: 0 = due today, negative = overdue; null for new cards. */
    DUE("due"),

    /** `card.stability` in days (null for new cards). */
    STABILITY("stability"),

    /** `card.difficulty` 1–10 (null for new cards). */
    DIFFICULTY("difficulty"),
}

/** Comparison operator. */
public enum class CompareOp(
    /** Source text. */
    public val wire: String,
) {
    /** `<`. */
    LT("<"),

    /** `<=`. */
    LE("<="),

    /** `>`. */
    GT(">"),

    /** `>=`. */
    GE(">="),

    /** `=`. */
    EQ("="),

    /** `!=`. */
    NE("!="),
}

/** A leaf of the search tree. Each subtype documents its syntax and exact meaning. */
public sealed interface SearchTerm {
    /** Bare word or quoted string: the note's searchable text (`note.search_text`: all field values, mnemonic, source sentence) contains the pattern (implicit wildcards at both ends). */
    public data class Text(
        /** The pattern. */
        public val pattern: SearchPattern,
    ) : SearchTerm

    /** `deck:<pattern>`: the card is a member of a live deck whose whole `name` matches (membership as in [kotobaya.contracts.queue.DeckMembership]). */
    public data class Deck(
        /** The pattern. */
        public val pattern: SearchPattern,
    ) : SearchTerm

    /** `tag:<pattern>`: one of the note's tags matches as a whole; `tag:leech` also matches leeches (virtual tag). */
    public data class Tag(
        /** The pattern. */
        public val pattern: SearchPattern,
    ) : SearchTerm

    /** `is:<value>`. */
    public data class Is(
        /** The value. */
        public val value: IsValue,
    ) : SearchTerm

    /** `kind:<key>`: `card.kind` equals the key. */
    public data class Kind(
        /** The card kind. */
        public val kind: String,
    ) : SearchTerm

    /** `type:<base type>`: the note's base type. */
    public data class Type(
        /** The base type. */
        public val baseType: NoteBaseType,
    ) : SearchTerm

    /** `notetype:<pattern>`: the note type's `name` matches as a whole. */
    public data class NoteType(
        /** The pattern. */
        public val pattern: SearchPattern,
    ) : SearchTerm

    /** `preset:<pattern>`: the card's effective preset's `name` matches as a whole. */
    public data class Preset(
        /** The pattern. */
        public val pattern: SearchPattern,
    ) : SearchTerm

    /** `kanji:<one kanji>`: a kanji note with that `kanji_literal`, or a vocab note whose `expression` field contains it. */
    public data class Kanji(
        /** The kanji (one code point, possibly a surrogate pair). */
        public val literal: String,
    ) : SearchTerm

    /** `entry:<digits>`: `note.entry_seq` equals it. */
    public data class Entry(
        /** JMdict sequence number, `>= 1`. */
        public val entrySeq: Long,
    ) : SearchTerm

    /** `status:<word status>`: the effective `word_status.status` of the note's entry (no row = new). Notes without an entry never match. */
    public data class Status(
        /** The status. */
        public val status: WordStatus,
    ) : SearchTerm

    /** `flag:<0-7>`: `card.flags` equals it. */
    public data class Flag(
        /** 0–7. */
        public val flag: Int,
    ) : SearchTerm

    /** `rated:<days>[:<rating>]`: a non-undone scheduled/learning review of the card (or of a card merged into it) within the last [days] days, with that rating if given. */
    public data class Rated(
        /** 1–36500. */
        public val days: Int,
        /** 1–4, or null for any rating. */
        public val rating: Int?,
    ) : SearchTerm

    /** `added:<days>`: `card.created_at` within the last [days] days. */
    public data class Added(
        /** 1–36500. */
        public val days: Int,
    ) : SearchTerm

    /** `nid:<uuid>`: the note id (after merge resolution: a loser's id finds the survivor's cards). */
    public data class NoteId(
        /** Canonical lowercase UUID. */
        public val id: Uuid,
    ) : SearchTerm

    /** `cid:<uuid>`: the card id (a loser's id finds its survivor). */
    public data class CardId(
        /** Canonical lowercase UUID. */
        public val id: Uuid,
    ) : SearchTerm

    /** `field:<key>:<pattern>`: the note's field [key] (missing = `""`) matches as a whole. */
    public data class Field(
        /** Field key. */
        public val key: String,
        /** The pattern. */
        public val pattern: SearchPattern,
    ) : SearchTerm

    /** `<property><op><number>`, e.g. `lapses>3`, `due<=0`, `stability>10.5`. */
    public data class Compare(
        /** Property. */
        public val property: SearchProperty,
        /** Operator. */
        public val op: CompareOp,
        /** Right-hand side. */
        public val value: Double,
    ) : SearchTerm
}

/** A syntax or value error. */
public data class SearchError(
    /** Stable machine code. */
    public val code: SearchErrorCode,
    /** Where; for an unexpected end of input, `[length, length)`. */
    public val span: SourceSpan,
    /** i18n key for the message (`search.error.<code>`); the UI renders it, never the code. */
    public val messageKey: String,
)

/** Error codes of [SearchError]. */
public enum class SearchErrorCode {
    /** `xyz:` with a key that is not a search key (e.g. `dek:`). */
    UNKNOWN_KEY,

    /** A known key with an invalid value (e.g. `is:newer`, `flag:8`, `rated:0`, `kanji:abc`). */
    BAD_VALUE,

    /** A key without a value (`deck:`), or an operator without a number (`lapses>`). */
    MISSING_VALUE,

    /** `(` without `)`, or `)` without `(`. */
    UNBALANCED_PAREN,

    /** A `"` without its closing quote, or `""`. */
    BAD_QUOTE,

    /** `or` / `and` without an operand on one side, or `-` not directly followed by a term or group. */
    DANGLING_OPERATOR,
}

/** Result of [SearchQueryParser.parse]. Exactly one of [query] (no errors) and a non-empty [errors] holds. */
public data class SearchParseResult(
    /** The tree, or null if there are errors. */
    public val query: SearchNode?,
    /** All errors found (the parser recovers and keeps going), in source order. */
    public val errors: List<SearchError>,
)

/** What kind of thing a completion inserts. */
public enum class CompletionKind {
    /** A search key with its separator, e.g. `deck:` or `lapses>`. */
    KEY,

    /** A value after a key. */
    VALUE,

    /** `or` / `and`. */
    OPERATOR,
}

/** One autocomplete suggestion: replace `[replaceStart, replaceEnd)` with [insert]. */
public data class SearchCompletion(
    /** Text to insert (quoted and escaped if needed, e.g. `"Frieren: Beyond"`). */
    public val insert: String,
    /** Text to show in the list (unquoted). */
    public val label: String,
    /** Kind. */
    public val kind: CompletionKind,
    /** UTF-16 start offset of the text to replace. */
    public val replaceStart: Int,
    /** UTF-16 end offset of the text to replace. */
    public val replaceEnd: Int,
)

/** Values the user has, for value completion (the parser itself never needs them). */
public data class SearchVocabulary(
    /** Live deck names. */
    public val deckNames: List<String>,
    /** Tags in use. */
    public val tags: List<String>,
    /** Card kinds in use (built-in ones are always offered too). */
    public val cardKinds: List<String>,
    /** Live note type names. */
    public val noteTypeNames: List<String>,
    /** Live preset names. */
    public val presetNames: List<String>,
    /** Field keys of live note types. */
    public val fieldKeys: List<String>,
) {
    /** Constants. */
    public companion object {
        /** No user values: only keys and fixed values are completed. */
        public val EMPTY: SearchVocabulary = SearchVocabulary(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/** Parser, printer and autocompletion for the search language. Pure. */
public interface SearchQueryParser {
    /**
     * Parses [query]. Whitespace is U+0020, U+0009, U+000A, U+000D and U+3000 (ideographic space).
     * Keys and `is:`/`type:`/`status:` values are lowercase only; `or`/`OR`/`and`/`AND` are the only
     * operator spellings (`Or` is text). A bare word cannot start with `-` and cannot contain
     * whitespace, `"`, `(`, `)`, `:`, `<`, `>`, `=` unescaped (quote it instead).
     * `and`/`or` are flattened (`a b c` is one [SearchNode.And] with three children), and so is a
     * parenthesised group whose node has the same operator as its parent: `(a b) c` = `a b c` =
     * `And(a, b, c)`; `(a or b) or c` = `Or(a, b, c)`. A [SearchNode.Not] is never flattened.
     *
     * **Well-formed trees** are exactly the trees [parse] can return: [SearchNode.All] only as the
     * root; [SearchNode.And]/[SearchNode.Or] with `>= 2` children, never directly inside a node of
     * the same type; patterns with at least one non-empty segment or at least one wildcard; term
     * values within the ranges the grammar and [SearchErrorCode.BAD_VALUE] allow;
     * [SearchTerm.Compare.value] finite.
     */
    public fun parse(query: String): SearchParseResult

    /**
     * Canonical text of a well-formed tree (see [parse]). Used to store smart-deck queries in a
     * stable form. **Round trip:** `parse(format(n)).query == n` ignoring spans, for **every**
     * well-formed `n`; and `format(parse(q).query)` is a fixed point of `format ∘ parse`.
     *
     * Rules:
     * - [SearchNode.All] → `""`.
     * - [SearchNode.And] → children joined by one space; a child that is an [SearchNode.Or] is wrapped
     *   in `(` `)`.
     * - [SearchNode.Or] → children joined by ` or ` (an [SearchNode.And] child needs no parentheses).
     * - [SearchNode.Not] → `-` followed by the child; a child that is an [SearchNode.And] or an
     *   [SearchNode.Or] is wrapped in `(` `)` (`Not(And(a, b))` → `-(a b)`, never `-a b`);
     *   `Not(Not(x))` → `--x`.
     * - Keyed terms → `key:` + value: enum values by their wire text, numbers in decimal without
     *   leading zeros, `rated:<days>[:<rating>]`, `field:<key>:<pattern>`.
     * - [SearchTerm.Compare] → property, operator, number: the shortest decimal digits that read back
     *   as the same double (as ECMAScript `Number::toString` chooses them) written **without
     *   exponent**; integral values without a fraction; a leading `-` iff the sign bit is set
     *   (`-0.0` → `-0`). Examples: `10.5`, `3`, `0.0000001`.
     * - A pattern is written **bare** when possible: segments joined by `*`, each segment with `\`,
     *   `*`, `(`, `)`, `"` escaped by a backslash; bare is possible iff the result is non-empty and
     *   contains no whitespace. A [SearchTerm.Text] bare word must additionally contain none of
     *   `:` `<` `>` `=`, not start with `-`, and not equal `or`/`OR`/`and`/`AND`. Otherwise the
     *   pattern is **quoted**: `"` + segments joined by `*`, each with `\`, `"`, `*` escaped + `"`.
     *   Examples: Text `["or"]` → `"or"`; Text `["a:b"]` → `"a:b"`; Text `["-a"]` → `"-a"`; Deck
     *   `["Frieren: Beyond"]` → `deck:"Frieren: Beyond"`; Deck `["Anime:S1"]` → `deck:Anime:S1`.
     *
     * The reference implementation of these rules is `contracts/core/check_search.py`, which checks
     * the round trip on every `+` line of `search-examples.tsv`.
     *
     * @throws IllegalArgumentException if [node] is not well-formed.
     */
    public fun format(node: SearchNode): String

    /**
     * Suggestions for the token around [cursor] (UTF-16 offset, `0..query.length`), at most [limit]:
     * - in a word without `:` (or at a token start): keys whose name starts with the typed prefix,
     *   then `or`/`and` when an operand precedes;
     * - after `key:` (also inside an open quote): values — `is:`, `type:`, `status:`, `flag:`, `rated:`
     *   fixed values; `deck:`, `tag:`, `kind:` (plus built-in kinds), `notetype:`, `preset:`, `field:`
     *   from [vocabulary]; none for `nid:`, `cid:`, `entry:`, `kanji:`, `added:`.
     * Ranking: case-insensitive prefix matches first, then substring matches; alphabetical within
     * each group; duplicates removed. Never throws; malformed queries still complete.
     */
    public fun complete(query: String, cursor: Int, vocabulary: SearchVocabulary, limit: Int): List<SearchCompletion>
}
