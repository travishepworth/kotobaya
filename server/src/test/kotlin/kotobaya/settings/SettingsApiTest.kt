package kotobaya.settings

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import kotobaya.auth.TokenKind
import kotobaya.sync.SyncFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T212b: settings and presets over HTTP with real tokens; every write must arrive as ops. */
class SettingsApiTest : SyncFixture() {
    private val module by lazy { SettingsModule(db, sync.opApply) }

    private fun settingsApp(block: suspend ApplicationTestBuilder.() -> Unit) = app(extraHandlers = module.handlers, block = block)

    private fun writer() = mint(TokenKind.PERSONAL, listOf("read", "write")).second

    private fun head(): Long = count("select coalesce(max(seq), 0) from app.change")

    private fun opsSince(after: Long): List<String> =
        db.txBlocking { dsl -> dsl.fetch("select op_type from app.change where seq > ? order by seq", after).map { it.get(0, String::class.java) } }

    private fun stored(key: String): String? =
        db.txBlocking { it.fetchOptional("select value::text from app.settings where key = ?", key).map { r -> r.get(0, String::class.java) }.orElse(null) }

    private suspend fun ApplicationTestBuilder.patch(token: String, body: String): HttpResponse =
        client.call(HttpMethod.Patch, "/settings", bearer = token, body = body)

    private fun ptr(r: HttpResponse): String = r.json()["errors"][0]["pointer"].asText()

    private fun item(list: JsonNode, key: String): JsonNode? = list["items"].firstOrNull { it["key"].asText() == key }

    @Test
    fun roundTripEmitsOneSettingsSetPerKeyInAscendingOrderAndResetKeepsTheRow() = settingsApp {
        val t = writer()
        val before = head()
        val r = patch(t, """{"values":{"scheduler.timezone":"Asia/Tokyo","audio.prefetch_days":3,"scheduler.bury_siblings":false}}""")
        assertEquals(200, r.status.value, r.json().toString())
        assertEquals(listOf("settings.set", "settings.set", "settings.set"), opsSince(before))
        assertEquals(head().toString(), r.headers["Kotobaya-Seq"])
        assertEquals(
            listOf("audio.prefetch_days", "scheduler.bury_siblings", "scheduler.timezone"),
            r.json()["items"].map { it["key"].asText() },
        )
        val listed = client.call(HttpMethod.Get, "/settings", bearer = t).json()
        assertEquals("Asia/Tokyo", item(listed, "scheduler.timezone")?.get("value")?.asText())

        val reset = patch(t, """{"values":{"scheduler.timezone":null}}""")
        assertEquals(200, reset.status.value)
        assertNull(item(reset.json(), "scheduler.timezone"), "null rows are not listed")
        assertEquals("null", stored("scheduler.timezone"), "the row stays so the reset wins")
    }

    @Test
    fun intKeysFollowTheBinary64ValidityTest() = settingsApp {
        val t = writer()
        for (key in listOf("scheduler.mined_priority", "scheduler.new_per_day", "scheduler.leech_threshold", "audio.prefetch_days")) {
            for (ok in listOf("-1", "1.0", "1e2", "-0", "2147483647", "-2147483648")) {
                assertEquals(200, patch(t, """{"values":{"$key":$ok}}""").status.value, "$key=$ok")
            }
            for (bad in listOf("1.5", "2147483648", "\"1\"", "true", "[1]", "1e309", "1e400", "null1")) {
                val before = head()
                val r = patch(t, """{"values":{"$key":$bad}}""")
                if (bad == "null1") {
                    assertEquals(400, r.status.value)
                    continue
                }
                assertProblem(r, 422, "validation_failed")
                assertEquals("/values/$key", ptr(r), "$key=$bad")
                assertEquals(before, head(), "nothing minted")
            }
        }
        patch(t, """{"values":{"scheduler.mined_priority":1.0}}""")
        // The op layer canonicalises numbers (canonical JSON), so 1.0 is stored as 1: same value, valid by 6.1.
        assertEquals("1", stored("scheduler.mined_priority"))
    }

    @Test
    fun boolStringAndNarrowIntKeysRejectTheWrongTypeAndWriteNothing() = settingsApp {
        val t = writer()
        val cases = mapOf(
            "scheduler.kanji_before_vocab" to listOf("\"yes\"", "1", "0", "\"true\""),
            "scheduler.bury_siblings" to listOf("\"false\"", "1"),
            "scheduler.timezone" to listOf("5", "true"),
            "scheduler.day_rollover_hour" to listOf("24", "-1", "3.5"),
            "scheduler.grading_buttons" to listOf("3", "6", "\"4\""),
        )
        for ((key, bads) in cases) {
            for (bad in bads) {
                val before = head()
                val r = patch(t, """{"values":{"$key":$bad,"ui.theme":"dark"}}""")
                assertProblem(r, 422, "validation_failed")
                assertEquals("/values/$key", ptr(r), "$key=$bad")
                assertEquals(before, head(), "nothing in the request is written")
            }
        }
        assertEquals(200, patch(t, """{"values":{"scheduler.kanji_before_vocab":true,"scheduler.day_rollover_hour":23,"scheduler.grading_buttons":5.0}}""").status.value)
        assertEquals("true", stored("scheduler.kanji_before_vocab"))
    }

    @Test
    fun unknownKeysAreStoredAsAnyJsonAndBadShapesAre422() = settingsApp {
        val t = writer()
        val before = head()
        val r = patch(t, """{"values":{"ui.theme":{"dark":[1,2.50,"x"]},"zz":null}}""")
        assertEquals(200, r.status.value)
        assertEquals(2, opsSince(before).size)
        assertEquals(listOf(1, 2.5, "x"), item(r.json(), "ui.theme")!!["value"]["dark"].map { if (it.isTextual) it.asText() else it.numberValue() })
        for (bad in listOf("""{"values":{}}""", """{}""", """{"values":{"Bad":1}}""", """{"values":[1]}""", """{"values":{"a":1},"x":1}""")) {
            assertProblem(patch(t, bad), 422, "validation_failed")
        }
    }

    @Test
    fun settingsWritesNeedTheWriteScope() = settingsApp {
        val reader = mint(TokenKind.PERSONAL, listOf("read")).second
        val before = head()
        assertProblem(patch(reader, """{"values":{"ui.theme":"x"}}"""), 403, "insufficient_scope")
        assertEquals(before, head())
        assertEquals(200, client.call(HttpMethod.Get, "/settings", bearer = reader).status.value)
        assertProblem(client.call(HttpMethod.Get, "/settings"), 401, "unauthenticated")
    }

    @Test
    fun presetCreateUpdateDeleteEmitFsrsParamsOps() = settingsApp {
        val t = writer()
        var mark = head()
        val created = client.call(
            HttpMethod.Post, "/presets", bearer = t,
            body = mapOf("name" to "Kanji", "desired_retention" to 0.85, "learning_steps_s" to listOf(60, 600, 3600)),
        )
        assertEquals(201, created.status.value, created.json().toString())
        assertEquals(listOf("fsrs_params.created"), opsSince(mark))
        val id = created.json()["id"].asText()
        assertEquals(0.85, created.json()["desired_retention"].asDouble())
        assertEquals(36500, created.json()["maximum_interval_days"].asInt())
        assertEquals(listOf(60, 600, 3600), created.json()["learning_steps_s"].map { it.asInt() })

        mark = head()
        val again = client.call(HttpMethod.Post, "/presets", bearer = t, body = mapOf("id" to id, "name" to "Other"))
        assertEquals(200, again.status.value)
        assertEquals("Kanji", again.json()["name"].asText())
        assertEquals(mark, head())

        val upd = client.call(HttpMethod.Patch, "/presets/$id", bearer = t, body = mapOf("easy_days" to listOf(1, 1, 1, 1, 1, 1, 0.5), "fuzz" to false))
        assertEquals(200, upd.status.value, upd.json().toString())
        assertEquals(listOf("fsrs_params.created", "fsrs_params.updated").last(), opsSince(mark).single())
        assertEquals(0.5, upd.json()["easy_days"][6].asDouble())
        assertEquals(false, upd.json()["fuzz"].asBoolean())

        val list = client.call(HttpMethod.Get, "/presets", bearer = t).json()["items"]
        assertEquals("Default", list[0]["name"].asText())
        assertTrue(list.any { it["id"].asText() == id })

        mark = head()
        assertEquals(204, client.call(HttpMethod.Delete, "/presets/$id", bearer = t).status.value)
        assertEquals(listOf("fsrs_params.deleted"), opsSince(mark))
        mark = head()
        assertEquals(204, client.call(HttpMethod.Delete, "/presets/$id", bearer = t).status.value)
        assertEquals(mark, head(), "already deleted: no op")
        assertProblem(client.call(HttpMethod.Get, "/presets/$id", bearer = t), 410, "gone")
        assertProblem(client.call(HttpMethod.Patch, "/presets/$id", bearer = t, body = mapOf("fuzz" to true)), 410, "gone")
        assertProblem(client.call(HttpMethod.Delete, "/presets/00000000-0000-7000-8000-000000000010", bearer = t), 409, "builtin_immutable")
        assertProblem(client.call(HttpMethod.Get, "/presets/${java.util.UUID.randomUUID()}", bearer = t), 404, "not_found")
    }

    @Test
    fun presetValidationFailuresAre422WithNothingMinted() = settingsApp {
        val t = writer()
        val before = head()
        val bad = listOf(
            mapOf("name" to ""),
            mapOf("name" to "x", "desired_retention" to 1.0),
            mapOf("name" to "x", "desired_retention" to 0.5),
            mapOf("name" to "x", "maximum_interval_days" to 36501),
            mapOf("name" to "x", "learning_steps_s" to listOf(0)),
            mapOf("name" to "x", "easy_days" to listOf(1, 1)),
            mapOf("name" to "x", "weights" to listOf(1)),
            mapOf("desired_retention" to 0.9),
        )
        for (b in bad) assertProblem(client.call(HttpMethod.Post, "/presets", bearer = t, body = b), 422, "validation_failed")
        assertProblem(client.call(HttpMethod.Patch, "/presets/00000000-0000-7000-8000-000000000010", bearer = t, body = emptyMap<String, Any>()), 422, "validation_failed")
        assertEquals(before, head())
    }

    @Test
    fun optimizeOnlyEnqueuesAJobAndDedupes() = settingsApp {
        val importer = mint(TokenKind.PERSONAL, listOf("read", "write", "import")).second
        val default = "00000000-0000-7000-8000-000000000010"
        val before = head()
        val first = client.call(HttpMethod.Post, "/presets/$default/optimize", bearer = importer)
        assertEquals(202, first.status.value, first.json().toString())
        assertEquals("fsrs-optimize", first.json()["kind"].asText())
        assertEquals(default, first.json()["payload"]["preset_id"].asText())
        val second = client.call(HttpMethod.Post, "/presets/$default/optimize", bearer = importer)
        assertEquals(200, second.status.value)
        assertEquals(first.json()["id"], second.json()["id"])
        assertEquals(before, head(), "no ops")
        assertProblem(client.call(HttpMethod.Post, "/presets/$default/optimize", bearer = writer()), 403, "insufficient_scope")
    }

    @Test
    fun presetWritesNeedTheWriteScope() = settingsApp {
        val reader = mint(TokenKind.PERSONAL, listOf("read")).second
        val default = "00000000-0000-7000-8000-000000000010"
        val before = head()
        assertProblem(client.call(HttpMethod.Post, "/presets", bearer = reader, body = mapOf("name" to "x")), 403, "insufficient_scope")
        assertProblem(client.call(HttpMethod.Patch, "/presets/$default", bearer = reader, body = mapOf("fuzz" to true)), 403, "insufficient_scope")
        assertProblem(client.call(HttpMethod.Delete, "/presets/$default", bearer = reader), 403, "insufficient_scope")
        assertEquals(before, head())
        assertEquals(200, client.call(HttpMethod.Get, "/presets", bearer = reader).status.value)
        assertEquals(200, client.call(HttpMethod.Get, "/presets/$default", bearer = reader).status.value)
    }
}
