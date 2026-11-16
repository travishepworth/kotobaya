package kotobaya.android.shell.api.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.identity.IdentityTestSupport.call
import kotobaya.android.shell.api.identity.IdentityTestSupport.json
import kotobaya.android.shell.parity.ParityJson
import kotobaya.android.shell.parity.SeedLog
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonString

/** Read views of the replica (presets, note types, meta) and the client-error intake. */
class IdentityReadsTest {
    private val d = IdentityTestSupport.device()

    private fun items(path: String) =
        ParityJson.arr(ParityJson.obj(d.call("GET", path).json()).members["items"]).map { ParityJson.obj(it).members }

    @Test
    fun presetsAndNoteTypesAreTheBuiltInRows() {
        val presets = items("/api/v1/presets")
        assertEquals(listOf(JsonString("Default")), presets.map { it["name"] })
        assertEquals(JsonString("1970-01-01T00:00:00.000Z"), presets[0]["created_at"])
        assertEquals(JsonNull, presets[0]["deleted_at"])
        assertEquals(200, d.call("GET", "/api/v1/presets/00000000-0000-7000-8000-000000000010").status)
        assertEquals(404, d.call("GET", "/api/v1/presets/0193b000-0000-7000-8000-000000000000").status)
        assertEquals(404, d.call("GET", "/api/v1/presets/not-a-uuid").status)
        val types = items("/api/v1/note-types")
        assertEquals(6, types.size)
        assertEquals(JsonString("Vocab"), types[0]["name"])
        assertEquals(200, d.call("GET", "/api/v1/note-types/00000000-0000-7000-8000-000000000101").status)
        assertEquals(422, d.call("GET", "/api/v1/note-types", query = "include_deleted=maybe").status)
        assertEquals(422, d.call("GET", "/api/v1/presets", query = "nope=1").status)
    }

    @Test
    fun metaCarriesTheDeviceCursorAndDefaults() {
        val m = ParityJson.obj(d.call("GET", "/api/v1/meta").json()).members
        assertEquals(JsonString("v1"), m["api_version"])
        assertEquals(JsonString("unknown"), m["server_version"])
        assertEquals(JsonString("00000000-0000-7000-8000-000000000001"), m["server_device_id"])
        assertEquals(JsonString("00000000-0000-0000-0000-000000000000"), m["log_epoch"], "no epoch before the first pull")
        val seeded = IdentityTestSupport.device(IdentityTestSupport.OTHER_DEVICE, IdentityTestSupport.seedOf("queue-kinds-filter-counts"))
        val sm = ParityJson.obj(seeded.call("GET", "/api/v1/meta").json()).members
        assertEquals(JsonString(SeedLog.LOG_EPOCH), sm["log_epoch"])
        assertEquals(seeded.log.size.toLong(), (sm["high_water_seq"] as kotobaya.contracts.sync.JsonNumber).value.toLong(), "the pull cursor")
        assertEquals(JsonString(timestamp(IdentityTestSupport.START_MS)), m["server_time"])
        assertEquals(7, ParityJson.obj(m["limits"]).members.size)
    }

    @Test
    fun clientErrorsAreValidatedLimitedAndQueued() {
        fun item(msg: String) =
            """{"client":"web","app_version":"1.0","occurred_at":"2026-11-19T12:00:00+09:00","level":"error","kind":"exception","message":"$msg","route":"/review?q=x"}"""
        val ok = d.call("POST", "/api/v1/client-errors", """{"items":[${item("boom")}]}""")
        assertEquals(202, ok.status)
        assertEquals(1, d.reported.size)
        assertEquals("2026-11-19T03:00:00Z", d.reported[0].occurredAt)
        assertEquals(422, d.call("POST", "/api/v1/client-errors", """{"items":[]}""").status)
        assertEquals(422, d.call("POST", "/api/v1/client-errors", """{"items":[{"client":"ios"}]}""").status)
        assertEquals(413, d.call("POST", "/api/v1/client-errors", """{"items":[${(1..21).joinToString(",") { item("m$it") }}]}""").status)
        assertEquals(1, d.reported.size, "refused batches queue nothing")
        // 4 requests so far; the 13th in a minute is 429 with Retry-After
        var last = ok
        repeat(9) { last = d.call("POST", "/api/v1/client-errors", """{"items":[${item("x")}]}""") }
        assertEquals(429, last.status)
        assertTrue((last.headers["retry-after"]?.toLongOrNull() ?: 0) >= 1)
        // a minute later the window admits again
        assertEquals(202, d.call("POST", "/api/v1/client-errors", """{"items":[${item("y")}]}""", at = IdentityTestSupport.START_MS + 61_000).status)
    }
}
