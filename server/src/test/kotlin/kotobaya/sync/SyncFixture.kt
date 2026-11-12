package kotobaya.sync

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotobaya.auth.AuthFixture
import kotobaya.auth.Principal
import kotobaya.auth.TokenKind
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.OpType
import kotobaya.server.StartupSequence
import kotobaya.server.json
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Records every [CardCacheHook] call, so tests can see that replay was requested where SYNC.md 9.2 says. */
class RecordingCacheHook : CardCacheHook {
    val calls = CopyOnWriteArrayList<String>()
    override fun reviewLogged(tx: org.jooq.DSLContext, reviewId: UUID, targetCardId: UUID) {
        calls += "logged $reviewId -> $targetCardId"
    }
    override fun reviewUndone(tx: org.jooq.DSLContext, reviewId: UUID, targetCardId: UUID) {
        calls += "undone $reviewId -> $targetCardId"
    }
    override fun replayCards(tx: org.jooq.DSLContext, cardIds: Set<UUID>) {
        calls += "replay ${cardIds.sorted()}"
    }
    override fun presetChanged(tx: org.jooq.DSLContext, presetId: UUID, change: PresetChange) {
        calls += "preset $presetId $change"
    }
}

/**
 * A migrated database with a log epoch, a controllable sync clock ([syncNow]), a [SyncModule], and
 * helpers to push ops as a device (service level, or over HTTP through [AuthFixture.app]).
 */
abstract class SyncFixture : AuthFixture() {
    /** Server wall clock seen by the sync module. Default: just after the contract examples' base time. */
    protected val syncNow = AtomicReference(Instant.ofEpochMilli(0x0193a1b2c100L))
    protected val cache = RecordingCacheHook()
    protected open fun syncConfig() = SyncConfig(clock = { syncNow.get() }, cardCache = cache)
    protected val sync: SyncModule by lazy {
        StartupSequence(db).runOnce()
        SyncModule(db, syncConfig())
    }

    protected fun principal(deviceId: String, scopes: List<String> = listOf("read", "write", "review")): Principal {
        ensureDevice(deviceId)
        return Principal(UUID.randomUUID(), "test-device", TokenKind.DEVICE, scopes, UUID.fromString(deviceId), null)
    }

    protected fun ensureDevice(id: String) {
        db.txBlocking {
            it.execute("insert into app.device (id, name, kind) values (?::uuid, 'dev', 'android') on conflict (id) do nothing", id)
        }
    }

    /** Pushes [ops] (op JSON objects without `seq`/`caused_by_op_id`) as [who]; fails on a whole-request 400. */
    protected fun push(who: Principal, vararg ops: JsonNode): List<PushResult> {
        val body = json.writeValueAsString(mapOf("ops" to ops.map(::pushed)))
        return when (val o = sync.push.push(body, who)) {
            is PushOutcome.Ok -> o.results
            is PushOutcome.BadRequest -> fail("push was a 400: ${o.message}")
        }
    }

    /** Pushes one op as its own device at server time `wall(hlc)` (so no clock check fires); returns its result. */
    protected fun pushAsOwner(op: JsonNode, at: Long? = null): PushResult {
        syncNow.set(Instant.ofEpochMilli(at ?: wall(op["hlc"].asText())))
        return push(principal(op["device_id"].asText()), op).single()
    }

    protected fun assertAccepted(r: PushResult) = assertEquals(PushResult.ACCEPTED, r.status, "${r.opId}: ${r.error}")

    protected fun assertRejected(code: String, r: PushResult) {
        assertEquals(PushResult.REJECTED, r.status, "${r.opId} expected $code")
        assertEquals(code, r.error!!.code, "${r.opId}: ${r.error.message}")
    }

    /** The server mints [type] itself (REST/job path). */
    protected fun emit(type: OpType, payload: String, tokenId: UUID? = null): LoggedOp =
        sync.opApply.transactionBlocking(tokenId) { it.emit(type, obj(payload)) }

    protected fun obj(text: String): JsonObject = DefaultCanonicalJson.decode(text).value as JsonObject

    /** `to_jsonb` of one row, or null. */
    protected fun row(table: String, vararg key: Pair<String, Any?>): ObjectNode? = db.txBlocking { Rows(it).load(table, Rows.key(*key)) }

    protected fun count(sql: String, vararg args: Any?): Long = db.txBlocking { (it.fetchValue(sql, *args) as Number).toLong() }

    companion object {
        val contracts: File by lazy {
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .map { File(it, "contracts/sync") }.first { it.isDirectory }
        }

        fun example(name: String): ObjectNode = json.readTree(File(contracts, "examples/ops/$name.json")) as ObjectNode

        /** A pulled/scenario op as a client would push it: without `seq` and `caused_by_op_id`. */
        fun pushed(op: JsonNode): JsonNode = (op.deepCopy<JsonNode>() as ObjectNode).apply {
            remove("seq"); remove("caused_by_op_id")
        }

        const val A = "01931f3e-0000-7000-8000-0000000000a1"
        const val B = "01931f3e-0000-7000-8000-0000000000b2"
        const val SERVER = "00000000-0000-7000-8000-000000000001"

        /** An op envelope for tests (entity id derived as `ops/index.json` says). */
        fun op(type: OpType, device: String, hlcMs: Long, payload: String, ctr: Int = 0, opId: String = kotobaya.server.Uuid7.generate(hlcMs).toString()): ObjectNode {
            val p = DefaultCanonicalJson.decode(payload).value as JsonObject
            val node = json.createObjectNode()
            node.put("op_id", opId)
            node.put("op_type", type.wire)
            node.put("op_version", 1)
            node.put("entity_type", type.entityType)
            node.put("entity_id", DefaultOpCodec.entityIdOf(type, p))
            node.put("device_id", device)
            node.put("hlc", "%012x-%04x-%s".format(hlcMs, ctr, device))
            node.set<JsonNode>("payload", json.readTree(payload))
            return node
        }

        fun assertJsonEquals(expected: JsonNode, actual: JsonNode?, label: String) {
            val a = actual ?: SyncJson.nodes.nullNode()
            // Device representation: instants are epoch ms; Postgres renders timestamptz as text.
            val normal = if (expected.isNumber && a.isTextual && a.asText().length > 18 && a.asText()[4] == '-') {
                SyncJson.nodes.numberNode(SyncJson.ms(a)!!)
            } else {
                a
            }
            assertTrue(SyncJson.jsonEquals(expected, normal), "$label: expected $expected, was $a")
        }
    }
}
