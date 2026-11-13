package kotobaya.android.ankiconnect

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AnkiConnectProtocolTest {
    private fun protocol(backend: AnkiConnectBackend = FakeBackend(), key: String? = null) = AnkiConnectProtocol(backend) { key }

    /** JSON as plain maps/lists (org.json on Android has no structural equality); numbers compare as text. */
    private fun canon(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().associateWith { canon(v.get(it)) }
        is org.json.JSONArray -> (0 until v.length()).map { canon(v.get(it)) }
        JSONObject.NULL -> null
        is Number -> v.toLong().toString()
        else -> v
    }

    @Test
    fun everyFixtureRequestGetsItsExpectedResponse() {
        val files = File("src/test/resources/ankiconnect").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        assertTrue(files.size >= 15, "fixtures missing")
        for (f in files) {
            val fx = JSONObject(f.readText())
            val actual = JSONObject(protocol().handle(fx.getJSONObject("request").toString()))
            assertEquals(canon(fx.getJSONObject("response")), canon(actual), f.name)
        }
    }

    @Test
    fun apiKeyIsRequiredExceptForRequestPermission() {
        val p = protocol(key = "s3cret")
        val none = JSONObject(p.handle("""{"action":"deckNames","version":6}"""))
        assertEquals("valid api key must be provided", none.getString("error"))
        val wrong = JSONObject(p.handle("""{"action":"deckNames","version":6,"key":"x"}"""))
        assertEquals("valid api key must be provided", wrong.getString("error"))
        val ok = JSONObject(p.handle("""{"action":"deckNames","version":6,"key":"s3cret"}"""))
        assertEquals(2, ok.getJSONArray("result").length())
        val perm = JSONObject(p.handle("""{"action":"requestPermission","version":6}"""))
        assertTrue(perm.getJSONObject("result").getBoolean("requireApiKey"))
    }

    @Test
    fun notJsonIsAnEnvelopeError() {
        val r = JSONObject(protocol().handle("nope"))
        assertTrue(r.isNull("result"))
        assertTrue(r.getString("error").startsWith("failed to parse"))
    }

    @Test
    fun mediaReferencesAreRewrittenToTheStoredName() {
        val backend = object : AnkiConnectBackend by FakeBackend() {
            val seen = mutableListOf<AcNote>()
            override fun storeMedia(filename: String, bytes: ByteArray) = "h_$filename"
            override fun addNote(note: AcNote): AcAdd {
                seen += note
                return AcAdd.Added(5)
            }
        }
        val p = protocol(backend)
        p.handle("""{"action":"storeMediaFile","version":6,"params":{"filename":"a.mp3","data":"AAEC"}}""")
        val r = JSONObject(
            p.handle(
                """{"action":"addNote","version":6,"params":{"note":{"deckName":"Mined","modelName":"Takoboto Vocab",
                "fields":{"Expression":"猫","Sentence":"[sound:a.mp3]"},
                "picture":[{"filename":"p.png","data":"AAEC","fields":["Meaning"]}]}}}""",
            ),
        )
        assertEquals(5, r.getInt("result"))
        val n = backend.seen.single()
        assertEquals("[sound:h_a.mp3]", n.fields["Sentence"])
        assertEquals("猫", n.fields["Expression"])
        assertEquals("<img src=\"h_p.png\">", n.fields["Meaning"])
    }

    @Test
    fun urlMediaIsRefusedNotDropped() {
        val r = JSONObject(
            protocol().handle(
                """{"action":"addNote","version":6,"params":{"note":{"deckName":"Mined","modelName":"Takoboto Vocab",
                "fields":{"Expression":"猫"},"audio":[{"filename":"a.mp3","url":"http://x/a.mp3","fields":["Expression"]}]}}}""",
            ),
        )
        assertTrue(r.getString("error").contains("inline base64"))
    }
}
