package kotobaya.contracts.cards

import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonValue

/**
 * The displayed form of one vocab note, as [KanjiRequirement] sees it (ADR-042).
 *
 * The host fills it once per note, from the same data on every platform:
 * - [expression]: the plain text of the note's `expression` field exactly as the card renderer
 *   resolves it (`CardTextRenderer` "text of a field", `kanji` mode: a non-blank note field wins,
 *   else the dictionary fill, i.e. the entry's `headword`).
 * - [formTags]: the JMdict `ke_inf` tags (entity codes such as `ateji`, `rK`) of the element of the
 *   entry's `kanji_forms` whose `text` equals [expression] exactly (code point for code point).
 *   Empty when the note has no entry (`entry_seq` null), the entry is not available on this
 *   replica, or no kanji form equals [expression] (a custom expression, or a kana form).
 * - [usuallyKana]: true iff the **sense used** carries the JMdict misc tag `uk`. The sense used is
 *   the first element of the entry's `senses` (JMdict order) whose `applies_to_kanji` contains
 *   `"*"` or [expression]; no such sense, no entry or entry not available → false.
 *
 * Example (寿司, entry 1595650): `WordForm("寿司", setOf("ateji"), false)`. Example (a note without
 * an entry whose field says 鱈子): `WordForm("鱈子", emptySet(), false)`.
 */
public data class WordForm(
    /** The displayed form as plain text. May be empty or kana-only (then nothing applies). Example `"昨日"`. */
    public val expression: String,
    /** JMdict `ke_inf` codes of the kanji form equal to [expression]; empty = none or unknown. Example `["ateji"]`. */
    public val formTags: Set<String>,
    /** The sense used is tagged `uk` ("usually written using kana alone"). Example: 珈琲 → true. */
    public val usuallyKana: Boolean,
)

/**
 * What KANJIDIC says about one kanji, for [KanjiRequirement.isLearnable]: `ref.kanji.grade` and
 * `ref.kanji.freq_rank` (device: the `kanji` table of the dictionary package; API `Kanji.grade` /
 * `Kanji.freq_rank`). A kanji with no row is passed with both null (or not passed at all).
 *
 * Example: 日 → `KanjiFacts("日", 1, 1)`; 薔 → `KanjiFacts("薔", null, 2356)`; 鱈 → `KanjiFacts("鱈", null, null)`.
 */
public data class KanjiFacts(
    /** The kanji, exactly one code point. */
    public val literal: String,
    /** KANJIDIC2 grade: 1–6 kyōiku, 8 the remaining jōyō, 9–10 jinmeiyō; null = none. */
    public val grade: Int?,
    /** KANJIDIC2 newspaper frequency rank, 1 = most frequent (the list has 2,500 kanji); null = unranked. */
    public val freqRank: Int?,
)

/**
 * The answer of [KanjiRequirement.evaluate] for one displayed form.
 *
 * Invariants: [learnableKanji] is a sub-list of `CardGenerator.kanjiOf(expression)` in the same
 * order, without duplicates; `wordExempt` implies `learnableKanji.isEmpty()`; `frontFurigana` is
 * true iff the expression contains at least one kanji and `learnableKanji.isEmpty()` and the
 * setting is on. With the setting off: `wordExempt = false`, `learnableKanji` = every kanji,
 * `frontFurigana = false`.
 *
 * Example (setting on): 寿司 → `(wordExempt = true, [], frontFurigana = true)`; 昨日 →
 * `(false, ["昨", "日"], false)`; 鱈 → `(false, [], true)`; 鱈子 → `(false, ["子"], false)`;
 * たべる → `(false, [], false)`.
 */
public data class KanjiRequirementResult(
    /** Rule (a): the word needs no kanji cards at all because of how JMdict tags the form. */
    public val wordExempt: Boolean,
    /**
     * The kanji this word **requires**: the only kanji that get a kanji card because of this word,
     * that block it under kanji-first (ADR-040), that are minted for it and that it backfills.
     * Loaders pass it as `QueueCandidate.requiredKanji` / `PendingEntry.requiredKanji`.
     */
    public val learnableKanji: List<String>,
    /** The card front shows the reading as furigana over the whole word (`RenderInput.frontFurigana`, API `QueueItem.front_furigana`). */
    public val frontFurigana: Boolean,
)

/**
 * ADR-042: the **one** predicate that says which kanji a vocab word requires and whether its front
 * carries furigana. Kanji-first ordering (`QueueBuilder` rule 4), kanji-note materialization,
 * backfill and the card front all use its result and nothing else, so every platform agrees. Pure
 * and deterministic; implementation `kotobaya.cards.DefaultKanjiRequirement` (ticket CORE-28).
 *
 * **Setting.** [SETTING_KANJI_EXEMPTIONS] = `cards.kanji_exemptions` (bool, default **true**,
 * SCHEMA.md 6.5), typed by [exemptionsEnabled]. The host reads it once per operation and passes
 * the bool.
 *
 * **evaluate** (normative). `all` = `CardGenerator.kanjiOf(form.expression)` (distinct, order of
 * first appearance; `々` and kana are not kanji).
 * - Setting **off**: `wordExempt = false`, `learnableKanji = all`, `frontFurigana = false`
 *   (ADR-040 as written: every kanji is required, no furigana fronts).
 * - Setting **on**:
 *   1. `wordExempt` = `all` is not empty **and** (`form.usuallyKana` **or** `form.formTags`
 *      contains one of [EXEMPT_FORM_TAGS]: `ateji`, `rK`, `oK`, `iK`, `sK`). Tag comparison is
 *      exact and case-sensitive (`rk` is not `rK`).
 *   2. `learnableKanji` = empty if `wordExempt`, else the elements of `all` for which
 *      [isLearnable] holds with that literal's [KanjiFacts] (the first element of `kanji` with
 *      that literal; none → not learnable).
 *   3. `frontFurigana` = `all` is not empty **and** `learnableKanji` is empty. So a word with at
 *      least one learnable kanji never shows furigana, also when its reading is irregular.
 *
 * **isLearnable** (normative): `grade` in 1..6 or 8 (jōyō; 9 and 10 are jinmeiyō and do not
 * count), **or** `freqRank` in 1..[FREQ_TOP] (2500). Both null → false.
 *
 * Edge cases: an empty or kana-only expression → `(false, [], false)` whatever the tags; a form
 * tagged `uk` whose expression has no kanji is not exempt (nothing to exempt); a kanji repeated in
 * the expression counts once; a kanji outside the BMP is one literal; unknown tags are ignored;
 * the entry missing on a device (dictionary not installed, L-7 miss) gives `formTags = {}`,
 * `usuallyKana = false` and `kanji` without facts, i.e. no word exemption and no learnable kanji
 * → `frontFurigana = true` and no kanji cards until the data is there (the result then changes,
 * which is harmless: nothing was minted).
 *
 * Worked examples (setting on unless stated; facts as in KANJIDIC2):
 * - 寿司 (`ateji`; 寿 grade 8, 司 grade 4): exempt → no kanji cards, furigana front. Off → `[寿, 司]`, no furigana.
 * - 珈琲 (`ateji`, `uk`), 海老 (`uk`): exempt → furigana front.
 * - **The word-level exemption beats learnable kanji** (ADR-042 (a) comes first): お化け (`uk`; 化
 *   is jōyō, grade 3) and 彼 read あれ (`rK`, `uk`; 彼 is jōyō) are exempt → no kanji card for 化 /
 *   彼 because of this word, furigana front. Another, untagged word with the same kanji still
 *   requires it.
 * - 今日, 昨日 (no tag; both kanji jōyō): `[今, 日]` / `[昨, 日]`, no furigana although the reading is irregular.
 * - 鱈 (no grade, no rank), no tag: `[]`, furigana front, no kanji card. Off → `[鱈]`.
 * - 鱈子 without an entry (鱈 unranked, 子 grade 1): `[子]`, no furigana, no card for 鱈.
 * - 薔薇 **if it were untagged** (薔 rank 2356, 薇 unranked): `[薔]`, no furigana; with its JMdict `uk` tag it is exempt.
 */
public interface KanjiRequirement {
    /** Evaluates [form] as the interface documents. [kanji] may hold facts for other literals too; duplicates: the first counts. Never throws. */
    public fun evaluate(form: WordForm, kanji: List<KanjiFacts>, exemptionsEnabled: Boolean = KANJI_EXEMPTIONS_DEFAULT): KanjiRequirementResult

    /** True iff a kanji with these facts is learnable (see "isLearnable"). Independent of the setting. Example: `(8, null)` → true; `(9, null)` → false; `(null, 2500)` → true; `(null, 2501)` → false. */
    public fun isLearnable(grade: Int?, freqRank: Int?): Boolean

    /** Constants and the setting helper. */
    public companion object {
        /** Settings key (SCHEMA.md 6, 6.5): `cards.kanji_exemptions`, bool, default true. Synced by `settings.set`. */
        public const val SETTING_KANJI_EXEMPTIONS: String = "cards.kanji_exemptions"

        /** Default of [SETTING_KANJI_EXEMPTIONS]: **true**. A missing row, JSON `null` and every invalid value mean this default (SCHEMA.md 6.1). */
        public const val KANJI_EXEMPTIONS_DEFAULT: Boolean = true

        /** Largest KANJIDIC2 frequency rank that makes a kanji learnable. */
        public const val FREQ_TOP: Int = 2500

        /** JMdict `ke_inf` codes that exempt a form: ateji, rarely-used kanji form, out-dated kanji, irregular kanji usage, search-only kanji form. */
        public val EXEMPT_FORM_TAGS: Set<String> = setOf("ateji", "rK", "oK", "iK", "sK")

        /** JMdict misc code "usually written using kana alone". */
        public const val MISC_USUALLY_KANA: String = "uk"

        /**
         * The typed value of [SETTING_KANJI_EXEMPTIONS] from its stored JSON value by the SCHEMA.md
         * 6.1 `bool` test with default true: false iff [value] is JSON `false`; null (no row), JSON
         * `null`, `true`, `0`, `"false"` and anything else → true.
         */
        public fun exemptionsEnabled(value: JsonValue?): Boolean = value != JsonBool(false)
    }
}
