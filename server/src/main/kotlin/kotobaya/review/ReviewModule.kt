package kotobaya.review

import kotobaya.contracts.fsrs.Scheduler
import kotobaya.contracts.queue.QueueBuilder
import kotobaya.fsrs.FsrsScheduler
import kotobaya.queue.DefaultQueueBuilder
import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.sync.OpApply

/**
 * Handlers for reviewing (T206), keyed by operationId for `AppDeps.handlers`: `getReviewQueue`, `answerReview`,
 * `undoReview`, `createDrillSession`, `getWordStatus`, `setWordStatus`, `clearWordStatus`.
 *
 * Every write is an op minted through [opApply] (CLAUDE.md rule 8); the card cache is folded by the
 * `CardCacheHook` of that same [OpApply] (`SyncConfig.cardCache = CoreCardCache()`). The queue is built by the
 * core [QueueBuilder]; this module only loads inputs. The clock is the one of `opApply.config`.
 */
class ReviewModule(
    db: Database,
    opApply: OpApply,
    scheduler: Scheduler = FsrsScheduler,
    builder: QueueBuilder = DefaultQueueBuilder,
) {
    private val clock = opApply.config.clock
    private val reviews = ReviewApi(db, opApply, scheduler, builder, clock)
    private val drills = DrillApi(db, scheduler, clock)
    private val words = WordStatusApi(db, opApply)

    val handlers: Map<String, OperationHandler> = mapOf(
        "getReviewQueue" to { reviews.queue(this) },
        "answerReview" to { reviews.answer(this) },
        "undoReview" to { reviews.undo(this) },
        "createDrillSession" to { drills.create(this) },
        "getWordStatus" to { words.get(this) },
        "setWordStatus" to { words.set(this) },
        "clearWordStatus" to { words.clear(this) },
    )
}
