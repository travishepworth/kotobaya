package kotobaya.decks

import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.sync.OpApply

/**
 * Handlers for decks and deck items (T204), keyed by operationId for `AppDeps.handlers`.
 *
 * Every write is an op minted through [opApply] (CLAUDE.md rule 8). `getDeckCoverage` is not registered: coverage
 * data is Phase 4, so it stays the spec-derived `501 not_implemented` stub. Wiring (Main.kt, not this ticket's
 * path): `handlers += DecksModule(db, sync.opApply).handlers`, with the same [OpApply] the sync module uses.
 */
class DecksModule(db: Database, opApply: OpApply) {
    private val decks = DeckApi(db, opApply)
    private val items = DeckItemApi(db, opApply)

    val handlers: Map<String, OperationHandler> = mapOf(
        "listDeckLists" to { decks.lists(this) },
        "listDecks" to { decks.list(this) },
        "createDeck" to { decks.create(this) },
        "getDeck" to { decks.get(this) },
        "updateDeck" to { decks.update(this) },
        "deleteDeck" to { decks.delete(this) },
        "listDeckItems" to { items.list(this) },
        "addDeckItems" to { items.add(this) },
        "putDeckItem" to { items.put(this) },
        "removeDeckItem" to { items.remove(this) },
    )
}
