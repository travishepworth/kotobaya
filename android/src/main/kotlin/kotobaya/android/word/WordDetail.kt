package kotobaya.android.word

import app.cash.sqldelight.db.SqlDriver
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.net.ApiClient
import kotobaya.android.net.ApiError
import kotobaya.android.search.noteTitle
import kotobaya.android.search.rows
import kotobaya.android.search.wordStatuses
import org.json.JSONArray
import org.json.JSONException

data class SenseView(val pos: List<String>, val glosses: List<String>)
data class CardView(val cardId: String, val kind: String, val state: String, val suspended: Boolean)
data class NoteView(val noteId: String, val title: String, val cards: List<CardView>)

data class WordDetail(
    val seq: Long,
    val headword: String,
    val reading: String,
    val kanjiForms: List<String>,
    val readings: List<String>,
    val senses: List<SenseView>,
    val kanji: List<String>,
    val status: String,
    val retired: Boolean,
    val notes: List<NoteView>,
)

sealed interface WordLoad {
    data class Found(val detail: WordDetail) : WordLoad
    data object NotFound : WordLoad
    data class Failed(val offline: Boolean) : WordLoad
}

/** A note without a dictionary entry (a card result whose note has no `entry_seq`). */
sealed interface NoteLoad {
    data class Found(val note: NoteView, val mnemonic: String, val tags: List<String>) : NoteLoad
    data object Unavailable : NoteLoad
}

private fun JSONArray.strings(key: String? = null): List<String> =
    (0 until length()).mapNotNull { i -> if (key == null) optString(i) else optJSONObject(i)?.optString(key) }

/**
 * The word detail: the entry from the installed package (else `GET /dict/entries/{seq}`), and the user's notes,
 * cards and status for it from the replica (else `GET /cards?query=entry:<seq>` and the entry's own `status`).
 * Read only; no learning data is written here.
 */
class WordRepository(
    private val dict: InstalledDictionary,
    private val driver: SqlDriver,
    private val api: ApiClient,
    private val replicaUsable: () -> Boolean,
) {
    suspend fun load(seq: Long): WordLoad {
        val local = if (dict.isAvailable()) runCatching { dict.entry(seq) }.getOrNull() else null
        if (local != null && replicaUsable()) {
            val notes = runCatching { localNotes(seq) }.getOrNull()
            if (notes != null) {
                val status = driver.wordStatuses(listOf(seq))[seq] ?: "new"
                return WordLoad.Found(
                    WordDetail(
                        seq, local.headword, local.headwordReading,
                        local.kanjiForms.strings("text"), local.readings.strings("text"), senses(local.senses),
                        local.kanji, status, local.retired, notes,
                    ),
                )
            }
        }
        return remote(seq)
    }

    private fun senses(arr: JSONArray): List<SenseView> = (0 until arr.length()).map { i ->
        val s = arr.getJSONObject(i)
        SenseView(s.optJSONArray("pos")?.strings().orEmpty(), s.optJSONArray("gloss")?.strings("text").orEmpty())
    }

    private fun localNotes(seq: Long): List<NoteView> {
        val notes = driver.rows(
            "SELECT id, fields FROM note WHERE entry_seq = ? AND deleted_at IS NULL AND merged_into_id IS NULL ORDER BY created_at, id",
            listOf(seq),
        ) { it.getString(0)!! to noteTitle(it.getString(1).orEmpty()) }
        return notes.map { (id, title) -> NoteView(id, title, localCards(id)) }
    }

    private fun localCards(noteId: String): List<CardView> = driver.rows(
        "SELECT id, kind, state, suspended FROM card WHERE note_id = ? AND deleted_at IS NULL AND merged_into_id IS NULL ORDER BY created_at, id",
        listOf(noteId),
    ) { CardView(it.getString(0)!!, it.getString(1)!!, it.getString(2)!!, (it.getLong(3) ?: 0L) != 0L) }

    /** Local data for a note-only card result. */
    fun loadNote(noteId: String): NoteLoad {
        if (!replicaUsable()) return NoteLoad.Unavailable
        return runCatching {
            val row = driver.rows(
                "SELECT fields, mnemonic, tags FROM note WHERE id = ? AND deleted_at IS NULL",
                listOf(noteId),
            ) { Triple(it.getString(0).orEmpty(), it.getString(1).orEmpty(), it.getString(2).orEmpty()) }.firstOrNull()
            if (row == null) {
                NoteLoad.Unavailable
            } else {
                NoteLoad.Found(
                    NoteView(noteId, noteTitle(row.first), localCards(noteId)),
                    row.second,
                    runCatching { JSONArray(row.third).strings() }.getOrDefault(emptyList()),
                )
            }
        }.getOrDefault(NoteLoad.Unavailable)
    }

    private suspend fun remote(seq: Long): WordLoad = try {
        val e = api.get("/dict/entries/$seq").body
        val cards = api.get("/cards?query=entry%3A$seq&limit=100").body.optJSONArray("items") ?: JSONArray()
        val byNote = LinkedHashMap<String, MutableList<CardView>>()
        val titles = HashMap<String, String>()
        for (i in 0 until cards.length()) {
            val c = cards.getJSONObject(i)
            val nid = c.getString("note_id")
            titles[nid] = c.optJSONObject("note")?.optJSONObject("fields")?.toString()?.let(::noteTitle).orEmpty()
            byNote.getOrPut(nid) { mutableListOf() } +=
                CardView(c.getString("id"), c.getString("kind"), c.getString("state"), c.optBoolean("suspended"))
        }
        WordLoad.Found(
            WordDetail(
                seq, e.getString("headword"), e.getString("headword_reading"),
                e.optJSONArray("kanji_forms")?.strings("text").orEmpty(), e.optJSONArray("readings")?.strings("text").orEmpty(),
                senses(e.optJSONArray("senses") ?: JSONArray()), e.optJSONArray("kanji")?.strings().orEmpty(),
                e.optString("status", "new"), e.optBoolean("retired"),
                byNote.map { (id, cs) -> NoteView(id, titles[id].orEmpty(), cs) },
            ),
        )
    } catch (e: ApiError) {
        when {
            e is ApiError.Other && e.status == 404 -> WordLoad.NotFound
            else -> WordLoad.Failed(offline = e is ApiError.Offline || e is ApiError.Unreachable)
        }
    } catch (e: JSONException) {
        WordLoad.Failed(offline = false)
    }
}
