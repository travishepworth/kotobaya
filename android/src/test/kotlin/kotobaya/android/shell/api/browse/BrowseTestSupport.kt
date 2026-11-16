package kotobaya.android.shell.api.browse

import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.actions.ActionsTestSupport
import kotobaya.android.shell.api.actions.ActionsTestSupport.json
import kotobaya.android.shell.api.actions.ActionsTestSupport.obj
import kotobaya.android.shell.api.actions.ActionsTestSupport.remote
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString

/** Helpers of the SHELL-L6 tests: a [ParityDevice] over the browse routes, seeded like the actions tests (2 notes, 2 cards). */
internal object BrowseTestSupport {
    const val VOCAB_TYPE = "00000000-0000-7000-8000-000000000101"

    fun device(): ParityDevice {
        Class.forName("org.sqlite.JDBC")
        return ParityDevice(ActionsTestSupport.X, ActionsTestSupport.T0, routes).also { it.pull(ActionsTestSupport.seed(), "seed") }
    }

    fun ParityDevice.get(path: String, query: String = ""): ShellResponse {
        clock.now = ActionsTestSupport.T0 + 10_000
        return request("GET", path, query, null)
    }

    fun uuid(prefix: Int, n: Int): String = "0193e%03x-0000-7000-8000-%012x".format(prefix, n)

    /** Ops creating notes `uuid(1, i)` each with one `meaning` card `uuid(2, i)`, for i in [from] until from + [count]. */
    fun notesAndCards(from: Int, count: Int, wall: (Int) -> Long, firstOp: Int): List<JsonObject> = (from until from + count).flatMap { i ->
        listOf(
            remote(firstOp + 2 * i, "note.created", "note", uuid(1, i), wall(i), obj("id" to JsonString(uuid(1, i)), "note_type_id" to JsonString(VOCAB_TYPE))),
            remote(
                firstOp + 2 * i + 1, "card.created", "card", uuid(2, i), wall(i) + 1,
                obj("id" to JsonString(uuid(2, i)), "note_id" to JsonString(uuid(1, i)), "kind" to JsonString("meaning")),
            ),
        )
    }

    fun ids(r: ShellResponse): List<String> = (r.json().members["items"] as JsonArray).items.map { ParityJson.str(ParityJson.obj(it).members["id"]) }

    fun next(r: ShellResponse): String? = r.json().members["next_cursor"].let { if (it == null || it == JsonNull) null else ParityJson.str(it) }
}
