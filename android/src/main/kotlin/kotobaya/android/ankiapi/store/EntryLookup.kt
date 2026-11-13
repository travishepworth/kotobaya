package kotobaya.android.ankiapi.store

import kotobaya.android.dict.InstalledDictionary

/** What a note matched to an entry shows in fields it does not store itself. */
data class Headword(val word: String, val reading: String, val meaning: String)

/** The dictionary as the store reads it: a matched note's headword, reading and first-sense meaning. */
fun interface EntryLookup {
    fun headword(seq: Long): Headword?
}

/** [EntryLookup] over the installed dictionary, with a small per-process cache (duplicate queries scan all notes). */
class InstalledEntryLookup(private val dict: InstalledDictionary, private val capacity: Int = 4096) : EntryLookup {
    private val cache = object : LinkedHashMap<Long, Headword>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Headword>?): Boolean = size > capacity
    }

    override fun headword(seq: Long): Headword? {
        synchronized(cache) { cache[seq]?.let { return it } }
        val d = runCatching { dict.entry(seq) }.getOrNull() ?: return null // a missing dictionary is not cached
        val gloss = d.senses.optJSONObject(0)?.optJSONArray("gloss")?.let { gl ->
            (0 until gl.length()).mapNotNull { gl.optJSONObject(it)?.optString("text")?.takeIf(String::isNotEmpty) }
        }.orEmpty()
        val h = Headword(d.headword, d.headwordReading, gloss.joinToString("; "))
        synchronized(cache) { cache[seq] = h }
        return h
    }
}
