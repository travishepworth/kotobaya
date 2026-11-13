package kotobaya.android.ankiapi.takoboto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotobaya.android.notes.CreateResult
import kotobaya.android.notes.DEVICE_A
import kotobaya.android.notes.DICT
import kotobaya.android.notes.FakeSource
import kotobaya.android.notes.NoteRequest
import kotobaya.android.notes.T0
import kotobaya.android.notes.cand
import kotobaya.android.notes.creator
import kotobaya.android.notes.duplicateCheck
import kotobaya.android.sync.SqlDevice
import kotobaya.android.sync.TestServer

/** T305b review carry-over: a matched note with `fields.expression` is found when the word itself matches no entry. */
class DuplicateCheckExpressionTest {
    private val taberu = NoteRequest(word = "食べる", reading = "たべる", fields = mapOf("expression" to "食べる", "reading" to "たべる"), origin = "takoboto")

    @Test
    fun matchedNoteIsFoundByExpressionWhenTheDictionaryIsMissing() {
        val d = SqlDevice(DEVICE_A, TestServer(T0), T0)
        val made = assertIs<CreateResult.Created>(creator(d).create(taberu))
        val noDict = FakeSource(DICT, available = false)

        assertEquals(made.noteId, duplicateCheck(d, noDict).find("食べる", "たべる")?.noteId)
        assertEquals(made.noteId, duplicateCheck(d, noDict).find("食べる", null)?.noteId)
        assertNull(duplicateCheck(d, noDict).find("食べる", "くべる"), "another reading is another word")
        val again = assertIs<CreateResult.Duplicate>(creator(d, noDict, seed = 9).create(taberu))
        assertEquals(made.noteId, again.existing.noteId)
    }

    @Test
    fun withAMatchANoteOfAnotherEntryWithTheSameSpellingIsNotADuplicate() {
        val d = SqlDevice(DEVICE_A, TestServer(T0), T0)
        val homograph = listOf(cand(1000001, "上手", "じょうず"), cand(1000002, "上手", "うわて"))
        val first = assertIs<CreateResult.Created>(
            creator(d, FakeSource(homograph)).create(NoteRequest(word = "上手", reading = "じょうず", fields = mapOf("expression" to "上手"))),
        )
        assertEquals(first.noteId, duplicateCheck(d, FakeSource(homograph)).find("上手", "じょうず")?.noteId)
        assertNull(duplicateCheck(d, FakeSource(homograph)).find("上手", "うわて"))
    }
}
