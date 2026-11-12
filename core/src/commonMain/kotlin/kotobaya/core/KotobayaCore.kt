package kotobaya.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotobaya.contracts.js.CoreJs
import kotobaya.contracts.js.JsCardSchedule
import kotobaya.contracts.js.JsCompletion
import kotobaya.contracts.js.JsIntervalPreview
import kotobaya.contracts.js.JsPreset
import kotobaya.contracts.js.JsRenderInput
import kotobaya.contracts.js.JsRenderedCard
import kotobaya.contracts.js.JsSearchError
import kotobaya.contracts.js.JsSearchParseResult
import kotobaya.contracts.js.JsSearchToken
import kotobaya.contracts.js.JsSearchVocabulary
import kotobaya.contracts.search.SearchVocabulary
import kotobaya.search.DefaultSearchQueryParser
import kotobaya.search.searchTokens

/** The web entry point: one object whose methods are filled in by their tickets. */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun kotobayaCore(): CoreJs = CoreJsImpl

internal object CoreJsImpl : CoreJs {
    override fun parseSearch(query: String): JsSearchParseResult {
        val result = DefaultSearchQueryParser.parse(query)
        val errors = result.errors.map {
            JsSearchError(it.code.name.lowercase(), it.span.start, it.span.end, it.messageKey)
        }
        val tokens = searchTokens(query, result.errors).map { JsSearchToken(it.kind, it.start, it.end) }
        val canonical = result.query?.let { DefaultSearchQueryParser.format(it) }
        return JsSearchParseResult(result.errors.isEmpty(), errors.toTypedArray(), tokens.toTypedArray(), canonical)
    }

    override fun completeSearch(query: String, cursor: Int, vocabulary: JsSearchVocabulary, limit: Int): Array<JsCompletion> {
        val vocab = SearchVocabulary(
            vocabulary.deckNames.toList(),
            vocabulary.tags.toList(),
            vocabulary.cardKinds.toList(),
            vocabulary.noteTypeNames.toList(),
            vocabulary.presetNames.toList(),
            vocabulary.fieldKeys.toList(),
        )
        return DefaultSearchQueryParser.complete(query, cursor, vocab, limit).map {
            JsCompletion(it.insert, it.label, it.kind.name.lowercase(), it.replaceStart, it.replaceEnd)
        }.toTypedArray()
    }

    override fun renderCard(input: JsRenderInput): JsRenderedCard = throw NotImplementedError("renderCard (T213)")

    override fun previewIntervals(
        schedule: JsCardSchedule,
        preset: JsPreset,
        nowMs: Double,
        reviewId: String?,
        utcOffsetMin: Int?,
    ): Array<JsIntervalPreview> = throw NotImplementedError("previewIntervals (T107b)")
}
