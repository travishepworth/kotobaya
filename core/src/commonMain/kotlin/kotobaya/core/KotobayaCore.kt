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
import kotobaya.contracts.Reserved
import kotobaya.contracts.fsrs.CardSchedule
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.PresetRow
import kotobaya.contracts.search.SearchVocabulary
import kotobaya.fsrs.FsrsScheduler
import kotobaya.search.DefaultSearchQueryParser
import kotobaya.search.searchTokens
import kotlin.math.floor

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
    ): Array<JsIntervalPreview> {
        val current = CardSchedule(
            state = CardState.entries.firstOrNull { it.wire == schedule.state }
                ?: throw IllegalArgumentException("unknown card state: ${schedule.state}"),
            step = schedule.step,
            stability = schedule.stability,
            difficulty = schedule.difficulty,
            dueAt = schedule.dueAtMs?.let { instant(it) },
            intervalDays = schedule.intervalDays,
            lastReviewAt = schedule.lastReviewAtMs?.let { instant(it) },
            reps = schedule.reps,
            lapses = schedule.lapses,
        )
        val row = PresetRow(
            id = Reserved.DEFAULT_PRESET_ID, // not read by paramsOf
            desiredRetention = preset.desiredRetention,
            maximumIntervalDays = preset.maximumIntervalDays,
            learningStepsSeconds = preset.learningStepsSeconds.toList(),
            relearningStepsSeconds = preset.relearningStepsSeconds.toList(),
            fuzz = preset.fuzz,
            easyDays = preset.easyDays.toList(),
            fsrsVersion = preset.fsrsVersion,
            weights = preset.weights?.toList(),
            weightsVersion = preset.weightsVersion,
            deleted = false,
        )
        val now = instant(nowMs)
        return FsrsScheduler.preview(current, now, FsrsScheduler.paramsOf(row), reviewId, utcOffsetMin).map {
            JsIntervalPreview(
                rating = it.rating.value,
                dueAtMs = (it.after.dueAt ?: now).toDouble(),
                intervalMs = it.intervalMs.toDouble(),
                state = it.after.state.wire,
            )
        }.toTypedArray()
    }

    /** An instant crossing the JS boundary: a non-negative integral number of ms (exact up to 2^53). */
    private fun instant(ms: Double): Long {
        require(ms >= 0.0 && ms <= MAX_SAFE_INTEGER && floor(ms) == ms) { "not an instant in ms: $ms" }
        return ms.toLong()
    }

    private const val MAX_SAFE_INTEGER: Double = 9007199254740991.0
}
