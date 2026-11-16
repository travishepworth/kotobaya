package kotobaya.parity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotobaya.server.json
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SHELL-P1: the server is the parity oracle (SHELL.md 8). Every scenario in `contracts/api/shell/parity` is run against
 * the real server (8.2), normalised (8.3), checked against its `expect` and `invariants`, and compared with
 * `fixtures/shell-parity/golden/<id>.json`. `RECORD=1` writes the goldens instead of comparing.
 */
class ParityGoldenTest {
    private val record = System.getenv("RECORD") == "1"

    @TestFactory
    fun everyScenarioHasAGoldenAndTheServerReproducesIt(): List<DynamicTest> {
        val files = ParityScenario.files()
        assertTrue(files.isNotEmpty(), "no scenarios in ${ParityScenario.scenarioDir}")
        val executed = AtomicInteger()
        return files.map { f ->
            DynamicTest.dynamicTest(f.name.removeSuffix(".json")) {
                runScenario(ParityScenario.load(f))
                executed.incrementAndGet()
            }
        } + DynamicTest.dynamicTest("executed scenarios = scenario files") {
            assertEquals(files.size, executed.get(), "every scenario file must run; none may be skipped")
        }
    }

    @Test
    fun everyGoldenBelongsToAScenario() {
        if (record) return
        val ids = ParityScenario.files().map { it.name }.toSet()
        val goldens = ParityScenario.goldenDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().map { it.name }.toSet()
        assertEquals(ids, goldens, "goldens must match scenario files one to one")
    }

    @Test
    fun aRejectedSeedOpFailsTheScenarioLoudly() {
        // answer-retry-same-id without its first note.created: the card.created that follows is `unknown_reference`.
        val base = ParityScenario.load(File(ParityScenario.scenarioDir, "answer-retry-same-id.json"))
        val root = base.root.deepCopy()
        val log = root["seed"]["log"] as com.fasterxml.jackson.databind.node.ArrayNode
        log.remove(0)
        val broken = ParityScenario(json.writeValueAsString(root))
        val e = assertFailsWith<SeedRejected> { ParityServerHarness.run(broken) }
        assertTrue("0193a1b2-00ff-7009-8000-000000000002" in (e.message ?: ""), e.message)
    }

    private fun runScenario(s: ParityScenario) {
        val raw = ParityServerHarness.run(s)
        val actual = normalise(s, raw)
        val problems = checkExpect(s, actual) + checkInvariants(s, actual)
        val golden = File(ParityScenario.goldenDir, "${s.id}.json")
        if (record) {
            if (problems.isNotEmpty()) fail("${s.id}: not recorded, the server contradicts the scenario:\n" + problems.joinToString("\n"))
            golden.parentFile.mkdirs()
            golden.writeText(json.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n")
            return
        }
        if (!golden.isFile) fail("${s.id}: no golden at $golden (run with RECORD=1)")
        val expected = json.readTree(golden)
        if (!Normalizer.jsonEqual(expected, actual)) {
            fail("${s.id}: the server's answers differ from the golden\nexpected: $expected\nactual:   $actual")
        }
        if (problems.isNotEmpty()) fail("${s.id}:\n" + problems.joinToString("\n"))
    }

    /** `{id, steps:[{status, headers, body} | null]}`. */
    private fun normalise(s: ParityScenario, raw: List<RawResponse?>): ObjectNode {
        val n = Normalizer(s.knownIds, ParityServerHarness.SERVER_DEVICE)
        val steps = JsonNodeFactory.instance.arrayNode()
        raw.forEachIndexed { i, r ->
            if (r == null) {
                steps.add(NullNode.instance)
            } else {
                val ignore = s.steps[i]["ignore"]?.map { it.asText() }.orEmpty()
                steps.add(n.step(r, ignore))
            }
        }
        return JsonNodeFactory.instance.objectNode().apply {
            put("id", s.id)
            set<JsonNode>("steps", steps)
        }
    }

    private fun checkExpect(s: ParityScenario, actual: ObjectNode): List<String> {
        val out = mutableListOf<String>()
        s.steps.forEachIndexed { i, step ->
            val e = step["expect"] ?: return@forEachIndexed
            val got = actual["steps"][i]
            val body = got["body"]
            fun miss(what: String) { out += "step $i: $what" }
            if (got["status"].asInt() != e["status"].asInt()) miss("status expected ${e["status"]}, actual ${got["status"]}")
            e["problem_code"]?.let { if (body?.get("code")?.asText() != it.asText()) miss("problem_code expected $it, actual ${body?.get("code")}") }
            e["field_error"]?.let { fe ->
                val ok = body?.get("errors")?.any { err ->
                    err["pointer"]?.asText() == fe["pointer"].asText() && (fe["code"] == null || err["code"]?.asText() == fe["code"].asText())
                } == true
                if (!ok) miss("field_error expected $fe, actual errors ${body?.get("errors")}")
            }
            e["body"]?.fields()?.forEach { (p, v) ->
                val at = Normalizer.at(body, p)
                if (!Normalizer.jsonEqual(at, v)) miss("$p expected $v, actual $at")
            }
            e["lengths"]?.fields()?.forEach { (p, v) ->
                val at = Normalizer.at(body, p)
                if (at == null || !at.isArray || at.size() != v.asInt()) miss("length of $p expected $v, actual ${at?.size()}")
            }
            e["absent"]?.forEach { p ->
                val at = Normalizer.at(body, p.asText())
                if (at != null) miss("${p.asText()} expected absent, actual $at")
            }
        }
        return out
    }

    private fun checkInvariants(s: ParityScenario, actual: ObjectNode): List<String> = s.invariants.mapIndexedNotNull { k, inv ->
        val places = inv["equal"].map { it["step"].asInt() to it["pointer"].asText() }
        val values = places.map { (i, p) -> Normalizer.at(actual["steps"][i]?.get("body"), p) }
        when {
            values.any { it == null } -> "invariant $k: unresolved ${places.filterIndexed { j, _ -> values[j] == null }}"
            values.drop(1).any { !Normalizer.jsonEqual(values[0], it) } -> "invariant $k: not equal: " + places.zip(values).joinToString { "${it.first}=${it.second}" }
            else -> null
        }
    }
}
