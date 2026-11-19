package kotobaya.android.shell.api.actions

import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
import kotobaya.android.shell.api.actions.ActionsTestSupport.json
import kotobaya.android.shell.api.actions.ActionsTestSupport.obj
import kotobaya.android.shell.api.actions.ActionsTestSupport.patchNote
import kotobaya.android.shell.api.actions.ActionsTestSupport.problem
import kotobaya.android.shell.api.actions.ActionsTestSupport.remote
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue

/** `getNote`, `getCard`, `listCardReviews` and the `updateNote` answer (openapi `Note`, `Card`, `ReviewPage`). */
// a re-match of entry_seq reads dictionary details (Android SQLite / org.json): Robolectric, as the queue parity tests
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class NoteCardReadTest {
    private val serverDevice = "00000000-0000-7000-8000-000000000001"
    private val deck = "0193c000-0d00-7000-8000-000000000001"

    @Test
    fun aNoteHasEveryPropertyItsLiveCardsAndItsDecks() {
        val d = device()
        d.clock.now = T0 + 500
        d.pull(
            listOf(
                remote(1, "deck.created", "deck", deck, T0, obj("id" to JsonString(deck), "name" to JsonString("Frieren"), "type" to JsonString("user"))),
                remote(2, "deck.item_put", "deck_item", "$deck|e:1001010", T0 + 1, obj("deck_id" to JsonString(deck), "item_key" to JsonString("e:1001010"), "position" to JsonNumber(1024.0))),
            ),
            "a deck holding entry 1001010",
        )
        val note = d.call("GET", "/api/v1/notes/$N1").json()
        assertEquals(
            listOf(
                "cards", "created_at", "created_by_device_id", "deck_ids", "deleted_at", "entry_seq", "fields", "grammar_point_id", "id",
                "kanji_literal", "language", "merged_into_id", "mnemonic", "note_type_id", "origin", "source_media_id", "source_sentence",
                "source_sentence_id", "tags", "type", "updated_at",
            ),
            note.members.keys.sorted(),
        )
        assertEquals(ParityJson.parse("""["$deck"]"""), note.members["deck_ids"])
        val cards = ParityJson.arr(note.members["cards"]).map(ParityJson::obj)
        assertEquals(listOf(C1), cards.map { ParityJson.str(it.members["id"]) })
        assertNull(cards[0].members["note"], "an embedded card has no note summary")
        assertEquals(JsonNumber(1001010.0), note.members["entry_seq"])
        assertEquals(ParityJson.parse("[]"), d.call("GET", "/api/v1/notes/$N2").json().members["deck_ids"])
    }

    @Test
    fun aMergeLoserAnswersWithItsSurvivorAndContentLocation() {
        val d = device()
        d.clock.now = T0 + 500
        d.pull(
            listOf(
                remote(1, "note.merged", "note", N2, T0, obj("loser_id" to JsonString(N2), "survivor_id" to JsonString(N1)), serverDevice),
                remote(2, "card.merged", "card", C2, T0 + 1, obj("loser_id" to JsonString(C2), "survivor_id" to JsonString(C1)), serverDevice),
            ),
            "server merge",
        )
        val note = d.call("GET", "/api/v1/notes/$N2")
        assertEquals(200, note.status)
        assertEquals("/api/v1/notes/$N1", note.headers["content-location"])
        assertEquals(N1, ParityJson.str(note.json().members["id"]))
        val card = d.call("GET", "/api/v1/cards/$C2")
        assertEquals("/api/v1/cards/$C1", card.headers["content-location"])
        assertEquals(C1, ParityJson.str(card.json().members["id"]))
        assertNull(d.call("GET", "/api/v1/cards/$C1").headers["content-location"])
        // an edit of the loser acts on the survivor
        val patched = d.patchNote(N2, """{"mnemonic":"x"}""", at = T0 + 1_000)
        assertEquals("/api/v1/notes/$N1", patched.headers["content-location"])
        assertEquals(N1, ParityJson.str(ParityJson.at(d.minted().single(), "/payload/id")))
    }

    @Test
    fun deletedRowsAre410UnlessIncludeDeleted() {
        val d = device()
        d.clock.now = T0 + 500
        d.pull(listOf(remote(1, "card.deleted", "card", C2, T0, obj("id" to JsonString(C2)))), "B deletes card 2")
        assertEquals("410 gone", d.call("GET", "/api/v1/cards/$C2").problem())
        assertEquals(200, d.call("GET", "/api/v1/cards/$C2", query = "include_deleted=true").status)
        assertEquals("422 /include_deleted invalid_value", d.call("GET", "/api/v1/cards/$C2", query = "include_deleted=yes").problem())
        assertEquals(ParityJson.parse("[]"), d.call("GET", "/api/v1/notes/$N2").json().members["cards"], "only live cards")
        assertEquals("404 not_found", d.call("GET", "/api/v1/cards/$UNKNOWN").problem())
        assertEquals("404 not_found", d.call("GET", "/api/v1/notes/$UNKNOWN").problem())
    }

    @Test
    fun updateNoteValidatesAsTheServer() {
        val d = device()
        assertEquals("422  required", d.patchNote(N1, "{}").problem())
        assertEquals("422 /origin immutable", d.patchNote(N1, """{"origin":"x"}""").problem())
        assertEquals("422 /tags conflicting_fields", d.patchNote(N1, """{"tags":["a"],"add_tags":["b"]}""").problem())
        assertEquals("422 /add_tags/0 invalid_value", d.patchNote(N1, """{"add_tags":["leech"]}""").problem())
        assertEquals("422 /add_tags/0 invalid_value", d.patchNote(N1, """{"add_tags":["a b"]}""").problem())
        assertEquals("422 /fields/Bad invalid_value", d.patchNote(N1, """{"fields":{"Bad":"x"}}""").problem())
        assertEquals("422 /entry_seq out_of_range", d.patchNote(N1, """{"entry_seq":0}""").problem())
        // an entry the authoritative fixture package does not hold
        assertEquals("422 /entry_seq not_found", d.patchNote(N1, """{"entry_seq":999999999}""").problem())
        assertEquals("404 not_found", d.patchNote(UNKNOWN, """{"mnemonic":"x"}""").problem())
        val tooMany = (1..100).joinToString(",") { "\"t$it\"" }
        d.patchNote(N1, """{"tags":[$tooMany]}""", at = T0 + 1_000)
        val more = (101..201).take(100).joinToString(",") { "\"t$it\"" }
        d.patchNote(N1, """{"add_tags":[$more]}""", at = T0 + 2_000)
        assertEquals("422 /add_tags out_of_range", d.patchNote(N1, """{"add_tags":["z"]}""", at = T0 + 3_000).problem())
        assertEquals(2, d.minted().size)
    }

    @Test
    fun reviewsArePagedNewestFirstAcrossMergedCards() {
        val d = device()
        fun answer(n: Int, card: String, at: Long) {
            val id = "0193b000-0003-7000-8000-%012x".format(n)
            assertEquals(200, d.call("POST", "/api/v1/review/answer", """{"id":"$id","card_id":"$card","rating":3}""", at = at).status)
        }
        answer(1, C1, T0 + 1_000)
        answer(2, C2, T0 + 2_000)
        answer(3, C1, T0 + 3_000)
        d.clock.now = T0 + 4_000
        d.pull(listOf(remote(1, "card.merged", "card", C2, T0 + 3_500, obj("loser_id" to JsonString(C2), "survivor_id" to JsonString(C1)), serverDevice)), "merge")

        val ids = ArrayList<String>()
        var cursor: JsonValue? = null
        var pages = 0
        do {
            val q = "limit=2" + ((cursor as? JsonString)?.let { "&cursor=${it.value}" } ?: "")
            val page = d.call("GET", "/api/v1/cards/$C2/reviews", query = q).json()
            ids += ParityJson.arr(page.members["items"]).map { ParityJson.str(ParityJson.obj(it).members["id"]) }
            cursor = page.members["next_cursor"]
            pages++
        } while (cursor is JsonString)
        assertEquals(listOf(3, 2, 1).map { "0193b000-0003-7000-8000-%012x".format(it) }, ids)
        assertEquals(2, pages)
        assertEquals("422 /cursor invalid_value", d.call("GET", "/api/v1/cards/$C1/reviews", query = "cursor=bogus").problem())
        assertEquals("422 /limit out_of_range", d.call("GET", "/api/v1/cards/$C1/reviews", query = "limit=0").problem())
        assertEquals("404 not_found", d.call("GET", "/api/v1/cards/$UNKNOWN/reviews").problem())
    }
}
