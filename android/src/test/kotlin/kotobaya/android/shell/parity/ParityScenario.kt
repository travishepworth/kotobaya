package kotobaya.android.shell.parity

import java.io.File
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonValue

/** One request step of a scenario (`parity.schema.json` `request_step`). */
data class RequestStep(
    val atMs: Long,
    val method: String,
    val path: String,
    val query: String,
    /** The body as a JSON value; null for none. */
    val body: JsonValue?,
    val ignore: List<String>,
)

/** One `contracts/api/shell/parity/<id>.json` (schema `parity.schema.json`, SHELL.md 8.1), plus its raw text. */
class ParityScenario(val text: String) {
    val root: JsonObject = ParityJson.obj(ParityJson.parse(text))
    private fun m(name: String): JsonValue? = root.members[name]

    val id: String = ParityJson.str(m("id"))
    val clockMs: Long = ParityJson.long(m("clock_ms"))

    /** The device under test: the store's `sync_state.device_id`, the minting device of the steps. */
    val deviceId: String = ParityJson.str(m("device_id"))
    val seedDeviceId: String = ParityJson.str(ParityJson.obj(m("seed")).members["device_id"])
    val seedLog: List<JsonObject> = ParityJson.arr(ParityJson.obj(m("seed")).members["log"]).map { ParityJson.obj(it) }
    val operations: List<String> = ParityJson.arr(m("operations")).map { ParityJson.str(it) }
    val dictEntries: List<Long> = ParityJson.arr(m("dict_entries")).map { ParityJson.long(it) }
    val dictMissing: List<Long> = m("dict_missing")?.let { v -> ParityJson.arr(v).map { ParityJson.long(it) } }.orEmpty()
    val steps: List<JsonObject> = ParityJson.arr(m("steps")).map { ParityJson.obj(it) }
    val invariants: List<JsonObject> = m("invariants")?.let { v -> ParityJson.arr(v).map { ParityJson.obj(it) } }.orEmpty()

    fun isApply(i: Int): Boolean = "apply" in steps[i].members

    fun atMs(i: Int): Long = ParityJson.long(steps[i].members["at_ms"])

    fun applyOps(i: Int): List<JsonObject> = ParityJson.arr(steps[i].members["apply"]).map { ParityJson.obj(it) }

    fun request(i: Int): RequestStep {
        val s = steps[i].members
        val r = ParityJson.obj(s["request"]).members
        return RequestStep(
            atMs = atMs(i),
            method = ParityJson.str(r["method"]),
            path = ParityJson.str(r["path"]),
            query = ParityJson.str(r["query"]),
            body = r["body"]?.takeIf { it != JsonNull },
            ignore = s["ignore"]?.let { v -> ParityJson.arr(v).map { ParityJson.str(it) } }.orEmpty(),
        )
    }

    /** SHELL.md 8.3 rule 5: every UUID that appears anywhere in the scenario file is "known". */
    val knownIds: Set<String> = Normalizer.UUID.findAll(text).map { it.value }.toSet()

    companion object {
        val repoRoot: File by lazy {
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .first { File(it, "contracts/api/shell/parity").isDirectory }
        }
        val scenarioDir: File get() = File(repoRoot, "contracts/api/shell/parity")
        val goldenDir: File get() = File(repoRoot, "fixtures/shell-parity/golden")

        fun files(): List<File> = scenarioDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }

        fun load(file: File): ParityScenario = ParityScenario(file.readText()).also {
            require(it.id == file.name.removeSuffix(".json")) { "${file.name}: id ${it.id} is not the file name" }
        }

        fun load(id: String): ParityScenario = load(File(scenarioDir, "$id.json"))

        fun all(): List<ParityScenario> = files().map { load(it) }

        /** The golden of [id] (`{id, steps:[{status, headers, body} | null]}`), or null when there is none. */
        fun golden(id: String): JsonObject? =
            File(goldenDir, "$id.json").takeIf { it.isFile }?.let { ParityJson.obj(ParityJson.parse(it.readText())) }
    }
}
