package kotobaya.android.shell.api.struggle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.struggle.StruggleTestSupport.C1
import kotobaya.android.shell.api.struggle.StruggleTestSupport.C2
import kotobaya.android.shell.api.struggle.StruggleTestSupport.N1
import kotobaya.android.shell.api.struggle.StruggleTestSupport.call
import kotobaya.android.shell.api.struggle.StruggleTestSupport.device
import kotobaya.android.shell.api.struggle.StruggleTestSupport.items
import kotobaya.android.shell.api.struggle.StruggleTestSupport.json
import kotobaya.android.shell.api.struggle.StruggleTestSupport.problem
import kotobaya.android.shell.api.struggle.StruggleTestSupport.struggled
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SHELL-L8 parity scenarios (SHELL.md L-3) for the three struggle lists and a `hot_words` drill session, against the
 * answers of the server's `StruggleModule` / `DrillApi` for the same history: shapes, order, statuses and problem
 * codes. The struggle scenarios have no `contracts/api/shell/parity` file yet (as the deck ones), so they are written
 * out here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class StruggleParityTest {
    private fun str(o: JsonObject, k: String) = ParityJson.str(o.members[k])

    @Test
    fun emptyReplicaHasNoStruggle() {
        val d = device()
        assertEquals(emptyList(), call(d, "GET", "/api/v1/words/hot").items())
        assertEquals(emptyList(), call(d, "GET", "/api/v1/kanji/hot").items())
        val leeches = call(d, "GET", "/api/v1/words/leeches").json()
        assertEquals(emptyList(), (leeches.members["items"] as JsonArray).items)
        assertEquals(JsonNull, leeches.members["next_cursor"])
        val session = call(d, "POST", "/api/v1/drill/session", """{"source":"hot_words"}""").json()
        assertEquals(emptyList(), (session.members["items"] as JsonArray).items)
        assertEquals(JsonString("drill"), session.members["mode"])
    }

    @Test
    fun hotWordsAreTheRecentFailuresHottestFirst() {
        val d = struggled()
        val items = call(d, "GET", "/api/v1/words/hot").items()
        val first = items.first()
        // the lapse in review state outranks every other note (a relearning failure weighs extra)
        assertEquals(N1, str(first, "note_id"))
        assertEquals(JsonNumber(1001010.0), first.members["entry_seq"])
        assertEquals(JsonNumber(1.0), first.members["failures_14d"])
        assertTrue((first.members["score"] as JsonNumber).value > 0.0)
        assertEquals(items.map { (it.members["score"] as JsonNumber).value }, items.map { (it.members["score"] as JsonNumber).value }.sortedDescending())
        val entry = ParityJson.obj(first.members["entry"] ?: error("no entry"))
        assertEquals(JsonNumber(1001010.0), entry.members["seq"])
        assertEquals(JsonString(N1), entry.members["note_id"])
        assertEquals(JsonString("learning"), entry.members["status"])
        assertTrue(str(entry, "headword").isNotEmpty())
        val note = ParityJson.obj(first.members["note"] ?: error("no note"))
        assertEquals(JsonString(N1), note.members["id"])
        assertEquals(JsonString("vocab"), note.members["type"])
        val cards = (first.members["cards"] as JsonArray).items.map(ParityJson::obj)
        assertEquals(listOf(JsonString(C1)), cards.map { it.members["id"] })
        assertTrue(cards.all { "note" !in it.members }, "a hot word's cards omit their note")
        assertEquals(JsonBool(true), cards.single().members["leech"])
        assertEquals(JsonNumber(1.0), cards.single().members["lapses"])
        assertEquals(1, call(d, "GET", "/api/v1/words/hot", query = "limit=1").items().size)
    }

    @Test
    fun undoingTheFailureTakesTheWordOffTheList() {
        val d = struggled()
        val c2Failure = kotobaya.android.shell.api.review.AnswerTestSupport.reviewId(5)
        assertEquals(200, call(d, "POST", "/api/v1/review/undo", """{"review_id":"$c2Failure"}""", at = StruggleTestSupport.NOW).status)
        val c1Failure = kotobaya.android.shell.api.review.AnswerTestSupport.reviewId(4)
        assertEquals(200, call(d, "POST", "/api/v1/review/undo", """{"review_id":"$c1Failure"}""", at = StruggleTestSupport.NOW).status)
        assertEquals(emptyList(), call(d, "GET", "/api/v1/words/hot").items())
    }

    @Test
    fun hotWordsFallOutOfTheWindow() {
        val d = struggled()
        val later = StruggleTestSupport.T0 + 15L * 86_400_000L
        d.clock.now = later
        assertEquals(emptyList(), call(d, "GET", "/api/v1/words/hot").items())
    }

    @Test
    fun hotKanjiSumTheHotWordScores() {
        val d = struggled()
        // the seed's entries are kana-only (C-28): point the hot note at a fixture entry with kanji (学校)
        assertEquals(200, call(d, "PATCH", "/api/v1/notes/$N1", """{"entry_seq":1206730}""", StruggleTestSupport.NOW).status)
        val words = call(d, "GET", "/api/v1/words/hot").items()
        val kanji = call(d, "GET", "/api/v1/kanji/hot").items()
        assertTrue(kanji.isNotEmpty(), "the fixture entry of the hot word has kanji")
        val total = words.sumOf { (it.members["score"] as JsonNumber).value }
        for (k in kanji) {
            assertEquals(1, str(k, "literal").codePointCount(0, str(k, "literal").length))
            val score = (k.members["score"] as JsonNumber).value
            assertTrue(score > 0.0 && score <= total + 1e-9)
            val count = (k.members["word_count"] as JsonNumber).value.toInt()
            assertTrue(count in 1..words.size)
            assertTrue(k.members["meanings"] is JsonArray)
            assertTrue(k.members["note_id"] == JsonNull || k.members["note_id"] is JsonString)
        }
        assertEquals(kanji.map { (it.members["score"] as JsonNumber).value }.sortedDescending(), kanji.map { (it.members["score"] as JsonNumber).value })
        assertEquals(1, call(d, "GET", "/api/v1/kanji/hot", query = "limit=1").items().size)
    }

    @Test
    fun leechesAreLapsesAtOrAboveTheThreshold() {
        val d = struggled()
        val page = call(d, "GET", "/api/v1/words/leeches").json()
        val cards = (page.members["items"] as JsonArray).items.map(ParityJson::obj)
        assertEquals(listOf(JsonString(C1)), cards.map { it.members["id"] })
        assertEquals(JsonNull, page.members["next_cursor"])
        assertEquals(JsonString(N1), ParityJson.obj(cards.single().members["note"] ?: error("no note")).members["id"])
        // C2 has no lapse yet: a threshold of 0 would make it one, the threshold of 1 does not
        assertTrue(C2 !in cards.map { str(it, "id") })
    }

    @Test
    fun leechesPageWithOffsetCursors() {
        val d = struggled()
        assertEquals(200, call(d, "PATCH", "/api/v1/settings", """{"values":{"scheduler.leech_threshold":0}}""", at = StruggleTestSupport.NOW).status)
        val first = call(d, "GET", "/api/v1/words/leeches", query = "limit=1").json()
        assertEquals(listOf(JsonString(C1)), (first.members["items"] as JsonArray).items.map { ParityJson.obj(it).members["id"] })
        val cursor = ParityJson.str(first.members["next_cursor"])
        val second = call(d, "GET", "/api/v1/words/leeches", query = "limit=1&cursor=$cursor").json()
        assertEquals(listOf(JsonString(C2)), (second.members["items"] as JsonArray).items.map { ParityJson.obj(it).members["id"] })
        assertEquals(JsonNull, second.members["next_cursor"])
    }

    @Test
    fun parameterErrorsAreTheServers() {
        val d = struggled()
        assertEquals("422 /limit out_of_range", call(d, "GET", "/api/v1/words/hot", query = "limit=0").problem())
        assertEquals("422 /limit out_of_range", call(d, "GET", "/api/v1/kanji/hot", query = "limit=201").problem())
        assertEquals("422 /foo unknown_field", call(d, "GET", "/api/v1/words/hot", query = "foo=1").problem())
        assertEquals("422 /limit out_of_range", call(d, "GET", "/api/v1/words/leeches", query = "limit=501").problem())
        assertEquals("422 /cursor invalid_value", call(d, "GET", "/api/v1/words/leeches", query = "cursor=zzz").problem())
    }

    @Test
    fun hotWordsDrillSessionHasTheHottestCardFirstWithoutPreview() {
        val d = struggled()
        val resp = call(d, "POST", "/api/v1/drill/session", """{"source":"hot_words","limit":5}""")
        assertEquals(200, resp.status)
        val session = resp.json()
        assertTrue(ParityJson.str(session.members["id"]).matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}$")))
        assertEquals(JsonString("drill"), session.members["mode"])
        val items = (session.members["items"] as JsonArray).items.map(ParityJson::obj)
        val hot = call(d, "GET", "/api/v1/words/hot").items().map { str(it, "note_id") }
        assertEquals(C1, ParityJson.str(ParityJson.obj(items.first().members["card"] ?: error("no card")).members["id"]))
        assertEquals(hot.size, items.size, "one card per hot note here")
        for (i in items) {
            assertEquals(JsonString("drill"), i.members["review_type"])
            assertEquals(JsonNull, i.members["preview"])
            assertNotEquals(JsonNull, i.members["entry"])
            assertTrue("text" in i.members && "decks" in i.members && "deck_count" in i.members)
        }
        // another session, another id
        assertNotEquals(session.members["id"], call(d, "POST", "/api/v1/drill/session", """{"source":"hot_words"}""").json().members["id"])
    }

    @Test
    fun otherSourcesAndModes() {
        val d = struggled()
        fun ids(body: String): List<String> =
            (call(d, "POST", "/api/v1/drill/session", body).json().members["items"] as JsonArray).items
                .map { ParityJson.str(ParityJson.obj(ParityJson.obj(it).members["card"] ?: error("no card")).members["id"]) }
        assertEquals(listOf(C1), ids("""{"source":"leeches"}"""))
        assertEquals(listOf(C2, C1), ids("""{"source":"cards","card_ids":["$C2","$C1","0193a1b2-0002-7000-8000-0000000000ff"]}"""))
        assertEquals(listOf(C2), ids("""{"source":"cards","card_ids":["$C2","$C1"],"limit":1}"""))
        assertEquals(emptyList(), ids("""{"source":"confusables"}"""))
        assertEquals(listOf(C1, C2), ids("""{"source":"query","query":"is:learning OR is:relearning"}"""))
        val cram = call(d, "POST", "/api/v1/drill/session", """{"source":"cards","card_ids":["$C1"],"mode":"cram"}""").json()
        assertEquals(JsonString("cram"), cram.members["mode"])
        assertEquals(JsonString("cram"), ParityJson.obj((cram.members["items"] as JsonArray).items.single()).members["review_type"])
    }

    @Test
    fun drillRequestErrorsAreTheServers() {
        val d = struggled()
        fun post(body: String) = call(d, "POST", "/api/v1/drill/session", body).problem()
        assertEquals("422 /source required", post("{}"))
        assertEquals("422 /source invalid_value", post("""{"source":"nope"}"""))
        assertEquals("422 /foo unknown_field", post("""{"source":"hot_words","foo":1}"""))
        assertEquals("422 /mode invalid_value", post("""{"source":"hot_words","mode":"schedule"}"""))
        assertEquals("422 /limit out_of_range", post("""{"source":"hot_words","limit":501}"""))
        assertEquals("422 /query required", post("""{"source":"query"}"""))
        assertEquals("422 /card_ids required", post("""{"source":"cards"}"""))
        assertEquals("422 /card_ids invalid_value", post("""{"source":"cards","card_ids":[]}"""))
        assertEquals("422 /card_ids/0 invalid_value", post("""{"source":"cards","card_ids":["x"]}"""))
        assertEquals("422 /card_ids invalid_value", post("""{"source":"cards","card_ids":["$C1","$C1"]}"""))
    }
}
