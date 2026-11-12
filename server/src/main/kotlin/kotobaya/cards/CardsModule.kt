package kotobaya.cards

import kotobaya.cards.bulk.BulkApi
import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.sync.OpApply

/**
 * Handlers for cards (T210a), keyed by operationId for `AppDeps.handlers`: `listCards`, `createCard`, `getCard`,
 * `updateCard`, `deleteCard`, `listCardReviews`, `bulkCards`, `bulkScheduleCards`.
 *
 * Every write is an op minted through [opApply] (CLAUDE.md rule 8); the card cache is folded by that OpApply's
 * `CardCacheHook`. Wiring (Main.kt): `CardsModule(db, sync.opApply).handlers`.
 */
class CardsModule(db: Database, opApply: OpApply, basePath: String = "/api/v1") {
    private val clock = opApply.config.clock
    private val cards = CardApi(db, opApply, clock, basePath)
    private val bulk = BulkApi(db, opApply, clock)

    val handlers: Map<String, OperationHandler> = mapOf(
        "listCards" to { cards.list(this) },
        "createCard" to { cards.create(this) },
        "getCard" to { cards.get(this) },
        "updateCard" to { cards.update(this) },
        "deleteCard" to { cards.delete(this) },
        "listCardReviews" to { cards.reviews(this) },
        "bulkCards" to { bulk.bulk(this) },
        "bulkScheduleCards" to { bulk.schedule(this) },
    )
}
