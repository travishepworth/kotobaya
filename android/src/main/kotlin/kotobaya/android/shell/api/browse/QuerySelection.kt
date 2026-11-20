package kotobaya.android.shell.api.browse

import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.ShellServices
import kotobaya.android.shell.api.actions.CardQuery
import kotobaya.android.shell.api.actions.CardQueryResult
import kotobaya.android.shell.api.get

/**
 * The cards a search `query` selects on the device (SHELL.md 4.3, C-31: every query is evaluated locally), live ids
 * ascending, for `listCards` and the `query` source of `createDrillSession`. A query the parser refuses is `422
 * invalid_query` (thrown). Null only when no [CardQuery] is bound, or the bound one is not the device evaluator
 * ([kotobaya.android.search.ReplicaCardQuery], which never forwards) and asked to forward: a wiring error the
 * caller answers with its fall-through.
 */
internal fun ShellServices.cardsMatching(query: String, nowMs: Long): List<String>? {
    val selector = runCatching { get<CardQuery>() }.getOrNull() ?: return null
    return when (val r = selector.select(query, nowMs)) {
        is CardQueryResult.Invalid -> throw ProblemException(invalidQuery(r.errors))
        is CardQueryResult.Ids -> r.ids
        is CardQueryResult.Forward -> null
    }
}
