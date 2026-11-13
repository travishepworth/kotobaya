package kotobaya.stats

import io.ktor.http.HttpMethod
import kotobaya.auth.TokenKind
import kotobaya.contracts.sync.OpType
import kotobaya.notes.NotesModule
import kotobaya.server.Uuid7
import kotobaya.settings.SettingsModule
import kotobaya.sync.SyncConfig
import kotobaya.sync.SyncFixture
import kotobaya.sync.merge.CoreCardCache
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** T605: heatmap day bucketing across the rollover hour in a non-UTC zone, with an undone row excluded. */
class StatsHeatmapTest : SyncFixture() {
    override fun syncConfig() = SyncConfig(clock = { syncNow.get() }, cardCache = CoreCardCache())

    private val handlers by lazy {
        NotesModule(db, sync.opApply).handlers + SettingsModule(db, sync.opApply).handlers + StatsModule(db, clock = { syncNow.get() }).handlers
    }
    private val vocab = "00000000-0000-7000-8000-000000000101"

    private fun review(card: String, utc: String, rating: Int = 3): String {
        val at = Instant.parse(utc).toEpochMilli()
        val id = Uuid7.generate(at).toString()
        emit(
            OpType.REVIEW_LOGGED,
            """{"id":"$id","card_id":"$card","reviewed_at":$at,"utc_offset_min":null,"review_type":"scheduled","rating":$rating}""",
        )
        return id
    }

    @Test
    fun reviewsAreBucketedByStudyDayInTheUserZoneAndUndoneRowsAreExcluded() = app(extraHandlers = handlers) {
        val writer = mint(TokenKind.PERSONAL, listOf("read", "write")).second
        assertEquals(
            200,
            client.call(HttpMethod.Patch, "/settings", bearer = writer, body = mapOf("values" to mapOf("scheduler.timezone" to "Asia/Tokyo"))).status.value,
        )
        val created = client.call(
            HttpMethod.Post, "/notes", bearer = writer,
            body = mapOf("note_type_id" to vocab, "fields" to mapOf("expression" to "x"), "tags" to emptyList<String>()),
        )
        assertEquals(201, created.status.value)
        val card = created.json()["cards"].first { it["kind"].asText() == "meaning" }["id"].asText()

        // Tokyo is UTC+9, rollover 04:00 local = 19:00 UTC.
        review(card, "2024-03-10T18:30:00Z") // 03:30 JST on the 11th: study day 03-10
        review(card, "2024-03-10T19:30:00Z") // 04:30 JST on the 11th: study day 03-11
        review(card, "2024-03-11T18:59:00Z") // 03:59 JST on the 12th: study day 03-11
        val undone = review(card, "2024-03-10T10:00:00Z") // 19:00 JST on the 10th: study day 03-10, then undone
        emit(OpType.REVIEW_UNDONE, """{"review_id":"$undone"}""")

        val r = client.call(HttpMethod.Get, "/stats/heatmap?from=2024-03-09&to=2024-03-12", bearer = writer)
        assertEquals(200, r.status.value)
        val days = r.json()["days"].map { it["date"].asText() to it["reviews"].asInt() }
        assertEquals(listOf("2024-03-10" to 1, "2024-03-11" to 2), days)
    }
}
