package kotobaya.android.shell.api.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotobaya.android.shell.api.actions.ActionsTestSupport.N1
import kotobaya.android.shell.api.actions.ActionsTestSupport.T0
import kotobaya.android.shell.api.actions.ActionsTestSupport.X
import kotobaya.android.shell.api.actions.ActionsTestSupport.Y
import kotobaya.android.shell.api.actions.ActionsTestSupport.Z
import kotobaya.android.shell.api.actions.ActionsTestSupport.call
import kotobaya.android.shell.api.actions.ActionsTestSupport.device
import kotobaya.android.shell.api.actions.ActionsTestSupport.field
import kotobaya.android.shell.api.actions.ActionsTestSupport.note
import kotobaya.android.shell.api.actions.ActionsTestSupport.obj
import kotobaya.android.shell.api.actions.ActionsTestSupport.patchNote
import kotobaya.android.shell.api.actions.ActionsTestSupport.problem
import kotobaya.android.shell.api.actions.ActionsTestSupport.remote
import kotobaya.android.shell.api.actions.ActionsTestSupport.replica
import kotobaya.android.shell.api.actions.ActionsTestSupport.rows
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTable

/**
 * SHELL-L4 acceptance `NoteEditLwwTest` (SYNC.md 7.1, 7.3; SHELL.md L-6): an `updateNote` made offline on this device
 * and edits / a delete of the same note pulled from another device, in both arrival orders. Each case also checks
 * convergence: replicas that receive all the ops in replay order and in reverse order hold the same note.
 */
class NoteEditLwwTest {
    private fun remoteEdit(n: Int, wall: Long, vararg fields: Pair<String, String>): JsonObject = remote(
        n, "note.updated", "note", N1, wall,
        obj("id" to JsonString(N1), "set" to obj("fields" to obj(*fields.map { (k, v) -> k to (JsonString(v) as kotobaya.contracts.sync.JsonValue) }.toTypedArray()))),
    )

    private fun remoteDelete(n: Int, wall: Long): JsonObject = remote(n, "note.deleted", "note", N1, wall, obj("id" to JsonString(N1)))

    /** Every op the device minted plus [pulled], applied on two fresh replicas in opposite orders: all three agree. */
    private fun assertConverges(d: ParityDevice, pulled: List<JsonObject>, end: Long) {
        val all = d.minted() + pulled
        val forward = replica(Y, all, end) { ops -> ops.sortedBy { ParityJson.str(it.members["hlc"]) } }
        val backward = replica(Z, all, end) { ops -> ops.sortedByDescending { ParityJson.str(it.members["hlc"]) } }
        assertEquals(d.rows(LearningTable.NOTE), forward.rows(LearningTable.NOTE))
        assertEquals(d.rows(LearningTable.NOTE), backward.rows(LearningTable.NOTE))
    }

    @Test
    fun aNewerPulledEditOfTheSameFieldWins() {
        val d = device(X)
        assertEquals(200, d.patchNote(N1, """{"fields":{"meaning":"mine"}}""", at = T0 + 10_000).status)
        val theirs = remoteEdit(1, T0 + 20_000, "meaning" to "theirs")
        d.clock.now = T0 + 30_000
        d.pull(listOf(theirs), "B's newer edit")
        assertEquals("theirs", d.note(N1).field("meaning"))
        assertConverges(d, listOf(theirs), T0 + 40_000)
    }

    @Test
    fun anOlderPulledEditOfTheSameFieldLoses() {
        val d = device(X)
        val theirs = remoteEdit(1, T0 + 5_000, "meaning" to "theirs")
        d.patchNote(N1, """{"fields":{"meaning":"mine"}}""", at = T0 + 10_000)
        d.clock.now = T0 + 30_000
        d.pull(listOf(theirs), "B's older edit arrives late")
        assertEquals("mine", d.note(N1).field("meaning"))
        assertConverges(d, listOf(theirs), T0 + 40_000)
    }

    @Test
    fun editsOfDifferentFieldsAreBothKept() {
        val d = device(X)
        val theirs = remoteEdit(1, T0 + 20_000, "reading" to "かれら")
        d.patchNote(N1, """{"fields":{"meaning":"mine"},"mnemonic":"m"}""", at = T0 + 10_000)
        d.clock.now = T0 + 30_000
        d.pull(listOf(theirs), "B's edit of another field")
        val n = d.note(N1)
        assertEquals("mine", n.field("meaning"))
        assertEquals("かれら", n.field("reading"))
        assertEquals(JsonString("m"), n["mnemonic"])
        assertConverges(d, listOf(theirs), T0 + 40_000)
    }

    /** L-6 / 10.5: a delete beats every edit, older or newer; afterwards the note answers `410`. */
    @Test
    fun aPulledDeleteBeatsTheOlderLocalEdit() {
        val d = device(X)
        d.patchNote(N1, """{"fields":{"meaning":"mine"}}""", at = T0 + 10_000)
        val delete = remoteDelete(1, T0 + 20_000)
        d.clock.now = T0 + 30_000
        d.pull(listOf(delete), "B deletes the note")
        assertEquals(JsonString("mine").value, d.note(N1).field("meaning"), "the edit is applied to the tombstone")
        assertEquals(false, d.note(N1)["deleted_at"] == JsonNull)
        assertEquals("410 gone", d.call("GET", "/api/v1/notes/$N1").problem())
        assertEquals("410 gone", d.patchNote(N1, """{"mnemonic":"again"}""", at = T0 + 40_000).problem())
        assertEquals(1, d.minted().size, "nothing minted after the delete")
        assertConverges(d, listOf(delete), T0 + 50_000)
    }

    @Test
    fun aPulledDeleteOlderThanTheLocalEditStillWins() {
        val d = device(X)
        val delete = remoteDelete(1, T0 + 5_000)
        d.patchNote(N1, """{"fields":{"meaning":"mine"}}""", at = T0 + 10_000)
        d.clock.now = T0 + 30_000
        d.pull(listOf(delete), "B's older delete arrives after the edit")
        assertEquals(false, d.note(N1)["deleted_at"] == JsonNull)
        assertEquals("410 gone", d.call("GET", "/api/v1/notes/$N1").problem())
        assertEquals(200, d.call("GET", "/api/v1/notes/$N1", query = "include_deleted=true").status)
        assertConverges(d, listOf(delete), T0 + 50_000)
    }

    /** Only the registers the body names are written: one `note.updated` with exactly those (openapi register table). */
    @Test
    fun absentPropertiesAreUnchangedAndOnlyNamedRegistersAreWritten() {
        val d = device(X)
        d.pull(listOf(remoteEdit(1, T0 + 1_000, "meaning" to "keep", "reading" to "keep too")), "B's fields")
        d.patchNote(N1, """{"add_tags":["b","a"],"source_sentence":"文"}""", at = T0 + 10_000)
        val set = ParityJson.obj(ParityJson.at(d.minted().single(), "/payload/set"))
        assertEquals(
            ParityJson.parse("""{"tags":["a","b"],"source_sentence":"文","source_sentence_id":null,"source_media_id":null}"""),
            set,
        )
        assertEquals("keep", d.note(N1).field("meaning"))
        assertEquals("keep too", d.note(N1).field("reading"))
        d.patchNote(N1, """{"remove_tags":["a"],"fields":{"meaning":""}}""", at = T0 + 20_000)
        assertEquals(ParityJson.parse("""{"fields":{"meaning":""},"tags":["b"]}"""), ParityJson.at(d.minted()[1], "/payload/set"))
        assertEquals("", d.note(N1).field("meaning"))
    }
}
