package kotobaya.decks

import org.jooq.DSLContext

/** One generated list that can be enabled as a `list` deck. */
internal class ListEntry(val key: String, val title: String, val itemCount: Long)

/**
 * The catalogue of `GET /deck-lists`: `jlpt-n5` .. `jlpt-n1` and `freq-<list id>-<N>` per loaded frequency list.
 * Empty while no dictionary release is loaded. There is no JLPT vocabulary source in the database yet, so the
 * JLPT lists report `item_count` 0 (the `deck-build` job owns their content).
 */
internal object DeckLists {
    val FREQ_BANDS = listOf(1000, 2000, 5000, 10000, 20000)

    fun catalogue(dsl: DSLContext): List<ListEntry> {
        if (!dsl.fetchExists(dsl.selectOne().from("ref.dict_release"))) return emptyList()
        val jlpt = (5 downTo 1).map { ListEntry("jlpt-n$it", "JLPT N$it", 0) }
        val freq = dsl.fetch("select id, title from ref.freq_list order by id").flatMap { l ->
            val id = l.get(0, String::class.java)
            val title = l.get(1, String::class.java)
            FREQ_BANDS.map { n ->
                val count = (dsl.fetchValue("select count(*) from ref.entry_freq where list_id = ? and rank <= ?", id, n) as Number).toLong()
                ListEntry("freq-${id.replace('_', '-')}-$n", "$title top $n", count)
            }
        }
        return (jlpt + freq).sortedBy { it.key }
    }
}
