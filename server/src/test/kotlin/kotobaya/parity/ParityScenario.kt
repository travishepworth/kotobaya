package kotobaya.parity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotobaya.server.json
import java.io.File

/** One `contracts/api/shell/parity/<id>.json` (schema `parity.schema.json`, SHELL.md 8.1), plus its raw text. */
class ParityScenario(val text: String) {
    val root: ObjectNode = json.readTree(text) as ObjectNode
    val id: String = root["id"].asText()
    val clockMs: Long = root["clock_ms"].asLong()
    val deviceId: String = root["device_id"].asText()
    val seedDeviceId: String = root["seed"]["device_id"].asText()
    val seedLog: List<JsonNode> = root["seed"]["log"].toList()
    val steps: List<JsonNode> = root["steps"].toList()
    val invariants: List<JsonNode> = root["invariants"]?.toList().orEmpty()

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
    }
}
