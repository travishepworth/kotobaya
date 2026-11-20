package kotobaya.android.shell.api.grammar

import kotobaya.android.shell.api.LocalRoute

/** Grammar points and manual spans (LF-A8b, SHELL.md 4.3 / L-10): writes and reads from the replica. */
val routes: List<LocalRoute> = listOf(
    LocalRoute("listGrammarPoints", "GET", "/api/v1/grammar") { call -> listGrammarPoints(call) },
    LocalRoute("createGrammarPoint", "POST", "/api/v1/grammar") { call -> createGrammarPoint(call) },
    LocalRoute("getGrammarPoint", "GET", "/api/v1/grammar/{grammar_point_id}") { call -> getGrammarPoint(call) },
    LocalRoute("updateGrammarPoint", "PATCH", "/api/v1/grammar/{grammar_point_id}") { call -> updateGrammarPoint(call) },
    LocalRoute("deleteGrammarPoint", "DELETE", "/api/v1/grammar/{grammar_point_id}") { call -> deleteGrammarPoint(call) },
    LocalRoute(
        "putGrammarSpan", "PUT", "/api/v1/grammar/{grammar_point_id}/spans/{sentence_id}/{start_offset}/{end_offset}",
    ) { call -> putGrammarSpan(call) },
    LocalRoute(
        "removeGrammarSpan", "DELETE", "/api/v1/grammar/{grammar_point_id}/spans/{sentence_id}/{start_offset}/{end_offset}",
    ) { call -> removeGrammarSpan(call) },
)
