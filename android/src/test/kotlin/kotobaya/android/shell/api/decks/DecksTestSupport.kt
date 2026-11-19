package kotobaya.android.shell.api.decks

import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.actions.ActionsTestSupport
import kotobaya.android.shell.api.actions.routes as actionRoutes
import kotobaya.android.shell.api.decks.routes as deckRoutes
import kotobaya.android.shell.api.identity.routes as identityRoutes
import kotobaya.android.shell.api.mining.routes as miningRoutes
import kotobaya.android.shell.api.review.routes as reviewRoutes
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.android.shell.parity.ParityScenario
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.Row

/**
 * Helpers of the SHELL-L7 tests: [ParityDevice]s over the decks, review and actions routes, seeded with the parity
 * scenario `queue-materialize-count` (device A created the user deck "Core words" [D1] with three entries
 * `e:1001010`, `e:1001150`, `e:1001580` at positions 1024, 2048, 3072; `scheduler.new_per_day` 2).
 */
internal object DecksTestSupport {
    const val T0 = ActionsTestSupport.T0
    const val X = ActionsTestSupport.X
    const val Y = ActionsTestSupport.Y
    const val B = ActionsTestSupport.B
    const val D1 = "0193a1b2-0004-7000-8000-000000000001"
    const val UNKNOWN = "0193a1b2-0004-7000-8000-0000000000ff"

    /** Two fixture entries the seed does not use. */
    const val SEQ_A = 1000470L
    const val SEQ_B = 1000110L

    /** Wall time of the seed's `deck.created` hlc (`01a1f82f1e81`). */
    val DECK_CREATED_MS: Long = java.lang.Long.parseLong("01a1f82f1e81", 16)

    fun seed(): List<JsonObject> = ParityScenario.load("queue-materialize-count").seedLog

    fun device(id: String = X, seed: List<JsonObject> = seed(), startMs: Long = T0): ParityDevice {
        Class.forName("org.sqlite.JDBC")
        return ParityDevice(id, startMs, deckRoutes + reviewRoutes + actionRoutes + identityRoutes + miningRoutes).also { if (seed.isNotEmpty()) it.pull(seed, "seed") }
    }

    fun ParityDevice.call(method: String, path: String, body: String? = null, query: String = "", at: Long? = null): ShellResponse {
        at?.let { clock.now = it }
        return request(method, path, query, body?.let { ParityJson.parse(it) })
    }

    fun ShellResponse.json(): JsonObject = ParityJson.obj(ParityJson.parse(body ?: error("no body (status $status)")))

    fun ShellResponse.items(): List<JsonObject> = (json().members["items"] as JsonArray).items.map(ParityJson::obj)

    /** `status pointer code` of a problem (`errors[0]`), or `status code` when it has no `errors`. */
    fun ShellResponse.problem(): String = with(ActionsTestSupport) { problem() }

    fun ParityDevice.rows(table: LearningTable): List<Row> = with(ActionsTestSupport) { rows(table) }

    fun ParityDevice.liveItemKeys(deckId: String): List<String> =
        rows(LearningTable.DECK_ITEM).filter { it["deck_id"] == JsonString(deckId) && (it["deleted_at"] ?: kotobaya.contracts.sync.JsonNull) == kotobaya.contracts.sync.JsonNull }
            .map { ParityJson.str(it["item_key"]) }
}
