package kotobaya.sync.client.mint

import kotobaya.contracts.sync.ClockGuardResult
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpDecodeResult
import kotobaya.contracts.sync.OpSource
import kotobaya.contracts.sync.OpType
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.SyncExamples
import kotobaya.sync.client.protocol.Recording
import kotobaya.sync.client.protocol.RecordingApplier
import kotobaya.sync.client.protocol.ScriptedIds
import kotobaya.sync.client.protocol.TestClock
import kotobaya.sync.client.store.ApplyOrigin
import kotobaya.sync.client.store.DeviceStore
import kotobaya.sync.client.store.InMemoryDeviceStore
import kotobaya.sync.client.store.OutboxTable
import kotobaya.sync.client.store.PendingOpRow
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.StoreTransaction
import kotobaya.sync.client.store.SyncStateKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

const val DEVICE_A = "01931f3e-0000-7000-8000-0000000000a1"
const val T0 = 1_733_584_666_000L

/** Payload of `examples/ops/<name>`. */
fun examplePayload(name: String): JsonObject {
    val tree = DefaultCanonicalJson.decode(SyncExamples.ops.getValue(name)).value as JsonObject
    return tree.members.getValue("payload") as JsonObject
}

fun JsonObject.with(key: String, value: JsonValue): JsonObject = JsonObject(members + (key to value))

class OpMinterTest {
    private val clock = TestClock(T0)
    private val applier = RecordingApplier()
    private val store = InMemoryDeviceStore(Recording()) { it.copy() }
    private val minter = OpMinter(store, applier, clock, ScriptedIds())

    init {
        store.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, DEVICE_A) }
    }

    @Test
    fun mintAppliesTheLocalEffectInsertsThePendingRowAndPersistsHlcLast() {
        val r = assertIs<MintResult.Minted>(minter.mint(OpType.NOTE_CREATED, examplePayload("note.created.json")))
        val op = r.ops.single()
        assertEquals(DEVICE_A, op.deviceId)
        assertEquals(DefaultHlcRules.format(kotobaya.contracts.sync.HlcParts(T0, 0, DEVICE_A)), op.hlc)
        val row = store.rows().single()
        assertEquals(PendingState.PENDING, row.state)
        assertEquals(op.opId, row.opId)
        assertEquals("note.created", row.opType)
        assertEquals(op.entityId, row.entityId)
        assertEquals(T0, row.createdAt)
        assertEquals(DefaultCanonicalJson.encode(op.payload), row.payload)
        assertEquals(op.hlc, store.syncState()[SyncStateKeys.HLC_LAST])
        assertEquals(listOf(ApplyOrigin.LOCAL), store.learning().applied.map { it.origin })
        // what the client stores is exactly what the codec accepts as pushed
        assertIs<OpDecodeResult.Known>(DefaultOpCodec.decode(DefaultOpCodec.encode(op), OpSource.PUSHED))
    }

    @Test
    fun hlcsAreStrictlyIncreasingAcrossMintsAtTheSameMillisecond() {
        val a = assertIs<MintResult.Minted>(minter.mint(OpType.SETTINGS_SET, examplePayload("settings.set.json"))).ops.single()
        val b = assertIs<MintResult.Minted>(minter.mint(OpType.SETTINGS_SET, examplePayload("settings.set.json"))).ops.single()
        assertTrue(a.hlc < b.hlc)
        assertEquals(1, DefaultHlcRules.parse(b.hlc)?.counter)
    }

    @Test
    fun crashBetweenApplyingTheLocalEffectAndInsertingThePendingOpLeavesNeither() {
        val failing = FailingInsertStore(store)
        val m = OpMinter(failing, applier, clock, ScriptedIds())
        assertFailsWith<IllegalStateException> { m.mint(OpType.NOTE_CREATED, examplePayload("note.created.json")) }
        assertEquals(emptyList(), store.rows())
        assertEquals(emptyList(), store.learning().applied)
        assertEquals(null, store.syncState()[SyncStateKeys.HLC_LAST])
    }

    @Test
    fun applierThatThrowsLeavesNothing() {
        applier.failOn = { true }
        assertFailsWith<IllegalStateException> { minter.mint(OpType.SETTINGS_SET, examplePayload("settings.set.json")) }
        assertEquals(emptyList(), store.rows())
        assertEquals(null, store.syncState()[SyncStateKeys.HLC_LAST])
    }

    @Test
    fun maxOpBytesBelowOneMiBRefusesAnOversizeNoteAndMintsNothing() {
        store.transaction { it.syncState.put(SyncStateKeys.META_MAX_OP_BYTES, "4096") }
        val big = examplePayload("note.created.json").let { p ->
            p.with("fields", JsonObject(mapOf("sentence" to JsonString("食".repeat(2000)))))
        }
        val r = assertIs<MintResult.Refused>(minter.mint(OpType.NOTE_CREATED, big))
        val reason = assertIs<MintRefusal.TooLarge>(r.reason)
        assertEquals(4096L, reason.maxOpBytes)
        assertTrue(reason.sizeBytes > 4096)
        assertEquals(emptyList(), store.rows())
        assertEquals(emptyList(), store.learning().applied)
        // the same note is fine under the 1 MiB default
        store.transaction { it.syncState.remove(SyncStateKeys.META_MAX_OP_BYTES) }
        assertIs<MintResult.Minted>(minter.mint(OpType.NOTE_CREATED, big))
    }

    @Test
    fun anOpOfExactlyMaxOpBytesIsAllowed() {
        val payload = examplePayload("note.created.json")
        val probe = assertIs<MintResult.Minted>(
            OpMinter(InMemoryDeviceStore(Recording()) { it.copy() }.also { s -> s.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, DEVICE_A) } }, applier, clock, ScriptedIds())
                .mint(OpType.NOTE_CREATED, payload),
        ).ops.single()
        val size = DefaultOpCodec.sizeBytes(probe)
        store.transaction { it.syncState.put(SyncStateKeys.META_MAX_OP_BYTES, size.toString()) }
        assertIs<MintResult.Minted>(minter.mint(OpType.NOTE_CREATED, payload))
        store.transaction { it.syncState.put(SyncStateKeys.META_MAX_OP_BYTES, (size - 1).toString()) }
        assertIs<MintRefusal.TooLarge>(assertIs<MintResult.Refused>(minter.mint(OpType.NOTE_CREATED, payload)).reason)
    }

    @Test
    fun everyInvalidExamplePayloadIsRefusedBeforeMinting() {
        var checked = 0
        val examples = SyncExamples.invalid + SyncExamples.invalidCodec
        for ((name, text) in examples) {
            val doc = DefaultCanonicalJson.decode(text).value as JsonObject
            if ((doc.members["schema"] as? JsonString)?.value != "op.schema.json") continue
            val instance = doc.members.getValue("instance") as? JsonObject ?: continue
            val stripped = JsonObject(instance.members - "seq" - "caused_by_op_id")
            val d = DefaultOpCodec.decodeTree(stripped, OpSource.PUSHED)
            if (d !is OpDecodeResult.Invalid || !d.pointer.startsWith("/payload")) continue
            val type = OpType.entries.single { it.wire == (instance.members.getValue("op_type") as JsonString).value }
            val payload = instance.members.getValue("payload") as JsonObject
            val r = minter.mint(type, payload)
            assertIs<MintResult.Refused>(r, name)
            checked++
        }
        assertTrue(checked >= 20, "only $checked payload-invalid examples")
        assertEquals(emptyList(), store.rows())
        assertEquals(emptyList(), store.learning().applied)
    }

    @Test
    fun tagWithIdeographicSpaceAndNulInAFieldAreRefusedWithTheirPointers() {
        val base = examplePayload("note.created.json")
        val tag = assertIs<MintRefusal.Invalid>(
            assertIs<MintResult.Refused>(minter.mint(OpType.NOTE_CREATED, base.with("tags", JsonArray(listOf(JsonString("a　b")))))).reason,
        )
        assertEquals("/payload/tags/0", tag.pointer)
        val nul = assertIs<MintRefusal.Invalid>(
            assertIs<MintResult.Refused>(minter.mint(OpType.NOTE_CREATED, base.with("fields", JsonObject(mapOf("sentence" to JsonString("a\u0000b")))))).reason,
        )
        assertTrue(nul.pointer.startsWith("/payload/fields"), nul.pointer)
    }

    @Test
    fun anOpDeeperThan500IsRefused() {
        var deep: JsonValue = JsonString("x")
        repeat(498) { deep = JsonArray(listOf(deep)) }
        val payload = examplePayload("note.created.json").with("fields", JsonObject(mapOf("meaning" to deep)))
        assertIs<MintRefusal.Invalid>(assertIs<MintResult.Refused>(minter.mint(OpType.NOTE_CREATED, payload)).reason)
        assertEquals(emptyList(), store.rows())
    }

    @Test
    fun mintAllIsAllOrNothing() {
        val ok = MintRequest(OpType.SETTINGS_SET, examplePayload("settings.set.json"))
        val bad = MintRequest(OpType.SETTINGS_SET, JsonObject(mapOf("key" to JsonString("Bad Key"), "value" to JsonString("x"))))
        val r = assertIs<MintResult.Refused>(minter.mintAll(listOf(ok, ok, bad)))
        assertEquals(2, assertIs<MintRefusal.Invalid>(r.reason).index)
        assertEquals(emptyList(), store.rows())
        val minted = assertIs<MintResult.Minted>(minter.mintAll(listOf(ok, ok)))
        assertEquals(2, store.rows().size)
        assertEquals(minted.ops.map { it.hlc }.sorted(), minted.ops.map { it.hlc })
    }

    @Test
    fun serverOnlyTypesAreNeverMinted() {
        val r = assertIs<MintResult.Refused>(minter.mint(OpType.NOTE_MERGED, examplePayload("note.merged.json")))
        assertIs<MintRefusal.ServerOnly>(r.reason)
    }

    @Test
    fun mintingIsBlockedWhileRebuildPendingClockWrongOrUnpaired() {
        val p = examplePayload("settings.set.json")
        store.transaction { it.syncState.put(SyncStateKeys.REBUILD_PENDING, "1") }
        assertEquals(MintRefusal.RebuildPending, assertIs<MintResult.Refused>(minter.mint(OpType.SETTINGS_SET, p)).reason)
        store.transaction { it.syncState.remove(SyncStateKeys.REBUILD_PENDING) }

        clock.now = 946_684_800_000L // 2000-01-01: G1
        assertEquals(MintRefusal.ClockGuard(ClockGuardResult.G1_BEFORE_FLOOR), assertIs<MintResult.Refused>(minter.mint(OpType.SETTINGS_SET, p)).reason)
        clock.now = T0
        assertIs<MintResult.Minted>(minter.mint(OpType.SETTINGS_SET, p))
        clock.now = T0 - 86_400_001L // G2: far behind hlc_last
        assertEquals(MintRefusal.ClockGuard(ClockGuardResult.G2_BEHIND_LAST), assertIs<MintResult.Refused>(minter.mint(OpType.SETTINGS_SET, p)).reason)
        clock.now = T0

        store.transaction { it.syncState.put(SyncStateKeys.SERVER_SKEW_MS, (86_400_001L).toString()) }
        assertIs<MintRefusal.ClockFarFromServer>(assertIs<MintResult.Refused>(minter.mint(OpType.SETTINGS_SET, p)).reason)
        store.transaction { it.syncState.put(SyncStateKeys.SERVER_SKEW_MS, "-120000") }
        assertIs<MintResult.Minted>(minter.mint(OpType.SETTINGS_SET, p))

        store.transaction { it.syncState.remove(SyncStateKeys.DEVICE_ID) }
        assertEquals(MintRefusal.NotPaired, assertIs<MintResult.Refused>(minter.mint(OpType.SETTINGS_SET, p)).reason)
        assertEquals(2, store.rows().size)
    }
}

/** A store whose outbox insert throws: a crash between the local effect and the `pending_op` row. */
private class FailingInsertStore<L>(private val inner: DeviceStore<L>) : DeviceStore<L> {
    override fun <R> transaction(block: (StoreTransaction<L>) -> R): R = inner.transaction { tx ->
        block(
            object : StoreTransaction<L> by tx {
                override val outbox: OutboxTable = object : OutboxTable by tx.outbox {
                    override fun insert(row: PendingOpRow): Unit = throw IllegalStateException("disk full")
                }
            },
        )
    }
}
