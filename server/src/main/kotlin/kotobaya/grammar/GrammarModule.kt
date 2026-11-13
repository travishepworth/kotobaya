package kotobaya.grammar

import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.sync.OpApply

/**
 * Grammar library CRUD (T505b), keyed by operationId. Every write is an op minted through [opApply] (CLAUDE.md rule 8).
 * Create/update validate `detection_rules` and `testGrammarRules` scans stored sentences, both through [tester]
 * (worker `POST /internal/grammar/rules/test`); the op-apply path never calls it.
 * Wiring: `handlers += GrammarModule(db, sync.opApply).handlers`. Seeding: [GrammarSeeder].
 */
class GrammarModule(db: Database, opApply: OpApply, tester: RuleTesterClient = HttpRuleTesterClient(null)) {
    private val validator = RuleValidator(tester)
    private val api = GrammarApi(db, opApply, validator)
    private val ruleTests = GrammarRuleTestApi(db, tester, validator)
    private val examples = GrammarExamplesApi(db)
    private val spans = GrammarSpansApi(opApply)

    val handlers: Map<String, OperationHandler> = mapOf(
        "listGrammarPoints" to { api.list(this) },
        "createGrammarPoint" to { api.create(this) },
        "getGrammarPoint" to { api.get(this) },
        "updateGrammarPoint" to { api.update(this) },
        "deleteGrammarPoint" to { api.delete(this) },
        "testGrammarRules" to { ruleTests.test(this) },
        "listGrammarExamples" to { examples.list(this) },
        "putGrammarSpan" to { spans.put(this) },
        "removeGrammarSpan" to { spans.remove(this) },
    )
}
