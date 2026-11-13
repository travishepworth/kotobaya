package kotobaya.sync.client.apply

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.client.store.ApplyOrigin
import kotobaya.sync.client.store.InMemoryDeviceStore

/**
 * BUG-8: [DeviceOpApplier.batch] (a pull chunk: row cache, deferred full replays, `word_status`
 * re-derived once per entry) commits exactly the rows op-by-op [DeviceOpApplier.apply] commits, on
 * every column of every table except `word_status.updated_at` (SYNC.md 11 item 4). The logs are
 * seeded mixes of everything that touches a card cache or a word status: out-of-order and imported
 * reviews, undos, merges, preset changes and publishes, deletes, overrides; batch sizes are random.
 */
class BatchApplyEquivalenceTest {
    @Test
    fun batchesCommitTheSameRowsAsOneOpAtATime() {
        var deferred = 0
        for (seed in 0 until SEEDS) {
            val ops = randomLog(Random(seed), OPS)
            val one = InMemoryDeviceStore(InMemoryLearningTables.seeded()) { it.copy() }
            val oneApplier = DeviceOpApplier<InMemoryLearningTables>()
            for (op in ops) one.transaction { tx -> oneApplier.apply(tx, op, ApplyOrigin.PULLED) }

            val batched = InMemoryDeviceStore(InMemoryLearningTables.seeded()) { it.copy() }
            val batchApplier = DeviceOpApplier<InMemoryLearningTables>()
            val r = Random(seed + 7919)
            var i = 0
            while (i < ops.size) {
                val n = 1 + r.nextInt(MAX_BATCH)
                val chunk = ops.subList(i, minOf(ops.size, i + n))
                batched.transaction { tx -> batchApplier.batch(tx) { chunk.forEach { batchApplier.apply(tx, it, ApplyOrigin.PULLED) } } }
                i += n
            }
            // the durable outcome of refusals is in the rows (refused caches); the event lists may differ in count
            val want = comparable(one.learning())
            val got = comparable(batched.learning())
            val diff = diffRows(want, got)
            assertEquals(emptyList(), diff, "seed $seed: ${diff.size} rows differ")
            deferred += ops.count { it.opType == OpType.FSRS_PARAMS_PUBLISHED || it.opType == OpType.CARD_MERGED || it.opType == OpType.REVIEW_UNDONE }
        }
        assertTrue(deferred > SEEDS, "the logs exercise deferred replays")
    }

    @Test
    fun aBatchOfNothingAndANestedBatchAreHarmless() {
        val store = InMemoryDeviceStore(InMemoryLearningTables.seeded()) { it.copy() }
        val applier = DeviceOpApplier<InMemoryLearningTables>()
        val ops = randomLog(Random(1), 60)
        store.transaction { tx -> applier.batch(tx) { } }
        store.transaction { tx ->
            applier.batch(tx) {
                ops.take(30).forEach { applier.apply(tx, it, ApplyOrigin.PULLED) }
                applier.batch(tx) { ops.drop(30).forEach { applier.apply(tx, it, ApplyOrigin.PULLED) } }
            }
        }
        val ref = InMemoryDeviceStore(InMemoryLearningTables.seeded()) { it.copy() }
        val refApplier = DeviceOpApplier<InMemoryLearningTables>()
        for (op in ops) ref.transaction { tx -> refApplier.apply(tx, op, ApplyOrigin.PULLED) }
        assertEquals(emptyList(), diffRows(comparable(ref.learning()), comparable(store.learning())))
    }

    /**
     * Every column of every row, except `word_status.updated_at` and the cache columns of cards whose
     * cache is not maintained (tombstoned or merged losers: SYNC.md 9.3, 11 item 3; a deferred replay
     * skips a card deleted later in the batch, op by op it was replayed before the delete).
     */
    private fun comparable(t: LearningTables): Map<String, Map<String, Row>> = dump(t).mapValues { (table, rows) ->
        when (table) {
            LearningTable.WORD_STATUS.sqlName -> rows.mapValues { (_, row) -> row - "updated_at" }
            LearningTable.CARD.sqlName -> rows.mapValues { (_, row) -> if (DeviceCardCache.isMaintained(row)) row else row - CACHE_COLUMNS }
            else -> rows
        }
    }

    // ------------------------------------------------------------------------------- the logs

    private val device = "01931f3e-0000-7000-8000-0000000000a1"
    private val custom = "0193a1b2-0006-7000-8000-000000000001"

    private fun uuid(kind: Int, n: Int): String =
        "0193a1b2-" + kind.toString(16).padStart(4, '0') + "-7000-8000-" + n.toString(16).padStart(12, '0')

    private fun op(type: OpType, n: Int, payload: Map<String, JsonValue>): Op {
        val p = JsonObject(payload)
        val wall = 0x0193a1b2c000L + n * 1000L
        val hlc = wall.toString(16).padStart(12, '0') + "-0000-" + device
        return Op(uuid(0xff, n), type, 1, type.entityType, DefaultOpCodec.entityIdOf(type, p), device, hlc, p)
    }

    private fun s(v: String) = JsonString(v)

    private fun num(v: Long) = JsonNumber(v.toDouble())

    private val weights = listOf(
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796, 1.4835, 0.0614,
        0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
    )

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun randomLog(r: Random, size: Int): List<Op> {
        val out = ArrayList<Op>()
        val notes = ArrayList<String>()
        val cards = ArrayList<String>()
        val reviews = ArrayList<String>()
        val published = HashMap<String, Long>()
        var n = 0
        out += op(OpType.FSRS_PARAMS_CREATED, ++n, mapOf("id" to s(custom), "name" to s("Custom"), "desired_retention" to JsonNumber(0.93)))
        while (out.size < size) {
            n++
            val roll = r.nextInt(100)
            when {
                notes.size < 3 || (roll < 8 && notes.size < 14) -> {
                    val id = uuid(1, n)
                    notes += id
                    out += op(
                        OpType.NOTE_CREATED, n,
                        mapOf("id" to s(id), "note_type_id" to s(Reserved.NOTE_TYPE_VOCAB), "entry_seq" to num(1000L + r.nextInt(6)), "fields" to JsonObject(mapOf("expression" to s("x")))),
                    )
                }
                cards.size < 3 || roll < 16 -> {
                    val id = uuid(2, n)
                    cards += id
                    val p = linkedMapOf<String, JsonValue>("id" to s(id), "note_id" to s(notes.random(r)), "kind" to s(listOf("meaning", "reading").random(r)))
                    if (r.nextInt(4) == 0) p["preset_id"] = s(custom)
                    out += op(OpType.CARD_CREATED, n, p)
                }
                roll < 62 -> {
                    val id = uuid(3, n)
                    reviews += id
                    val p = linkedMapOf<String, JsonValue>(
                        "id" to s(id), "card_id" to s(cards.random(r)),
                        // answer times jump around, so many reviews arrive out of order (a full replay)
                        "reviewed_at" to num(1_700_000_000_000L + r.nextLong(0, 40L * 86_400_000L)),
                        "utc_offset_min" to num(540), "review_type" to s(listOf("scheduled", "learning", "scheduled", "drill").random(r)),
                        "rating" to num(1L + r.nextInt(4)),
                    )
                    if (r.nextInt(5) == 0) p["import_source"] = s("jpdb")
                    out += op(OpType.REVIEW_LOGGED, n, p)
                }
                roll < 68 && reviews.isNotEmpty() -> out += op(OpType.REVIEW_UNDONE, n, mapOf("review_id" to s(reviews.random(r))))
                roll < 71 && cards.size > 2 -> {
                    val loser = cards.random(r)
                    val survivor = cards.random(r)
                    if (loser != survivor) out += op(OpType.CARD_MERGED, n, mapOf("loser_id" to s(loser), "survivor_id" to s(survivor)))
                }
                roll < 75 -> out += op(OpType.CARD_PRESET_SET, n, mapOf("id" to s(cards.random(r)), "preset_id" to s(listOf(custom, Reserved.DEFAULT_PRESET_ID).random(r))))
                roll < 79 -> {
                    val preset = listOf(custom, Reserved.DEFAULT_PRESET_ID).random(r)
                    val v = (published[preset] ?: 0L) + 1
                    published[preset] = v
                    out += op(
                        OpType.FSRS_PARAMS_PUBLISHED, n,
                        mapOf(
                            "id" to s(preset), "weights_version" to num(v), "fsrs_version" to s("6"),
                            "weights" to JsonArray(weights.map { JsonNumber(it * (1 + v * 0.01)) }),
                            "optimized_at" to num(1_733_584_666_631L), "optimizer_stats" to JsonNull,
                        ),
                    )
                }
                roll < 82 -> out += op(
                    OpType.FSRS_PARAMS_UPDATED, n,
                    mapOf("id" to s(custom), "set" to JsonObject(mapOf("desired_retention" to JsonNumber(0.85 + r.nextInt(10) / 100.0)))),
                )
                roll < 86 -> out += op(
                    OpType.WORD_STATUS_SET, n,
                    mapOf("entry_seq" to num(1000L + r.nextInt(6)), "status" to s(listOf("known", "ignored", "never_forget").random(r))),
                )
                roll < 89 -> out += op(OpType.WORD_STATUS_CLEARED, n, mapOf("entry_seq" to num(1000L + r.nextInt(6))))
                roll < 91 -> out += op(OpType.NOTE_DELETED, n, mapOf("id" to s(notes.random(r))))
                roll < 93 -> out += op(OpType.CARD_DELETED, n, mapOf("id" to s(cards.random(r))))
                roll < 96 -> out += op(OpType.CARD_SUSPENDED, n, mapOf("id" to s(cards.random(r))))
                else -> if (r.nextInt(3) == 0) out += op(OpType.FSRS_PARAMS_DELETED, n, mapOf("id" to s(custom)))
            }
        }
        return out
    }

    private companion object {
        val CACHE_COLUMNS = setOf(
            "state", "step", "stability", "difficulty", "due_at", "interval_days", "last_review_at", "last_review_id",
            "reps", "lapses", "weights_version",
        )
        const val SEEDS = 40
        const val OPS = 300
        const val MAX_BATCH = 60
    }
}
