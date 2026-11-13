package kotobaya.sync.client.apply

import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Every example op of `contracts/sync/examples/ops` through [DeviceOpApplier] (SYNC.md 7): it applies;
 * re-applying it changes nothing (7.8); arriving before its parents is a logged no-op and the
 * re-delivery after them gives the in-order result (7.7); arriving before or after a tombstone of its
 * row or parent gives the same result (7.3); an older write arriving after a newer one loses (7.1/7.4).
 */
class ExampleOpsApplyTest {
    private val names = Examples.all.keys.sorted()

    private fun applier() = DeviceOpApplier<InMemoryLearningTables>()

    private fun apply(t: InMemoryLearningTables, vararg ops: Op): InMemoryLearningTables {
        val a = applier()
        for (op in ops) a.applyTo(t, op)
        return t
    }

    /** The world without [name] and what depends on it (the world prefix before it), or the whole world. */
    private fun baseFor(name: String): InMemoryLearningTables {
        val i = Examples.world.indexOf(name)
        val prefix = if (i < 0) Examples.world else Examples.world.subList(0, i)
        return InMemoryLearningTables.seeded().also { t -> prefix.forEach { apply(t, Examples.op(it)) } }
    }

    @Test
    fun theExamplesCoverEveryOpType() {
        assertEquals(OpType.entries.toSet(), Examples.all.values.map { it.opType }.toSet())
    }

    @Test
    fun everyExampleOpApplies() {
        for (name in names) {
            val op = Examples.op(name)
            val base = baseFor(name)
            val before = dump(base)
            val missing = ArrayList<String>()
            val a = DeviceOpApplier<InMemoryLearningTables>(onMissing = { _, what -> missing += what })
            assertTrue(a.applyTo(base, op), "$name: a reference is missing: $missing")
            if (op.opType.deviceApplies) {
                assertNotEquals(before, dump(base), "$name changed nothing")
            } else {
                assertEquals(before, dump(base), "$name: devices do nothing for ${op.opType.wire}")
            }
        }
    }

    @Test
    fun reApplyingEveryOpTypeChangesNothing() {
        val covered = HashSet<OpType>()
        for (name in names) {
            val op = Examples.op(name)
            val t = apply(baseFor(name), op)
            val once = dump(t)
            apply(t, op)
            assertEquals(once, dump(t), "$name: second apply changed the replica (SYNC.md 7.8)")
            apply(t, op, op)
            assertEquals(once, dump(t), "$name: third apply changed the replica")
            covered += op.opType
        }
        assertEquals(OpType.entries.toSet(), covered)
    }

    @Test
    fun everyOpTypeArrivingBeforeItsParentsIsANoOpAndConvergesOnReDelivery() {
        val covered = HashSet<OpType>()
        for (name in names) {
            val op = Examples.op(name)
            val missing = ArrayList<String>()
            val early = InMemoryLearningTables.seeded()
            val seeded = dump(early)
            val applied = DeviceOpApplier<InMemoryLearningTables>(onMissing = { _, what -> missing += what }).applyTo(early, op)
            if (!applied) {
                assertEquals(seeded, dump(early), "$name: an op with a missing reference must be a no-op (SYNC.md 7.7)")
                assertTrue(missing.isNotEmpty(), "$name: the missing reference is reported")
            }
            // the parents arrive, then the op is delivered again (a rebuild's re-apply step)
            for (w in Examples.world) apply(early, Examples.op(w))
            apply(early, op)
            val inOrder = apply(Examples.worldTables(), op)
            assertEquals(dump(inOrder), dump(early), "$name: early arrival + re-delivery differs from in-order apply")
            covered += op.opType
        }
        assertEquals(OpType.entries.toSet(), covered)
    }

    /** The tombstone an op meets: its own row's, or its parent's (SYNC.md 7.3: ops on tombstones and children of tombstones are applied). */
    private fun tombstoneFor(op: Op, base: LearningTables): Pair<OpType, String>? {
        val p = op.payload.members
        fun s(k: String) = (p[k] as? JsonString)?.value
        return when (op.opType) {
            OpType.NOTE_CREATED -> OpType.NOTE_TYPE_DELETED to s("note_type_id")!!
            OpType.CARD_CREATED -> OpType.NOTE_DELETED to s("note_id")!!
            OpType.REVIEW_LOGGED -> OpType.CARD_DELETED to s("card_id")!!
            OpType.REVIEW_UNDONE -> OpType.CARD_DELETED to
                (base.get(LearningTable.REVIEW_LOG, listOf(JsonString(s("review_id")!!)))!!["card_id"] as JsonString).value
            OpType.DECK_ITEM_PUT, OpType.DECK_ITEM_REMOVED, OpType.DECK_CONTENT_PUBLISHED -> OpType.DECK_DELETED to s("deck_id")!!
            OpType.CONFUSABLE_SUGGESTED, OpType.CONFUSABLE_CONFIRMED, OpType.CONFUSABLE_REJECTED -> OpType.NOTE_DELETED to s("note_a_id")!!
            OpType.GRAMMAR_SPAN_PUT, OpType.GRAMMAR_SPAN_REMOVED -> OpType.GRAMMAR_DELETED to s("grammar_point_id")!!
            OpType.NOTE_MERGED, OpType.CARD_MERGED ->
                (if (op.opType == OpType.NOTE_MERGED) OpType.NOTE_DELETED else OpType.CARD_DELETED) to s("loser_id")!!
            OpType.MEDIA_CONTENT_PUBLISHED -> OpType.MEDIA_DELETED to s("media_id")!!
            else -> {
                if (op.opType.wire.endsWith(".created") || op.opType.wire.endsWith(".deleted")) return null
                OpType.entries.firstOrNull { it.wire.endsWith(".deleted") && it.entityType == op.entityType && it.deviceApplies }
                    ?.let { it to op.entityId }
            }
        }
    }

    private fun deleteOp(type: OpType, id: String, hlc: String): Op =
        Op("0193a1b2-00ff-7000-8000-0000000d0000", type, 1, type.entityType, id, hlc.takeLast(36), hlc, JsonObject(mapOf("id" to JsonString(id))))

    @Test
    fun tombstoneBeforeOrAfterAnOpGivesTheSameReplica() {
        val covered = HashSet<OpType>()
        for (name in names) {
            val op = Examples.op(name)
            val (delType, target) = tombstoneFor(op, baseFor(name)) ?: continue
            for (delta in listOf(-5L, 5L)) {
                val d = deleteOp(delType, target, later(op.hlc, delta))
                val deleteFirst = apply(baseFor(name), d, op)
                val deleteLast = apply(baseFor(name), op, d)
                // SYNC.md 11 projection: caches of tombstoned cards are not maintained
                assertEquals(projection(deleteLast), projection(deleteFirst), "$name with ${delType.wire} at ${if (delta < 0) "older" else "newer"} clock")
                val row = deleteFirst.get(tableOf(delType), listOf(JsonString(target)))
                assertTrue(row != null && row["deleted_at"] != JsonNull, "$name: the tombstone stays (deletion is permanent)")
            }
            covered += op.opType
        }
        val expected = OpType.entries.filter { it.deviceApplies && !it.wire.endsWith(".deleted") && it.wire !in ROOTS }.toSet()
        assertEquals(expected, covered.filter { !it.wire.endsWith(".deleted") }.toSet())
    }

    private fun tableOf(type: OpType): LearningTable = LearningTable.entries.single { it.sqlName == type.entityType }

    @Test
    fun anOlderWriteArrivingAfterANewerOneLoses() {
        for (name in names) {
            val op = Examples.op(name)
            if (op.opType in NOT_LWW || !op.opType.deviceApplies) continue
            val newer = op.copy(opId = "0193a1b2-00ff-7000-8000-0000000e0000", hlc = later(op.hlc, 7))
            val inOrder = apply(baseFor(name), op, newer)
            val reversed = apply(baseFor(name), newer, op)
            assertEquals(dump(inOrder), dump(reversed), "$name: an older write applied after a newer one must lose")
        }
    }

    private companion object {
        /** Ops with no parent and no own tombstone before they exist. */
        val ROOTS = setOf(
            "note_type.created", "grammar.created", "fsrs_params.created", "media.created", "deck.created",
            "settings.set", "word.status_set", "word.status_cleared",
        )

        /** Not last-writer-wins by clock: creations and reviews (id decides), version ops, merges (one per loser). */
        val NOT_LWW = OpType.entries.filter {
            it.wire.endsWith(".created") || it.wire.endsWith("published") || it.wire.endsWith(".merged") || it == OpType.REVIEW_LOGGED
        }.toSet()
    }
}
