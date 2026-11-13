package kotobaya.acceptance.offline

import java.io.File
import kotlin.random.Random

/** One thing that happens in the simulated week. */
enum class Kind {
    // user actions on a device
    REVIEW, UNDO, ADD_WORD, ADD_KANJI, EDIT_NOTE, DELETE_NOTE, DELETE_CARD, CARD_STATE, WORD_STATUS, DECK, PRESET, SETTINGS,

    // connectivity and sync of a device
    SYNC, PUSH_ONLY, GO_OFFLINE, GO_ONLINE, LOSE_PUSH_RESPONSE, FAIL_PUSH, LOSE_PULL_RESPONSE, KILL_MID_PUSH, KILL_AT_COMMIT,

    // the device's clock
    CLOCK_AHEAD, CLOCK_BEHIND, CLOCK_SMALL_JUMP, CLOCK_FIX,

    // the server
    BACKUP, RESTORE, SERVER_IMPORT, SERVER_PUBLISH,
}

/** A step of the trace: at true time [at], on device [dev] (-1 = the server), with its own random seed [r]. */
data class Step(val at: Long, val dev: Int, val kind: Kind, val r: Long) {
    override fun toString(): String {
        val t = at - WEEK_START
        val who = if (dev < 0) "server" else "dev${dev}"
        return "day %d %02d:%02d:%02d %-6s %-18s r=%d".format(t / DAY, t % DAY / HOUR, t % HOUR / MINUTE, t % MINUTE / 1000, who, kind, r)
    }
}

/** Per-run device setup (not shrunk). */
data class DeviceSetup(val skew: Long, val driftPerDay: Long, val airplane: Boolean, val legacy: Boolean)

/** A run: devices and the trace. Everything is derived from [seed]. */
data class Plan(val seed: Int, val devices: List<DeviceSetup>, val pageSize: Int, val steps: List<Step>)

/** Result of executing a [Plan]. */
class RunResult(val plan: Plan, val violations: List<String>, val stats: Stats, val anomalies: Long, val serverKind: String, val failedAt: Int?)

object Chaos {
    val DEVICE_IDS: List<String> = listOf(
        "01931f3e-0000-7000-8000-0000000000a1",
        "01931f3e-0000-7000-8000-0000000000b2",
        "01931f3e-0000-7000-8000-0000000000c3",
    )

    /** The randomised offline week of seed [seed]. */
    fun plan(seed: Int): Plan {
        val rnd = Random(seed * 7919L + 13)
        val n = if (rnd.nextInt(4) == 0) 3 else 2
        val legacyDev = if (rnd.nextInt(7) == 0) rnd.nextInt(n) else -1
        val devices = (0 until n).map { i ->
            DeviceSetup(
                skew = rnd.nextLong(-10 * MINUTE, 10 * MINUTE + 1),
                driftPerDay = rnd.nextLong(-60_000, 60_001),
                airplane = i == 0 && rnd.nextInt(5) < 3,
                legacy = i == legacyDev,
            )
        }
        val steps = ArrayList<Step>()
        fun add(at: Long, dev: Int, kind: Kind) {
            steps += Step(at, dev, kind, rnd.nextLong(1, Long.MAX_VALUE))
        }
        for (i in 0 until n) if (devices[i].airplane) add(WEEK_START + 1, i, Kind.GO_OFFLINE)
        // day 0 morning: every device has synced once before the week (common starting point)
        for (i in 0 until n) {
            add(WEEK_START + 6 * HOUR + i * MINUTE, i, Kind.ADD_WORD)
            if (!devices[i].airplane) add(WEEK_START + 6 * HOUR + 30 * MINUTE + i * MINUTE, i, Kind.SYNC)
        }
        for (day in 0 until 7) {
            val base = WEEK_START + day * DAY
            add(base + 3 * HOUR, -1, Kind.BACKUP)
            for (i in 0 until n) {
                repeat(1 + rnd.nextInt(3)) {
                    var t = base + rnd.nextLong(7 * HOUR, 23 * HOUR)
                    if (!devices[i].airplane && rnd.nextInt(4) == 0) add(t++, i, if (rnd.nextBoolean()) Kind.GO_OFFLINE else Kind.GO_ONLINE)
                    repeat(4 + rnd.nextInt(14)) {
                        t += rnd.nextLong(5_000, 90_000)
                        add(t, i, action(rnd))
                    }
                    if (!devices[i].airplane && rnd.nextInt(5) < 3) {
                        t += rnd.nextLong(1_000, 60_000)
                        when (rnd.nextInt(10)) {
                            0 -> add(t++, i, Kind.LOSE_PUSH_RESPONSE)
                            1 -> add(t++, i, Kind.FAIL_PUSH)
                            2 -> add(t++, i, Kind.LOSE_PULL_RESPONSE)
                            3 -> add(t++, i, Kind.KILL_MID_PUSH)
                            4 -> add(t++, i, Kind.KILL_AT_COMMIT)
                            else -> Unit
                        }
                        add(t, i, if (rnd.nextInt(8) == 0) Kind.PUSH_ONLY else Kind.SYNC)
                    }
                }
            }
            // a large clock jump on some days, fixed some hours or days later
            if (rnd.nextInt(4) == 0) {
                val i = rnd.nextInt(n)
                val t = base + rnd.nextLong(6 * HOUR, 20 * HOUR)
                add(t, i, listOf(Kind.CLOCK_AHEAD, Kind.CLOCK_AHEAD, Kind.CLOCK_BEHIND, Kind.CLOCK_SMALL_JUMP).random(rnd))
                add(t + rnd.nextLong(2 * HOUR, 3 * DAY), i, Kind.CLOCK_FIX)
            }
        }
        if (rnd.nextInt(5) < 2) add(WEEK_START + rnd.nextLong(2 * DAY, 6 * DAY), -1, Kind.RESTORE)
        repeat(rnd.nextInt(3)) { add(WEEK_START + rnd.nextLong(0, 7 * DAY), -1, Kind.SERVER_IMPORT) }
        if (rnd.nextInt(5) < 2) add(WEEK_START + rnd.nextLong(DAY, 7 * DAY), -1, Kind.SERVER_PUBLISH)
        steps.sortBy { it.at }
        return Plan(seed, devices, pageSize = listOf(500, 500, 37, 7).random(rnd), steps = steps)
    }

    private fun action(rnd: Random): Kind {
        val x = rnd.nextInt(100)
        return when {
            x < 52 -> Kind.REVIEW
            x < 57 -> Kind.UNDO
            x < 67 -> Kind.ADD_WORD
            x < 69 -> Kind.ADD_KANJI
            x < 75 -> Kind.EDIT_NOTE
            x < 76 -> Kind.DELETE_NOTE
            x < 77 -> Kind.DELETE_CARD
            x < 82 -> Kind.CARD_STATE
            x < 86 -> Kind.WORD_STATUS
            x < 92 -> Kind.DECK
            x < 97 -> Kind.PRESET
            else -> Kind.SETTINGS
        }
    }

    /** Executes [plan] against a fresh server; never throws for a simulation failure (it is a violation). */
    fun execute(plan: Plan): RunResult {
        val server = SimServer.create(WEEK_START, plan.pageSize, plan.seed)
        server.use {
            val world = World()
            val devices = plan.devices.mapIndexed { i, s ->
                SimDevice("dev$i", DEVICE_IDS[i], server, world, plan.seed * 31 + i).also {
                    it.clock.offset = s.skew
                    it.clock.driftPerDay = s.driftPerDay
                }
            }
            val sim = Sim(server, world, devices, plan.devices.indices.filter { plan.devices[it].legacy }.toSet())
            var failedAt: Int? = null
            try {
                for ((idx, step) in plan.steps.withIndex()) {
                    failedAt = idx
                    sim.at(step.at)
                    run(sim, plan, step)
                }
                failedAt = null
                sim.at(WEEK_START + 7 * DAY + HOUR)
                sim.finish(Random(plan.seed + 99L))
            } catch (e: Throwable) {
                sim.violations += "exception ${if (failedAt != null) "at step $failedAt (${plan.steps[failedAt]})" else "in the final syncs"}: $e\n" +
                    e.stackTrace.take(12).joinToString("\n") { "      at $it" }
            }
            val violations = try {
                sim.check()
            } catch (e: Throwable) {
                sim.violations + "exception in the oracle: $e"
            }
            val anomalies = runCatching { sim.skewAnomalies() }.getOrDefault(-1)
            sim.stats.inc("log.ops", server.log().size.toLong())
            for (op in server.log()) if (op.deviceId == kotobaya.contracts.Reserved.SERVER_DEVICE_ID) sim.stats.inc("log.server.${op.opType.wire}")
            for (d in devices) sim.stats.inc("device.boots", d.boots - 1L)
            return RunResult(plan, violations, sim.stats, anomalies, server.kind, failedAt)
        }
    }

    private fun run(sim: Sim, plan: Plan, step: Step) {
        val rnd = Random(step.r)
        if (step.dev < 0) {
            when (step.kind) {
                Kind.BACKUP -> sim.backup()
                Kind.RESTORE -> sim.restore()
                Kind.SERVER_IMPORT -> sim.serverImport(rnd)
                Kind.SERVER_PUBLISH -> sim.serverPublish(rnd)
                else -> error("not a server step: $step")
            }
            return
        }
        val d = sim.devices.getOrNull(step.dev) ?: return
        val legacy = plan.devices[step.dev].legacy
        sim.stats.inc("step.${step.kind.name.lowercase()}")
        when (step.kind) {
            Kind.REVIEW -> sim.review(d, rnd, legacy)
            Kind.UNDO -> sim.undo(d, rnd)
            Kind.ADD_WORD -> sim.addWord(d, rnd)
            Kind.ADD_KANJI -> sim.addKanji(d, rnd)
            Kind.EDIT_NOTE -> sim.editNote(d, rnd)
            Kind.DELETE_NOTE -> sim.deleteNote(d, rnd)
            Kind.DELETE_CARD -> sim.deleteCard(d, rnd)
            Kind.CARD_STATE -> sim.cardState(d, rnd)
            Kind.WORD_STATUS -> sim.wordStatus(d, rnd)
            Kind.DECK -> sim.deck(d, rnd)
            Kind.PRESET -> sim.preset(d, rnd)
            Kind.SETTINGS -> sim.settings(d, rnd)
            Kind.SYNC -> sim.sync(d, choice(rnd))
            Kind.PUSH_ONLY -> d.pushOnly()
            Kind.GO_OFFLINE -> d.transport.offline = true
            Kind.GO_ONLINE -> if (!plan.devices[step.dev].airplane) d.transport.offline = false
            Kind.LOSE_PUSH_RESPONSE -> d.transport.loseNextPushResponse = true
            Kind.FAIL_PUSH -> d.transport.failNextPushBeforeServer = true
            Kind.LOSE_PULL_RESPONSE -> d.transport.loseNextPullResponse = true
            Kind.KILL_MID_PUSH -> d.transport.killAfterNextPush = true
            Kind.KILL_AT_COMMIT -> d.crash.killAtTx = 1 + rnd.nextInt(6)
            Kind.CLOCK_AHEAD -> d.clock.offset += rnd.nextLong(2 * DAY, 12 * DAY)
            Kind.CLOCK_BEHIND -> d.clock.offset -= if (rnd.nextInt(3) == 0) d.clock.nowMs() - 946_684_800_000L else rnd.nextLong(2 * DAY, 30 * DAY)
            Kind.CLOCK_SMALL_JUMP -> d.clock.offset += rnd.nextLong(-20 * HOUR, 20 * HOUR)
            Kind.CLOCK_FIX -> {
                d.clock.fix(rnd.nextLong(-2 * MINUTE, 2 * MINUTE))
                sim.stats.inc("clock.fixed")
                if (!d.transport.offline) sim.sync(d, choice(rnd))
            }
            else -> error("not a device step: $step")
        }
    }

    private fun choice(rnd: Random): Choice = when (rnd.nextInt(20)) {
        in 0..13 -> Choice.REMINT
        in 14..16 -> Choice.DISCARD_ALL
        else -> Choice.RETRY_THEN_REMINT
    }

    /**
     * Greedy delta-debugging of a failing plan: drop chunks of steps (halves, quarters, … single steps)
     * while the run still fails, within [budget] re-runs. Returns the smallest failing plan found.
     */
    fun shrink(failing: RunResult, budget: Int = 120): RunResult {
        var best = failing
        var runs = 0
        var chunk = maxOf(1, best.plan.steps.size / 2)
        while (chunk >= 1 && runs < budget) {
            var i = 0
            var progressed = false
            while (i < best.plan.steps.size && runs < budget) {
                val candidate = best.plan.copy(steps = best.plan.steps.filterIndexed { k, _ -> k < i || k >= i + chunk })
                runs++
                val r = execute(candidate)
                if (r.violations.isNotEmpty() && sameFailure(r, failing)) {
                    best = r
                    progressed = true
                } else {
                    i += chunk
                }
            }
            if (!progressed) chunk /= 2
        }
        return best
    }

    private fun category(v: String): String = v.substringBefore(':').replace(Regex("[0-9]+"), "#").take(40)

    private fun sameFailure(a: RunResult, b: RunResult): Boolean =
        a.violations.map(::category).toSet().intersect(b.violations.map(::category).toSet()).isNotEmpty()

    /** The text printed for a failure: seed, setup, violations, the shrunk trace. */
    fun failureReport(original: RunResult, shrunk: RunResult): String = buildString {
        appendLine("OFFLINE CHAOS FAILURE: seed ${original.plan.seed} (server: ${original.serverKind}); re-run with -Pseeds=${original.plan.seed}..${original.plan.seed}")
        appendLine("devices: ${original.plan.devices}; page size ${original.plan.pageSize}")
        appendLine("violations:")
        original.violations.forEach { appendLine("  - $it") }
        appendLine("shrunk trace (${shrunk.plan.steps.size} of ${original.plan.steps.size} steps, still failing with):")
        shrunk.violations.forEach { appendLine("  - ${it.lineSequence().first()}") }
        shrunk.plan.steps.forEach { appendLine("  $it") }
    }

    /** `-Pseeds=` : `n` (seeds 0..n-1), `a..b`, or `a,b,c`. Default 100 runs. */
    fun seeds(): List<Int> {
        val s = System.getProperty("kotobaya.seeds")?.trim().orEmpty()
        return when {
            s.isEmpty() -> (0 until 100).toList()
            ".." in s -> (s.substringBefore("..").toInt()..s.substringAfter("..").toInt()).toList()
            "," in s -> s.split(',').map { it.trim().toInt() }
            else -> (0 until s.toInt()).toList()
        }
    }

    fun reportDir(): File = File(System.getProperty("kotobaya.reportDir") ?: "build/reports/offline-chaos").also { it.mkdirs() }
}
