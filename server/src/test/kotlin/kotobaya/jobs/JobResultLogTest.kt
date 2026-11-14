package kotobaya.jobs

import kotobaya.server.LogCapture
import kotobaya.server.json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** OBS-1s: a finished job is logged with its counts; drops are WARN, zero output from input ERROR, failures ERROR. */
class JobResultLogTest {
    private fun job(kind: String, status: String, result: String? = null, error: String? = null) = JobRow(
        id = UUID.randomUUID(), kind = kind, status = status, payload = json.createObjectNode(),
        result = result?.let(json::readTree), progress = 1.0, progressMessage = null, error = error, attempts = 1, maxAttempts = 3,
        createdAt = Instant.parse("2026-11-14T00:00:00Z"), startedAt = Instant.parse("2026-11-14T00:00:01Z"),
        finishedAt = Instant.parse("2026-11-14T00:00:03Z"),
    )

    private fun jpdb(cards: Int, existing: Int, unmapped: Int, statuses: Int = 0) = """
        {"vocabulary_total": ${cards + existing + unmapped}, "vocabulary_carded": ${cards + existing}, "vocabulary_status_only": 0,
         "notes_created": $cards, "notes_existing": $existing, "cards_created": $cards, "kanji_total": 0,
         "reviews_total": 0, "reviews_imported": 0, "reviews_already_imported": 0, "reviews_skipped": 0,
         "word_statuses_set": $statuses, "unmapped": [${(1..unmapped).joinToString(",") { "{\"vid\":$it}" }}], "kanji_unmapped": []}
    """

    @Test
    fun jpdbImportWithZeroCardsFromNonEmptyInputIsError() {
        // The 0.1.9 incident: 12 rows in, 0 cards out.
        val line = JobResultLog.assess(job("jpdb-history-import", "succeeded", jpdb(cards = 0, existing = 0, unmapped = 12)))
        assertEquals(Level.ERROR, line.level)
        assertTrue(line.message.contains("produced nothing"))
        assertEquals(12L, line.fields["vocabulary_total"])
        assertEquals(12L, line.fields["unmapped_count"])
    }

    @Test
    fun reimportAndStatusOnlyAreNotZeroOutput() {
        assertEquals(Level.INFO, JobResultLog.assess(job("jpdb-history-import", "succeeded", jpdb(cards = 0, existing = 5, unmapped = 0))).level)
        // a status-only export sets word statuses but creates no cards
        assertEquals(Level.INFO, JobResultLog.assess(job("jpdb-history-import", "succeeded", jpdb(cards = 0, existing = 0, unmapped = 0, statuses = 4)
            .replace("\"vocabulary_total\": 0", "\"vocabulary_total\": 4"))).level)
        // an empty export is not an error
        assertEquals(Level.INFO, JobResultLog.assess(job("jpdb-history-import", "succeeded", jpdb(cards = 0, existing = 0, unmapped = 0))).level)
    }

    @Test
    fun droppedRowsAreWarnWithCounts() {
        val line = JobResultLog.assess(job("jpdb-history-import", "succeeded", jpdb(cards = 3, existing = 0, unmapped = 2)))
        assertEquals(Level.WARN, line.level)
        assertTrue(line.message.contains("unmapped_count=2"), line.message)
        assertEquals(3L, line.fields["cards_created"])
    }

    @Test
    fun failedJobIsErrorWithItsErrorAndTheLineIsJson() {
        val j = job("dict-refresh", "failed", error = "ChecksumMismatch: kanjidic2.xml.gz")
        assertEquals(Level.ERROR, JobResultLog.assess(j).level)
        LogCapture("kotobaya.test.jobresult").use { cap ->
            JobResultLog.log(j, LoggerFactory.getLogger("kotobaya.test.jobresult"))
            val l = cap.lines().single()
            assertEquals("ERROR", l["level"].asText())
            assertEquals("dict-refresh", l["job_kind"].asText())
            assertEquals(j.id.toString(), l["job_id"].asText())
            assertEquals("ChecksumMismatch: kanjidic2.xml.gz", l["error"].asText())
            assertEquals(2000, l["duration_ms"].asInt())
        }
    }
}
