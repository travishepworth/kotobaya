package kotobaya.android.shell.api.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotobaya.android.shell.api.actions.ActionsTestSupport.C1
import kotobaya.android.shell.api.actions.ActionsTestSupport.C2
import kotobaya.android.shell.api.actions.ActionsTestSupport.N1
import kotobaya.android.shell.api.actions.ActionsTestSupport.N2
import kotobaya.android.shell.api.actions.ActionsTestSupport.T0
import kotobaya.android.shell.api.actions.ActionsTestSupport.UNKNOWN
import kotobaya.android.shell.api.actions.ActionsTestSupport.call
import kotobaya.android.shell.api.actions.ActionsTestSupport.device
import kotobaya.android.shell.api.actions.ActionsTestSupport.mintedTypes
import kotobaya.android.shell.api.actions.ActionsTestSupport.note
import kotobaya.android.shell.api.actions.ActionsTestSupport.obj
import kotobaya.android.shell.api.actions.ActionsTestSupport.problem
import kotobaya.android.shell.api.actions.ActionsTestSupport.remote
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonString

/** SHELL-L4 acceptance `DeleteIdempotentTest` (openapi `deleteNote`, SHELL.md L-2 / L-4 / 10.11). */
class DeleteIdempotentTest {
    @Test
    fun aSecondDeleteMintsNothing() {
        val d = device()
        val first = d.call("DELETE", "/api/v1/notes/$N1", at = T0 + 1_000)
        assertEquals(204, first.status)
        assertNull(first.body)
        assertEquals(listOf("note.deleted"), d.mintedTypes())
        assertEquals(1, d.localChanges)
        val deletedAt = d.note(N1)["deleted_at"]

        assertEquals(204, d.call("DELETE", "/api/v1/notes/$N1", at = T0 + 2_000).status)
        assertEquals(1, d.minted().size, "the retry mints nothing")
        assertEquals(1, d.localChanges, "and triggers no sync")
        assertEquals(deletedAt, d.note(N1)["deleted_at"])
    }

    @Test
    fun deletingANoteHidesItsCardsWithoutCardOps() {
        val d = device()
        d.call("DELETE", "/api/v1/notes/$N1", at = T0 + 1_000)
        assertEquals("410 gone", d.call("GET", "/api/v1/cards/$C1").problem())
        assertEquals(200, d.call("GET", "/api/v1/cards/$C2").status)
        assertEquals(listOf("note.deleted"), d.mintedTypes())
    }

    @Test
    fun aNoteDeletedElsewhereIsAlreadyDeleted() {
        val d = device()
        d.clock.now = T0 + 1_000
        d.pull(listOf(remote(1, "note.deleted", "note", N1, T0 + 500, obj("id" to JsonString(N1)))), "B deletes")
        assertEquals(204, d.call("DELETE", "/api/v1/notes/$N1", at = T0 + 2_000).status)
        assertEquals(0, d.minted().size)
    }

    /** Deleting a merge loser never deletes the survivor: `204`, no op. */
    @Test
    fun aMergeLoserIsNotDeleted() {
        val d = device()
        d.clock.now = T0 + 1_000
        d.pull(
            listOf(remote(1, "note.merged", "note", N2, T0 + 500, obj("loser_id" to JsonString(N2), "survivor_id" to JsonString(N1)), "00000000-0000-7000-8000-000000000001")),
            "the server merges note 2 into note 1",
        )
        assertEquals(204, d.call("DELETE", "/api/v1/notes/$N2", at = T0 + 2_000).status)
        assertEquals(0, d.minted().size)
        assertEquals(JsonNull, d.note(N1)["deleted_at"] ?: JsonNull)
    }

    @Test
    fun unknownAndMalformedIdsAre404() {
        val d = device()
        assertEquals("404 not_found", d.call("DELETE", "/api/v1/notes/$UNKNOWN").problem())
        assertEquals("404 not_found", d.call("DELETE", "/api/v1/notes/NOT-A-UUID").problem())
        assertEquals(0, d.minted().size)
    }
}
