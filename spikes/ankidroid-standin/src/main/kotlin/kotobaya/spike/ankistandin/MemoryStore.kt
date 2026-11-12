package kotobaya.spike.ankistandin

import java.security.MessageDigest
import java.util.UUID

data class DeckRow(val id: Long, val name: String)

data class TemplateRow(val name: String, val qfmt: String, val afmt: String)

data class ModelRow(
    val id: Long,
    val name: String,
    val fields: List<String>,
    val templates: MutableList<TemplateRow>,
    val css: String,
    val deckId: Long,
    val sortField: Int,
    val type: Int,
)

data class NoteRow(
    val id: Long,
    val guid: String,
    val mid: Long,
    val fields: List<String>,
    val tags: String,
    val mod: Long,
    val sortField: String,
    val csum: Long,
)

data class CardRow(val noteId: Long, val ord: Int, var deckId: Long)

/** In-memory stand-in for the collection. Lost on process death by design (spike, no persistence). */
class MemoryStore {
    private var nextId = 1_700_000_000_000L

    val decks = mutableListOf(DeckRow(1, "Default"))
    val models = mutableListOf(
        ModelRow(
            id = newId(),
            name = "Basic",
            fields = listOf("Front", "Back"),
            templates = mutableListOf(TemplateRow("Card 1", "{{Front}}", "{{FrontSide}}<hr id=answer>{{Back}}")),
            css = "",
            deckId = 1,
            sortField = 0,
            type = 0,
        ),
    )
    val notes = mutableListOf<NoteRow>()
    val cards = mutableListOf<CardRow>()
    var selectedDeckId = 1L
    var currentModelId = models.first().id

    @Synchronized
    fun newId(): Long = nextId++

    @Synchronized
    fun addDeck(name: String): DeckRow =
        decks.firstOrNull { it.name == name } ?: DeckRow(newId(), name).also { decks.add(it) }

    @Synchronized
    fun addModel(model: ModelRow): ModelRow = model.also { models.add(it) }

    @Synchronized
    fun addNote(mid: Long, fields: List<String>, tags: String, deckId: Long?): NoteRow? {
        val model = models.firstOrNull { it.id == mid } ?: return null
        val sfld = fields.getOrElse(model.sortField) { "" }
        val note = NoteRow(
            id = newId(),
            guid = UUID.randomUUID().toString(),
            mid = mid,
            fields = fields,
            tags = tags,
            mod = System.currentTimeMillis() / 1000,
            sortField = sfld,
            csum = fieldChecksum(sfld),
        )
        notes.add(note)
        val targetDeck = deckId ?: model.deckId
        model.templates.indices.forEach { cards.add(CardRow(note.id, it, targetDeck)) }
        return note
    }

    companion object {
        /** Anki's checksum: first 8 hex digits of SHA-1 over the field with HTML stripped. */
        fun fieldChecksum(field: String): Long {
            val stripped = field.replace(Regex("<[^>]*>"), "").trim()
            val digest = MessageDigest.getInstance("SHA-1").digest(stripped.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }
            return hex.substring(0, 8).toLong(16)
        }
    }
}
