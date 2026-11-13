package kotobaya.grammar

import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.sync.OpApply

/**
 * Grammar library CRUD (T505b), keyed by operationId. Every write is an op minted through [opApply] (CLAUDE.md rule 8).
 * `testGrammarRules` stays 501 until worker-parse.yaml gains the rule-test endpoint (W-6 CONTRACT_CHANGE_REQUEST).
 * Wiring: `handlers += GrammarModule(db, sync.opApply).handlers`. Seeding: [GrammarSeeder].
 */
class GrammarModule(db: Database, opApply: OpApply) {
    private val api = GrammarApi(db, opApply)
    private val examples = GrammarExamplesApi(db)
    private val spans = GrammarSpansApi(opApply)

    val handlers: Map<String, OperationHandler> = mapOf(
        "listGrammarPoints" to { api.list(this) },
        "createGrammarPoint" to { api.create(this) },
        "getGrammarPoint" to { api.get(this) },
        "updateGrammarPoint" to { api.update(this) },
        "deleteGrammarPoint" to { api.delete(this) },
        "listGrammarExamples" to { examples.list(this) },
        "putGrammarSpan" to { spans.put(this) },
        "removeGrammarSpan" to { spans.remove(this) },
    )
}
