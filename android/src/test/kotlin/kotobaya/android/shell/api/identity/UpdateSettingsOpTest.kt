package kotobaya.android.shell.api.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.identity.IdentityTestSupport.call
import kotobaya.android.shell.api.identity.IdentityTestSupport.isValidOp
import kotobaya.android.shell.api.identity.IdentityTestSupport.json
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.api.identity.IdentityTestSupport.settingRows
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString

/** SHELL-L1 acceptance: `updateSettings` mints `settings.set` ops that validate, replay and converge. */
class UpdateSettingsOpTest {
    private val patch = """{"values":{"scheduler.new_per_day":15,"scheduler.timezone":"Asia/Tokyo","scheduler.reviews_unlimited":true}}"""

    @Test
    fun oneValidOpPerPresentKeyInKeyOrder() {
        val d = IdentityTestSupport.device()
        val res = d.call("PATCH", "/api/v1/settings", patch)
        assertEquals(200, res.status)
        val ops = d.minted()
        assertEquals(3, ops.size)
        assertTrue(ops.all { it.members["op_type"] == JsonString("settings.set") })
        assertTrue(ops.all(IdentityTestSupport::isValidOp), "ops validate against op.schema.json and settings.set.schema.json")
        assertEquals(
            listOf("scheduler.new_per_day", "scheduler.reviews_unlimited", "scheduler.timezone"),
            ops.map { ParityJson.str(ParityJson.obj(it.members["payload"]).members["key"]) },
        )
        assertEquals(1, d.localChanges, "one sync trigger for the whole request")
        val items = ParityJson.arr(ParityJson.obj(res.json()).members["items"]).map { ParityJson.obj(it) }
        assertEquals(listOf("scheduler.new_per_day", "scheduler.reviews_unlimited", "scheduler.timezone"), items.map { ParityJson.str(it.members["key"]) })
        assertEquals(JsonNumber(15.0), items[0].members["value"])
    }

    @Test
    fun anAbsentKeyIsUnchangedAndASameValueStillWrites() {
        val d = IdentityTestSupport.device()
        d.call("PATCH", "/api/v1/settings", """{"values":{"scheduler.new_per_day":15,"scheduler.leech_threshold":8}}""")
        val before = d.minted().size
        d.call("PATCH", "/api/v1/settings", """{"values":{"scheduler.new_per_day":15}}""", at = IdentityTestSupport.START_MS + 1000)
        assertEquals(before + 1, d.minted().size, "same value mints again")
        assertEquals(JsonNumber(8.0), d.settingRows()["scheduler.leech_threshold"], "absent key untouched")
    }

    @Test
    fun replayingTheSameRequestMintsNewOpsButConvergesToTheSameState() {
        val d = IdentityTestSupport.device()
        val first = d.call("PATCH", "/api/v1/settings", patch)
        val opsAfterFirst = d.minted()
        val stateAfterFirst = d.settingRows()
        val second = d.call("PATCH", "/api/v1/settings", patch, at = IdentityTestSupport.START_MS + 5000)
        val opsAfterSecond = d.minted()
        assertEquals(200, second.status)
        assertEquals(6, opsAfterSecond.size)
        assertEquals(3, opsAfterSecond.map { it.members["op_id"] }.toSet().size - opsAfterFirst.size, "three new op ids")
        assertEquals(stateAfterFirst, d.settingRows())
        val v1 = ParityJson.obj(first.json()).members["items"]
        val v2 = ParityJson.obj(second.json()).members["items"]
        assertEquals(
            ParityJson.arr(v1).map { ParityJson.obj(it).members.filterKeys { k -> k != "updated_at" } },
            ParityJson.arr(v2).map { ParityJson.obj(it).members.filterKeys { k -> k != "updated_at" } },
        )
    }

    @Test
    fun opsAppliedInReverseOrderOnASecondStoreConverge() {
        val a = IdentityTestSupport.device()
        // the same key written three times, then another key, with a null reset in the middle
        a.call("PATCH", "/api/v1/settings", """{"values":{"scheduler.new_per_day":10}}""")
        a.call("PATCH", "/api/v1/settings", """{"values":{"scheduler.new_per_day":20,"cards.reading_enabled":true}}""", at = IdentityTestSupport.START_MS + 1000)
        a.call("PATCH", "/api/v1/settings", """{"values":{"cards.reading_enabled":null}}""", at = IdentityTestSupport.START_MS + 2000)
        a.call("PATCH", "/api/v1/settings", """{"values":{"scheduler.new_per_day":30}}""", at = IdentityTestSupport.START_MS + 3000)
        val ops = a.minted()
        assertEquals(5, ops.size)

        val forward = IdentityTestSupport.device(IdentityTestSupport.OTHER_DEVICE)
        forward.pull(ops, "forward")
        val reversed = IdentityTestSupport.device("01931f3e-0000-7000-8000-0000000000d6")
        reversed.pull(ops.reversed(), "reversed")

        assertEquals(a.settingRows(), forward.settingRows())
        assertEquals(a.settingRows(), reversed.settingRows())
        assertEquals(JsonNumber(30.0), reversed.settingRows()["scheduler.new_per_day"])
        assertEquals(JsonNull, reversed.settingRows()["cards.reading_enabled"], "the null reset wins over the older true")
    }

    @Test
    fun validationMatchesTheServer() {
        val d = IdentityTestSupport.device()
        fun problem(body: String): Pair<Int, String?> {
            val r = d.call("PATCH", "/api/v1/settings", body)
            val b = ParityJson.obj(r.json())
            val first = (b.members["errors"] as? kotobaya.contracts.sync.JsonArray)?.items?.firstOrNull()?.let { ParityJson.obj(it) }
            return r.status to first?.members?.get("pointer")?.let { ParityJson.str(it) }
        }
        assertEquals(422 to "/values/scheduler.reviews_unlimited", problem("""{"values":{"scheduler.reviews_unlimited":"true"}}"""))
        assertEquals(422 to "/values/scheduler.day_rollover_hour", problem("""{"values":{"scheduler.day_rollover_hour":24}}"""))
        assertEquals(422 to "/values/Bad Key", problem("""{"values":{"Bad Key":1}}"""))
        assertEquals(422 to "/values", problem("""{"values":{}}"""))
        assertEquals(422 to "/values", problem("{}"))
        assertEquals(422 to "/other", problem("""{"values":{"a.b":1},"other":1}"""))
        assertEquals(0, d.minted().size, "a refused request mints nothing")
        // null resets; unknown keys are stored untouched
        assertEquals(200, d.call("PATCH", "/api/v1/settings", """{"values":{"scheduler.reviews_unlimited":null,"my.custom":{"a":[1,true]}}}""").status)
        val listed = ParityJson.arr(ParityJson.obj(d.call("GET", "/api/v1/settings").json()).members["items"]).map { ParityJson.obj(it) }
        assertEquals(listOf("my.custom"), listed.map { ParityJson.str(it.members["key"]) }, "a reset key is not listed")
        assertEquals(JsonObject(mapOf("a" to kotobaya.contracts.sync.JsonArray(listOf(JsonNumber(1.0), JsonBool(true))))), listed[0].members["value"])
    }
}
