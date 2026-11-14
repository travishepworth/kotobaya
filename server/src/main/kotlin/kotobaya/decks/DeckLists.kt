package kotobaya.decks

import org.jooq.DSLContext

/** One generated list that can be enabled as a `list` deck. */
internal class ListEntry(val key: String, val title: String, val itemCount: Long)

/**
 * The catalogue of `GET /deck-lists` (contracts/db/VOCAB_LISTS.md 4.1): every `ref.vocab_list` with items
 * (`item_count = entry_count`) and, per frequency list whose key resolves (4.2), `freq-<slug>-<N>` counting live
 * entries of rank <= N. Lists with no items are not offered. Empty while no dictionary release is loaded.
 */
internal object DeckLists {
    val FREQ_BANDS = listOf(1000, 2000, 5000, 10000, 20000)

    fun catalogue(dsl: DSLContext): List<ListEntry> {
        if (!dsl.fetchExists(dsl.selectOne().from("ref.dict_release"))) return emptyList()
        val vocab = dsl.fetch("select id, title, entry_count from ref.vocab_list where entry_count > 0").map {
            ListEntry(it.get(0, String::class.java), it.get(1, String::class.java), it.get(2, Long::class.java))
        }
        val lists = dsl.fetch("select id, title from ref.freq_list order by id").map { it.get(0, String::class.java) to it.get(1, String::class.java) }
        // 4.2 step 2: a slug names a band only if exactly one list has it (the loader rejects collisions anyway).
        val bySlug = lists.groupBy { slug(it.first) }.filterValues { it.size == 1 }.mapValues { it.value.single() }
        val freq = bySlug.flatMap { (slug, list) ->
            val (id, title) = list
            val counts = dsl.fetch(
                "select n, (select count(*) from ref.entry_freq f join ref.dict_entry e on e.seq = f.seq " +
                    "where f.list_id = ? and f.rank <= n and e.retired_release_id is null) " +
                    "from unnest(?::int[]) as n",
                id, FREQ_BANDS.toTypedArray(),
            ).associate { it.get(0, Int::class.java) to it.get(1, Long::class.java) }
            FREQ_BANDS.map { n -> ListEntry("freq-$slug-$n", "$title top $n", counts[n] ?: 0L) }
        }
        return (vocab + freq).filter { it.itemCount > 0 }.sortedWith { a, b -> compareBytes(a.key, b.key) }
    }

    private fun slug(id: String) = id.replace('_', '-')

    /** `list_key` byte order (4.1); keys are ASCII slugs, so this equals UTF-8 byte order. */
    private fun compareBytes(a: String, b: String): Int {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(x.size, y.size)) {
            val c = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
            if (c != 0) return c
        }
        return x.size - y.size
    }
}
