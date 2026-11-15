package kotobaya.android.ankiapi.store

import kotobaya.android.dict.InstalledDictionary

/** What a note matched to an entry shows in fields it does not store itself. */
data class Headword(val word: String, val reading: String, val meaning: String)

/** An entry's headword and reading only ([Headword] without the meaning, which needs the senses). */
data class HeadwordForms(val word: String, val reading: String)

/** The dictionary as the store reads it: a matched note's headword, reading and first-sense meaning. */
fun interface EntryLookup {
    fun headword(seq: Long): Headword?

    /**
     * [Headword.word] and [Headword.reading] of every entry in [seqs] that exists (absent ones are left out).
     * The duplicate query checksums every vocab note's first field, so implementations should batch this.
     */
    fun forms(seqs: Collection<Long>): Map<Long, HeadwordForms> =
        seqs.mapNotNull { seq -> headword(seq)?.let { seq to HeadwordForms(it.word, it.reading) } }.toMap()

    /** Which dictionary answers (changes when another one is installed); indexes built from it rebuild then. */
    fun identity(): String = ""
}

/** [EntryLookup] over the installed dictionary, with a small per-process cache of [headword]. */
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

    /**
     * One primary-key query per [CHUNK] seqs on `dict_entry` (the same `headword`/`headword_reading` columns
     * [InstalledDictionary.entry] reads), instead of a full entry read (three queries and JSON parsing) per seq.
     * The seqs are inlined as integer literals (Longs, nothing to escape). No dictionary → empty.
     */
    override fun forms(seqs: Collection<Long>): Map<Long, HeadwordForms> {
        val out = HashMap<Long, HeadwordForms>(seqs.size * 2)
        for (chunk in seqs.distinct().chunked(CHUNK)) {
            val sql = chunk.joinToString(",", "SELECT seq, headword, headword_reading FROM dict_entry WHERE seq IN (", ")")
            val rows = runCatching { dict.formRows(sql, emptyList()) { true } }.getOrNull() ?: return out
            for (r in rows) out[r.seq] = HeadwordForms(r.form, r.norm)
        }
        return out
    }

    override fun identity(): String =
        runCatching { dict.info() }.getOrNull()?.let { "${it.releaseId}:${it.scope}:${it.version}" } ?: "none"

    private companion object {
        const val CHUNK = 500
    }
}
