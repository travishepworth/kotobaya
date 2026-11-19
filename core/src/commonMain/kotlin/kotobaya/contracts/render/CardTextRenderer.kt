package kotobaya.contracts.render

import kotobaya.contracts.cards.NoteTypeInfo

/**
 * Everything needed for the plain-text render of one card (ARCH-CARD-TEXT). The same data as
 * [RenderInput] minus what only matters for HTML (side, options, mnemonic): both sides are produced at once.
 */
public data class TextRenderInput(
    /** The note type. */
    public val noteType: NoteTypeInfo,
    /** `card.kind`. */
    public val kind: String,
    /** `note.fields` (key → HTML fragment); missing key = `""`. */
    public val fields: Map<String, String>,
    /** Dictionary data, or null (no entry, or not available on this replica). Fills empty fields exactly as for [CardRenderer]. */
    public val dictionary: DictionaryData?,
    /** The cloze sentence for grammar `cloze` / `spot` cards (`QueueItem.grammar_sentence`), else null. */
    public val cloze: ClozeSentence?,
    /** Kanji data for a kanji note, else null; fills empty fields exactly as for [CardRenderer]. Ignored for other base types. */
    public val kanji: KanjiData? = null,
    /**
     * ADR-042 (additive): furigana front, as `RenderInput.frontFurigana`. When true, for the rows
     * vocab / meaning and vocab / pitch of the table (built-in templates only), the front line
     * `expression[kanji]` becomes `expression[kanji]（reading[kana]）` (fullwidth parentheses U+FF08,
     * U+FF09, no space), unless the reading text is empty or equals the expression text. Pitch: the
     * separate `reading[kana]` front line stays. Nothing else changes. Default false = the text
     * before ADR-042. Example: 寿司 → front `"寿司（すし）"`.
     */
    public val frontFurigana: Boolean = false,
)

/**
 * The plain-text render of one card (API `CardText`, `QueueItem.text`). No HTML, no entities, no
 * markup of any kind: what a desktop widget or terminal prints. Example (built-in vocab `meaning`, note
 * `fields = {}` with dictionary data for 食べ物):
 * `CardText(front = "食べ物", back = "たべもの\nfood; provisions", reading = "たべもの", sentence = null, warnings = [])`.
 */
public data class CardText(
    /** The question side. `""` iff the question has no text (audio-only `listening` fronts, or an empty note). */
    public val front: String,
    /**
     * The answer: what the back adds to the front, per the table of [CardTextRenderer]. May be `""`. It includes the
     * reading where the kind reveals it, so a layout that prints [reading] separately should not also print all of it.
     */
    public val back: String,
    /** The kana reading (vocab, phrase, sentence) or the readings (kanji), or null when empty / not applicable. Answer-side information. */
    public val reading: String?,
    /** The context sentence the built-in front shows (the note's `sentence` field), or null. Never set for sentence notes; not the grammar cloze sentence. */
    public val sentence: String?,
    /** Non-fatal problems as i18n keys `render.warning.<code>` (same codes as [RenderedCard.warnings]); empty when clean. */
    public val warnings: List<String>,
)

/**
 * The plain-text card renderer (ARCH-CARD-TEXT; CLI-DAEMON "Card content"). The server runs it for every
 * `QueueItem` so text-only clients (the CLI daemon, desktop widgets) never re-implement rendering. Pure:
 * same input, same output, on every platform.
 *
 * **Text of a field** under a mode (`html`, `kanji`, `kana`): the value resolved exactly as
 * [CardRenderer] does (a non-blank `note.fields[key]` wins, else the [DictionaryData] / [KanjiData]
 * fill), sanitised with furigana off (bracket furigana `漢字[かんじ]`: `kanji` mode keeps the base,
 * `kana` mode the reading, `html` mode keeps the brackets as typed, exactly like the HTML render's
 * `{{key}}`; `<ruby>` keeps its base text,
 * `rt`/`rp` are dropped), then reduced to text: tags removed, entities decoded, `[sound:…]`, images and
 * audio buttons dropped; `<br>`, `<hr>` and the start and end of `div p ul ol li` are line breaks.
 *
 * **Lines.** Every text is split into lines; in each line runs of `U+0020 U+0009 U+000D U+000C U+00A0`
 * become one space and the line is trimmed of those characters (U+3000 ideographic space is kept);
 * empty lines are dropped. Fields and outputs are joined with `"\n"`.
 *
 * **Per-kind rules** (built-in (base type, kind) pairs whose [kotobaya.contracts.cards.TemplateInfo] has
 * `front` and `back` both null). Field names in brackets give the mode; `pitch` is the pitch line below.
 *
 * | base / kind | front lines | back lines |
 * |---|---|---|
 * | vocab, phrase / meaning | expression[kanji] | reading[kana], meaning |
 * | vocab / reading | expression[kanji] | reading[kana], meaning |
 * | vocab, phrase / listening | — | expression[kanji], reading[kana], meaning |
 * | vocab, phrase / production | meaning | expression[kanji], reading[kana] |
 * | vocab / pitch | expression[kanji], reading[kana] | pitch (empty when there is no pitch line) |
 * | kanji / keyword | literal | keyword, readings |
 * | kanji / writing | keyword | literal, readings |
 * | kanji / components | literal | notes |
 * | grammar / cloze | cloze sentence with the span replaced by `＿＿＿`, cloze translation | marked sentence, pattern, meaning |
 * | grammar / spot | cloze sentence | marked sentence, pattern, meaning, cloze translation |
 * | grammar / recognition | pattern | meaning |
 * | grammar / production | meaning | pattern |
 * | sentence / comprehension | sentence[kanji] | reading[kana], translation |
 * | sentence / listening | — | sentence[kanji], reading[kana], translation |
 * | custom / basic | front | back |
 *
 * Unmarked fields are `html` mode. The *marked sentence* is the cloze text with the span wrapped in
 * `【` `】` (U+3010, U+3011). Grammar `cloze`/`spot` with a null cloze: front = pattern, back = meaning,
 * warning `missing_cloze`. With an invalid span (the [CardRenderer] rule): the sentence is shown
 * unmarked (front and back) and warning `invalid_cloze`. The cloze translation counts only if non-blank.
 *
 * **Pitch line**: from [DictionaryData] only (`reading`, `pitchDrops`); absent when there is no dictionary
 * data or no drops in `0..mora count`. One pattern per drop in that range, in the given order, written
 * as the reading's morae (small kana join the preceding mora, as in the HTML pitch) with `ꜜ` (U+A71C)
 * inserted after mora `drop` (heiban, 0, has no mark); drops outside the range are skipped; identical
 * pattern strings are kept once; patterns are joined with `" / "`.
 * Example: `たべもの`, drops `[3, 2]` → `たべもꜜの / たべꜜもの`.
 *
 * The table's lines are output as listed, with no de-duplication (a meaning equal to the expression
 * is kept).
 *
 * **Fallback** (a custom template on either side, or a (base type, kind) not in the table, e.g. a custom
 * kind): front = the lines of [CardRenderer] FRONT output; back = the lines of its BACK output minus
 * every line equal to a front line or to an earlier back line (this removes what `{{FrontSide}}`
 * repeated; only the fallback de-duplicates). Rendered with furigana and pitch off,
 * no mnemonic. An unknown kind with no template gives `""`/`""` and warning `unknown_kind`.
 *
 * **reading**: vocab, phrase, sentence: reading[kana]; kanji: readings; grammar, custom: null.
 * **sentence**: every base type except sentence: sentence[kanji] of the note; sentence: null. Both are
 * null when their text is empty and are independent of the kind and of the fallback.
 *
 * **Warnings**: the codes [CardRenderer] would emit for the same content (`dropped_tag`,
 * `dropped_attribute`, `missing_cloze`, `invalid_cloze`, `unknown_kind`, `unknown_placeholder` in the
 * fallback), each once, as `render.warning.<code>`.
 */
public interface CardTextRenderer {
    /** Renders both sides as text. Never throws for any note content. */
    public fun renderText(input: TextRenderInput): CardText
}
