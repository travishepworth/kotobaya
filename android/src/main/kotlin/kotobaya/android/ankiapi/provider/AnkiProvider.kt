package kotobaya.android.ankiapi.provider

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import kotobaya.android.ankiapi.provider.AnkiContract.CardTemplate
import kotobaya.android.ankiapi.provider.AnkiContract.Deck
import kotobaya.android.ankiapi.provider.AnkiContract.Model
import kotobaya.android.ankiapi.provider.AnkiContract.Note
import org.koin.core.context.GlobalContext

/**
 * AnkiDroid's FlashCardsContract provider (authority `com.ichi2.anki.flashcards`), answering what
 * AddContentApi-based clients such as Takoboto call: decks, selected deck, models (+ current,
 * fields, templates), notes / notes_v2 (duplicate query, insert, bulkInsert), single notes and
 * their cards. Ported from the S01 spike; state lives behind [AnkiStore].
 *
 * - Every call is logged to [ProviderCallLog]. Unknown URIs/columns/selections are logged with
 *   `!!` and answered empty (no rows, null, 0) rather than thrown, so a client keeps going.
 * - Callers must hold [AnkiContract.READ_WRITE_PERMISSION]; otherwise [SecurityException].
 * - Store failures become [IllegalArgumentException] (one of the exception types that cross the
 *   binder, as AnkiDroid reports bad ids and field counts); anything not parcelable becomes
 *   [IllegalStateException] so the provider process never crashes on a client's call.
 */
class AnkiProvider : ContentProvider() {
    @Volatile
    private var attachedStore: AnkiStore? = null
    private val fallbackStore by lazy {
        ProviderCallLog.log("no AnkiStore registered; using the in-memory store (lost on restart)")
        InMemoryAnkiStore()
    }

    /** The store: one attached explicitly (tests), else Koin's (T305c), else in-memory. */
    private val store: AnkiStore
        get() = attachedStore
            ?: runCatching { GlobalContext.getOrNull()?.getOrNull<AnkiStore>() }.getOrNull()
            ?: fallbackStore

    /** Test seam: pins the store this provider instance uses. */
    fun attachStore(store: AnkiStore) {
        attachedStore = store
    }

    override fun onCreate(): Boolean = true

    private fun caller(): String = runCatching { callingPackage }.getOrNull() ?: "?"

    private fun requirePermission(what: String, uri: Uri?) {
        val granted = context?.checkCallingOrSelfPermission(AnkiContract.READ_WRITE_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ProviderCallLog.log("$what refused: caller=${caller()} lacks ${AnkiContract.READ_WRITE_PERMISSION} uri=$uri")
            throw SecurityException("Permission ${AnkiContract.READ_WRITE_PERMISSION} required for $what $uri")
        }
    }

    override fun getType(uri: Uri): String? {
        val type = when (matcher.match(uri)) {
            NOTES, NOTES_V2 -> Note.CONTENT_TYPE
            NOTE -> Note.CONTENT_ITEM_TYPE
            NOTE_CARDS -> AnkiContract.Card.CONTENT_TYPE
            NOTE_CARD -> AnkiContract.Card.CONTENT_ITEM_TYPE
            MODELS -> Model.CONTENT_TYPE
            MODEL, MODEL_CURRENT -> Model.CONTENT_ITEM_TYPE
            MODEL_TEMPLATES -> CardTemplate.CONTENT_TYPE
            MODEL_TEMPLATE -> CardTemplate.CONTENT_ITEM_TYPE
            DECKS -> Deck.CONTENT_TYPE
            DECK, SELECTED_DECK -> Deck.CONTENT_ITEM_TYPE
            else -> null
        }
        if (type == null) {
            ProviderCallLog.unknown("getType caller=${caller()} uri=$uri")
        } else {
            ProviderCallLog.log("getType caller=${caller()} uri=$uri -> $type")
        }
        return type
    }

    // --- query -----------------------------------------------------------------------------------

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        ProviderCallLog.log(
            "query caller=${caller()} uri=$uri projection=${ProviderCallLog.show(projection)} " +
                "selection=${ProviderCallLog.show(selection)} args=${ProviderCallLog.show(selectionArgs)} " +
                "sort=${ProviderCallLog.show(sortOrder)}",
        )
        requirePermission("query", uri)
        val cursor = mapped { queryChecked(uri, projection, selection, selectionArgs) }
        ProviderCallLog.log("  -> ${cursor.count} row(s)${describe(cursor)}")
        cursor.moveToPosition(-1)
        return cursor
    }

    private fun queryChecked(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        args: Array<out String>?,
    ): MatrixCursor {
        val s = store
        val onUnknown = { col: String -> ProviderCallLog.unknown("unknown column $col for $uri") }
        return when (matcher.match(uri)) {
            NOTES, NOTES_V2 -> AnkiColumns.note.cursor(projection, notesFor(s, selection, args), onUnknown)
            NOTE -> AnkiColumns.note.cursor(projection, listOfNotNull(s.note(uri.idAt(1))), onUnknown)
            NOTE_CARDS -> AnkiColumns.card.cursor(projection, s.cards(uri.idAt(1)), onUnknown)
            NOTE_CARD -> AnkiColumns.card.cursor(
                projection,
                s.cards(uri.idAt(1)).filter { it.ord == uri.ordAt(3) },
                onUnknown,
            )
            MODELS -> AnkiColumns.model.cursor(projection, s.models(), onUnknown)
            MODEL_CURRENT -> AnkiColumns.model.cursor(
                projection,
                s.currentModelId()?.let { id -> s.models().filter { it.id == id } }.orEmpty(),
                onUnknown,
            )
            MODEL -> AnkiColumns.model.cursor(projection, listOfNotNull(model(s, uri)), onUnknown)
            MODEL_FIELDS -> AnkiColumns.field.cursor(
                projection,
                model(s, uri)?.fields?.mapIndexed(::FieldRef).orEmpty(),
                onUnknown,
            )
            MODEL_TEMPLATES -> AnkiColumns.template.cursor(
                projection,
                model(s, uri)?.let { m -> m.templates.indices.map { TemplateRef(m, it) } }.orEmpty(),
                onUnknown,
            )
            MODEL_TEMPLATE -> AnkiColumns.template.cursor(
                projection,
                listOfNotNull(
                    model(s, uri)?.let { m -> uri.ordAt(3).takeIf { it in m.templates.indices }?.let { TemplateRef(m, it) } },
                ),
                onUnknown,
            )
            DECKS -> AnkiColumns.deck.cursor(projection, s.decks(), onUnknown)
            DECK -> AnkiColumns.deck.cursor(projection, s.decks().filter { it.id == uri.idAt(1) }, onUnknown)
            SELECTED_DECK -> AnkiColumns.deck.cursor(
                projection,
                s.selectedDeckId().let { id -> s.decks().filter { it.id == id } },
                onUnknown,
            )
            else -> {
                ProviderCallLog.unknown("unhandled query uri=$uri")
                MatrixCursor(projection?.let { arrayOf(*it) } ?: arrayOf("_id"))
            }
        }
    }

    private fun notesFor(s: AnkiStore, selection: String?, args: Array<out String>?): List<AnkiNote> =
        when (val parsed = NoteSelection.parse(selection, args)) {
            NoteSelection.Parsed.All -> s.findNotes(NoteFilter())
            is NoteSelection.Parsed.Filter -> s.findNotes(parsed.filter)
            NoteSelection.Parsed.NotUnderstood -> {
                ProviderCallLog.unknown("notes selection not understood; answering no rows: ${ProviderCallLog.show(selection)}")
                emptyList()
            }
        }

    // --- insert ----------------------------------------------------------------------------------

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        ProviderCallLog.log("insert caller=${caller()} uri=$uri values=${ProviderCallLog.show(values)}")
        requirePermission("insert", uri)
        val result = mapped { insertChecked(store, uri, values ?: ContentValues()) }
        ProviderCallLog.log("  -> $result")
        return result
    }

    private fun insertChecked(s: AnkiStore, uri: Uri, v: ContentValues): Uri? = when (matcher.match(uri)) {
        NOTES, NOTES_V2 -> ContentUris.withAppendedId(AnkiContract.NOTES_URI, s.addNote(newNote(uri, v)).id)
        MODELS -> {
            val fields = v.getAsString(Model.FIELD_NAMES)?.let(AnkiContract::splitFields).orEmpty()
            val model = s.addModel(
                NewModel(
                    name = v.getAsString(Model.NAME) ?: throw AnkiStoreException.Invalid("${Model.NAME} is required"),
                    fields = fields,
                    numCards = v.getAsInteger(Model.NUM_CARDS) ?: 1,
                    css = v.getAsString(Model.CSS).orEmpty(),
                    deckId = v.getAsLong(Model.DECK_ID),
                    sortFieldIndex = v.getAsInteger(Model.SORT_FIELD_INDEX) ?: 0,
                    type = v.getAsInteger(Model.TYPE) ?: 0,
                    latexHeader = v.getAsString(Model.LATEX_HEADER),
                    latexFooter = v.getAsString(Model.LATEX_FOOTER),
                    callerPackage = caller(),
                ),
            )
            ContentUris.withAppendedId(AnkiContract.MODELS_URI, model.id)
        }
        MODEL_TEMPLATES -> {
            val ord = s.addTemplate(uri.idAt(1), templateEdit(v))
            ContentUris.withAppendedId(uri, ord.toLong())
        }
        DECKS -> {
            val name = v.getAsString(Deck.DECK_NAME) ?: throw AnkiStoreException.Invalid("${Deck.DECK_NAME} is required")
            ContentUris.withAppendedId(AnkiContract.DECKS_URI, s.addDeck(name, v.getAsString(Deck.DECK_DESC)).id)
        }
        else -> {
            ProviderCallLog.unknown("unhandled insert uri=$uri")
            null
        }
    }

    private fun newNote(uri: Uri, v: ContentValues): NewNote {
        val mid = v.getAsLong(Note.MID) ?: throw AnkiStoreException.Invalid("${Note.MID} is required")
        val flds = v.getAsString(Note.FLDS) ?: throw AnkiStoreException.Invalid("${Note.FLDS} is required")
        val deckId = uri.getQueryParameter(Note.DECK_ID_QUERY_PARAM)?.let {
            it.toLongOrNull() ?: throw AnkiStoreException.Invalid("bad ${Note.DECK_ID_QUERY_PARAM}=$it")
        }
        return NewNote(
            modelId = mid,
            fields = AnkiContract.splitFields(flds),
            tags = AnkiContract.splitTags(v.getAsString(Note.TAGS)),
            deckId = deckId,
            callerPackage = caller(),
        )
    }

    /**
     * Notes are added in one store transaction; a note the store rejects is skipped and logged, and
     * the return value counts the notes actually added (AddContentApi compares it to its input).
     */
    override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int {
        ProviderCallLog.log("bulkInsert caller=${caller()} uri=$uri count=${values.size}")
        requirePermission("bulkInsert", uri)
        val count = when (matcher.match(uri)) {
            NOTES, NOTES_V2 -> mapped {
                val s = store
                s.transaction {
                    values.count { v ->
                        try {
                            s.addNote(newNote(uri, v))
                            true
                        } catch (e: AnkiStoreException) {
                            ProviderCallLog.log("  skipped ${ProviderCallLog.show(v)}: ${e.message}")
                            false
                        }
                    }
                }
            }
            else -> values.count { insert(uri, it) != null }
        }
        ProviderCallLog.log("  -> $count inserted")
        return count
    }

    // --- update / delete -------------------------------------------------------------------------

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        ProviderCallLog.log(
            "update caller=${caller()} uri=$uri values=${ProviderCallLog.show(values)} " +
                "selection=${ProviderCallLog.show(selection)} args=${ProviderCallLog.show(selectionArgs)}",
        )
        requirePermission("update", uri)
        val v = values ?: ContentValues()
        val count = mapped {
            val s = store
            when (matcher.match(uri)) {
                NOTE -> s.updateNote(
                    uri.idAt(1),
                    v.getAsString(Note.FLDS)?.let(AnkiContract::splitFields),
                    if (v.containsKey(Note.TAGS)) AnkiContract.splitTags(v.getAsString(Note.TAGS)) else null,
                ).toCount()
                NOTE_CARD -> v.getAsLong(AnkiContract.Card.DECK_ID)
                    ?.let { s.moveCard(uri.idAt(1), uri.ordAt(3), it).toCount() } ?: 0
                MODEL_TEMPLATE -> s.updateTemplate(uri.idAt(1), uri.ordAt(3), templateEdit(v)).toCount()
                SELECTED_DECK -> v.getAsLong(Deck.DECK_ID)?.let { s.selectDeck(it).toCount() } ?: 0
                MODEL_CURRENT -> v.getAsLong(Model.ID)?.let { s.setCurrentModel(it).toCount() } ?: 0
                else -> {
                    ProviderCallLog.unknown("unhandled update uri=$uri")
                    0
                }
            }
        }
        ProviderCallLog.log("  -> $count row(s)")
        return count
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        ProviderCallLog.log(
            "delete caller=${caller()} uri=$uri selection=${ProviderCallLog.show(selection)} " +
                "args=${ProviderCallLog.show(selectionArgs)}",
        )
        requirePermission("delete", uri)
        val count = mapped {
            when (matcher.match(uri)) {
                NOTE -> store.deleteNote(uri.idAt(1)).toCount()
                else -> {
                    ProviderCallLog.unknown("unhandled delete uri=$uri")
                    0
                }
            }
        }
        ProviderCallLog.log("  -> $count row(s)")
        return count
    }

    /** AnkiDroid's provider defines no `call()` methods; logged so we learn if a client uses one. */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        ProviderCallLog.unknown(
            "call caller=${caller()} method=$method arg=${ProviderCallLog.show(arg)} extras=${ProviderCallLog.show(extras)}",
        )
        requirePermission("call", null)
        return null
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun <T> mapped(block: () -> T): T = try {
        block()
    } catch (e: AnkiStoreException) {
        ProviderCallLog.log("  -> error ${e::class.simpleName}: ${e.message}")
        throw IllegalArgumentException(e.message, e)
    } catch (e: RuntimeException) {
        ProviderCallLog.log("  -> error ${e::class.java.name}: ${e.message}")
        if (e.isParcelable()) throw e
        throw IllegalStateException(e.message, e)
    }

    /** Exception types Parcel.writeException / DatabaseUtils carry back to the client. */
    private fun RuntimeException.isParcelable(): Boolean =
        this is SecurityException || this is IllegalArgumentException || this is IllegalStateException ||
            this is UnsupportedOperationException || this is NullPointerException ||
            this is android.database.SQLException || this is android.os.OperationCanceledException

    private fun model(s: AnkiStore, uri: Uri): AnkiModel? {
        val id = uri.idAt(1)
        return s.models().firstOrNull { it.id == id }
    }

    private fun templateEdit(v: ContentValues) = TemplateEdit(
        name = v.getAsString(CardTemplate.NAME),
        questionFormat = v.getAsString(CardTemplate.QUESTION_FORMAT),
        answerFormat = v.getAsString(CardTemplate.ANSWER_FORMAT),
        browserQuestionFormat = v.getAsString(CardTemplate.BROWSER_QUESTION_FORMAT),
        browserAnswerFormat = v.getAsString(CardTemplate.BROWSER_ANSWER_FORMAT),
    )

    private fun Uri.idAt(i: Int): Long = pathSegments[i].toLongOrNull()
        ?: throw AnkiStoreException.Invalid("bad id '${pathSegments[i]}' in $this")

    private fun Uri.ordAt(i: Int): Int = pathSegments[i].toIntOrNull()
        ?: throw AnkiStoreException.Invalid("bad ord '${pathSegments[i]}' in $this")

    private fun Boolean.toCount(): Int = if (this) 1 else 0

    private fun describe(c: Cursor): String {
        if (c.count == 0 || c.count > DESCRIBE_MAX_ROWS) return ""
        val sb = StringBuilder()
        c.moveToPosition(-1)
        while (c.moveToNext()) {
            sb.append("\n     ").append(
                c.columnNames.indices.joinToString { "${c.getColumnName(it)}=${ProviderCallLog.show(c.getString(it))}" },
            )
        }
        return sb.toString()
    }

    private companion object {
        const val DESCRIBE_MAX_ROWS = 20

        const val NOTES = 1
        const val NOTES_V2 = 2
        const val NOTE = 3
        const val NOTE_CARDS = 4
        const val NOTE_CARD = 5
        const val MODELS = 10
        const val MODEL_CURRENT = 11
        const val MODEL = 12
        const val MODEL_TEMPLATES = 13
        const val MODEL_TEMPLATE = 14
        const val MODEL_FIELDS = 15
        const val DECKS = 20
        const val DECK = 21
        const val SELECTED_DECK = 22

        val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            val a = AnkiContract.AUTHORITY
            addURI(a, "notes", NOTES)
            addURI(a, "notes_v2", NOTES_V2)
            addURI(a, "notes/#", NOTE)
            addURI(a, "notes/#/cards", NOTE_CARDS)
            addURI(a, "notes/#/cards/#", NOTE_CARD)
            addURI(a, "models", MODELS)
            addURI(a, "models/${Model.CURRENT_MODEL_ID}", MODEL_CURRENT)
            addURI(a, "models/#", MODEL)
            addURI(a, "models/#/templates", MODEL_TEMPLATES)
            addURI(a, "models/#/templates/#", MODEL_TEMPLATE)
            addURI(a, "models/#/fields", MODEL_FIELDS)
            addURI(a, "decks", DECKS)
            addURI(a, "decks/#", DECK)
            addURI(a, "selected_deck", SELECTED_DECK)
        }
    }
}
