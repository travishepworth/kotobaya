package kotobaya.android.shell.parity

import kotobaya.android.shell.api.LocalRoute
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue

/** What running one scenario on the shell gave. */
sealed interface ParityOutcome {
    val id: String

    /** Not run: these of its `operations` have no registered route yet (SHELL.md 8.2: run only when all are). */
    data class Pending(override val id: String, val missing: List<String>) : ParityOutcome

    /**
     * Run. [actual] is the normalised `{id, steps}`; [problems] lists every difference from the golden as
     * `<JSON pointer>: expected …, actual …`, then failed invariants and forwarded requests. Empty = parity.
     */
    data class Ran(override val id: String, val actual: JsonObject, val problems: List<String>) : ParityOutcome {
        val matches: Boolean get() = problems.isEmpty()
    }
}

/**
 * SHELL.md 8.2 / 8.3, shell side (SHELL-P2): seed by pull, run the steps on [kotobaya.android.shell.api.LocalApi]
 * with the clock fixed per step, normalise, check the invariants and compare with
 * `fixtures/shell-parity/golden/<id>.json`.
 */
object ParityRunner {
    fun run(scenario: ParityScenario, routes: List<LocalRoute>): ParityOutcome {
        val registered = routes.map { it.operationId }.toSet()
        val missing = scenario.operations.filter { it !in registered }
        if (missing.isNotEmpty()) return ParityOutcome.Pending(scenario.id, missing)
        val actual = execute(scenario, routes)
        val problems = mutableListOf<String>()
        val golden = ParityScenario.golden(scenario.id)
        if (golden == null) {
            problems += "no golden ${scenario.id}.json in ${ParityScenario.goldenDir}"
        } else {
            problems += ParityJson.diff(golden, actual.normalised)
        }
        problems += invariantProblems(scenario, actual.normalised)
        actual.forwarded.forEach { problems += "forwarded upstream (a scenario never goes online): $it" }
        return ParityOutcome.Ran(scenario.id, actual.normalised, problems)
    }

    /** The device's raw answers (apply steps: null), normalised, plus what [LocalApi] forwarded. */
    class Execution(val raw: List<RawResponse?>, val normalised: JsonObject, val forwarded: List<String>, val device: ParityDevice)

    fun execute(scenario: ParityScenario, routes: List<LocalRoute>): Execution {
        val device = ParityDevice(scenario.deviceId, scenario.clockMs, routes)
        device.pull(scenario.seedLog, "seed")
        val raw = ArrayList<RawResponse?>()
        scenario.steps.indices.forEach { i ->
            device.clock.now = scenario.atMs(i)
            if (scenario.isApply(i)) {
                device.pull(scenario.applyOps(i), "step $i")
                raw += null
            } else {
                val r = scenario.request(i)
                val res = device.request(r.method, r.path, r.query, r.body)
                raw += RawResponse(res.status, res.headers.mapKeys { it.key.lowercase() }, res.body?.ifEmpty { null })
            }
        }
        return Execution(raw, normalise(scenario, raw), device.forwarded.toList(), device)
    }

    /** `{id, steps:[{status, headers, body} | null]}`, as SHELL-P1 writes its goldens. */
    fun normalise(scenario: ParityScenario, raw: List<RawResponse?>): JsonObject {
        val n = Normalizer(scenario.knownIds, scenario.deviceId)
        val steps = raw.mapIndexed { i, r -> if (r == null) JsonNull else n.step(r, scenario.request(i).ignore) }
        return JsonObject(linkedMapOf("id" to JsonString(scenario.id), "steps" to JsonArray(steps)))
    }

    /** `invariants[].equal`: the values at those places of the normalised bodies are equal; unresolved fails. */
    fun invariantProblems(scenario: ParityScenario, actual: JsonObject): List<String> {
        val steps = ParityJson.arr(actual.members["steps"])
        return scenario.invariants.mapIndexedNotNull { k, inv ->
            val places = ParityJson.arr(inv.members["equal"]).map { p ->
                val o = ParityJson.obj(p)
                ParityJson.long(o.members["step"]).toInt() to ParityJson.str(o.members["pointer"])
            }
            val values: List<JsonValue?> = places.map { (i, p) -> ParityJson.at((steps.getOrNull(i) as? JsonObject)?.members?.get("body"), p) }
            when {
                values.any { it == null } ->
                    "invariant $k: unresolved ${places.filterIndexed { j, _ -> values[j] == null }.joinToString { "step ${it.first} ${it.second}" }}"
                values.drop(1).any { it != values[0] } ->
                    "invariant $k: not equal: " + places.zip(values).joinToString { "step ${it.first.first} ${it.first.second}=${ParityJson.show(it.second)}" }
                else -> null
            }
        }
    }

    /** The gate's report: pending scenarios (with what they wait for), then every mismatch. */
    fun report(outcomes: List<ParityOutcome>): String = buildString {
        val pending = outcomes.filterIsInstance<ParityOutcome.Pending>()
        val ran = outcomes.filterIsInstance<ParityOutcome.Ran>()
        appendLine("shell parity: ${ran.count { it.matches }} match, ${ran.count { !it.matches }} differ, ${pending.size} pending")
        pending.forEach { appendLine("  pending ${it.id}: needs ${it.missing.joinToString()}") }
        ran.filterNot { it.matches }.forEach { r ->
            appendLine("  DIFFERS ${r.id}:")
            r.problems.forEach { appendLine("    $it") }
        }
    }
}
