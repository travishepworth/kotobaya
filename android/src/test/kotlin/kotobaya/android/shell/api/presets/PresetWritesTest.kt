package kotobaya.android.shell.api.presets

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
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTable
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** LF-A8: `createPreset`, `updatePreset`, `deletePreset` answered on the device (SHELL.md 4.3, L-10). */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class PresetWritesTest {
    private val scenario = "preset-writes-local"
    private val kanji = "0193b000-0006-7000-8000-000000000001"
    private val default = "00000000-0000-7000-8000-000000000010"
    private val t0 = 1_795_168_800_000L
    private val me = "01931f3e-0000-7000-8000-0000000000d4"

    @Test
    fun presetWritesLocalScenarioPassesWithoutTheUpstream() {
        val d = runScenario(scenario)
        assertEquals(listOf("fsrs_params.created", "fsrs_params.updated", "fsrs_params.deleted"), d.mintedTypes())
        val ops = d.minted()
        // the server's payloads: the options present, in its order; the registers present
        assertEquals(ParityJson.parse("""{"id":"$kanji","name":"Kanji","desired_retention":0.85}"""), ParityJson.at(ops[0], "/payload"))
        assertEquals(
            ParityJson.parse("""{"id":"$kanji","set":{"desired_retention":0.88,"learning_steps_s":[60,600,3600]}}"""),
            ParityJson.at(ops[1], "/payload"),
        )
    }

    @Test
    fun mintedPresetOpsValidateAndConvergeInAnyOrder() {
        val d = runScenario(scenario)
        assertOpsValidAndConverge(d, emptyList(), listOf(LearningTable.FSRS_PARAMS), t0 + 60_000, setOf("fsrs_params.created"))
    }

    @Test
    fun createTwiceAndDeleteTwiceMintOneOpEach() {
        val d = device(me, t0, emptyList())
        assertEquals(201, d.call("POST", "/api/v1/presets", """{"id":"$kanji","name":"Kanji"}""").status)
        val again = d.call("POST", "/api/v1/presets", """{"id":"$kanji","name":"Else","fuzz":false}""")
        assertEquals(200, again.status)
        assertEquals(JsonString("Kanji"), again.json().members["name"])
        assertEquals(204, d.call("DELETE", "/api/v1/presets/$kanji").status)
        assertEquals(204, d.call("DELETE", "/api/v1/presets/$kanji").status)
        // a deleted preset still "exists" for a create with its id (L-4)
        assertEquals(200, d.call("POST", "/api/v1/presets", """{"id":"$kanji","name":"Kanji"}""").status)
        assertEquals(listOf("fsrs_params.created", "fsrs_params.deleted"), d.mintedTypes())
        assertEquals(2, d.localChanges)
    }

    @Test
    fun refusalsMintNothing() {
        val d = device(me, t0, emptyList())
        val unknown = "0193b000-0006-7000-8000-0000000000ff"
        assertEquals("409 builtin_immutable", d.call("DELETE", "/api/v1/presets/$default").problem())
        assertEquals("404 not_found", d.call("DELETE", "/api/v1/presets/$unknown").problem())
        assertEquals("404 not_found", d.call("PATCH", "/api/v1/presets/$unknown", """{"name":"x"}""").problem())
        assertEquals("422 /name required", d.call("POST", "/api/v1/presets", """{"fuzz":true}""").problem())
        assertEquals("422 /easy_days invalid_value", d.call("POST", "/api/v1/presets", """{"name":"x","easy_days":[1,1]}""").problem())
        assertEquals("422 /learning_steps_s invalid_value", d.call("POST", "/api/v1/presets", """{"name":"x","learning_steps_s":[0]}""").problem())
        assertEquals("422 /maximum_interval_days out_of_range", d.call("POST", "/api/v1/presets", """{"name":"x","maximum_interval_days":36501}""").problem())
        assertEquals("422  required", d.call("PATCH", "/api/v1/presets/$default", "{}").problem())
        assertEquals(201, d.call("POST", "/api/v1/presets", """{"id":"$kanji","name":"Kanji"}""").status)
        assertEquals(204, d.call("DELETE", "/api/v1/presets/$kanji").status)
        assertEquals("410 gone", d.call("PATCH", "/api/v1/presets/$kanji", """{"name":"x"}""").problem())
        assertEquals(listOf("fsrs_params.created", "fsrs_params.deleted"), d.mintedTypes())
    }

    @Test
    fun theDefaultPresetCanBeUpdatedAndTheListShowsItAtOnce() {
        val d = device(me, t0, emptyList())
        assertEquals(200, d.call("PATCH", "/api/v1/presets/$default", """{"desired_retention":0.8}""").status)
        val listed = d.call("GET", "/api/v1/presets").items().single { it.members["id"] == JsonString(default) }
        assertEquals(0.8, ParityJson.at(listed, "/desired_retention").let { (it as kotobaya.contracts.sync.JsonNumber).value })
        assertTrue(d.forwarded.isEmpty())
    }
}
