package kotobaya.confusables

import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.sync.OpApply

/** Confusables API (T502). Wiring: `handlers += ConfusableModule(db, sync.opApply).handlers`; suggestions: [ConfusableSuggester]. */
class ConfusableModule(db: Database, opApply: OpApply) {
    private val api = ConfusableApi(db, opApply)

    val handlers: Map<String, OperationHandler> = mapOf(
        "listWordConfusables" to { api.listForWord(this) },
        "listConfusables" to { api.list(this) },
        "setConfusable" to { api.set(this) },
    )
}
