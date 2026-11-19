package kotobaya.contracts.cards

import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonValue

/**
 * The displayed form of one vocab note, as [KanjiRequirement] sees it (ADR-042, ADR-045, ADR-046).
 *
 * The host fills it once per note, from the same data on every platform:
 * - [expression]: the plain text of the note's `expression` field exactly as the card renderer
 *   resolves it (`CardTextRenderer` "text of a field", `kanji` mode: a non-blank note field wins,
 *   else the dictionary fill, i.e. the entry's `headword`). **Kana words (ADR-046 (a))**: the
 *   dictionary build stores the entry's first reading as `headword` when every kanji form of the
 *   entry is tagged `rK` or `sK` (SCHEMA.md 4.5 "Headword"), so for such a note with a blank
 *   field [expression] is kana (`シンガポール`, not `新嘉坡`) and nothing below applies. The host
 *   does **not** test the form tags for this itself: it takes `headword` as stored. A note whose
 *   own `expression` field says `新嘉坡` keeps that form (the user wrote it) and is evaluated
 *   as a tagged word.
 * - [formTags]: the JMdict `ke_inf` tags (entity codes such as `ateji`, `rK`) of the element of the
 *   entry's `kanji_forms` whose `text` equals [expression] exactly (code point for code point).
 *   Empty when the note has no entry (`entry_seq` null), the entry is not available on this
 *   replica, or no kanji form equals [expression] (a custom expression, or a kana form).
 * - [usuallyKana]: true iff the **sense used** carries the JMdict misc tag `uk`. The sense used is
 *   the first element of the entry's `senses` (JMdict order) whose `applies_to_kanji` contains
 *   `"*"` or [expression]; no such sense, no entry or entry not available → false.
 *
 * Example (寿司, entry 1595650): `WordForm("寿司", setOf("ateji"), false)`. Example (a note without
 * an entry whose field says 鱈子): `WordForm("鱈子", emptySet(), false)`. Example (シンガポール, entry
 * 1063410, blank field; kanji forms 新嘉坡 / 新加坡 / 星嘉坡 all `ateji`, `rK`, so `headword` is the
 * reading): `WordForm("シンガポール", emptySet(), true)` (no kanji form equals the kana expression →
 * no form tags; the sense is `uk`, which is irrelevant without a kanji). Example (the same entry on
 * a note whose field says 新嘉坡): `WordForm("新嘉坡", setOf("ateji", "rK"), true)`.
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
 * order, without duplicates. For a word that is **not** tagged it holds every learnable kanji of
 * the expression ([KanjiRequirement.isLearnable]); for a **tagged** word ([wordExempt]) it holds
 * the **jōyō** kanji only ([KanjiRequirement.isJoyo]; ADR-046 (b)). So for the same expression and
 * facts the tagged list is a sub-list of the untagged one, the two differ only in non-jōyō kanji
 * that are learnable by frequency rank alone, and **a jōyō kanji is never removed by a tag**
 * (ADR-045). `wordExempt` implies `frontFurigana`; `frontFurigana` is true iff the setting is on
 * and the expression contains at least one kanji and (`wordExempt` or
 * `learnableKanji.isEmpty()`). With the setting off: `wordExempt = false`, `learnableKanji` =
 * every kanji, `frontFurigana = false`.
 *
 * Example (setting on): 寿司 → `(wordExempt = true, ["寿", "司"], frontFurigana = true)`; 珈琲 →
 * `(true, [], true)`; 薔薇 (`uk`) → `(true, [], true)`; 昨日 → `(false, ["昨", "日"], false)`; 鱈 →
 * `(false, [], true)`; 鱈子 → `(false, ["子"], false)`; たべる and シンガポール → `(false, [], false)`.
 */
public data class KanjiRequirementResult(
    /**
     * The displayed form carries a **word-level tag** (sense `uk`, or form `ateji` / `rK` / `oK` /
     * `iK` / `sK`) and contains a kanji. It gives the furigana front and, since ADR-046 (b),
     * narrows [learnableKanji] to the jōyō kanji (it never removes a jōyō kanji, ADR-045). The
     * name is kept from ADR-042 for source compatibility: read it as "word-level tagged", not as
     * "needs no kanji cards".
     */
    public val wordExempt: Boolean,
    /**
     * The kanji this word **requires**: the only kanji that get a kanji card because of this word,
     * that block it under kanji-first (ADR-040), that are minted for it and that it backfills.
     * Loaders pass it as `QueueCandidate.requiredKanji` / `PendingEntry.requiredKanji`. Untagged
     * word: its learnable kanji (jōyō or frequency rank 1..2500). Tagged word: its jōyō kanji only
     * (ADR-046 (b)). The name is kept: read it as "required kanji".
     */
    public val learnableKanji: List<String>,
    /** The card front shows the reading as furigana over the whole word (`RenderInput.frontFurigana`, API `QueueItem.front_furigana`): [wordExempt] or no learnable kanji. */
    public val frontFurigana: Boolean,
)

/**
 * ADR-042 as amended by ADR-045 and ADR-046: the **one** predicate that says which kanji a vocab
 * word requires and whether its front carries furigana. In one line: **an untagged word requires
 * its learnable kanji (jōyō or frequency top 2500); a word-level tagged word requires its jōyō
 * kanji only and shows furigana; a kana word (ADR-046 (a)) has no kanji at all by the time it gets
 * here.** Kanji-first ordering (`QueueBuilder` rule 4), kanji-note materialization, backfill and
 * the card front all use its result and nothing else, so every platform agrees. Pure and
 * deterministic; implementation `kotobaya.cards.DefaultKanjiRequirement` (tickets CORE-28,
 * CORE-28d).
 *
 * **Kana words are not decided here (ADR-046 (a)).** "Every kanji form of the entry is tagged `rK`
 * or `sK` → the word displays its reading" is a rule of the **dictionary build**: it is stored in
 * the entry's `headword` (SCHEMA.md 4.5 "Headword"), which is what [WordForm.expression] is for a
 * note with a blank `expression` field. This predicate then sees a kana expression and answers
 * `(false, [], false)` by its ordinary kana-only edge case. It never looks at the entry's other
 * forms, and an implementation MUST NOT add such a test (two sources would disagree while a
 * device's dictionary package is older than the server's).
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
 *   1. `wordExempt` ("word-level tagged") = `all` is not empty **and** (`form.usuallyKana` **or**
 *      `form.formTags` contains one of [EXEMPT_FORM_TAGS]: `ateji`, `rK`, `oK`, `iK`, `sK`). Tag
 *      comparison is exact and case-sensitive (`rk` is not `rK`).
 *   2. `learnableKanji` = the elements of `all` that pass the test below with that literal's
 *      [KanjiFacts] (the first element of `kanji` with that literal; none → fails both tests):
 *      - `wordExempt` **false** → [isLearnable]`(grade, freqRank)`;
 *      - `wordExempt` **true** → [isJoyo]`(grade)` (ADR-046 (b): the frequency rank is not
 *        looked at; a jōyō kanji is still never removed, ADR-045).
 *   3. `frontFurigana` = `all` is not empty **and** (`wordExempt` **or** `learnableKanji` is
 *      empty). So an untagged word with at least one learnable kanji never shows furigana, also
 *      when its reading is irregular; a tagged word always does, also when it requires kanji.
 *
 * **isLearnable** (normative): `grade` in 1..6 or 8 (jōyō; 9 and 10 are jinmeiyō and do not
 * count), **or** `freqRank` in 1..[FREQ_TOP] (2500). Both null → false.
 *
 * **isJoyo** (normative): `grade` in 1..6 or 8. Null, 7, 9, 10 and everything else → false.
 * `isJoyo(g)` implies `isLearnable(g, r)` for every `r`.
 *
 * Edge cases: an empty or kana-only expression → `(false, [], false)` whatever the tags (this is
 * the whole of a kana word's evaluation); a form tagged `uk` whose expression has no kanji is not
 * tagged for this rule (no furigana to show); a tagged word and an untagged word with the same
 * **jōyō** kanji require it alike; a non-jōyō kanji with a frequency rank ≤ 2500 is required by
 * the untagged word only (when an untagged word mints its card, the tagged word still does not
 * require it: the card is not in the tagged word's unit and does not block it); a kanji repeated in
 * the expression counts once; a kanji outside the BMP is one literal; unknown tags are ignored;
 * the entry missing on a device (dictionary not installed, L-7 miss) gives `formTags = {}`,
 * `usuallyKana = false` and `kanji` without facts, i.e. no word-level tag and no learnable kanji
 * → `frontFurigana = true` and no kanji cards until the data is there (the result then changes,
 * which is harmless: nothing was minted).
 *
 * Worked examples (setting on unless stated; facts as in KANJIDIC2):
 * Each line gives `(wordExempt, learnableKanji, frontFurigana)`.
 * - 海老 (`uk`; 海 grade 2, 老 grade 4): `(true, [海, 老], true)` → kanji cards 海 and 老 first,
 *   furigana front. Off → `(false, [海, 老], false)`.
 * - 寿司 (`ateji`; 寿 grade 8, 司 grade 4): `(true, [寿, 司], true)` → kanji cards 寿 and 司 first,
 *   furigana front. Off → `(false, [寿, 司], false)`.
 * - 珈琲 (`ateji`, `uk`; 珈 grade 9 = jinmeiyō, 琲 no grade, both unranked): `(true, [], true)` → no kanji
 *   cards, furigana front. Off → `(false, [珈, 琲], false)`.
 * - 林檎 (`uk`; 林 grade 1, 檎 grade 9, unranked): `(true, [林], true)` → kanji card 林 only,
 *   furigana front. Without the tag: `(false, [林], false)`.
 * - **Tags never remove a jōyō kanji** (ADR-045): お化け (`uk`; 化 is jōyō, grade 3) →
 *   `(true, [化], true)`; a note whose `expression` field says 彼 for あれ (form `rK`, sense `uk`;
 *   彼 is jōyō, grade 8) → `(true, [彼], true)`. Both get their kanji card first and a furigana
 *   front. (With a blank field the entry あれ, 1000580, is a kana word since ADR-046 (a): both its
 *   kanji forms 彼 and 彼れ are `rK`, its `headword` is `あれ` → `(false, [], false)`.)
 * - **A tagged word requires jōyō kanji only** (ADR-046 (b)): 金糸雀 (カナリア; `ateji`, `uk`; 金
 *   grade 1, 糸 grade 1, 雀 grade 9 with rank 1856) → `(true, [金, 糸], true)`: no card for 雀
 *   although it is learnable. Off → `(false, [金, 糸, 雀], false)`. 鯛 (`uk`; grade 9, rank 2446) →
 *   `(true, [], true)`; without the tag `(false, [鯛], false)`.
 * - **Kana words** (ADR-046 (a); the form reaching this predicate is the stored `headword`):
 *   シンガポール (entry 1063410; forms 新嘉坡, 新加坡, 星嘉坡 all `ateji` + `rK`; sense `uk`) →
 *   `WordForm("シンガポール", {}, true)` → `(false, [], false)`: no kanji card, plain front, on and
 *   off. かるた (entry 1590710; forms 歌留多, 骨牌, 加留多 `rK`, six more `sK`; first reading
 *   かるた) → `WordForm("かるた", {}, true)` → `(false, [], false)`, on and off.
 * - **The same entries when the note's own field holds the kanji form** (not a kana word: the
 *   user's text wins): 新嘉坡 (`ateji`, `rK`, `uk`; 新 grade 2, 嘉 grade 9 with rank 1553, 坡 no
 *   grade, unranked) → `(true, [新], true)`: no card for 嘉 (the card ADR-045 minted) or 坡. Off →
 *   `(false, [新, 嘉, 坡], false)`. 歌留多 (`ateji`, `rK`, `uk`; 歌 grade 2, 留 grade 5, 多 grade 2) →
 *   `(true, [歌, 留, 多], true)`.
 * - 今日, 昨日 (no tag; both kanji jōyō): `(false, [今, 日], false)` / `(false, [昨, 日], false)`,
 *   no furigana although the reading is irregular.
 * - 鱈 (no grade, no rank), no tag: `(false, [], true)`, furigana front, no kanji card. Off → `(false, [鱈], false)`.
 * - 鱈子 without an entry (鱈 unranked, 子 grade 1): `(false, [子], false)`, no furigana, no card for 鱈.
 * - 薔薇 (`uk`; 薔 no grade, rank 2356; 薇 no grade, unranked): `(true, [], true)` → **no kanji
 *   card**, furigana front (ADR-046 (b); under ADR-045 it was `[薔]`). **If it were untagged**:
 *   `(false, [薔], false)`. Off → `(false, [薔, 薇], false)`.
 */
public interface KanjiRequirement {
    /** Evaluates [form] as the interface documents. [kanji] may hold facts for other literals too; duplicates: the first counts. Never throws. */
    public fun evaluate(form: WordForm, kanji: List<KanjiFacts>, exemptionsEnabled: Boolean = KANJI_EXEMPTIONS_DEFAULT): KanjiRequirementResult

    /** True iff a kanji with these facts is learnable (see "isLearnable"): what an **untagged** word requires. Independent of the setting. Example: `(8, null)` → true; `(9, null)` → false; `(null, 2500)` → true; `(null, 2501)` → false. */
    public fun isLearnable(grade: Int?, freqRank: Int?): Boolean

    /** Constants and the setting helper. */
    public companion object {
        /** Settings key (SCHEMA.md 6, 6.5): `cards.kanji_exemptions`, bool, default true. Synced by `settings.set`. */
        public const val SETTING_KANJI_EXEMPTIONS: String = "cards.kanji_exemptions"

        /** Default of [SETTING_KANJI_EXEMPTIONS]: **true**. A missing row, JSON `null` and every invalid value mean this default (SCHEMA.md 6.1). */
        public const val KANJI_EXEMPTIONS_DEFAULT: Boolean = true

        /** Largest KANJIDIC2 frequency rank that makes a kanji learnable. */
        public const val FREQ_TOP: Int = 2500

        /**
         * JMdict `ke_inf` codes that make a form word-level tagged (furigana front, and jōyō-only
         * required kanji since ADR-046): ateji, rarely-used kanji form, out-dated kanji, irregular
         * kanji usage, search-only kanji form. They never exempt a jōyō kanji (ADR-045); the name
         * is kept from ADR-042.
         */
        public val EXEMPT_FORM_TAGS: Set<String> = setOf("ateji", "rK", "oK", "iK", "sK")

        /** JMdict misc code "usually written using kana alone". */
        public const val MISC_USUALLY_KANA: String = "uk"

        /**
         * JMdict `ke_inf` codes that make an entry a **kana word** when **every** kanji form of
         * the entry carries at least one of them (ADR-046 (a)): rarely-used and search-only kanji
         * forms. Used by the dictionary build to choose `headword` (SCHEMA.md 4.5 "Headword"), not
         * by [evaluate]; listed here so the one definition has a name. `ateji`, `oK` and `iK` do
         * not count: 寿司 (`ateji`) stays 寿司.
         */
        public val KANA_WORD_FORM_TAGS: Set<String> = setOf("rK", "sK")

        /**
         * True iff [grade] is a jōyō grade: 1..6 (kyōiku) or 8. The test a **word-level tagged**
         * word applies to its kanji (ADR-046 (b)). Independent of the setting. Example: `1` → true;
         * `8` → true; `7`, `9`, `10`, `0`, `null` → false.
         */
        public fun isJoyo(grade: Int?): Boolean = grade != null && (grade in 1..6 || grade == 8)

        /**
         * The typed value of [SETTING_KANJI_EXEMPTIONS] from its stored JSON value by the SCHEMA.md
         * 6.1 `bool` test with default true: false iff [value] is JSON `false`; null (no row), JSON
         * `null`, `true`, `0`, `"false"` and anything else → true.
         */
        public fun exemptionsEnabled(value: JsonValue?): Boolean = value != JsonBool(false)
    }
}
