package kotobaya.android.shell.api.notetypes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotobaya.android.shell.api.cards.LocalWritesSupport.assertOpsValidAndConverge
import kotobaya.android.shell.api.cards.LocalWritesSupport.call
import kotobaya.android.shell.api.cards.LocalWritesSupport.device
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

/** LF-A8: `createNoteType`, `updateNoteType`, `deleteNoteType` answered on the device (SHELL.md 4.3, L-10). */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class NoteTypeWritesTest {
    private val scenario = "note-type-writes-local"
    private val custom = "0193b000-0007-7000-8000-000000000001"
    private val vocab = "00000000-0000-7000-8000-000000000101"
    private val t0 = 1_795_168_800_000L
    private val me = "01931f3e-0000-7000-8000-0000000000d4"
    private val fields3 = """[{"key":"expression","label":"Expression","kind":"text"},{"key":"reading","label":"Reading","kind":"text"},{"key":"meaning","label":"Meaning","kind":"text"}]"""
    private val templates = """{"meaning":{"default":true,"front":null,"back":null}}"""

    private fun create(extra: String = "") = """{"id":"$custom","name":"Custom vocab","base_type":"vocab","fields":$fields3,"templates":$templates$extra}"""

    @Test
    fun noteTypeWritesLocalScenarioPassesWithoutTheUpstream() {
        val d = runScenario(scenario)
        val types = d.mintedTypes()
        assertEquals("note_type.created", types.first())
        assertEquals("note_type.updated", types[1])
        assertEquals(listOf("note.deleted", "note_type.deleted"), types.takeLast(2))
        assertEquals(1, types.count { it.startsWith("note_type.deleted") })
        assertEquals(
            ParityJson.parse("""{"id":"$custom","name":"Custom vocab","base_type":"vocab","fields":$fields3,"templates":$templates,"css":""}"""),
            ParityJson.at(d.minted().first(), "/payload"),
        )
    }

    @Test
    fun mintedNoteTypeOpsValidateAndConvergeInAnyOrder() {
        val d = device(me, t0, emptyList())
        assertEquals(201, d.call("POST", "/api/v1/note-types", create(), at = t0 + 1_000).status)
        assertEquals(200, d.call("PATCH", "/api/v1/note-types/$custom", """{"name":"Renamed","css":".x{}"}""", at = t0 + 2_000).status)
        assertEquals(200, d.call("PATCH", "/api/v1/note-types/$vocab", """{"css":".v{}"}""", at = t0 + 3_000).status)
        assertEquals(204, d.call("DELETE", "/api/v1/note-types/$custom", at = t0 + 4_000).status)
        assertEquals(listOf("note_type.created", "note_type.updated", "note_type.updated", "note_type.deleted"), d.mintedTypes())
        assertOpsValidAndConverge(d, emptyList(), listOf(LearningTable.NOTE_TYPE), t0 + 60_000, setOf("note_type.created"))
    }

    @Test
    fun createTwiceAndDeleteTwiceMintOneOpEach() {
        val d = device(me, t0, emptyList())
        val first = d.call("POST", "/api/v1/note-types", create())
        assertEquals(201, first.status)
        val again = d.call("POST", "/api/v1/note-types", """{"id":"$custom","name":"Other","base_type":"custom","fields":[{"key":"front","label":"F","kind":"text"}],"templates":{"basic":{"default":true,"front":null,"back":null}}}""")
        assertEquals(200, again.status)
        assertEquals(first.json(), again.json())
        assertEquals(204, d.call("DELETE", "/api/v1/note-types/$custom").status)
        assertEquals(204, d.call("DELETE", "/api/v1/note-types/$custom").status)
        assertEquals(listOf("note_type.created", "note_type.deleted"), d.mintedTypes())
    }

    @Test
    fun refusalsMintNothing() {
        val d = device(me, t0, emptyList())
        val unknown = "0193b000-0007-7000-8000-0000000000ff"
        // built-in: cannot be deleted, cannot lose a seeded field key
        assertEquals("409 builtin_immutable", d.call("DELETE", "/api/v1/note-types/$vocab").problem())
        assertEquals("409 builtin_immutable", d.call("PATCH", "/api/v1/note-types/$vocab", """{"fields":$fields3}""").problem())
        // a vocab type without meaning, at create and at update
        val two = """[{"key":"expression","label":"E","kind":"text"},{"key":"reading","label":"R","kind":"text"}]"""
        assertEquals("422 /fields invalid_value", d.call("POST", "/api/v1/note-types", """{"name":"B","base_type":"vocab","fields":$two,"templates":$templates}""").problem())
        assertEquals("422 /fields invalid_value", d.call("PATCH", "/api/v1/note-types/$vocab", """{"fields":$two}""").problem())
        assertEquals(
            "422 /fields/1/key invalid_value",
            d.call("POST", "/api/v1/note-types", """{"name":"B","base_type":"custom","fields":[{"key":"a","label":"A","kind":"text"},{"key":"a","label":"A","kind":"text"}],"templates":$templates}""").problem(),
        )
        assertEquals("422 /base_type invalid_value", d.call("POST", "/api/v1/note-types", """{"name":"B","base_type":"reading","fields":$fields3,"templates":$templates}""").problem())
        assertEquals("422 /base_type unknown_field", d.call("PATCH", "/api/v1/note-types/$vocab", """{"base_type":"custom"}""").problem())
        assertEquals("404 not_found", d.call("PATCH", "/api/v1/note-types/$unknown", """{"css":""}""").problem())
        assertEquals("404 not_found", d.call("DELETE", "/api/v1/note-types/$unknown").problem())
        // in use by a live note of the replica
        assertEquals(201, d.call("POST", "/api/v1/note-types", create()).status)
        val note = "0193b000-0001-7000-8000-000000000001"
        assertEquals(
            201,
            d.call("POST", "/api/v1/notes", """{"id":"$note","note_type_id":"$custom","fields":{"expression":"x","reading":"x","meaning":"x"}}""").status,
        )
        assertEquals("409 in_use", d.call("DELETE", "/api/v1/note-types/$custom").problem())
        assertEquals(204, d.call("DELETE", "/api/v1/notes/$note").status)
        assertEquals(204, d.call("DELETE", "/api/v1/note-types/$custom").status)
        assertEquals("410 gone", d.call("PATCH", "/api/v1/note-types/$custom", """{"css":""}""").problem())
        assertEquals(listOf("note_type.created", "note.created", "note.deleted", "note_type.deleted"), d.mintedTypes().filter { !it.startsWith("card.") })
    }

    @Test
    fun aBuiltInTypeMayGainAFieldAndATemplate() {
        val d = device(me, t0, emptyList())
        val current = d.call("GET", "/api/v1/note-types/$vocab").json()
        val extra = """{"key":"extra","label":"Extra","kind":"text"}"""
        val fields = ParityJson.encode(current.members["fields"]!!).dropLast(1) + ",$extra]"
        val r = d.call("PATCH", "/api/v1/note-types/$vocab", """{"fields":$fields,"templates":{"extra":{"default":false,"front":"{{extra}}","back":null}}}""")
        assertEquals(200, r.status)
        assertEquals(JsonString("extra"), ParityJson.at(r.json(), "/fields/9/key"))
        assertEquals(JsonString("{{extra}}"), ParityJson.at(r.json(), "/templates/extra/front"))
        assertEquals(listOf("note_type.updated"), d.mintedTypes())
    }

    @Test
    fun anOpOverMaxOpBytesIs413AndMintsNothing() {
        val d = device(me, t0, emptyList())
        val big = "あ".repeat(100_000)
        val tpl = (1..3).joinToString(",") { n -> "\"k$n\":" + """{"default":true,"front":"$big","back":"$big"}""" }
        val r = d.call("POST", "/api/v1/note-types", """{"name":"Big","base_type":"custom","fields":[{"key":"front","label":"F","kind":"text"}],"templates":{$tpl}}""")
        assertEquals("413 payload_too_large", r.problem())
        assertEquals(emptyList(), d.mintedTypes())
    }
}
