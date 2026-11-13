package kotobaya.android.ankiconnect

import java.io.File
import java.security.MessageDigest
import kotobaya.android.ankiapi.provider.AnkiContract
import kotobaya.android.ankiapi.provider.AnkiStoreException
import kotobaya.android.ankiapi.provider.NewNote
import kotobaya.android.ankiapi.provider.NoteFilter
import kotobaya.android.ankiapi.store.AddOutcome
import kotobaya.android.ankiapi.store.TakobotoAnkiStore
import kotobaya.android.media.BlobReason
import kotobaya.android.media.BlobStore

/** Where `storeMediaFile` data goes. */
fun interface MediaSink {
    /** Returns the name the stored file is known by. */
    fun store(filename: String, bytes: ByteArray): String
}

/** Content-addressed blob cache (`blob_cache`, reason media): the file is `<sha256>[.ext]`'s hash. */
class BlobMediaSink(private val blobs: BlobStore, private val nowMs: () -> Long = System::currentTimeMillis) : MediaSink {
    override fun store(filename: String, bytes: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val tmp = File(blobs.dir, "$hash.part")
        tmp.writeBytes(bytes)
        blobs.publish(hash, tmp)
        val now = nowMs()
        blobs.want(hash, BlobReason.MEDIA, now)
        blobs.markCached(hash, bytes.size.toLong(), null, now)
        val ext = filename.substringAfterLast('.', "").takeIf { it.length in 1..8 && it.all(Char::isLetterOrDigit) }
        return if (ext != null) "$hash.$ext" else hash
    }
}

/**
 * [AnkiConnectBackend] over the T305c [TakobotoAnkiStore]: decks and models as AnkiDroid callers see them
 * (the "Takoboto Vocab" model and any a client made), notes added through the store's [NoteCreator]
 * (so every note becomes sync ops, origin `api`), duplicates answered by the same word-level check.
 *
 * findNotes understands: `deck:...` (ignored: notes are not tied to a deck here), `note:`/`model:` Name, `Field:value`
 * and a bare term (both: the word, via the duplicate check), and no term (every note). Other `key:value` tokens are ignored.
 */
class StoreAnkiConnectBackend(
    private val store: TakobotoAnkiStore<*>,
    private val media: MediaSink,
) : AnkiConnectBackend {
    override fun decks(): List<AcDeck> = store.decks().map { AcDeck(it.id, it.name) }

    override fun models(): List<AcModel> = store.models().map { AcModel(it.id, it.name, it.fields) }

    override fun check(note: AcNote): AcAdd {
        val resolved = resolve(note)
        if (resolved is Resolved.Bad) return AcAdd.Invalid(resolved.message)
        resolved as Resolved.Ok
        val word = AnkiContract.stripHtmlMedia(resolved.fields[0]).trim()
        if (word.isNotEmpty() && store.findNotes(NoteFilter(sortField = word)).isNotEmpty()) return AcAdd.Duplicate
        return AcAdd.Added(0)
    }

    override fun addNote(note: AcNote): AcAdd {
        val resolved = resolve(note)
        if (resolved is Resolved.Bad) return AcAdd.Invalid(resolved.message)
        resolved as Resolved.Ok
        return try {
            when (val o = store.add(NewNote(resolved.modelId, resolved.fields, note.tags, resolved.deckId, ORIGIN))) {
                is AddOutcome.Added -> AcAdd.Added(o.note.id)
                is AddOutcome.Duplicate -> AcAdd.Duplicate
            }
        } catch (e: AnkiStoreException) {
            AcAdd.Invalid(e.message ?: "cannot add note")
        }
    }

    private sealed interface Resolved {
        class Ok(val modelId: Long, val deckId: Long, val fields: List<String>) : Resolved

        class Bad(val message: String) : Resolved
    }

    private fun resolve(note: AcNote): Resolved {
        val deck = decks().firstOrNull { it.name == note.deckName } ?: return Resolved.Bad("deck was not found: ${note.deckName}")
        val model = store.models().firstOrNull { it.name == note.modelName } ?: return Resolved.Bad("model was not found: ${note.modelName}")
        val unknown = note.fields.keys.firstOrNull { it !in model.fields }
        if (unknown != null) return Resolved.Bad("cannot create note because field was not found: $unknown")
        val values = model.fields.map { note.fields[it].orEmpty() }
        if (values.all { it.isBlank() }) return Resolved.Bad("cannot create note because it is empty")
        return Resolved.Ok(model.id, deck.id, values)
    }

    override fun storeMedia(filename: String, bytes: ByteArray): String = media.store(filename, bytes)

    override fun findNotes(query: String): List<Long> {
        var modelId: Long? = null
        var term: String? = null
        val models = store.models()
        for (m in TOKEN.findAll(query)) {
            val token = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val colon = token.indexOf(':')
            if (colon <= 0) {
                if (token.isNotBlank()) term = token
                continue
            }
            val key = token.substring(0, colon).lowercase()
            val value = token.substring(colon + 1).trim('"')
            when {
                key == "deck" -> Unit
                key == "note" || key == "model" -> modelId = models.firstOrNull { it.name.equals(value, ignoreCase = true) }?.id ?: return emptyList()
                models.any { mod -> mod.fields.any { it.equals(token.substring(0, colon), ignoreCase = true) } } -> term = value
                else -> Unit
            }
        }
        return store.findNotes(NoteFilter(modelId = modelId, sortField = term?.takeIf { it.isNotBlank() })).map { it.id }
    }

    override fun notesInfo(ids: List<Long>): List<AcNoteInfo> {
        val models = store.models().associateBy { it.id }
        return ids.mapNotNull { id ->
            val n = store.note(id) ?: return@mapNotNull null
            val model = models[n.modelId] ?: return@mapNotNull null
            val fields = LinkedHashMap<String, String>()
            model.fields.forEachIndexed { i, name -> fields[name] = n.fields.getOrElse(i) { "" } }
            AcNoteInfo(n.id, model.name, n.tags, fields)
        }
    }

    private companion object {
        const val ORIGIN = "ankiconnect"
        val TOKEN = Regex("\"([^\"]*)\"|(\\S+)")
    }
}
