package kotobaya.android.decks

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotobaya.android.queue.LoadedQueue
import kotobaya.android.queue.LocalQueueLoader
import kotobaya.android.queue.QueueCounts
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
)

/** A word of a deck: [label] is the note's expression when a note exists on this device, else the item key. */
data class DeckWord(val itemKey: String, val label: String, val entrySeq: Long? = null)

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

class DeckRepository(private val driver: SqlDriver, private val loader: LocalQueueLoader) : DeckSource {
    private class Row(val id: String, val name: String, val type: String, val enabled: Boolean, val query: String?)

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
    ) { c ->
        val key = c.getString(0).orEmpty()
        DeckWord(key, expressionOf(c.getString(2)) ?: key, c.getLong(1))
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
    )

    private fun rows(): List<Row> = query(
        "SELECT id, name, type, enabled, query FROM deck WHERE deleted_at IS NULL ORDER BY priority, id",
        0,
        {},
    ) { c -> Row(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3) == 1L, c.getString(4)) }

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

    private fun expressionOf(fields: String?): String? {
        if (fields == null) return null
        val v = (DefaultCanonicalJson.decode(fields).value as? JsonObject)?.members?.get("expression") as? JsonString
        return v?.value?.takeIf { it.isNotEmpty() }?.replace(Regex("<[^>]*>"), "")
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
