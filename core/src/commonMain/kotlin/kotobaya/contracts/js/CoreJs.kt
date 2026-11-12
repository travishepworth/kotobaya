@file:OptIn(ExperimentalJsExport::class)

package kotobaya.contracts.js

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

/*
 * The web's view of the core (ADR-002): exactly the declarations in this file are @JsExport-ed for
 * the facade, and nothing else of `kotobaya.contracts` is. Signatures use only JS-friendly types:
 * String, Boolean, Int (JS number, 32-bit), Double (JS number), nullable variants (`T | null`),
 * `Array<T>` (JS arrays) and the exported classes below. No Long, List, Map or Set.
 *
 * Instants cross the boundary as Double milliseconds since the epoch (exact up to 2^53). A value
 * that is not an integer, or is negative, makes the call throw a JS Error.
 *
 * ES modules flatten packages, so every exported name is unique across the core and prefixed `Js`
 * (except CoreJs). The instance is obtained from a top-level function that the implementing ticket
 * adds next to `coreVersion()` in package `kotobaya.core`:
 *
 *     @JsExport public fun kotobayaCore(): CoreJs
 *
 * and the web uses `import { kotobayaCore } from '@kotobaya/core'`. Methods whose implementation
 * has not landed yet throw `NotImplementedError` (a JS Error).
 */

/** The web facade. Every method is pure and synchronous and delegates to the common interfaces named in its KDoc. */
@JsExport
public interface CoreJs {
    /** `SearchQueryParser.parse` + tokens for highlighting + `SearchQueryParser.format` when valid. */
    public fun parseSearch(query: String): JsSearchParseResult

    /** `SearchQueryParser.complete`. [cursor] is a UTF-16 offset (JS string index). */
    public fun completeSearch(query: String, cursor: Int, vocabulary: JsSearchVocabulary, limit: Int): Array<JsCompletion>

    /** `CardRenderer.render`. */
    public fun renderCard(input: JsRenderInput): JsRenderedCard

    /**
     * `Scheduler.preview` for the card's current [schedule] under [preset] (`Scheduler.paramsOf`
     * semantics: optimizer weights used iff present and of the supported FSRS version). Four entries,
     * ratings 1–4. Pass the review id the answer will use as [reviewId] to get fuzzed previews
     * that match the real result exactly; null = unfuzzed.
     */
    public fun previewIntervals(
        schedule: JsCardSchedule,
        preset: JsPreset,
        nowMs: Double,
        reviewId: String?,
        utcOffsetMin: Int?,
    ): Array<JsIntervalPreview>
}

/** Result of [CoreJs.parseSearch]. */
@JsExport
public class JsSearchParseResult(
    /** True iff there are no errors. */
    public val ok: Boolean,
    /** Errors in source order; empty iff [ok]. */
    public val errors: Array<JsSearchError>,
    /** Every token of the query in source order (whitespace excluded), also for invalid queries. */
    public val tokens: Array<JsSearchToken>,
    /** `SearchQueryParser.format` of the tree when [ok], else null. */
    public val canonical: String?,
)

/** A `SearchError`. */
@JsExport
public class JsSearchError(
    /** `SearchErrorCode` name in lower snake case, e.g. `unknown_key`. */
    public val code: String,
    /** UTF-16 start. */
    public val start: Int,
    /** UTF-16 end (exclusive). */
    public val end: Int,
    /** i18n key `search.error.<code>`. */
    public val messageKey: String,
)

/** A token for syntax highlighting. */
@JsExport
public class JsSearchToken(
    /** One of `key` (incl. its `:` or operator), `value`, `text`, `operator` (`or`/`and`), `negation`, `paren`, `error`. */
    public val kind: String,
    /** UTF-16 start. */
    public val start: Int,
    /** UTF-16 end (exclusive). */
    public val end: Int,
)

/** `SearchVocabulary`. */
@JsExport
public class JsSearchVocabulary(
    /** Live deck names. */
    public val deckNames: Array<String>,
    /** Tags in use. */
    public val tags: Array<String>,
    /** Card kinds in use. */
    public val cardKinds: Array<String>,
    /** Live note type names. */
    public val noteTypeNames: Array<String>,
    /** Live preset names. */
    public val presetNames: Array<String>,
    /** Field keys of live note types. */
    public val fieldKeys: Array<String>,
)

/** `SearchCompletion`. */
@JsExport
public class JsCompletion(
    /** Text to insert. */
    public val insert: String,
    /** Text to show. */
    public val label: String,
    /** `key`, `value` or `operator`. */
    public val kind: String,
    /** UTF-16 start of the replaced range. */
    public val replaceStart: Int,
    /** UTF-16 end of the replaced range (exclusive). */
    public val replaceEnd: Int,
)

/** One note field. */
@JsExport
public class JsField(
    /** Field key. */
    public val key: String,
    /** HTML fragment value. */
    public val value: String,
)

/** `TemplateInfo`. */
@JsExport
public class JsTemplate(
    /** Card kind. */
    public val kind: String,
    /** Created by default. */
    public val isDefault: Boolean,
    /** Custom front HTML or null (built-in). */
    public val front: String?,
    /** Custom back HTML or null (built-in). */
    public val back: String?,
)

/** `NoteTypeInfo`. */
@JsExport
public class JsNoteType(
    /** `vocab`, `kanji`, `phrase`, `grammar`, `sentence` or `custom`. */
    public val baseType: String,
    /** Field keys in order. */
    public val fieldKeys: Array<String>,
    /** Templates in order. */
    public val templates: Array<JsTemplate>,
    /** Extra CSS. */
    public val css: String,
)

/** `FuriganaSegment`. */
@JsExport
public class JsFurigana(
    /** Surface text. */
    public val text: String,
    /** Reading, or null for kana. */
    public val reading: String?,
)

/** `DictionaryData`. */
@JsExport
public class JsDictionary(
    /** Headword. */
    public val expression: String,
    /** Kana reading. */
    public val reading: String,
    /** Furigana alignment. */
    public val furigana: Array<JsFurigana>,
    /** Glosses. */
    public val glosses: Array<String>,
    /** Pitch downstep positions. */
    public val pitchDrops: Array<Int>,
)

/** `ClozeSentence`. */
@JsExport
public class JsCloze(
    /** Sentence id or null. */
    public val sentenceId: String?,
    /** Sentence text. */
    public val text: String,
    /** UTF-16 start of the span. */
    public val start: Int,
    /** UTF-16 end of the span (exclusive). */
    public val end: Int,
    /** Translation or null. */
    public val translation: String?,
)

/** `RenderInput`, flattened. */
@JsExport
public class JsRenderInput(
    /** The note type. */
    public val noteType: JsNoteType,
    /** Card kind. */
    public val kind: String,
    /** Note fields (duplicate keys: the last wins). */
    public val fields: Array<JsField>,
    /** Note mnemonic. */
    public val mnemonic: String,
    /** Dictionary data or null. */
    public val dictionary: JsDictionary?,
    /** Cloze sentence or null. */
    public val cloze: JsCloze?,
    /** `front` or `back`. */
    public val side: String,
    /** Show furigana. */
    public val furigana: Boolean,
    /** Show pitch accent. */
    public val pitch: Boolean,
    /** Show the mnemonic. */
    public val showMnemonic: Boolean,
    /** `RenderOptions.mediaUrlTemplate`, e.g. `/api/v1/media/{sha256}`. */
    public val mediaUrlTemplate: String,
)

/** `RenderedCard`. */
@JsExport
public class JsRenderedCard(
    /** Sanitised HTML fragment. */
    public val html: String,
    /** Audio blob hashes in document order. */
    public val audio: Array<String>,
    /** Note type CSS to append. */
    public val extraCss: String,
    /** Warning i18n keys. */
    public val warnings: Array<String>,
)

/** `CardSchedule` (the card's cache columns as the API returns them). */
@JsExport
public class JsCardSchedule(
    /** `new`, `learning`, `review` or `relearning`. */
    public val state: String,
    /** Step or null. */
    public val step: Int?,
    /** Stability or null. */
    public val stability: Double?,
    /** Difficulty or null. */
    public val difficulty: Double?,
    /** Due instant (ms) or null. */
    public val dueAtMs: Double?,
    /** Interval days or null. */
    public val intervalDays: Int?,
    /** Last review instant (ms) or null. */
    public val lastReviewAtMs: Double?,
    /** Reps. */
    public val reps: Int,
    /** Lapses. */
    public val lapses: Int,
)

/** `PresetRow` subset (the card's effective preset). */
@JsExport
public class JsPreset(
    /** `desired_retention`. */
    public val desiredRetention: Double,
    /** `maximum_interval_days`. */
    public val maximumIntervalDays: Int,
    /** `learning_steps_s`. */
    public val learningStepsSeconds: Array<Int>,
    /** `relearning_steps_s`. */
    public val relearningStepsSeconds: Array<Int>,
    /** `fuzz`. */
    public val fuzz: Boolean,
    /** `easy_days` (7, Monday first). */
    public val easyDays: Array<Double>,
    /** `fsrs_version` or null. */
    public val fsrsVersion: String?,
    /** `weights` or null. */
    public val weights: Array<Double>?,
    /** `weights_version`. */
    public val weightsVersion: Int,
)

/** One `RatingOutcome`. */
@JsExport
public class JsIntervalPreview(
    /** Rating 1–4. */
    public val rating: Int,
    /** Due instant after this rating (ms). */
    public val dueAtMs: Double,
    /** `dueAtMs − nowMs`; the web formats it through i18n. */
    public val intervalMs: Double,
    /** State after this rating. */
    public val state: String,
)
