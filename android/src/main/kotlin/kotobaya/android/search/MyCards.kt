package kotobaya.android.search

import app.cash.sqldelight.db.SqlDriver

/** That the user has a note for a dictionary entry, and the decks that list it. */
data class MyCardInfo(val deckNames: List<String> = emptyList())

/** Which of some dictionary entries the user has a live note for (replica only: empty until the replica is usable). */
class MyCardsLookup(private val driver: SqlDriver, private val usable: () -> Boolean = { true }) {
    fun lookup(seqs: Collection<Long>): Map<Long, MyCardInfo> {
        if (seqs.isEmpty() || !usable()) return emptyMap()
        val decks = HashMap<Long, MutableList<String>>()
        val linked = HashSet<Long>()
        for (chunk in seqs.toList().chunked(400)) {
            driver.rows(
                "SELECT DISTINCT entry_seq FROM note WHERE deleted_at IS NULL AND entry_seq IN (${placeholders(chunk.size)})", chunk,
            ) { linked += it.getLong(0) ?: 0L }
            driver.rows(
                "SELECT DISTINCT di.entry_seq, d.name FROM deck_item di JOIN deck d ON d.id = di.deck_id " +
                    "WHERE di.deleted_at IS NULL AND d.deleted_at IS NULL AND di.entry_seq IN (${placeholders(chunk.size)}) ORDER BY d.priority, d.id",
                chunk,
            ) { decks.getOrPut(it.getLong(0) ?: 0L) { ArrayList() } += it.getString(1).orEmpty() }
        }
        return linked.associateWith { MyCardInfo(decks[it].orEmpty().distinct()) }
    }
}
