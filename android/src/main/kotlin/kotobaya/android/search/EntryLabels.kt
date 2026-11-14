package kotobaya.android.search

import kotobaya.android.dict.InstalledDictionary

/** What a dictionary entry is called in a list row: headword, reading and first gloss. */
data class EntryLabel(val headword: String, val reading: String, val gloss: String)

/** Resolves dictionary entries to display labels (blocking; callers are on IO). Missing entries are absent from the map. */
fun interface EntryLabeler {
    fun labels(seqs: Collection<Long>): Map<Long, EntryLabel>
}

/** [EntryLabeler] over the installed on-device dictionary; empty when none is installed. */
fun InstalledDictionary.asLabeler(): EntryLabeler = EntryLabeler { seqs ->
    if (!isAvailable()) {
        emptyMap()
    } else {
        val out = HashMap<Long, EntryLabel>()
        for (seq in seqs.toSet()) {
            val e = runCatching { entry(seq) }.getOrNull() ?: continue
            val gloss = e.senses.optJSONObject(0)?.optJSONArray("gloss")?.optJSONObject(0)?.optString("text").orEmpty()
            out[seq] = EntryLabel(e.headword, e.headwordReading, gloss)
        }
        out
    }
}
