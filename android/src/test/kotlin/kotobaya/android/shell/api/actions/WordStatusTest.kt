package kotobaya.android.shell.api.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotobaya.android.shell.api.actions.ActionsTestSupport.T0
import kotobaya.android.shell.api.actions.ActionsTestSupport.call
import kotobaya.android.shell.api.actions.ActionsTestSupport.device
import kotobaya.android.shell.api.actions.ActionsTestSupport.iso
import kotobaya.android.shell.api.actions.ActionsTestSupport.json
import kotobaya.android.shell.api.actions.ActionsTestSupport.mintedTypes
import kotobaya.android.shell.api.actions.ActionsTestSupport.problem
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.parity.ParityJson

/** `getWordStatus`, `setWordStatus`, `clearWordStatus` (openapi `/words/{seq}/status`; SHELL.md L-2, L-4). */
class WordStatusTest {
    private val path = "/api/v1/words/1000220/status"

    @Test
    fun anEntryWithoutARowIsNew() {
        val d = device()
        val res = d.call("GET", "/api/v1/words/999999999/status")
        assertEquals(
            ParityJson.parse("""{"entry_seq":999999999,"status":"new","auto_status":null,"override_status":null,"override_origin":null,"updated_at":null}"""),
            res.json(),
        )
    }

    @Test
    fun setMintsAManualOverrideEvenWhenUnchangedAndClearFallsBack() {
        val d = device()
        val before = d.call("GET", path).json()
        val set = d.call("PUT", path, """{"status":"never_forget"}""", at = T0 + 1_000)
        assertEquals(200, set.status)
        assertEquals("never_forget", ParityJson.str(set.json().members["status"]))
        assertEquals("never_forget", ParityJson.str(set.json().members["override_status"]))
        assertEquals("manual", ParityJson.str(set.json().members["override_origin"]))
        assertEquals(iso(T0 + 1_000), ParityJson.str(set.json().members["updated_at"]))
        assertEquals(ParityJson.parse("""{"entry_seq":1000220,"status":"never_forget","origin":"manual"}"""), ParityJson.at(d.minted()[0], "/payload"))
        assertEquals(set.json(), d.call("GET", path).json(), "read-your-writes")

        assertEquals(200, d.call("PUT", path, """{"status":"never_forget"}""", at = T0 + 2_000).status)
        assertEquals(listOf("word.status_set", "word.status_set"), d.mintedTypes(), "an unchanged status still mints")

        val cleared = d.call("DELETE", path, at = T0 + 3_000).json()
        assertEquals(before.members["status"], cleared.members["status"])
        assertEquals(before.members["auto_status"], cleared.members["auto_status"])
        assertEquals(ParityJson.parse("null"), cleared.members["override_status"])
        assertEquals(3, d.minted().size)
    }

    @Test
    fun clearingWithoutAnOverrideMintsNothing() {
        val d = device()
        assertEquals(200, d.call("DELETE", path, at = T0 + 1_000).status)
        assertEquals(200, d.call("DELETE", "/api/v1/words/42/status", at = T0 + 1_000).status)
        d.call("PUT", path, """{"status":"known"}""", at = T0 + 2_000)
        d.call("DELETE", path, at = T0 + 3_000)
        d.call("DELETE", path, at = T0 + 4_000)
        assertEquals(listOf("word.status_set", "word.status_cleared"), d.mintedTypes())
    }

    @Test
    fun validationAsTheServer() {
        val d = device()
        assertEquals("422 /status invalid_value", d.call("PUT", path, """{"status":"learning"}""").problem())
        assertEquals("422 /status required", d.call("PUT", path, """{}""").problem())
        assertEquals("422 /x unknown_field", d.call("PUT", path, """{"status":"known","x":1}""").problem())
        assertEquals("422 /seq invalid_value", d.call("GET", "/api/v1/words/0/status").problem())
        assertEquals("422 /seq invalid_value", d.call("PUT", "/api/v1/words/abc/status", """{"status":"known"}""").problem())
        assertEquals("422 /q unknown_field", d.call("GET", path, query = "q=1").problem())
        assertEquals(0, d.minted().size)
    }
}
