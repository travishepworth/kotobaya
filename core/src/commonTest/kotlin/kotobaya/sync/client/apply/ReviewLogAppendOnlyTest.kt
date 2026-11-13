package kotobaya.sync.client.apply

import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.Op
import kotobaya.sync.DefaultHlcRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SYNC.md 7.9 on a device, where SQLite has no trigger: the apply code never updates or deletes a
 * `review_log` row except `undone_hlc` / `undone_at` (earliest undo wins) and the rebuild wipe.
 */
class ReviewLogAppendOnlyTest {
    private val logged = Examples.op("review.logged.json")
    private val undo = Examples.op("review.undone.json")
    private val reviewId = (logged.payload.members.getValue("id") as JsonString).value

    private fun world() = Examples.worldTables()

    private fun review(t: LearningTables): Row = t.get(LearningTable.REVIEW_LOG, listOf(JsonString(reviewId)))!!

    private fun undoAt(ms: Long, opId: String): Op = undo.copy(opId = opId, hlc = later(undo.hlc, ms))

    @Test
    fun aDuplicateReviewWithDifferentContentKeepsTheFirst() {
        val t = world()
        val first = review(t)
        val other = logged.copy(
            opId = "0193a1b2-00ff-7000-8000-0000000f0001",
            hlc = later(logged.hlc, 1_000),
            payload = JsonObject(logged.payload.members + mapOf("rating" to JsonNumber(1.0), "reviewed_at" to JsonNumber(1733584999999.0))),
        )
        DeviceOpApplier<InMemoryLearningTables>().applyTo(t, other)
        assertEquals(first, review(t))
    }

    @Test
    fun theEarliestUndoWinsWhateverTheArrivalOrder() {
        val a = undoAt(20, "0193a1b2-00ff-7000-8000-0000000f0020")
        val b = undoAt(15, "0193a1b2-00ff-7000-8000-0000000f0015")
        for (order in listOf(listOf(a, b), listOf(b, a))) {
            val t = world()
            val applier = DeviceOpApplier<InMemoryLearningTables>()
            order.forEach { applier.applyTo(t, it) }
            assertEquals(JsonString(b.hlc), review(t)["undone_hlc"], "order ${order.map { it.opId }}")
            assertEquals(JsonNumber(DefaultHlcRules.wall(b.hlc).toDouble()), review(t)["undone_at"])
        }
    }

    @Test
    fun theSameUndoTwiceChangesNothingAndAnUndoIsNeverRaisedOrCleared() {
        val t = world()
        val applier = DeviceOpApplier<InMemoryLearningTables>()
        val a = undoAt(20, "0193a1b2-00ff-7000-8000-0000000f0020")
        applier.applyTo(t, a)
        val once = dump(t)
        applier.applyTo(t, a)
        assertEquals(once, dump(t))
        applier.applyTo(t, undoAt(30, "0193a1b2-00ff-7000-8000-0000000f0030"))
        assertEquals(JsonString(a.hlc), review(t)["undone_hlc"], "a later undo never raises undone_hlc")
    }

    @Test
    fun anUndoOfAnUnknownReviewIsANoOpWithoutError() {
        val t = world()
        val before = dump(t)
        val unknown = undo.copy(
            entityId = "0193a1b2-0003-7000-8000-0000000000ff",
            payload = JsonObject(mapOf("review_id" to JsonString("0193a1b2-0003-7000-8000-0000000000ff"))),
        )
        assertEquals(false, DeviceOpApplier<InMemoryLearningTables>().applyTo(t, unknown))
        assertEquals(before, dump(t))
        assertNull(t.get(LearningTable.REVIEW_LOG, listOf(JsonString("0193a1b2-0003-7000-8000-0000000000ff"))))
    }

    @Test
    fun updatesAndDeletesOfReviewRowsThroughTheApplierAreRejected() {
        val raw = world()
        val guarded = AppendOnlyReviewLog.guard(raw)
        val row = review(raw)
        val changed = LinkedHashMap(row).also { it["rating"] = JsonNumber(1.0) }
        assertFailsWith<AppendOnlyViolation> { guarded.put(LearningTable.REVIEW_LOG, changed) }
        assertFailsWith<AppendOnlyViolation> { guarded.delete(LearningTable.REVIEW_LOG, listOf(JsonString(reviewId))) }
        assertFailsWith<AppendOnlyViolation> { guarded.clear(LearningTable.REVIEW_LOG) }
        assertEquals(row, review(raw), "nothing was written")
        // other tables stay writable through the guarded view
        guarded.put(LearningTable.SETTINGS, mapOf("key" to JsonString("k"), "value" to JsonNull, "hlc" to JsonString(logged.hlc), "updated_at" to JsonNumber(0.0)))
        // an insert never carries an undo, and never replaces an existing row
        val log = AppendOnlyReviewLog(raw)
        assertFailsWith<AppendOnlyViolation> {
            log.insert(LinkedHashMap(row).also { it["id"] = JsonString("0193a1b2-0003-7000-8000-0000000000fe"); it["undone_hlc"] = JsonString(logged.hlc) })
        }
        assertEquals(false, log.insert(changed))
        assertEquals(row, review(raw))
        assertTrue(AppendOnlyReviewLog.guard(guarded) === guarded)
    }
}
