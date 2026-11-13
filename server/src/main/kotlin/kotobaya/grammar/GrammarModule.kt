package kotobaya.grammar

import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.sync.OpApply

/**
 * Grammar library CRUD (T505b), keyed by operationId. Every write is an op minted through [opApply] (CLAUDE.md rule 8).
 * `testGrammarRules`, `listGrammarExamples`, `putGrammarSpan` and `removeGrammarSpan` stay 501 (T506).
 * Wiring: `handlers += GrammarModule(db, sync.opApply).handlers`. Seeding: [GrammarSeeder].
 */
class GrammarModule(db: Database, opApply: OpApply) {
    private val api = GrammarApi(db, opApply)

    val handlers: Map<String, OperationHandler> = mapOf(
        "listGrammarPoints" to { api.list(this) },
        "createGrammarPoint" to { api.create(this) },
        "getGrammarPoint" to { api.get(this) },
        "updateGrammarPoint" to { api.update(this) },
        "deleteGrammarPoint" to { api.delete(this) },
    )
}
