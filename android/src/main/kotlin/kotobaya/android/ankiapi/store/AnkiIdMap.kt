package kotobaya.android.ankiapi.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotobaya.android.ankiapi.provider.AnkiModel
import kotobaya.android.ankiapi.provider.AnkiTemplate
import org.json.JSONArray
import org.json.JSONObject

/** A Kotobaya note as AnkiDroid callers know it: its Anki id, the model and deck it was added with. */
data class NoteLink(val ankiId: Long, val noteId: String, val modelId: Long, val deckAnkiId: Long)

/** A model a client created (AddContentApi `addNewCustomModel`) and the Kotobaya key of each of its fields. */
data class ClientModel(val model: AnkiModel, val keys: List<String?>)

/**
 * App-private bookkeeping of the AnkiDroid API (NOT a contract table, never synced): AnkiDroid's
 * numeric ids for Kotobaya notes and decks, client-created models, the selected deck/current model
 * and counters for the debug screen. Losing it loses only these ids: notes themselves live in the
 * learning tables, and an id is allocated again on demand.
 */
interface AnkiIdMap {
    /** The Anki id of [noteId], allocated (with [modelId]/[deckAnkiId]) on first use; an existing link is kept. */
    fun linkNote(noteId: String, modelId: Long, deckAnkiId: Long): NoteLink

    fun note(ankiId: Long): NoteLink?

    fun noteByKotobayaId(noteId: String): NoteLink?

    /** The Anki id of deck [deckId], allocated on first use (always > [AnkiIds.INBOX_DECK_ID]). */
    fun deckAnkiId(deckId: String): Long

    fun deckId(ankiId: Long): String?

    fun clientModels(): List<ClientModel>

    /** Stores [model]; `model.model.id == 0` allocates a new id. Returns what was stored. */
    fun putClientModel(model: ClientModel): ClientModel

    fun get(key: String): String?

    fun put(key: String, value: String)

    fun increment(counter: String)

    fun counter(counter: String): Long = get(counter)?.toLongOrNull() ?: 0
}

/** Fixed AnkiDroid-visible ids. */
object AnkiIds {
    /** The "Takoboto" inbox: notes added to it are deckless (mined). AnkiDroid's default deck id. */
    const val INBOX_DECK_ID: Long = 1
    const val INBOX_DECK_NAME: String = "Takoboto"

    /** The "Takoboto Vocab" model; client-created models get ids above it. */
    const val TAKOBOTO_MODEL_ID: Long = 1_600_000_000_000

    /** Deck Anki ids are this plus the map's row id. */
    const val DECK_ID_BASE: Long = 1_000

    const val COUNT_ADDED: String = "count.added"
    const val COUNT_DUPLICATE: String = "count.duplicate"
    const val COUNT_REFUSED: String = "count.refused"
    const val SELECTED_DECK: String = "selected_deck"
    const val CURRENT_MODEL: String = "current_model"
}

/** [AnkiIdMap] in its own SQLite file ([name], `null` = in memory) in the app's private storage. */
class SqliteAnkiIdMap(context: Context, name: String? = DB_NAME) : AnkiIdMap {
    private val helper = object : SQLiteOpenHelper(context, name, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE ankiapi_note_map (anki_id INTEGER PRIMARY KEY AUTOINCREMENT, note_id TEXT NOT NULL UNIQUE, " +
                    "model_id INTEGER NOT NULL, deck_anki_id INTEGER NOT NULL)",
            )
            db.execSQL("CREATE TABLE ankiapi_deck_map (row_id INTEGER PRIMARY KEY AUTOINCREMENT, deck_id TEXT NOT NULL UNIQUE)")
            db.execSQL("CREATE TABLE ankiapi_model (row_id INTEGER PRIMARY KEY AUTOINCREMENT, json TEXT NOT NULL)")
            db.execSQL("CREATE TABLE ankiapi_kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    private val db: SQLiteDatabase by lazy { helper.writableDatabase }
    private val lock = Any()

    override fun linkNote(noteId: String, modelId: Long, deckAnkiId: Long): NoteLink = synchronized(lock) {
        noteByKotobayaId(noteId)?.let { return it }
        val id = db.insertOrThrow(
            "ankiapi_note_map",
            null,
            ContentValues().apply {
                put("note_id", noteId)
                put("model_id", modelId)
                put("deck_anki_id", deckAnkiId)
            },
        )
        NoteLink(id, noteId, modelId, deckAnkiId)
    }

    override fun note(ankiId: Long): NoteLink? = noteWhere("anki_id = ?", ankiId.toString())

    override fun noteByKotobayaId(noteId: String): NoteLink? = noteWhere("note_id = ?", noteId)

    private fun noteWhere(where: String, arg: String): NoteLink? = synchronized(lock) {
        db.query("ankiapi_note_map", arrayOf("anki_id", "note_id", "model_id", "deck_anki_id"), where, arrayOf(arg), null, null, null).use { c ->
            if (c.moveToFirst()) NoteLink(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3)) else null
        }
    }

    override fun deckAnkiId(deckId: String): Long = synchronized(lock) {
        val existing = db.query("ankiapi_deck_map", arrayOf("row_id"), "deck_id = ?", arrayOf(deckId), null, null, null)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        AnkiIds.DECK_ID_BASE + (existing ?: db.insertOrThrow("ankiapi_deck_map", null, ContentValues().apply { put("deck_id", deckId) }))
    }

    override fun deckId(ankiId: Long): String? = synchronized(lock) {
        db.query("ankiapi_deck_map", arrayOf("deck_id"), "row_id = ?", arrayOf((ankiId - AnkiIds.DECK_ID_BASE).toString()), null, null, null)
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    override fun clientModels(): List<ClientModel> = synchronized(lock) {
        db.query("ankiapi_model", arrayOf("row_id", "json"), null, null, null, null, "row_id").use { c ->
            buildList {
                while (c.moveToNext()) add(decode(AnkiIds.TAKOBOTO_MODEL_ID + c.getLong(0), JSONObject(c.getString(1))))
            }
        }
    }

    override fun putClientModel(model: ClientModel): ClientModel = synchronized(lock) {
        if (model.model.id == 0L) {
            val row = db.insertOrThrow("ankiapi_model", null, ContentValues().apply { put("json", encode(model).toString()) })
            model.copy(model = model.model.copy(id = AnkiIds.TAKOBOTO_MODEL_ID + row))
        } else {
            db.update(
                "ankiapi_model",
                ContentValues().apply { put("json", encode(model).toString()) },
                "row_id = ?",
                arrayOf((model.model.id - AnkiIds.TAKOBOTO_MODEL_ID).toString()),
            )
            model
        }
    }

    override fun get(key: String): String? = synchronized(lock) {
        db.query("ankiapi_kv", arrayOf("value"), "key = ?", arrayOf(key), null, null, null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    override fun put(key: String, value: String): Unit = synchronized(lock) {
        db.insertWithOnConflict("ankiapi_kv", null, ContentValues().apply {
            put("key", key)
            put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun increment(counter: String): Unit = synchronized(lock) { put(counter, (counter(counter) + 1).toString()) }

    private fun encode(m: ClientModel): JSONObject = JSONObject().apply {
        put("name", m.model.name)
        put("fields", JSONArray(m.model.fields))
        put("keys", JSONArray(m.keys.map { it ?: JSONObject.NULL }))
        put(
            "templates",
            JSONArray(
                m.model.templates.map { t ->
                    JSONObject().put("name", t.name).put("q", t.questionFormat).put("a", t.answerFormat)
                        .put("bq", t.browserQuestionFormat).put("ba", t.browserAnswerFormat)
                },
            ),
        )
        put("css", m.model.css)
        put("deck_id", m.model.deckId)
        put("sort_field_index", m.model.sortFieldIndex)
        put("type", m.model.type)
        put("latex_header", m.model.latexHeader)
        put("latex_footer", m.model.latexFooter)
    }

    private fun decode(id: Long, o: JSONObject): ClientModel {
        fun strings(a: JSONArray) = List(a.length()) { a.getString(it) }
        val keysArr = o.getJSONArray("keys")
        val templates = o.getJSONArray("templates").let { a ->
            List(a.length()) {
                val t = a.getJSONObject(it)
                AnkiTemplate(t.getString("name"), t.getString("q"), t.getString("a"), t.optString("bq"), t.optString("ba"))
            }
        }
        return ClientModel(
            AnkiModel(
                id = id,
                name = o.getString("name"),
                fields = strings(o.getJSONArray("fields")),
                templates = templates,
                css = o.optString("css"),
                deckId = o.getLong("deck_id"),
                sortFieldIndex = o.optInt("sort_field_index"),
                type = o.optInt("type"),
                latexHeader = o.optString("latex_header"),
                latexFooter = o.optString("latex_footer"),
            ),
            List(keysArr.length()) { if (keysArr.isNull(it)) null else keysArr.getString(it) },
        )
    }

    companion object {
        const val DB_NAME: String = "ankiapi.db"
    }
}
