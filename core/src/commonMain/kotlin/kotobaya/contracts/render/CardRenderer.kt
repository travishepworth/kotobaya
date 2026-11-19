package kotobaya.contracts.render

import kotobaya.contracts.Uuid
import kotobaya.contracts.cards.NoteTypeInfo

/** One segment of a word with its reading, for furigana (`<ruby>`). */
public data class FuriganaSegment(
    /** Surface text, e.g. `食`. */
    public val text: String,
    /** Kana reading of [text], or null when [text] is kana (no ruby). */
    public val reading: String?,
)

/**
 * Dictionary data for a note with an entry, supplied by the caller from its dictionary (server:
 * `ref.*`; phone: the dictionary file; web: the API). Used for fields that are empty in the note
 * ("for referenced notes an empty field means render from the dictionary", note.fields comment).
 */
public data class DictionaryData(
    /** Headword as written, e.g. `食べる`. */
    public val expression: String,
    /** Kana reading, e.g. `たべる`. */
    public val reading: String,
    /** Furigana alignment of [expression]; empty = no alignment known (ruby over the whole word). */
    public val furigana: List<FuriganaSegment>,
    /** English glosses, in dictionary order. */
    public val glosses: List<String>,
    /**
     * Pitch accent patterns as downstep positions in morae of [reading] (0 = heiban, 1 = atamadaka,
     * n = drop after mora n). Empty = unknown.
     */
    public val pitchDrops: List<Int>,
)

/**
 * Kanji data for a kanji note (C-2), supplied by the caller from `ref.kanji` of `note.kanji_literal`
 * (server: `ref.kanji`; phone: the dictionary file; web: `QueueItem.kanji` / `GET /kanji/{literal}`).
 * Used for fields that are empty in the note, exactly like [DictionaryData] for vocab: notes created
 * with only `literal` (`origin` `auto_kanji` or `jpdb_import`) render the same on every host.
 */
public data class KanjiData(
    /** The kanji, exactly one Unicode code point (`ref.kanji.literal`). */
    public val literal: String,
    /** English meanings, KANJIDIC2 order (`ref.kanji.meanings`). */
    public val meanings: List<String>,
    /** On readings in katakana, KANJIDIC2 order (`ref.kanji.on_readings`). */
    public val onyomi: List<String>,
    /** Kun readings in hiragana with okurigana dots, e.g. `た.べる` (`ref.kanji.kun_readings`). */
    public val kunyomi: List<String>,
    /** Stroke count, or null if unknown (`ref.kanji.stroke_count`). Not shown by the v1 built-in templates. */
    public val strokeCount: Int? = null,
)

/** A grammar cloze: the sentence shown and the span to blank (rotating example, SPEC "Grammar review"). */
public data class ClozeSentence(
    /** `sentence.id` (logged as `review_log.sentence_id`). */
    public val sentenceId: Uuid?,
    /** Sentence text. */
    public val text: String,
    /** UTF-16 start of the grammar span in [text], inclusive. */
    public val start: Int,
    /** UTF-16 end, exclusive; `start < end <= text.length`. */
    public val end: Int,
    /** Translation for the back, or null. */
    public val translation: String?,
)

/** Which side to render. */
public enum class CardSide {
    /** The question. */
    FRONT,

    /** The answer (contains the front, see `{{FrontSide}}`). */
    BACK,
}

/** Rendering options. */
public data class RenderOptions(
    /** Show furigana (`<ruby>` with `<rt>`); off = `<ruby>` dropped, base text kept. */
    public val furigana: Boolean,
    /** Show pitch-accent marks on readings. */
    public val pitch: Boolean,
    /** Show the note's mnemonic on the back (true while the word is hot). */
    public val showMnemonic: Boolean,
    /**
     * How the host serves media blobs: a URL template containing `{sha256}` exactly once, replaced
     * by the 64-hex blob hash in every emitted `img src`. Browsers and WebViews cannot load the stored
     * notation `blob:<sha256>`, so each host maps it to a URL it serves, e.g. web
     * `/api/v1/blobs/{sha256}` (`GET /blobs/{hash}`), Android WebView (WebViewAssetLoader)
     * `https://appassets.androidplatform.net/media/{sha256}`. Only this substitution differs between
     * hosts; the rest of the HTML is identical for identical input.
     */
    public val mediaUrlTemplate: String,
    /**
     * Field regions (C-19, for the WYSIWYG note view). `false` (the default): output is exactly what it
     * was before C-19 and [RenderedCard.fields] is empty. `true`: every field-value wrapper span carries
     * `data-field` and `data-field-source`, and [RenderedCard.fields] lists the fields the side refers
     * to. See "Field regions" on [CardRenderer]. Review screens leave it off.
     */
    public val fieldRegions: Boolean = false,
)

/** Everything needed to render one side of one card. */
public data class RenderInput(
    /** The note type. */
    public val noteType: NoteTypeInfo,
    /** `card.kind`. */
    public val kind: String,
    /** `note.fields` (key → HTML fragment); missing key = `""`. */
    public val fields: Map<String, String>,
    /** `note.mnemonic` (Markdown source; rendered as escaped plain text with line breaks). */
    public val mnemonic: String,
    /** Dictionary data, or null (no entry, or not available on this replica). */
    public val dictionary: DictionaryData?,
    /** The cloze sentence for grammar `cloze` / `spot` cards, else null. */
    public val cloze: ClozeSentence?,
    /** Side. */
    public val side: CardSide,
    /** Options. */
    public val options: RenderOptions,
    /**
     * Kanji data for a note of base type `kanji` whose `kanji_literal` is in `ref.kanji`, else null
     * (C-2). Ignored for other base types.
     */
    public val kanji: KanjiData? = null,
    /**
     * ADR-042 (additive): **furigana front**. The host passes
     * `KanjiRequirement.evaluate(...).frontFurigana` of the note's displayed form (API
     * `QueueItem.front_furigana`); false for non-vocab notes. Default false = the output before
     * ADR-042, byte for byte. See "Furigana front" on [CardRenderer].
     */
    public val frontFurigana: Boolean = false,
    /**
     * ADR-047 (additive): the blob hash (64 lowercase hex) of the note's **generated** word audio,
     * or null when there is none or it is not known yet. Hosts pass API
     * `QueueItem.word_audio.blob_hash` unchanged (also when its `source` is `note`: the renderer
     * ignores this value whenever the note's own `audio` field holds a sound). A value that is not
     * 64 lowercase hex digits is treated as null. Default null = the output before ADR-047 for a
     * note without audio. See "Word audio" on [CardRenderer].
     */
    public val wordAudio: String? = null,
)

/** Where the value of a field came from when a side was rendered (C-19). */
public enum class FieldSource(
    /** The value of `data-field-source` and of `JsRenderedField.source`. */
    public val wire: String,
) {
    /** `note.fields[key]` is non-empty after trimming: the user's own value (an override, if a fallback exists). */
    NOTE("note"),

    /** The note field is empty and the value was filled from [DictionaryData] (`expression`, `reading`, `meaning`). */
    DICTIONARY("dictionary"),

    /** The note field is empty and the value was filled from [KanjiData] (`literal`, `keyword`, `readings`; kanji notes only). */
    KANJI("kanji"),

    /** The note field is empty and nothing fills it. Never the source of a region (an empty field emits no region). */
    EMPTY("empty"),
}

/**
 * One field the rendered side refers to (C-19). Example: a vocab note with `meaning` set by the user
 * and `expression` left to the dictionary gives `RenderedField("expression", DICTIONARY, true)` and
 * `RenderedField("meaning", NOTE, true)`; its empty `notes` gives `RenderedField("notes", EMPTY, false)`.
 */
public data class RenderedField(
    /** The `note.fields` key, matching `[a-z][a-z0-9_]*`, e.g. `keyword`. Not necessarily in `NoteTypeInfo.fieldKeys` (a custom template may name any key). */
    public val key: String,
    /** Where the value came from. Depends only on the key and the input, never on the placeholder's mode. */
    public val source: FieldSource,
    /**
     * True iff [RenderedCard.html] contains at least one region (`data-field="<key>"`) for this key.
     * False when the field is [FieldSource.EMPTY], is only named by section tags or inside a section
     * that is not shown, or its value sanitises to nothing (e.g. only a `<script>`).
     */
    public val rendered: Boolean,
)

/** A rendered side. */
public data class RenderedCard(
    /** Sanitised HTML fragment, root `<div class="kb-card kb-<base type> kb-kind-<kind> kb-front|kb-back">`. */
    public val html: String,
    /** Blob hashes (64 lowercase hex) of the audio referenced, in document order; the UI plays/prefetches them. */
    public val audio: List<String>,
    /** The note type's extra CSS to append after `assets/card/` CSS (passed through unchanged; scoped by the host). */
    public val extraCss: String,
    /** Non-fatal problems (unknown placeholder, dropped tag), as i18n keys `render.warning.<code>`; empty when clean. */
    public val warnings: List<String>,
    /**
     * With [RenderOptions.fieldRegions]: the fields this side refers to, one entry per key, in order of
     * first reference (see "Field regions" on [CardRenderer]). Empty when the option is off.
     */
    public val fields: List<RenderedField> = emptyList(),
    /**
     * ADR-047: the blob hash of the word audio this side carries (the first sound of its word-audio
     * block W, see "Word audio" on [CardRenderer]), or null when the side has no W. What `P` and a
     * tap on the speaker replay. Also contained in [audio].
     */
    public val wordAudio: String? = null,
    /**
     * ADR-047: the blob hash the host plays once when this side is shown and the setting
     * `review.autoplay_audio` is on, else null. FRONT: equal to [wordAudio]. BACK: [wordAudio] when
     * the FRONT of the same input has no word audio, else null (a sound already played with the
     * question is not repeated by the reveal). The renderer does not read the setting.
     */
    public val autoplayAudio: String? = null,
)

/**
 * The one card renderer (ADR-002), used by web review, template preview and the Android review
 * WebView. Pure: same input, same string, on every platform.
 *
 * **Templates.** A custom template (`TemplateInfo.front`/`back` non-null) or the built-in one for
 * (base type, kind) is expanded with: `{{key}}` (field value, sanitised); `{{furigana:key}}`
 * (bracket furigana `漢字[かんじ]` → ruby); `{{kana:key}}` (readings only); `{{kanji:key}}`
 * (base text only); `{{text:key}}` (HTML stripped); `{{#key}}…{{/key}}` (only if the field is
 * non-empty after trimming), `{{^key}}…{{/key}}` (only if empty); `{{FrontSide}}` on the back. An
 * empty field of a note with [DictionaryData] is filled from it: `expression`, `reading`
 * (`furigana` alignment), `meaning` (glosses joined with `; `). Likewise an empty field of a kanji
 * note with [RenderInput.kanji] is filled from [KanjiData] (C-2): `literal` ← `literal`; `keyword`
 * ← `meanings` joined with `; `; `readings` ← `onyomi` followed by `kunyomi`, joined with `、`
 * (kun readings as given, dots included). A non-empty field always wins. Unknown placeholders render
 * as `""` with warning `unknown_placeholder`.
 *
 * **Built-in templates** (front → back): vocab meaning: expression → reading, meaning ·
 * reading: expression (no furigana) → reading · listening: audio → expression, reading, meaning ·
 * production: meaning → expression, reading · pitch: expression → reading with pitch ·
 * kanji keyword: literal → keyword, readings · writing: keyword → literal (self-graded) ·
 * components: literal → notes · phrase meaning/production/listening as vocab · grammar cloze:
 * cloze sentence with blank → filled sentence, pattern, meaning · recognition: pattern → meaning ·
 * spot: sentence → highlighted span, pattern · production: meaning → pattern · sentence
 * comprehension: sentence → translation · listening: audio → sentence, translation ·
 * custom basic: front → back. Every side also shows `sentence` / `image` when present.
 *
 * **Furigana front** (ADR-042; only with [RenderInput.frontFurigana], otherwise nothing here
 * applies). For a note of base type vocab and the **built-in** templates of the kinds whose front
 * shows the expression as the question, `meaning` and `pitch`: the expression on the front is
 * rendered as one ruby over the **whole word**, `<ruby>E<rt>R</rt></ruby>`, with E = the text of
 * `expression` in `kanji` mode and R = the text of `reading` in `kana` mode (both as
 * [CardTextRenderer] resolves them, HTML-escaped), inside the wrapper the front already uses for
 * the expression. It does **not** depend on [RenderOptions.furigana] (that option is the user's
 * general furigana switch; this is part of the question) and it ignores per-kanji alignment
 * ([DictionaryData.furigana], bracket furigana in the field): always one `rt` for the word. The
 * back repeats the front unchanged (`{{FrontSide}}`), so it carries the same ruby. Not applied:
 * kind `reading` (the reading is the answer), `listening` and `production` (the expression is not
 * on the front), a custom template on that side (custom templates are rendered as written; a
 * follow-up may add a placeholder), an empty R or an R equal to E (nothing to show), and every
 * other base type. With field regions on, the region span wraps the ruby as it wraps the plain
 * value. Example (寿司, すし): `<ruby>寿司<rt>すし</rt></ruby>`; (珈琲, コーヒー): `<ruby>珈琲<rt>コーヒー</rt></ruby>`.
 *
 * **Word audio** (ADR-047, C-29). For a note of base type vocab the renderer builds one
 * *word-audio block* W from the input:
 * - `fields["audio"]` contains a `[sound:<sha256>]` ([WordAudioRules.SOUND_PATTERN]; the rule of
 *   [WordAudioResolver] step 2) → W = the `audio` field value exactly as `{{audio}}` renders it
 *   (sanitised, every sound a button, inside the field's usual wrapper span; with field regions
 *   that span is the region).
 * - else [RenderInput.wordAudio] is a hash H → W =
 *   `<button class="kb-audio" data-kb-audio="H"></button>`, bare (no wrapper span and no
 *   region: it is not a field value).
 * - else W = `""` (nothing is emitted).
 *
 * W adds no element or class of its own, so [CardClasses.ALL] is unchanged; `assets/card/` styles
 * the button by its place (`.kb-expression .kb-audio`).
 *
 * For every other base type W is `""` and nothing in this section applies.
 *
 * Where the **built-in** vocab templates put W (at most once per side):
 * - `meaning`, `reading`, `pitch` (expression on the front): FRONT has no W and is unchanged. On
 *   the BACK, W is the last child of the `kb-expression` element that `{{FrontSide}}` repeats:
 *   `<div class="kb-expression">食べる<button class="kb-audio" data-kb-audio="…"></button></div>` (after the ruby
 *   when [RenderInput.frontFurigana] applies; after the expression's wrapper span, never inside it).
 * - `production` (expression on the back): W is the last child of the back's `kb-expression` element.
 * - `listening`: the FRONT is W where `{{audio}}` used to stand (for a note with its own audio
 *   that is the same bytes as before); the BACK repeats it through `{{FrontSide}}` and its
 *   `kb-expression` element gets no second W.
 * - The backs of `meaning`, `reading`, `production` and `pitch` no longer contain a separate
 *   `{{audio}}`: the note's audio lives in W. A non-blank `audio` field that holds no sound is not
 *   shown by these templates. `{{sentence_audio}}` is untouched.
 *
 * So for a note with an empty `audio` field and with `wordAudio = null` every built-in vocab side is byte for
 * byte what it was before ADR-047. Worked examples (vocab `meaning`, 食べる, furigana and pitch
 * off), BACK up to the divider; everything except W is exactly the pre-ADR-047 output:
 * - deck-materialized note (`fields = {}`), `wordAudio = "b5bb9d8014a0f9b1d61e21e796d78dccdf1352f23cd32812f4850b878ae4944c"`:
 *   `<div class="kb-expression"><span class="kb-field-expression">食べる</span><button class="kb-audio" data-kb-audio="b5bb9d8014a0f9b1d61e21e796d78dccdf1352f23cd32812f4850b878ae4944c"></button></div>`;
 *   [RenderedCard.wordAudio] = [RenderedCard.autoplayAudio] = that hash; FRONT: no W, both null.
 * - note with `audio = "[sound:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08]"`,
 *   any `wordAudio`: the same with
 *   `<span class="kb-field-audio"><button class="kb-audio" data-kb-audio="9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"></button></span>`
 *   after the expression's span;
 *   `wordAudio` = `autoplayAudio` = `9f86…0a08`.
 * - the first note, `wordAudio = null` (not generated yet): no button; both null; the host asks
 *   `resolveSpeech` and renders again when it has the hash.
 * - vocab `listening`, first note with the hash: FRONT = W, `wordAudio` = `autoplayAudio` = hash;
 *   BACK: `wordAudio` = hash, `autoplayAudio` = null.
 *
 * **Custom templates** are rendered as written. They can place W with the placeholder
 * `{{audio:word}}` (either side, any number of times; `""` for non-vocab notes, no warning).
 * `{{audio}}` keeps its meaning (the field value).
 *
 * [RenderedCard.wordAudio] is the first `data-kb-audio` hash of the first non-empty W emitted on
 * this side, known by construction and not by scanning the HTML (null when no W was emitted,
 * including a W inside a section that is not shown; other sounds, such as `{{sentence_audio}}` or
 * a `[sound:]` typed into another field, never count);
 * [RenderedCard.autoplayAudio] as documented there, for built-in and custom templates alike.
 * [RenderedCard.audio] lists every hash as before, W's included. With field regions, `audio` is a
 * referred field at W's position (source NOTE and `rendered = true` only when W shows the field).
 *
 * **Sanitising** (applied to field values and to custom templates; the output contains nothing else):
 * allowed elements `div span p br hr b i u em strong small sub sup ruby rb rt rp ul ol li img button`;
 * allowed attributes: `class` (only classes of [CardClasses.ALL] or starting with `user-`), `lang`,
 * `img src` (in the source only `blob:<sha256>`; any other `src` drops the `img`; in the output the
 * hash is written through [RenderOptions.mediaUrlTemplate], attribute-escaped) and `alt`,
 * `button data-kb-audio` (a sha256; the host resolves it to audio). Everything else is
 * removed: other elements are unwrapped (their text kept) except `script style iframe object embed
 * svg math template`, which are removed with their content; comments removed. `[sound:<sha256>]`
 * becomes `<button class="kb-audio" data-kb-audio="<sha256>"></button>`. Text is HTML-escaped.
 *
 * **Field regions** (C-19; only with [RenderOptions.fieldRegions], otherwise nothing below applies and
 * the output is byte-identical to pre-C-19 output). A *region* is the wrapper span the renderer puts
 * around each non-empty field value. With the option on its start tag is exactly
 * `<span class="kb-field-<key>" data-field="<key>" data-field-source="note|dictionary|kanji">`
 * (attributes in this order; nothing else in the HTML changes), e.g.
 * `<div class="kb-keyword"><span class="kb-field-keyword" data-field="keyword" data-field-source="kanji">eat; food</span></div>`.
 * Invariants:
 * - Regions come only from the renderer, after sanitising. The sanitiser's allowlist is unchanged:
 *   `data-field`, `data-field-source` and `kb-field-*` classes in a field value or a custom template
 *   are dropped (`dropped_attribute`), so neither can forge, retarget or re-source a region. Every
 *   `data-field` in the output is a region of that key of this note.
 * - Regions never nest. One key may have several regions (two placeholders, or the front repeated by
 *   `{{FrontSide}}`); all carry the same source. A host that edits a field updates all of them by re-rendering.
 * - A region's content is the value as transformed by its placeholder mode (`furigana:`, `kana:`,
 *   `kanji:`, `text:`), not the stored value: an editor loads `note.fields[key]`, never the region's text.
 * - No region for an empty value: `{{key}}` of an [FieldSource.EMPTY] field still renders `""`.
 * - Not regions: `{{pitch:reading}}` and `{{cloze:…}}` output, the mnemonic, the root element.
 *
 * [RenderedCard.fields] has one entry per key *referred to* by the templates expanded for this side: a
 * value placeholder (`{{key}}` or with a `furigana:`/`kana:`/`kanji:`/`text:` prefix) or a section tag
 * (`{{#key}}`, `{{^key}}`), **including those inside sections that are not shown** (so a host can offer
 * an "add" box for a field the card would show once filled). Order: first reference in template order;
 * on the back, an expanded `{{FrontSide}}` contributes the front's entries at its position (a back
 * without it lists only the back's keys; a `{{FrontSide}}` inside a hidden section contributes none).
 * Built-in sentence/image extras count. Not referred: `{{/key}}`, `pitch:`/`cloze:` placeholders,
 * unknown prefixes, names that are not `[a-z][a-z0-9_]*`. An unknown kind gives an empty list.
 *
 * **Pitch** (when on): the reading's morae become `<span class="kb-mora kb-pitch-high|kb-pitch-low">`,
 * the mora before the drop gets `kb-pitch-drop` too; one `<span class="kb-pitch">` per pattern.
 */
public interface CardRenderer {
    /**
     * Renders one side. Never throws for any note content; malformed HTML is repaired by sanitising.
     *
     * @throws IllegalArgumentException if `options.mediaUrlTemplate` does not contain `{sha256}`
     *   exactly once (a host bug, not note content).
     */
    public fun render(input: RenderInput): RenderedCard
}

/** The CSS class vocabulary of `assets/card/` that [CardRenderer] may emit. T213 adds any missing rules to `assets/card/card.css`. */
public object CardClasses {
    /**
     * Every class the renderer emits (plus `kb-<base type>` and `kb-kind-<kind>` on the root, and
     * `kb-field-<key>` on a wrapper span around each field value).
     */
    public val ALL: List<String> = listOf(
        "kb-card", "kb-front", "kb-back", "kb-expression", "kb-reading", "kb-meaning",
        "kb-sentence", "kb-translation", "kb-keyword", "kb-literal", "kb-pattern", "kb-notes",
        "kb-image", "kb-audio", "kb-mnemonic", "kb-cloze", "kb-cloze-blank", "kb-cloze-answer",
        "kb-spot", "kb-pitch", "kb-mora", "kb-pitch-high", "kb-pitch-low", "kb-pitch-drop",
        "kb-hint", "kb-divider",
    )

    /** Prefix of classes a custom template or field may use freely (styled by `note_type.css`). */
    public const val USER_PREFIX: String = "user-"
}
