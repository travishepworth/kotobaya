package kotobaya.contracts.dict

import kotobaya.contracts.WordStatus

/** How a word reads one kanji (API `KanjiExample.reading_type`). */
public enum class KanjiReadingType(
    /** Value used on the wire. */
    public val wire: String,
) {
    /** An on'yomi of the kanji (possibly voiced by rendaku or geminated), e.g. 食 in 食事 (しょくじ). */
    ON("on"),

    /** A kun'yomi of the kanji (possibly voiced by rendaku, possibly with absorbed okurigana), e.g. 食 in 食べる. */
    KUN("kun"),

    /** Neither could be aligned: jukujikun and ateji (今日 きょう), nanori, irregular readings (日本 にほん). */
    OTHER("other"),
}

/** The readings of the kanji whose example words are picked, as stored (`ref.kanji`, API `Kanji`). */
public data class KanjiReadings(
    /** The kanji: exactly one code point (may be a surrogate pair). Example `"食"`. */
    public val literal: String,
    /** `on_readings` in KANJIDIC form (katakana; a `-` marks a prefix/suffix reading). Example `["ショク", "ジキ"]`. May be empty. */
    public val onReadings: List<String>,
    /** `kun_readings` in KANJIDIC form (hiragana; `.` before the okurigana; `-` marks a prefix/suffix). Example `["く.う", "た.べる", "-ぐ.い"]`. May be empty. */
    public val kunReadings: List<String>,
)

/** One dictionary entry that may be shown as an example word of a kanji. */
public data class KanjiExampleCandidate(
    /** `dict_entry.seq`. Identity of the candidate; of two candidates with one `seq` the first in input order counts. */
    public val seq: Long,
    /** `dict_entry.headword` (the first kanji form). A candidate whose headword does not contain the kanji is never picked. Example `"食べ物"`. */
    public val headword: String,
    /** `dict_entry.headword_reading` (kana; katakana allowed). Example `"たべもの"`. */
    public val reading: String,
    /** `dict_entry.freq_rank` (1 = most frequent), or null when the entry is unranked (sorts after every rank). */
    public val freqRank: Int?,
    /** The user's effective word status (`word_status.status`; no row = [WordStatus.NEW]). */
    public val status: WordStatus,
    /** The user has a live note (`deleted_at` null) with this `entry_seq`. */
    public val hasNote: Boolean,
    /** A live deck holds a live `deck_item` with this `entry_seq` (`e:<seq>`). */
    public val inDecks: Boolean,
)

/** A picked example word: the candidate and how it reads the kanji. */
public data class KanjiExample(
    /** The candidate, unchanged. */
    public val candidate: KanjiExampleCandidate,
    /** [KanjiExamplePicker.classify] of the candidate's headword and reading. */
    public val readingType: KanjiReadingType,
)

/**
 * Picks the example words shown on the back of a kanji card (C-25, ADR-029): the words the user is most
 * likely to recognise, with at least one on'yomi and one kun'yomi word when there are any. Pure and
 * deterministic: the server fills `QueueItem.kanji_examples` with it and Android runs it offline over the
 * local dictionary, with identical results for identical data. Implementation:
 * `kotobaya.dict.DefaultKanjiExamplePicker`.
 *
 * ## classify (normative)
 *
 * `hira(s)`: every char U+30A1..U+30F6 (katakana ァ..ヶ) minus 0x60; everything else unchanged (`ー` stays).
 * A *kana* char is, after `hira`, U+3041..U+3096 or U+30FC (`ー`). Every other code point is *opaque*
 * (other kanji, `々`, digits, Latin, punctuation).
 *
 * 1. `H = hira(headword)` as code points, `R = hira(reading)`. `p` = index of the first code point of `H`
 *    equal to `literal`; none → [KanjiReadingType.OTHER]. Only the first occurrence is classified (日曜日 → 日 = にち);
 *    later occurrences are opaque. `prefix = H[0, p)`, `suffix = H(p, end)`; `first = (p == 0)`,
 *    `last = (suffix is empty)`.
 * 2. **Forms** of the kanji, each with a type. Every stored reading is taken through `hira` and loses
 *    every `-`; an empty result is skipped.
 *    - ON, per on reading `r`: `r`; and, when `!last` and `r` has ≥ 2 chars and ends in `き く ち つ`,
 *      the geminated `r` with its last char replaced by `っ` (学 ガク → `がっ` in 学校).
 *    - KUN, per kun reading split at its first `.` into stem `s` and okurigana `o` (no `.`: `o` empty):
 *      `s`; and, when `o` is not empty, the absorbed-okurigana forms `s + o`, `s + masu(o)` and, when `o`
 *      ends in `る`, `s + o` without that `る` (取 と.る → `とり` in 取引; 受 う.ける → `うけ` in 受付).
 *      `masu(o)` replaces the last char of `o` by its i-row partner: `う→い く→き ぐ→ぎ す→し つ→ち ぬ→に ぶ→び む→み る→り`
 *      (any other last char: no such form). An empty stem is skipped.
 *    - When `!first`, every form above also yields its rendaku forms: the first char voiced,
 *      `か..こ → が..ご`, `さ..そ → ざ..ぞ`, `た..と → だ..ど`, `は..ほ → ば..ぼ` and `ぱ..ぽ` (other first chars: none).
 * 3. A form `f` **fits** when `R = A + f + B` for some split with `match(prefix, A)` and `match(suffix, B)`.
 *    `match(tokens, text)`: left to right, a kana token equals the next char of the text; a maximal run of
 *    `m` opaque tokens takes any `k ≥ m` chars whose first is none of `ぁぃぅぇぉっゃゅょゎんー`; all of the
 *    text is consumed (no tokens ↔ empty text).
 * 4. Result: the type of the longest fitting form (UTF-16 length); ON when an ON and a KUN form of that
 *    length both fit; [KanjiReadingType.OTHER] when none fits.
 *
 * Okurigana written in the headword is matched as plain kana (食べ物 = `た` + `べ` + opaque), so conjugated
 * forms need no table. Known limits, all classified OTHER or by the longest-form rule: irregular
 * shortenings (日本 にほん), nanori, and jukujikun whose tail happens to equal a short kun stem (下手 へた → KUN
 * through `た-`). The classification is a display hint, never learning data.
 *
 * Examples (食: on ショク ジキ, kun く.う く.らう た.べる は.む): 食べる たべる → KUN; 食事 しょくじ → ON;
 * 朝食 ちょうしょく → ON (`しょく` is longer than the kun stem `く`); 食器 しょっき → ON (geminated); 断食 だんじき → ON.
 * 生 (on セイ ショウ, kun い.きる う.まれる なま …): 生きる → KUN; 先生 せんせい → ON; 誕生日 たんじょうび → ON (rendaku).
 * 日 (on ニチ ジツ, kun ひ -び -か): 今日 きょう → OTHER; 二日 ふつか → KUN; 日記 にっき → ON.
 *
 * ## pick (normative)
 *
 * 1. **Eligible**: candidates, de-duplicated by `seq` (first wins), whose headword contains `literal` and
 *    whose `status` is neither [WordStatus.BLACKLISTED] nor [WordStatus.IGNORED].
 * 2. **Pick order** (ascending): tier, then `freqRank` (null last), then `seq`. Tier 0 ("mine") when
 *    `status` is [WordStatus.LEARNING], [WordStatus.KNOWN] or [WordStatus.NEVER_FORGET], or `hasNote`, or
 *    `inDecks`; else tier 1.
 * 3. **Window**: only the first [CANDIDATE_LIMIT] eligible candidates in pick order are considered, so a
 *    host may pass any superset of them (for example a SQL `LIMIT`) and get the same result.
 * 4. With `limit ≤ 0` the result is empty. Otherwise take, in this order and while fewer than `limit` are
 *    taken: the first ON candidate of the window; the first KUN candidate of the window; then the
 *    window's candidates in pick order that are not yet taken.
 * 5. Return the taken candidates in pick order (so at most `limit`, fewer when the window is smaller).
 *
 * Example (食, limit 3; ranks 食べる 312 KUN, 食事 480 ON, 食堂 1500 ON, 食品 900 ON; the user has a note for
 * 食堂 only): order = 食堂 (tier 0), 食べる, 食事, 食品 → ON 食堂, KUN 食べる, fill 食事 → `[食堂, 食べる, 食事]`.
 * With no kun word among the candidates: the top three by pick order. With no candidates: `[]`.
 */
public interface KanjiExamplePicker {
    /**
     * How [headword] (read [reading]) reads `kanji.literal`; see "classify". Never throws: malformed input
     * (empty strings, a headword without the kanji, a reading with non-kana) gives [KanjiReadingType.OTHER].
     */
    public fun classify(kanji: KanjiReadings, headword: String, reading: String): KanjiReadingType

    /** Up to [limit] example words for [kanji] out of [candidates], in pick order; see "pick". */
    public fun pick(kanji: KanjiReadings, candidates: List<KanjiExampleCandidate>, limit: Int = DEFAULT_LIMIT): List<KanjiExample>

    /** Constants. */
    public companion object {
        /** Number of example words a kanji card shows (`QueueItem.kanji_examples` has at most this many). */
        public const val DEFAULT_LIMIT: Int = 3

        /** Size of the pick window: an ON or KUN word ranked below this many eligible candidates is not looked for. */
        public const val CANDIDATE_LIMIT: Int = 50
    }
}
