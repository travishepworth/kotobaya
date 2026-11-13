package kotobaya.sync.client.apply

import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpDecodeResult
import kotobaya.contracts.sync.OpSource
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.SyncExamples
import kotobaya.sync.client.protocol.ClientScenarios
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.protocol.ScriptedIds
import kotobaya.sync.client.protocol.SyncEngine
import kotobaya.sync.client.protocol.TestClock
import kotobaya.sync.client.protocol.TestServer
import kotobaya.sync.client.protocol.TestTransport
import kotobaya.sync.client.protocol.runSync
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.rebuild.DeviceSyncCycle
import kotobaya.sync.client.rebuild.Rebuild
import kotobaya.sync.client.store.ApplyOrigin
import kotobaya.sync.client.store.InMemoryDeviceStore
import kotobaya.sync.client.store.PendingOpRow
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.bookkeeping
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

/** The op examples of `contracts/sync/examples/ops`, decoded. */
object Examples {
    val all: Map<String, Op> = SyncExamples.ops.mapValues { (name, text) -> decode(name, text) }

    fun op(name: String): Op = all.getValue(name)

    /**
     * Creation examples in dependency order: applied to a seeded replica they create every row the
     * other examples reference (the examples share one id universe).
     */
    val world: List<String> = listOf(
        "note_type.created.json", "grammar.created.json", "fsrs_params.created.json", "media.created.json",
        "deck.created.json", "deck.created.smart.json", "deck.created.media.json",
        "note.created.json", "note.created.duplicate.json", "note.created.second.json",
        "card.created.json", "card.created.duplicate.json",
        "review.logged.json", "review.logged.manual.json",
    )

    /** A seeded replica holding [world]. */
    fun worldTables(applier: DeviceOpApplier<InMemoryLearningTables> = DeviceOpApplier()): InMemoryLearningTables =
        InMemoryLearningTables.seeded().also { t -> for (n in world) applier.applyTo(t, op(n)) }

    private fun decode(name: String, text: String): Op {
        val tree = DefaultCanonicalJson.decode(text).value as JsonObject
        val source = if ("seq" in tree.members) OpSource.PULLED else OpSource.PUSHED
        return assertIs<OpDecodeResult.Known>(DefaultOpCodec.decodeTree(tree, source), name).op
    }
}

/** Every row of every table, keyed by table and canonical key (for exact comparisons). */
fun dump(t: LearningTables): Map<String, Map<String, Row>> =
    LearningTable.entries.associate { table ->
        table.sqlName to t.all(table).associateBy { DefaultCanonicalJson.encode(JsonArray(table.keyOf(it))) }
    }

private val CACHE_COLUMNS = listOf(
    "state", "step", "stability", "difficulty", "due_at", "interval_days", "last_review_at", "last_review_id",
    "reps", "lapses", "weights_version",
)

/** SYNC.md 11: the projection on which two converged replicas are equal. */
fun projection(t: LearningTables): Map<String, Map<String, Row>> {
    val compared = listOf(
        LearningTable.NOTE_TYPE, LearningTable.NOTE, LearningTable.CARD, LearningTable.REVIEW_LOG,
        LearningTable.WORD_STATUS, LearningTable.DECK, LearningTable.DECK_ITEM, LearningTable.CONFUSABLE,
        LearningTable.GRAMMAR_POINT, LearningTable.SENTENCE_GRAMMAR_SPAN, LearningTable.SETTINGS,
        LearningTable.FSRS_PARAMS, LearningTable.MEDIA,
    )
    return compared.associate { table ->
        val rows = t.all(table).filter { row ->
            when (table) {
                LearningTable.DECK_ITEM -> (row["hlc"] ?: JsonNull) != JsonNull // snapshot rows not compared
                LearningTable.SENTENCE_GRAMMAR_SPAN -> row["origin"] == JsonString("manual")
                else -> true
            }
        }.map { row ->
            val out = LinkedHashMap(row)
            if ((row["merged_into_id"] ?: JsonNull) != JsonNull) {
                out.keys.retainAll(setOf("id", "merged_into_id", "deleted_at"))
            }
            if (table == LearningTable.CARD && !DeviceCardCache.isMaintained(row)) CACHE_COLUMNS.forEach { out.remove(it) }
            if (table == LearningTable.WORD_STATUS) {
                out.remove("updated_at")
                out.remove("auto_status")
            }
            out.remove("installed_content_version")
            out as Row
        }
        table.sqlName to rows.associateBy { DefaultCanonicalJson.encode(JsonArray(table.keyOf(it))) }
    }
}

/** The raw JSON of scenario [name]. */
fun scenarioDoc(name: String): JsonObject = DefaultCanonicalJson.decode(ClientScenarios.files.getValue(name)).value as JsonObject

/** Scenario names, sorted. */
val scenarioNames: List<String> = ClientScenarios.files.keys.sorted()

/** The scenario's log (seq order). */
fun scenarioLog(name: String): List<Op> = (scenarioDoc(name).members.getValue("log") as JsonArray).items.map {
    assertIs<OpDecodeResult.Known>(DefaultOpCodec.decodeTree(it, OpSource.PULLED), name).op
}

private fun tableNamed(name: String): LearningTable = LearningTable.entries.single { it.sqlName == name }

private fun matches(row: Row, partial: Map<String, JsonValue>): Boolean = partial.all { (k, v) -> (row[k] ?: JsonNull) == v }

/** Checks `expected.rows`, `expected.absent` and `expected.replay_sets` of scenario [name] against [t]. */
fun assertScenarioExpected(name: String, t: LearningTables, cache: DeviceCardCache, context: String) {
    val expected = scenarioDoc(name).members.getValue("expected") as JsonObject
    (expected.members["rows"] as? JsonObject)?.members?.forEach { (tableName, list) ->
        val table = tableNamed(tableName)
        for (item in (list as JsonArray).items) {
            val partial = (item as JsonObject).members
            val key = table.keyColumns.associateWith { partial[it] ?: fail("$context: expected $tableName row without key column $it") }
            val row = t.all(table).singleOrNull { matches(it, key) }
            assertNotNull(row, "$context: $tableName row $key missing")
            for ((col, v) in partial) assertEquals(v, row[col] ?: JsonNull, "$context: $tableName $key column $col")
        }
    }
    (expected.members["absent"] as? JsonObject)?.members?.forEach { (tableName, list) ->
        val table = tableNamed(tableName)
        for (item in (list as JsonArray).items) {
            val partial = (item as JsonObject).members
            assertNull(t.all(table).firstOrNull { matches(it, partial) }, "$context: $tableName row $partial must be absent")
        }
    }
    (expected.members["replay_sets"] as? JsonObject)?.members?.forEach { (cardId, ids) ->
        val want = (ids as JsonArray).items.map { (it as JsonString).value }
        assertEquals(want, cache.replaySet(t, cardId).map { it.id }, "$context: replay set of $cardId")
    }
}

/** A higher hlc than [hlc]: wall + [ms], same device. */
fun later(hlc: String, ms: Long): String {
    val wall = DefaultHlcRules.wall(hlc) + ms
    return wall.toString(16).padStart(12, '0') + hlc.substring(12)
}

/**
 * One device with the real apply: in-memory store over [InMemoryLearningTables], [DeviceOpApplier],
 * [OpMinter], [SyncEngine], [Rebuild] and [DeviceSyncCycle].
 */
class ApplyDevice(val deviceId: String, server: TestServer, clockMs: Long, seed: Int = 1) {
    val clock = TestClock(clockMs)
    val ids = ScriptedIds(seed)
    val diagnostics = DeviceDiagnostics()
    val cache = DeviceCardCache(diagnostics = diagnostics)
    val applier = DeviceOpApplier<InMemoryLearningTables>(cache)
    val store = InMemoryDeviceStore(InMemoryLearningTables.seeded()) { it.copy() }
    val transport = TestTransport(server)
    val minter = OpMinter(store, applier, clock, ids)
    val engine = SyncEngine(store, transport, applier, clock)
    val rebuild = Rebuild(store, engine, applier)
    val sync = DeviceSyncCycle(store, engine, rebuild, StartupReplay(cache))

    init {
        store.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, deviceId) }
    }

    fun cycle(): CycleResult = runSync { sync.run() }

    fun tables(): InMemoryLearningTables = store.learning()

    fun state(key: String): String? = store.syncState()[key]

    /**
     * Puts [op] (a log op of this device) in the outbox and applies its local effect, like
     * `OpMinter` does, keeping the op byte-identical to the log (no new hlc).
     */
    fun inject(op: Op) {
        val o = op.copy(seq = null, causedByOpId = null)
        store.transaction { tx ->
            applier.apply(tx, o, ApplyOrigin.LOCAL)
            tx.outbox.insert(
                PendingOpRow(
                    opId = o.opId, opType = o.opType.wire, opVersion = o.opVersion, entityType = o.entityType,
                    entityId = o.entityId, hlc = o.hlc, payload = DefaultCanonicalJson.encode(o.payload),
                    createdAt = DefaultHlcRules.wall(o.hlc),
                ),
            )
            val bk = tx.bookkeeping
            val last = bk.hlcLast
            if (last == null || o.hlc > last) bk.hlcLast = o.hlc
        }
    }
}

/** The server's replica: its log applied in seq order with the device apply function. */
fun serverReplica(server: TestServer): InMemoryLearningTables {
    val applier = DeviceOpApplier<InMemoryLearningTables>()
    return InMemoryLearningTables.seeded().also { t -> for (op in server.loggedOps()) applier.applyTo(t, op) }
}

/** Runs [block] (a suspend sync step) to completion. */
fun <T> sync(block: suspend () -> T): T = runSync(block)
