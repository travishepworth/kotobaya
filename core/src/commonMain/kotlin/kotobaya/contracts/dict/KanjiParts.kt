package kotobaya.contracts.dict

/**
 * The reading of a kanji that a word uses (API `KanjiPart.used_reading`).
 *
 * Example (学 in 学校 がっこう; on ガク): `UsedReading(type = ON, reading = "ガク", kana = "がっ")`.
 * Example (食 in 食べる; kun た.べる): `UsedReading(KUN, "た.べる", "た")`.
 */
public data class UsedReading(
    /** [KanjiReadingType.ON] or [KanjiReadingType.KUN]; never OTHER (then there is no [UsedReading]). */
    public val type: KanjiReadingType,
    /**
     * The stored reading it comes from, **exactly** as in [KanjiReadings.onReadings] /
     * [KanjiReadings.kunReadings] (katakana for on, dots and `-` kept), so a UI highlights the
     * list element equal to it. Example `"ガク"`, `"た.べる"`, `"-び"`.
     */
    public val reading: String,
    /** The part of the word's reading this kanji takes, in hiragana (the fitting form of `classify`). Example `"がっ"`, `"び"`. */
    public val kana: String,
)

/** What the host knows about one kanji of the word, for [KanjiParts.build]. */
public data class KanjiPartSource(
    /** Readings of the kanji (`ref.kanji`); a kanji without a row has empty lists. */
    public val readings: KanjiReadings,
    /** `ref.kanji.meanings` (English, KANJIDIC2 order); empty without a row. */
    public val meanings: List<String>,
    /**
     * The plain text of the `keyword` field of the user's live kanji note for this literal (the
     * earliest by `(created_at, id)` when several), or null when there is no such note or the
     * field is blank after trimming.
     */
    public val noteKeyword: String?,
)

/**
 * One tile of the kanji breakdown on the back of a vocab card (API `KanjiPart`).
 *
 * Example (校 in 学校): `KanjiPart("校", "exam", ["exam", "school", "printing"], ["コウ", "キョウ"], [], UsedReading(ON, "コウ", "こう"))`.
 */
public data class KanjiPart(
    /** The kanji, one code point. */
    public val literal: String,
    /** The user's keyword if they have one, else the first meaning, else null. */
    public val keyword: String?,
    /** `ref.kanji.meanings`; may be empty. */
    public val meanings: List<String>,
    /** `ref.kanji.on_readings` as stored; may be empty. */
    public val on: List<String>,
    /** `ref.kanji.kun_readings` as stored; may be empty. */
    public val kun: List<String>,
    /** The reading this word uses, or null when it cannot be aligned. */
    public val usedReading: UsedReading?,
)

/**
 * ADR-041: the kanji breakdown of a vocab card's back, one tile per kanji of the word, with the
 * reading the word uses. Pure and deterministic: the server fills `QueueItem.kanji_parts` with it
 * and the phone's local API runs it over the local dictionary, with identical results for
 * identical data. Implementation `kotobaya.dict.DefaultKanjiParts` (ticket CORE-28). A display
 * aid only: no op, nothing stored.
 *
 * ## usedReading (normative)
 *
 * Runs `KanjiExamplePicker.classify` steps 1–3 for (`kanji`, `headword`, `reading`), remembering
 * for every form the stored reading it was derived from. Among the forms that **fit**:
 * 1. the longest (UTF-16 length of the form);
 * 2. then ON before KUN;
 * 3. then a **plain** form (the reading itself, or a kun stem) before a derived one (geminated,
 *    absorbed okurigana, rendaku), derived forms in the order `classify` step 2 lists them;
 * 4. then the stored reading that comes first in its list (`onReadings` / `kunReadings` order).
 * Result: `UsedReading(type, reading = that stored reading unchanged, kana = the form)`; null
 * when no form fits. So `usedReading(...) == null` iff `classify(...) == OTHER`, and otherwise
 * `usedReading(...).type == classify(...)`. As in `classify`, only the **first** occurrence of the
 * kanji in the headword is aligned.
 *
 * Examples: 学 (on ガク; kun まな.ぶ) in 学校 がっこう → `(ON, "ガク", "がっ")`; 校 (on コウ キョウ) in 学校 →
 * `(ON, "コウ", "こう")`; 大 (on ダイ タイ) in 大学 だいがく → `(ON, "ダイ", "だい")`; 食 (kun た.べる) in 食べる →
 * `(KUN, "た.べる", "た")`; 日 (kun ひ -び -か) in 誕生日 たんじょうび → `(KUN, "-び", "び")` (the plain `-び` beats the rendaku of `ひ`); 今 and 日 in 今日
 * きょう → null (jukujikun); 日 in 日本 にほん → null (irregular); a kanji with no readings → null.
 *
 * ## build (normative)
 *
 * `literals` = `CardGenerator.kanjiOf(expression)` (distinct, first appearance), cut to the first
 * [MAX_PARTS]. For each literal, with `s` = the element of [KanjiPartSource] list whose
 * `readings.literal` equals it (none: empty readings, no meanings, no keyword):
 * `KanjiPart(literal, keyword = s.noteKeyword ?: s.meanings.firstOrNull(), meanings = s.meanings,
 * on = s.readings.onReadings, kun = s.readings.kunReadings, usedReading = usedReading(s.readings,
 * expression, reading))`.
 *
 * `expression` and `reading` are the plain texts of the note's `expression` field (`kanji` mode)
 * and `reading` field (`kana` mode) exactly as `CardTextRenderer` resolves them (note field, else
 * dictionary fill). Edge cases: a kana-only or empty expression → `[]`; an empty reading → every
 * `usedReading` null; a kanji not in the dictionary → a tile with the literal alone; the same
 * kanji twice (日曜日) → one tile, aligned on its first occurrence; every kanji gets a tile whether
 * or not it is learnable (ADR-042 does not filter the breakdown).
 *
 * Example (学校 がっこう, no kanji notes): two parts, 学 `(keyword "study", used (ON, "ガク", "がっ"))` and
 * 校 `(keyword "exam", used (ON, "コウ", "こう"))`.
 */
public interface KanjiParts {
    /** The reading of `kanji.literal` that [headword] (read [reading]) uses; see "usedReading". Never throws. */
    public fun usedReading(kanji: KanjiReadings, headword: String, reading: String): UsedReading?

    /** The tiles for a word; see "build". [sources] in any order; a literal without a source gets an empty tile. */
    public fun build(expression: String, reading: String, sources: List<KanjiPartSource>): List<KanjiPart>

    /** Constants. */
    public companion object {
        /** A vocab card shows at most this many tiles (`QueueItem.kanji_parts` has at most this many). */
        public const val MAX_PARTS: Int = 8
    }
}
