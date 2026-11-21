package kotobaya.android.shell.api.grammar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.cards.LocalWritesSupport.assertOpsValidAndConverge
import kotobaya.android.shell.api.cards.LocalWritesSupport.call
import kotobaya.android.shell.api.cards.LocalWritesSupport.device
import kotobaya.android.shell.api.cards.LocalWritesSupport.items
import kotobaya.android.shell.api.cards.LocalWritesSupport.json
import kotobaya.android.shell.api.cards.LocalWritesSupport.mintedTypes
import kotobaya.android.shell.api.cards.LocalWritesSupport.problem
import kotobaya.android.shell.api.cards.LocalWritesSupport.runScenario
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTable
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** LF-A8b: grammar points and manual spans written and read on the device (SHELL.md 4.3, L-10). */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class GrammarWritesTest {
    private val scenario = "grammar-writes-local"
    private val g1 = "0193b000-0008-7000-8000-000000000001"
    private val g2 = "0193b000-0008-7000-8000-000000000002"
    private val g3 = "0193b000-0008-7000-8000-000000000003"
    private val s1 = "0193b000-0009-7000-8000-000000000001"
    private val media = "0193b000-000a-7000-8000-000000000001"
    private val segment = "0193b000-000b-7000-8000-000000000001"
    private val onDevice = "0193b000-0009-7000-8000-0000000000aa"
    private val t0 = 1_795_168_800_000L
    private val me = "01931f3e-0000-7000-8000-0000000000d4"

    private fun d(): ParityDevice = device(me, t0, emptyList())

    private fun ParityDevice.create(id: String, title: String, extra: String = "") =
        call("POST", "/api/v1/grammar", """{"id":"$id","title":"$title"$extra}""")

    private fun spanPath(point: String, sentence: String, start: Int, end: Int) = "/api/v1/grammar/$point/spans/$sentence/$start/$end"

    /** A media snapshot sentence on the device (as `ContentInstaller` writes it), text of 5 UTF-16 units. */
    private fun ParityDevice.installSentence() {
        fun run(sql: String) = driver.execute(null, sql, 0)
        run("INSERT INTO media (id, title, kind, source, created_at, updated_at, installed_content_version) VALUES ('$media', 'T', 'anime', 'file', 1, 1, 1)")
        run("INSERT INTO media_segment (id, media_id, ordinal, kind) VALUES ('$segment', '$media', 0, 'episode')")
        run("INSERT INTO sentence (id, media_id, segment_id, ordinal, text) VALUES ('$onDevice', '$media', '$segment', 0, '食べざるを')")
    }

    @Test
    fun grammarWritesLocalScenarioPassesWithoutTheUpstream() {
        val d = runScenario(scenario)
        assertEquals(
            listOf("grammar.created", "grammar.updated", "grammar.span_put", "grammar.span_removed", "grammar.deleted"),
            d.mintedTypes(),
        )
        val ops = d.minted()
        assertEquals(ParityJson.parse("""{"id":"$g1","title":"ざるを得ない","pattern":"〜ざるを得ない","jlpt":2}"""), ParityJson.at(ops[0], "/payload"))
        assertEquals(ParityJson.parse("""{"id":"$g1","set":{"meaning":"cannot help but","jlpt":null}}"""), ParityJson.at(ops[1], "/payload"))
        assertEquals(
            ParityJson.parse("""{"sentence_id":"$s1","grammar_point_id":"$g1","start_offset":0,"end_offset":5,"media_id":null}"""),
            ParityJson.at(ops[2], "/payload"),
        )
        assertEquals(ParityJson.parse("""{"sentence_id":"$s1","grammar_point_id":"$g1","start_offset":0,"end_offset":5}"""), ParityJson.at(ops[3], "/payload"))
    }

    @Test
    fun mintedGrammarOpsValidateAndConvergeInAnyOrder() {
        val d = runScenario(scenario)
        assertOpsValidAndConverge(
            d, emptyList(), listOf(LearningTable.GRAMMAR_POINT, LearningTable.SENTENCE_GRAMMAR_SPAN), t0 + 60_000, setOf("grammar.created"),
        )
    }

    @Test
    fun createTwiceDeleteTwicePutTwiceRemoveTwiceMintOneOpEach() {
        val d = d()
        assertEquals(201, d.create(g1, "A").status)
        val again = d.create(g1, "B", ""","jlpt":3""")
        assertEquals(200, again.status)
        assertEquals(JsonString("A"), again.json().members["title"])
        assertEquals(200, d.call("PUT", spanPath(g1, s1, 1, 3)).status)
        assertEquals(200, d.call("PUT", spanPath(g1, s1, 1, 3), "{}").status)
        assertEquals(204, d.call("DELETE", spanPath(g1, s1, 1, 3)).status)
        assertEquals(204, d.call("DELETE", spanPath(g1, s1, 1, 3)).status)
        assertEquals(204, d.call("DELETE", "/api/v1/grammar/$g1").status)
        assertEquals(204, d.call("DELETE", "/api/v1/grammar/$g1").status)
        // a deleted point still "exists" for a create with its id (L-4)
        assertEquals(200, d.create(g1, "C").status)
        // removing a span of a deleted point is no refusal (as the server); there is none, so nothing is minted
        assertEquals(204, d.call("DELETE", spanPath(g1, s1, 1, 3)).status)
        assertEquals(
            listOf("grammar.created", "grammar.span_put", "grammar.span_removed", "grammar.deleted"),
            d.mintedTypes(),
        )
        assertEquals(4, d.localChanges)
    }

    @Test
    fun refusalsMintNothing() {
        val d = d()
        val unknown = "0193b000-0008-7000-8000-0000000000ff"
        assertEquals("404 not_found", d.call("GET", "/api/v1/grammar/$unknown").problem())
        assertEquals("404 not_found", d.call("GET", "/api/v1/grammar/not-a-uuid").problem())
        assertEquals("404 not_found", d.call("PATCH", "/api/v1/grammar/$unknown", """{"title":"x"}""").problem())
        assertEquals("404 not_found", d.call("DELETE", "/api/v1/grammar/$unknown").problem())
        assertEquals("404 not_found", d.call("PUT", spanPath(unknown, s1, 0, 1)).problem())
        assertEquals("404 not_found", d.call("DELETE", spanPath(unknown, s1, 0, 1)).problem())
        assertEquals("422 /title required", d.call("POST", "/api/v1/grammar", """{"pattern":"x"}""").problem())
        assertEquals("422 /jlpt invalid_value", d.create(g1, "A", ""","jlpt":6""").problem())
        assertEquals("422 /is_stub invalid_type", d.create(g1, "A", ""","is_stub":"yes"""").problem())
        assertEquals("422 /detection_rules/0/rule required", d.create(g1, "A", ""","detection_rules":[{"draft":true}]""").problem())
        assertEquals("422 /detection_rules/0/x unknown_field", d.create(g1, "A", ""","detection_rules":[{"rule":"a","x":1}]""").problem())
        assertEquals("422 /related_ids invalid_value", d.create(g1, "A", ""","related_ids":["$g2","$g2"]""").problem())
        assertEquals("422 /links/0/url required", d.create(g1, "A", ""","links":[{"title":"t"}]""").problem())
        assertEquals("422 /id invalid_value", d.call("POST", "/api/v1/grammar", """{"id":"x","title":"A"}""").problem())
        assertEquals("422 /color unknown_field", d.create(g1, "A", ""","color":1""").problem())
        assertEquals(201, d.create(g1, "A").status)
        assertEquals("422  required", d.call("PATCH", "/api/v1/grammar/$g1", "{}").problem())
        assertEquals("422 /title invalid_value", d.call("PATCH", "/api/v1/grammar/$g1", """{"title":""}""").problem())
        assertEquals("422 /end_offset invalid_value", d.call("PUT", spanPath(g1, s1, 3, 3)).problem())
        assertEquals("422 /start_offset invalid_value", d.call("PUT", spanPath(g1, s1, -1, 3)).problem())
        assertEquals("422 /media_id invalid_value", d.call("PUT", spanPath(g1, s1, 0, 3), """{"media_id":"x"}""").problem())
        assertEquals("422 /other unknown_field", d.call("PUT", spanPath(g1, s1, 0, 3), """{"other":1}""").problem())
        assertEquals(204, d.call("DELETE", "/api/v1/grammar/$g1").status)
        assertEquals("410 gone", d.call("GET", "/api/v1/grammar/$g1").problem())
        assertEquals("410 gone", d.call("PATCH", "/api/v1/grammar/$g1", """{"title":"x"}""").problem())
        assertEquals("410 gone", d.call("PUT", spanPath(g1, s1, 0, 3)).problem())
        assertEquals(200, d.call("GET", "/api/v1/grammar/$g1", query = "include_deleted=true").status)
        assertEquals(listOf("grammar.created", "grammar.deleted"), d.mintedTypes())
    }

    @Test
    fun detectionRulesAreCheckedForShapeOnly() {
        val d = d()
        val r = d.create(g1, "A", ""","detection_rules":[{"rule":"((( not a pattern","draft":true},{"rule":"ざる を"}]""")
        assertEquals(201, r.status)
        assertEquals(
            ParityJson.parse("""[{"rule":"((( not a pattern","draft":true},{"rule":"ざる を"}]"""),
            r.json().members["detection_rules"],
        )
        assertEquals(ParityJson.at(r.json(), "/detection_rules"), ParityJson.at(d.minted().single(), "/payload/detection_rules"))
    }

    @Test
    fun aSpanOnASentenceOfTheDeviceTakesItsMediaAndCountsAsAnExample() {
        val d = d()
        d.installSentence()
        assertEquals(201, d.create(g1, "A").status)
        assertEquals(JsonNumber(0.0), d.call("GET", "/api/v1/grammar/$g1").json().members["example_count"])
        assertEquals("422 /end_offset out_of_range", d.call("PUT", spanPath(g1, onDevice, 0, 6)).problem())
        val other = "0193b000-000a-7000-8000-0000000000ff"
        val put = d.call("PUT", spanPath(g1, onDevice, 0, 5), """{"media_id":"$other"}""")
        assertEquals(200, put.status)
        assertEquals(JsonString(media), put.json().members["media_id"])
        assertEquals(JsonString("manual"), put.json().members["origin"])
        assertEquals(JsonNull, put.json().members["rule_index"])
        assertEquals(JsonString(media), ParityJson.at(d.minted().last(), "/payload/media_id"))
        // a span on a sentence the device does not have is stored with the hint, and not counted (documented difference)
        val away = d.call("PUT", spanPath(g1, s1, 0, 50), """{"media_id":"$other"}""")
        assertEquals(JsonString(other), away.json().members["media_id"])
        assertEquals(JsonNumber(1.0), d.call("GET", "/api/v1/grammar/$g1").json().members["example_count"])
        assertEquals(204, d.call("DELETE", spanPath(g1, onDevice, 0, 5)).status)
        assertEquals(JsonNumber(0.0), d.call("GET", "/api/v1/grammar/$g1").json().members["example_count"])
    }

    @Test
    fun theListFiltersAndPagesAsTheServer() {
        val d = d()
        assertEquals(201, d.create(g1, "ざるを得ない", ""","jlpt":2,"meaning":"Cannot help but"""").status)
        assertEquals(201, d.create(g2, "ても", ""","jlpt":4,"is_stub":true""").status)
        assertEquals(201, d.create(g3, "わけ", "").status)
        fun ids(query: String) = d.call("GET", "/api/v1/grammar", query = query).items().map { ParityJson.str(it.members["id"]) }
        assertEquals(listOf(g2, g1, g3), ids(""))
        assertEquals(listOf(g1), ids("q=CANNOT"))
        assertEquals(listOf(g1), ids("q=%E3%81%96%E3%82%8B"))
        assertEquals(listOf(g2), ids("is_stub=true"))
        assertEquals(listOf(g1), ids("jlpt=2"))
        val page1 = d.call("GET", "/api/v1/grammar", query = "limit=2").json()
        val cursor = ParityJson.str(page1.members["next_cursor"])
        assertEquals(listOf(g3), ids("limit=2&cursor=$cursor"))
        assertEquals("422 /cursor invalid_value", d.call("GET", "/api/v1/grammar", query = "jlpt=2&cursor=$cursor").problem())
        assertEquals("422 /jlpt invalid_value", d.call("GET", "/api/v1/grammar", query = "jlpt=0").problem())
        assertEquals("422 /is_stub invalid_value", d.call("GET", "/api/v1/grammar", query = "is_stub=1").problem())
        assertEquals("422 /sort unknown_field", d.call("GET", "/api/v1/grammar", query = "sort=title").problem())
        assertEquals(204, d.call("DELETE", "/api/v1/grammar/$g3").status)
        assertEquals(listOf(g2, g1), ids(""))
        assertEquals(listOf(g2, g1, g3), ids("include_deleted=true"))
        assertTrue(d.forwarded.isEmpty())
    }
}
