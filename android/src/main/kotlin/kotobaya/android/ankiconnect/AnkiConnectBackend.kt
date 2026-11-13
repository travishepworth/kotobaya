package kotobaya.android.ankiconnect

/** A note as AnkiConnect clients send it (`addNote` / `canAddNotes`). */
data class AcNote(
    val deckName: String,
    val modelName: String,
    val fields: Map<String, String>,
    val tags: List<String> = emptyList(),
    val allowDuplicate: Boolean = false,
)

data class AcModel(val id: Long, val name: String, val fields: List<String>)

data class AcDeck(val id: Long, val name: String)

data class AcNoteInfo(
    val noteId: Long,
    val modelName: String,
    val tags: List<String>,
    /** Field name -> value, in field order. */
    val fields: LinkedHashMap<String, String>,
)

sealed interface AcAdd {
    data class Added(val noteId: Long) : AcAdd

    /** A live note already studies the word (words are learned once; `allowDuplicate` is not honoured). */
    data object Duplicate : AcAdd

    data class Invalid(val message: String) : AcAdd
}

/** What the protocol layer needs of the collection. The production one is [StoreAnkiConnectBackend]. */
interface AnkiConnectBackend {
    fun decks(): List<AcDeck>

    fun models(): List<AcModel>

    /** Would [addNote] create it (no duplicate, valid deck/model/fields)? Never writes. */
    fun check(note: AcNote): AcAdd

    /** Creates the note through the op-minting path. */
    fun addNote(note: AcNote): AcAdd

    /** Stores [bytes] in the blob cache; returns the name the stored file is known by. */
    fun storeMedia(filename: String, bytes: ByteArray): String

    /** [query] is AnkiConnect search syntax; only the basic forms are understood (see [AnkiConnectProtocol]). */
    fun findNotes(query: String): List<Long>

    /** Unknown ids are absent from the result. */
    fun notesInfo(ids: List<Long>): List<AcNoteInfo>
}
