package kotobaya.android.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.store.SyncStateKeys

/**
 * A synthetic history shaped like the 0.1.6 field report (I-4): about 7 % vocab notes, two cards each,
 * the rest reviews (a few percent arriving out of order, so some reviews force a full replay), some word
 * status overrides and a few published Default-preset weights (each replays every card).
 */
object SyntheticHistory {
    const val DEVICE: String = "01931f3e-0000-7000-8000-0000000000a1"
    private const val BASE_WALL: Long = 0x0193a1b2c000L

    fun uuid(kind: Int, n: Long): String = "0193a1b2-%04x-7000-8000-%012x".format(kind, n)

    private fun hlc(wall: Long, device: String): String = "%012x-0000-%s".format(wall, device)

    private fun op(type: OpType, n: Long, wall: Long, payload: Map<String, JsonValue>, device: String = DEVICE): Op {
        val p = JsonObject(payload)
        return Op(
            opId = uuid(0x00ff, n), opType = type, opVersion = 1, entityType = type.entityType,
            entityId = DefaultOpCodec.entityIdOf(type, p), deviceId = device, hlc = hlc(wall, device), payload = p,
        )
    }

    private fun s(v: String) = JsonString(v)

    private fun num(v: Long) = JsonNumber(v.toDouble())

    /** [total] ops in log order. */
    fun ops(total: Int, seed: Int = 42): List<Op> {
        val r = Random(seed)
        val out = ArrayList<Op>(total)
        var wall = BASE_WALL
        var n = 0L
        val notes = total * 7 / 100
        val cards = ArrayList<String>()
        val lastReviewed = HashMap<String, Long>()
        var notesMade = 0
        var reviewAt = 1_700_000_000_000L
        val publishAt = setOf(total / 4, total / 2, 3 * total / 4)
        var weightsVersion = 0L
        while (out.size < total) {
            wall += 1000
            n++
            val i = out.size
            when {
                i in publishAt -> {
                    weightsVersion++
                    out += op(
                        OpType.FSRS_PARAMS_PUBLISHED, n, wall,
                        mapOf(
                            "id" to s(Reserved.DEFAULT_PRESET_ID), "weights_version" to num(weightsVersion),
                            "fsrs_version" to s("6"),
                            "weights" to JsonArray(WEIGHTS.map { JsonNumber(it) }),
                            "optimized_at" to num(1_733_584_666_631L), "optimizer_stats" to JsonNull,
                        ),
                        Reserved.SERVER_DEVICE_ID,
                    )
                }
                notesMade < notes && (cards.size < 20 || r.nextInt(100) < 10) -> {
                    val noteId = uuid(1, n)
                    out += op(
                        OpType.NOTE_CREATED, n, wall,
                        mapOf(
                            "id" to s(noteId), "note_type_id" to s(Reserved.NOTE_TYPE_VOCAB),
                            "entry_seq" to num(1_000_000L + notesMade),
                            "fields" to JsonObject(mapOf("expression" to s("語$notesMade"), "reading" to s("ご"), "meaning" to s("word $notesMade"))),
                        ),
                    )
                    notesMade++
                    for (kind in listOf("meaning", "reading")) {
                        if (out.size >= total) break
                        wall += 1000
                        n++
                        val cardId = uuid(2, n)
                        out += op(OpType.CARD_CREATED, n, wall, mapOf("id" to s(cardId), "note_id" to s(noteId), "kind" to s(kind)))
                        cards += cardId
                    }
                }
                r.nextInt(200) == 0 -> out += op(
                    OpType.WORD_STATUS_SET, n, wall,
                    mapOf("entry_seq" to num(1_000_000L + r.nextInt(maxOf(1, notesMade))), "status" to s("known")),
                )
                else -> {
                    val card = cards[r.nextInt(cards.size)]
                    reviewAt += 60_000
                    // 3 % arrive late with an earlier answer time than the card's last review: a full replay
                    val at = if (r.nextInt(100) < 3 && lastReviewed[card] != null) lastReviewed.getValue(card) - 1000 else reviewAt
                    lastReviewed[card] = maxOf(at, lastReviewed[card] ?: Long.MIN_VALUE)
                    val first = lastReviewed.size
                    out += op(
                        OpType.REVIEW_LOGGED, n, wall,
                        mapOf(
                            "id" to s(uuid(3, n)), "card_id" to s(card), "reviewed_at" to num(at),
                            "utc_offset_min" to num(540), "review_type" to s(if (first % 2 == 0) "scheduled" else "learning"),
                            "rating" to num(1L + r.nextInt(4)),
                        ),
                    )
                }
            }
        }
        return out
    }

    private val WEIGHTS = listOf(
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796, 1.4835, 0.0614,
        0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
    )
}

/**
 * BUG-8 acceptance: a fresh device pulls a synthetic 100k-op history on the real SQLite store (JDBC
 * driver, WAL, synchronous=FULL, a file on disk under build/) through the real SyncEngine, applier and
 * store, in under two minutes. The measured time is printed (`BENCH ...` in the test's stdout).
 * The environment variable `KOTOBAYA_BENCH_OPS` changes the size.
 */
class PullBenchTest {
    @Test
    fun aFreshDevicePullsAHundredThousandOpsInUnderTwoMinutes() {
        val total = System.getenv("KOTOBAYA_BENCH_OPS")?.toIntOrNull() ?: 100_000
        val history = SyntheticHistory.ops(total)
        val server = TestServer(nowMs = 0x0193a1b2c000L + total * 1000L + 60_000)
        server.pageSize = 500
        for (op in history) server.append(op, null)
        // on disk under build/ (not a tmpfs /tmp), so the per-chunk fsyncs of synchronous=FULL are real
        val device = SqlDevice(SyntheticHistory.DEVICE.replace("a1", "b2"), server, server.nowMs, dbDir = java.io.File("build/bench-db"))

        val start = System.nanoTime()
        val result = device.cycle()
        val seconds = (System.nanoTime() - start) / 1e9
        println("BENCH pull of $total ops: %.1f s (%.0f ops/s)".format(seconds, total / seconds))

        assertIs<CycleResult.Completed>(result, "$result")
        assertEquals(total, result.pulledOps)
        assertEquals(total.toLong(), device.state(SyncStateKeys.PULL_SEQ)?.toLong())
        val reviews = history.count { it.opType == OpType.REVIEW_LOGGED }
        assertEquals(reviews, device.store.read { it.learning.all(LearningTable.REVIEW_LOG).size })
        if (total >= 100_000) assertTrue(seconds < 120.0, "100k-op pull took $seconds s")
    }
}
