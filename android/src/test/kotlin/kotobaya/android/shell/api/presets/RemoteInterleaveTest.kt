package kotobaya.android.shell.api.presets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotobaya.android.shell.api.cards.LocalWritesSupport.ORACLE
import kotobaya.android.shell.api.cards.LocalWritesSupport.call
import kotobaya.android.shell.api.cards.LocalWritesSupport.device
import kotobaya.android.shell.api.cards.LocalWritesSupport.mintedTypes
import kotobaya.android.shell.api.cards.LocalWritesSupport.rows
import kotobaya.android.shell.api.confusables.RemoteOps
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.android.shell.parity.ParityScenario
import kotobaya.contracts.sync.JsonObject
import kotobaya.sync.client.apply.LearningTable
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LF-A8 review follow-up (SYNC.md 7.3): the device's own ops interleaved with a REMOTE op that arrives after them by
 * pull converge with every other arrival order on a fresh replica — a remote `fsrs_params.deleted` after a local
 * `card.preset_set`, a remote `note.created` after a local `note_type.deleted`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class RemoteInterleaveTest {
    private val t0 = 1_795_168_800_000L
    private val me = "01931f3e-0000-7000-8000-0000000000d4"
    private val seed = ParityScenario.load("card-writes-local").seedLog
    private val c1 = "0193a1b2-0002-7000-8000-000000000010"
    private val preset = "0193b000-0006-7000-8000-0000000000a7"
    private val type = "0193b000-0007-7000-8000-0000000000a7"
    private val note = "0193b000-0001-7000-8000-0000000000a7"

    private fun <T> permutations(xs: List<T>): List<List<T>> =
        if (xs.size <= 1) listOf(xs) else xs.indices.flatMap { i -> permutations(xs.take(i) + xs.drop(i + 1)).map { listOf(xs[i]) + it } }

    /** [d]'s tables equal those of a fresh replica given seed + [first] + every order of the rest (own and [remote] ops). */
    private fun assertConverges(d: ParityDevice, remote: List<JsonObject>, first: Set<String>, tables: List<LearningTable>) {
        val (creates, own) = d.minted().partition { ParityJson.str(it.members["op_type"]) in first }
        for ((i, order) in permutations(own + remote).withIndex()) {
            val r = device(ORACLE, t0 + 60_000, seed)
            (creates + order).forEach { r.pull(listOf(it), "oracle") }
            for (t in tables) assertEquals(d.rows(t), r.rows(t), "order $i: $t")
        }
    }

    @Test
    fun aRemotePresetDeleteAfterALocalPresetSetConverges() {
        val d = device(me, t0, seed)
        assertEquals(201, d.call("POST", "/api/v1/presets", """{"id":"$preset","name":"Mine"}""").status)
        assertEquals(200, d.call("PATCH", "/api/v1/cards/$c1", """{"preset_id":"$preset"}""", at = t0 + 1_000).status)
        val remote = listOf(RemoteOps.op("fsrs_params.deleted", "fsrs_params", preset, """{"id":"$preset"}""", t0 + 5_000))
        d.pull(remote, "remote preset delete")
        assertEquals(listOf("fsrs_params.created", "card.preset_set"), d.mintedTypes())
        assertConverges(d, remote, setOf("fsrs_params.created"), listOf(LearningTable.CARD, LearningTable.FSRS_PARAMS))
    }

    @Test
    fun aRemoteNoteCreatedAfterALocalNoteTypeDeleteConverges() {
        val d = device(me, t0, seed)
        val created = """{"id":"$type","name":"Mine","base_type":"custom","fields":[{"key":"front","label":"F","kind":"text"}],""" +
            """"templates":{"basic":{"default":true,"front":null,"back":null}}}"""
        assertEquals(201, d.call("POST", "/api/v1/note-types", created).status)
        assertEquals(204, d.call("DELETE", "/api/v1/note-types/$type", at = t0 + 1_000).status)
        val remote = listOf(
            RemoteOps.op("note.created", "note", note, """{"id":"$note","note_type_id":"$type","fields":{"front":"x"}}""", t0 + 5_000),
        )
        d.pull(remote, "remote note of the deleted type")
        assertEquals(listOf("note_type.created", "note_type.deleted"), d.mintedTypes())
        assertConverges(d, remote, setOf("note_type.created"), listOf(LearningTable.NOTE_TYPE, LearningTable.NOTE, LearningTable.CARD))
    }
}
