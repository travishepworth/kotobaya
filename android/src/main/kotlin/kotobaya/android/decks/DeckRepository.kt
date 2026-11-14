package kotobaya.android.decks

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotobaya.android.queue.LoadedQueue
import kotobaya.android.queue.LocalQueueLoader
import kotobaya.android.queue.QueueCounts
import kotobaya.android.search.EntryLabeler
import kotobaya.android.sync.content.ContentInstaller
import kotobaya.android.sync.content.ContentKind
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.queue.DefaultQueueBuilder
import kotobaya.sync.DefaultCanonicalJson

/** One deck of the replica with the counts the queue holds for it. */
data class DeckSummary(
    val id: String,
    val name: String,
    val type: String,
    val enabled: Boolean,
    val query: String?,
    val counts: QueueCounts,
    val totalCards: Int,
    /** Whether the deck's server-built items are on this device (AND-DECKCONTENT); user and smart decks are [DeckContent.READY]. */
    val content: DeckContent = DeckContent.READY,
)

/**
 * Install state of a media/list deck's items (SNAPSHOT.md 7): a deck with no words is truly empty only when
 * [READY]; otherwise its items are still to come.
 */
enum class DeckContent {
    /** Items installed (or the deck has no server-built items: user, smart). */
    READY,

    /** The server has not published items yet (`content_version = 0`). */
    NOT_PUBLISHED,

    /** A newer version is announced and not installed yet (queued, downloading, or waiting to retry). */
    DOWNLOADING,

    /** The announced archive was rejected (logged as `KotobayaContent`); waits for a newer version or app. */
    FAILED,
}

/**
 * A word of a deck. [label] is, in order: the note's expression, the dictionary headword, the item key (last resort).
 * [detail] is the reading and first gloss (the note's fields, else the dictionary's).
 */
data class DeckWord(val itemKey: String, val label: String, val entrySeq: Long? = null, val detail: String = "")

/**
 * Read-only view of decks over the local replica. Counts come from [LocalQueueLoader.load] with the deck filter,
 * the same builder a "Study this deck" session uses; this class applies no queue rule itself.
 */
/** What the Decks screen reads (blocking; the ViewModel calls it on IO). */
interface DeckSource {
    fun decks(): List<DeckSummary>

    /**
     * The decks plus the counts of the whole queue (what the Review tab starts from), from one build. AND-QUEUE: Home
     * must show [DecksOverview.queue], not a sum over decks: cards in no deck (mined, imported history) are only
     * counted there, and smart decks overlap the others.
     */
    fun overview(): DecksOverview

    fun deck(id: String): DeckSummary?

    fun words(deckId: String, limit: Int, offset: Int): List<DeckWord>
}

/** [decks] with [queue], the counts of the unfiltered queue built in the same load. */
data class DecksOverview(val decks: List<DeckSummary>, val queue: QueueCounts)

class DeckRepository(
    private val driver: SqlDriver,
    private val loader: LocalQueueLoader,
    /** The offline dictionary, for words whose note has no `expression` (or no note on this device). */
    private val labeler: EntryLabeler? = null,
) : DeckSource {
    private class Row(
        val id: String,
        val name: String,
        val type: String,
        val enabled: Boolean,
        val query: String?,
        val content: DeckContent,
    )

    override fun decks(): List<DeckSummary> = overview().decks

    override fun overview(): DecksOverview {
        val rows = rows()
        val loaded = loader.load()
        return DecksOverview(rows.map { summary(it, loaded) }, loaded.counts)
    }

    override fun deck(id: String): DeckSummary? = rows().firstOrNull { it.id == id }?.let { summary(it, loader.load()) }

    /** Live items of [deckId] in the deck's order, [limit] from [offset]. */
    override fun words(deckId: String, limit: Int, offset: Int): List<DeckWord> = query(
        "SELECT di.item_key, di.entry_seq, (SELECT n.fields FROM note n WHERE n.deleted_at IS NULL AND " +
            "(n.entry_seq = di.entry_seq OR n.id = di.note_id) LIMIT 1) " +
            "FROM deck_item di WHERE di.deck_id = ? AND di.deleted_at IS NULL ORDER BY di.position, di.item_key LIMIT ? OFFSET ?",
        3,
        { bindString(0, deckId); bindLong(1, limit.toLong()); bindLong(2, offset.toLong()) },
    ) { c -> Triple(c.getString(0).orEmpty(), c.getLong(1), c.getString(2)) }.let(::labelled)

    private fun labelled(rows: List<Triple<String, Long?, String?>>): List<DeckWord> {
        val missing = rows.filter { (_, seq, fields) -> seq != null && (fields == null || fieldOf(fields, "expression") == null) }
        val labels = if (missing.isEmpty()) emptyMap() else runCatching { labeler?.labels(missing.mapNotNull { it.second }) }.getOrNull().orEmpty()
        return rows.map { (key, seq, fields) ->
            val l = seq?.let(labels::get)
            val expression = fields?.let { fieldOf(it, "expression") }
            val reading = fields?.let { fieldOf(it, "reading") } ?: l?.reading.orEmpty()
            val meaning = fields?.let { fieldOf(it, "meaning") } ?: l?.gloss.orEmpty()
            DeckWord(key, expression ?: l?.headword?.takeIf { it.isNotEmpty() } ?: key, seq, listOf(reading, meaning).filter { it.isNotBlank() }.joinToString(" · "))
        }
    }

    /**
     * One load per reload: the same builder input as `load(deckFilter = id)` (it filters candidates only), re-run per
     * deck over the already-read rows, so the counts are identical without re-reading the database per deck.
     */
    private fun countsOf(loaded: LoadedQueue, deckId: String): QueueCounts {
        val input = loaded.input
        val filtered = input.copy(candidates = input.candidates.filter { c -> c.memberships.any { it.deckId == deckId } })
        return LoadedQueue(filtered, DefaultQueueBuilder.build(filtered)).counts
    }

    private fun summary(r: Row, loaded: LoadedQueue): DeckSummary = DeckSummary(
        id = r.id,
        name = r.name,
        type = r.type,
        enabled = r.enabled,
        query = r.query,
        counts = countsOf(loaded, r.id),
        totalCards = totalCards(r.id),
        content = r.content,
    )

    private fun rows(): List<Row> = query(
        "SELECT d.id, d.name, d.type, d.enabled, d.query, d.content_version, d.installed_content_version, " +
            "d.content_blob_hash = (SELECT s.value FROM sync_state s WHERE s.key = ? || d.id) " +
            "FROM deck d WHERE d.deleted_at IS NULL ORDER BY d.priority, d.id",
        1,
        { bindString(0, ContentInstaller.rejectedKey(ContentKind.DECK_ITEMS, "")) },
    ) { c ->
        val type = c.getString(2).orEmpty()
        val version = c.getLong(5) ?: 0L
        val content = when {
            type != "media" && type != "list" -> DeckContent.READY
            version == 0L -> DeckContent.NOT_PUBLISHED
            (c.getLong(6) ?: 0L) >= version -> DeckContent.READY
            c.getLong(7) == 1L -> DeckContent.FAILED
            else -> DeckContent.DOWNLOADING
        }
        Row(c.getString(0).orEmpty(), c.getString(1).orEmpty(), type, c.getLong(3) == 1L, c.getString(4), content)
    }

    /** Live cards of the live notes the deck's items name (merge losers resolve to the survivor, as the loader does). */
    private fun totalCards(deckId: String): Int = query(
        "SELECT COUNT(*) FROM card c WHERE c.deleted_at IS NULL AND c.merged_into_id IS NULL AND c.note_id IN (" +
            "SELECT n.id FROM deck_item di JOIN note n ON n.entry_seq = di.entry_seq AND n.deleted_at IS NULL " +
            "WHERE di.deck_id = ? AND di.deleted_at IS NULL AND di.entry_seq IS NOT NULL " +
            "UNION SELECT n.id FROM deck_item di JOIN note src ON src.id = di.note_id " +
            "JOIN note n ON n.id = COALESCE(src.merged_into_id, src.id) AND n.deleted_at IS NULL " +
            "WHERE di.deck_id = ? AND di.deleted_at IS NULL AND di.note_id IS NOT NULL)",
        2,
        { bindString(0, deckId); bindString(1, deckId) },
    ) { c -> (c.getLong(0) ?: 0L).toInt() }.singleOrNull() ?: 0

    private fun fieldOf(fields: String, key: String): String? {
        val v = (DefaultCanonicalJson.decode(fields).value as? JsonObject)?.members?.get(key) as? JsonString
        return v?.value?.replace(Regex("<[^>]*>"), "")?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun <T> query(sql: String, params: Int, bind: SqlPreparedStatement.() -> Unit, map: (SqlCursor) -> T): List<T> =
        driver.executeQuery(
            null,
            sql,
            { c ->
                val out = ArrayList<T>()
                while (c.next().value) out.add(map(c))
                QueryResult.Value(out)
            },
            params,
            bind,
        ).value
}
