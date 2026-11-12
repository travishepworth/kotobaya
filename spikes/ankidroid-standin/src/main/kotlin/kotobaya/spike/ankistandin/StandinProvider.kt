package kotobaya.spike.ankistandin

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle

/**
 * Answers the FlashCardsContract URIs that AnkiDroid's AddContentApi uses (decks, models, model
 * fields, templates, note insert, duplicate lookup, card deck moves) from an in-memory store, and
 * logs every call with its arguments. Unknown URIs are logged and answered with an empty result
 * instead of throwing, so a client keeps going and we see its next call too.
 */
class StandinProvider : ContentProvider() {
    private val store = MemoryStore()

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        val a = Contract.AUTHORITY
        addURI(a, "notes", NOTES)
        addURI(a, "notes_v2", NOTES_V2)
        addURI(a, "notes/#", NOTE)
        addURI(a, "notes/#/cards", NOTE_CARDS)
        addURI(a, "notes/#/cards/#", NOTE_CARD)
        addURI(a, "models", MODELS)
        addURI(a, "models/current", MODEL_CURRENT)
        addURI(a, "models/#", MODEL)
        addURI(a, "models/#/templates", MODEL_TEMPLATES)
        addURI(a, "models/#/templates/#", MODEL_TEMPLATE)
        addURI(a, "models/#/fields", MODEL_FIELDS)
        addURI(a, "decks", DECKS)
        addURI(a, "decks/#", DECK)
        addURI(a, "selected_deck", SELECTED_DECK)
        addURI(a, "schedule", SCHEDULE)
        addURI(a, "media", MEDIA)
    }

    override fun onCreate(): Boolean {
        CallLog.log("provider onCreate")
        return true
    }

    private fun caller(): String = callingPackage ?: "?"

    override fun getType(uri: Uri): String {
        CallLog.log("getType caller=${caller()} uri=$uri")
        return "vnd.android.cursor.dir/vnd.com.ichi2.anki.standin"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        CallLog.log(
            "query caller=${caller()} uri=$uri projection=${CallLog.show(projection)} " +
                "selection=${CallLog.show(selection)} args=${CallLog.show(selectionArgs)} sort=${CallLog.show(sortOrder)}",
        )
        val cursor = synchronized(store) { queryLocked(uri, projection, selection, selectionArgs) }
        CallLog.log("  -> ${cursor.count} row(s)${describe(cursor)}")
        cursor.moveToPosition(-1)
        return cursor
    }

    private fun queryLocked(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        args: Array<out String>?,
    ): MatrixCursor = when (matcher.match(uri)) {
        NOTES, NOTES_V2 -> rows(projection, Contract.Note.DEFAULT_PROJECTION, filterNotes(selection, args).map(::noteMap))
        NOTE -> rows(projection, Contract.Note.DEFAULT_PROJECTION, listOfNotNull(note(uri.pathSegments[1])).map(::noteMap))
        NOTE_CARDS -> rows(
            projection,
            Contract.Card.DEFAULT_PROJECTION,
            store.cards.filter { it.noteId == uri.pathSegments[1].toLong() }.map(::cardMap),
        )
        NOTE_CARD -> rows(
            projection,
            Contract.Card.DEFAULT_PROJECTION,
            store.cards.filter { it.noteId == uri.pathSegments[1].toLong() && it.ord == uri.pathSegments[3].toInt() }
                .map(::cardMap),
        )
        MODELS -> rows(projection, Contract.Model.DEFAULT_PROJECTION, store.models.map(::modelMap))
        MODEL_CURRENT -> rows(
            projection,
            Contract.Model.DEFAULT_PROJECTION,
            store.models.filter { it.id == store.currentModelId }.map(::modelMap),
        )
        MODEL -> rows(projection, Contract.Model.DEFAULT_PROJECTION, listOfNotNull(model(uri.pathSegments[1])).map(::modelMap))
        MODEL_TEMPLATES -> rows(
            projection,
            Contract.CardTemplate.DEFAULT_PROJECTION,
            model(uri.pathSegments[1])?.let { m -> m.templates.indices.map { templateMap(m, it) } }.orEmpty(),
        )
        MODEL_TEMPLATE -> rows(
            projection,
            Contract.CardTemplate.DEFAULT_PROJECTION,
            model(uri.pathSegments[1])?.let { m ->
                val ord = uri.pathSegments[3].toInt()
                if (ord in m.templates.indices) listOf(templateMap(m, ord)) else emptyList()
            }.orEmpty(),
        )
        MODEL_FIELDS -> rows(
            projection,
            arrayOf("_id", "name", "ord"),
            model(uri.pathSegments[1])?.fields?.mapIndexed { i, f -> mapOf("_id" to i.toLong(), "name" to f, "ord" to i) }.orEmpty(),
        )
        DECKS -> rows(projection, Contract.Deck.DEFAULT_PROJECTION, store.decks.map(::deckMap))
        DECK -> rows(
            projection,
            Contract.Deck.DEFAULT_PROJECTION,
            store.decks.filter { it.id == uri.pathSegments[1].toLong() }.map(::deckMap),
        )
        SELECTED_DECK -> rows(
            projection,
            Contract.Deck.DEFAULT_PROJECTION,
            store.decks.filter { it.id == store.selectedDeckId }.map(::deckMap),
        )
        else -> {
            CallLog.log("  !! unhandled query uri (match=${matcher.match(uri)})")
            MatrixCursor(projection?.let { arrayOf(*it) } ?: arrayOf("_id"))
        }
    }

    /** Duplicate lookup: honours `mid=N`, `csum in (...)`, `csum=N` and `sfld=?`/`sfld='x'` in any combination. */
    private fun filterNotes(selection: String?, args: Array<out String>?): List<NoteRow> {
        if (selection.isNullOrBlank()) return store.notes.toList()
        val mid = Regex("""\bmid\s*[=:]\s*(\d+)""").find(selection)?.groupValues?.get(1)?.toLong()
        val csums = Regex("""\bcsum\s+in\s*\(([^)]*)\)""", RegexOption.IGNORE_CASE).find(selection)
            ?.groupValues?.get(1)?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet()
            ?: Regex("""\bcsum\s*=\s*(\d+)""").find(selection)?.groupValues?.get(1)?.toLong()?.let { setOf(it) }
        val sfld = when {
            Regex("""\bsfld\s*=\s*\?""").containsMatchIn(selection) -> args?.firstOrNull()
            else -> Regex("""\bsfld\s*=\s*'((?:[^']|'')*)'""").find(selection)?.groupValues?.get(1)?.replace("''", "'")
        }
        if (mid == null && csums == null && sfld == null) {
            CallLog.log("  !! notes selection not understood; returning all notes")
        }
        return store.notes.filter { n ->
            (mid == null || n.mid == mid) && (csums == null || n.csum in csums) && (sfld == null || n.sortField == sfld)
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        CallLog.log("insert caller=${caller()} uri=$uri values=${CallLog.show(values)}")
        val result = synchronized(store) { insertLocked(uri, values ?: ContentValues()) }
        CallLog.log("  -> $result")
        return result
    }

    private fun insertLocked(uri: Uri, v: ContentValues): Uri? = when (matcher.match(uri)) {
        NOTES, NOTES_V2 -> {
            val mid = v.getAsLong(Contract.Note.MID)
            val flds = v.getAsString(Contract.Note.FLDS)
            if (mid == null || flds == null) {
                null
            } else {
                val deckId = uri.getQueryParameter(Contract.Note.DECK_ID_QUERY_PARAM)?.toLongOrNull()
                store.addNote(mid, flds.split(Contract.FIELD_SEPARATOR), v.getAsString(Contract.Note.TAGS).orEmpty(), deckId)
                    ?.let { ContentUris.withAppendedId(NOTES_URI, it.id) }
            }
        }
        MODELS -> {
            val fields = v.getAsString(Contract.Model.FIELD_NAMES)?.split(Contract.FIELD_SEPARATOR).orEmpty()
            val numCards = v.getAsInteger(Contract.Model.NUM_CARDS) ?: 1
            val model = store.addModel(
                ModelRow(
                    id = store.newId(),
                    name = v.getAsString(Contract.Model.NAME) ?: "Unnamed",
                    fields = fields,
                    templates = MutableList(numCards) { TemplateRow("Card ${it + 1}", "", "") },
                    css = v.getAsString(Contract.Model.CSS).orEmpty(),
                    deckId = v.getAsLong(Contract.Model.DECK_ID) ?: store.selectedDeckId,
                    sortField = v.getAsInteger(Contract.Model.SORT_FIELD_INDEX) ?: 0,
                    type = v.getAsInteger(Contract.Model.TYPE) ?: 0,
                ),
            )
            ContentUris.withAppendedId(MODELS_URI, model.id)
        }
        MODEL_TEMPLATES -> model(uri.pathSegments[1])?.let { m ->
            m.templates.add(templateFrom(v, TemplateRow("Card ${m.templates.size + 1}", "", "")))
            uri.buildUpon().appendPath((m.templates.size - 1).toString()).build()
        }
        DECKS -> v.getAsString(Contract.Deck.DECK_NAME)?.let { name ->
            ContentUris.withAppendedId(DECKS_URI, store.addDeck(name).id)
        }
        else -> {
            CallLog.log("  !! unhandled insert uri")
            null
        }
    }

    override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int {
        CallLog.log("bulkInsert caller=${caller()} uri=$uri count=${values.size}")
        return values.count { insert(uri, it) != null }
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        CallLog.log(
            "update caller=${caller()} uri=$uri values=${CallLog.show(values)} " +
                "selection=${CallLog.show(selection)} args=${CallLog.show(selectionArgs)}",
        )
        val v = values ?: ContentValues()
        val count = synchronized(store) {
            when (matcher.match(uri)) {
                NOTE_CARD -> {
                    val deck = v.getAsLong(Contract.Card.DECK_ID)
                    val hits = store.cards.filter {
                        it.noteId == uri.pathSegments[1].toLong() && it.ord == uri.pathSegments[3].toInt()
                    }
                    if (deck != null) hits.forEach { it.deckId = deck }
                    hits.size
                }
                NOTE -> {
                    val i = store.notes.indexOfFirst { it.id == uri.pathSegments[1].toLong() }
                    if (i < 0) {
                        0
                    } else {
                        val old = store.notes[i]
                        val fields = v.getAsString(Contract.Note.FLDS)?.split(Contract.FIELD_SEPARATOR) ?: old.fields
                        val sfld = fields.getOrElse(model(old.mid.toString())?.sortField ?: 0) { "" }
                        store.notes[i] = old.copy(
                            fields = fields,
                            tags = v.getAsString(Contract.Note.TAGS) ?: old.tags,
                            sortField = sfld,
                            csum = MemoryStore.fieldChecksum(sfld),
                        )
                        1
                    }
                }
                MODEL_TEMPLATE -> model(uri.pathSegments[1])?.let { m ->
                    val ord = uri.pathSegments[3].toInt()
                    if (ord in m.templates.indices) {
                        m.templates[ord] = templateFrom(v, m.templates[ord])
                        1
                    } else {
                        0
                    }
                } ?: 0
                SELECTED_DECK -> v.getAsLong(Contract.Deck.DECK_ID)?.let { store.selectedDeckId = it; 1 } ?: 0
                MODEL_CURRENT -> v.getAsLong(Contract.Model.ID)?.let { store.currentModelId = it; 1 } ?: 0
                else -> {
                    CallLog.log("  !! unhandled update uri")
                    0
                }
            }
        }
        CallLog.log("  -> $count row(s)")
        return count
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        CallLog.log("delete caller=${caller()} uri=$uri selection=${CallLog.show(selection)} args=${CallLog.show(selectionArgs)}")
        val count = synchronized(store) {
            if (matcher.match(uri) == NOTE) {
                val id = uri.pathSegments[1].toLong()
                store.cards.removeAll { it.noteId == id }
                if (store.notes.removeAll { it.id == id }) 1 else 0
            } else {
                0
            }
        }
        CallLog.log("  -> $count row(s)")
        return count
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        CallLog.log("call caller=${caller()} method=$method arg=${CallLog.show(arg)} extras=${CallLog.show(extras)}")
        return null
    }

    // --- row mapping -------------------------------------------------------------------------

    private fun note(id: String): NoteRow? = store.notes.firstOrNull { it.id == id.toLong() }

    private fun model(id: String): ModelRow? = store.models.firstOrNull { it.id == id.toLongOrNull() }

    private fun noteMap(n: NoteRow): Map<String, Any?> = mapOf(
        Contract.Note.ID to n.id,
        Contract.Note.GUID to n.guid,
        Contract.Note.MID to n.mid,
        Contract.Note.MOD to n.mod,
        Contract.Note.USN to -1,
        Contract.Note.TAGS to n.tags,
        Contract.Note.FLDS to n.fields.joinToString(Contract.FIELD_SEPARATOR),
        Contract.Note.SFLD to n.sortField,
        Contract.Note.CSUM to n.csum,
        Contract.Note.FLAGS to 0,
        Contract.Note.DATA to "",
    )

    private fun modelMap(m: ModelRow): Map<String, Any?> = mapOf(
        Contract.Model.ID to m.id,
        Contract.Model.NAME to m.name,
        Contract.Model.FIELD_NAMES to m.fields.joinToString(Contract.FIELD_SEPARATOR),
        Contract.Model.NUM_CARDS to m.templates.size,
        Contract.Model.CSS to m.css,
        Contract.Model.DECK_ID to m.deckId,
        Contract.Model.SORT_FIELD_INDEX to m.sortField,
        Contract.Model.TYPE to m.type,
        Contract.Model.LATEX_HEADER to "",
        Contract.Model.LATEX_FOOTER to "",
    )

    private fun templateMap(m: ModelRow, ord: Int): Map<String, Any?> {
        val t = m.templates[ord]
        return mapOf(
            Contract.CardTemplate.ID to ord.toLong(),
            Contract.CardTemplate.MODEL_ID to m.id,
            Contract.CardTemplate.ORD to ord,
            Contract.CardTemplate.NAME to t.name,
            Contract.CardTemplate.QUESTION_FORMAT to t.qfmt,
            Contract.CardTemplate.ANSWER_FORMAT to t.afmt,
            Contract.CardTemplate.BROWSER_QUESTION_FORMAT to "",
            Contract.CardTemplate.BROWSER_ANSWER_FORMAT to "",
        )
    }

    private fun templateFrom(v: ContentValues, base: TemplateRow) = TemplateRow(
        name = v.getAsString(Contract.CardTemplate.NAME) ?: base.name,
        qfmt = v.getAsString(Contract.CardTemplate.QUESTION_FORMAT) ?: base.qfmt,
        afmt = v.getAsString(Contract.CardTemplate.ANSWER_FORMAT) ?: base.afmt,
    )

    private fun cardMap(c: CardRow): Map<String, Any?> {
        val n = store.notes.first { it.id == c.noteId }
        val m = store.models.first { it.id == n.mid }
        val t = m.templates.getOrNull(c.ord)
        fun render(fmt: String): String =
            m.fields.foldIndexed(fmt) { i, acc, f -> acc.replace("{{$f}}", n.fields.getOrElse(i) { "" }) }
        val q = render(t?.qfmt.orEmpty())
        val a = render(t?.afmt.orEmpty()).replace("{{FrontSide}}", q)
        return mapOf(
            Contract.Card.NOTE_ID to c.noteId,
            Contract.Card.CARD_ORD to c.ord,
            Contract.Card.CARD_NAME to (t?.name ?: "Card ${c.ord + 1}"),
            Contract.Card.DECK_ID to c.deckId,
            Contract.Card.QUESTION to q,
            Contract.Card.ANSWER to a,
            Contract.Card.QUESTION_SIMPLE to q,
            Contract.Card.ANSWER_SIMPLE to a,
            Contract.Card.ANSWER_PURE to a,
        )
    }

    private fun deckMap(d: DeckRow): Map<String, Any?> = mapOf(
        Contract.Deck.DECK_ID to d.id,
        Contract.Deck.DECK_NAME to d.name,
        Contract.Deck.DECK_COUNTS to "[0,0,0]",
        Contract.Deck.OPTIONS to "{}",
        Contract.Deck.DECK_DYN to 0,
        Contract.Deck.DECK_DESC to "",
    )

    private fun rows(projection: Array<out String>?, default: Array<String>, data: List<Map<String, Any?>>): MatrixCursor {
        val cols: Array<String> = projection?.let { arrayOf(*it) } ?: default
        return MatrixCursor(cols).apply { data.forEach { row -> addRow(cols.map { row[it] }) } }
    }

    private fun describe(c: Cursor): String {
        if (c.count == 0 || c.count > 20) return ""
        val sb = StringBuilder()
        c.moveToPosition(-1)
        while (c.moveToNext()) {
            sb.append("\n     ").append(
                c.columnNames.indices.joinToString { "${c.getColumnName(it)}=${CallLog.show(c.getString(it))}" },
            )
        }
        return sb.toString()
    }

    private companion object {
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
        const val SCHEDULE = 30
        const val MEDIA = 31

        val NOTES_URI: Uri = Uri.parse("content://${Contract.AUTHORITY}/notes")
        val MODELS_URI: Uri = Uri.parse("content://${Contract.AUTHORITY}/models")
        val DECKS_URI: Uri = Uri.parse("content://${Contract.AUTHORITY}/decks")
    }
}
