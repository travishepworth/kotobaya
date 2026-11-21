package kotobaya.android.review

import android.util.Log
import kotobaya.android.dict.DictEntryDetail
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.dict.KanjiDetail
import kotobaya.android.report.ScreenLog
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.KanjiData

/**
 * The review renderer's dictionary and kanji data from the installed on-device dictionary (AND-CARDHTML).
 * Notes made from dictionary entries (jpdb imports, auto-kanji) keep their fields empty and render from this data,
 * as on the server and web; without it their cards are blank. A read failure degrades to "unavailable" (null) for
 * that card instead of failing the whole session.
 */
class InstalledRenderData(private val dict: InstalledDictionary) : DictionaryLookup, KanjiLookup {
    private class Slot(val generation: Long) {
        val entries = java.util.concurrent.ConcurrentHashMap<Long, java.util.Optional<DictionaryData>>()
    }

    @Volatile private var slot = Slot(Long.MIN_VALUE)

    /**
     * PERF-LF: answers (hits and misses) are kept for the dictionary's generation (a package install or a sidecar write
     * changes it): the queue plans every pending deck entry through this on every build, one full entry read (three
     * queries and a JSON parse behind the dictionary's one connection) per entry before. A failed read is not kept.
     */
    override fun lookup(entrySeq: Long): DictionaryData? {
        val g = runCatching { dict.generation }.getOrDefault(Long.MIN_VALUE)
        var s = slot
        if (s.generation != g) {
            s = Slot(g)
            slot = s
        }
        s.entries[entrySeq]?.let { return it.orElse(null) }
        var failed = false
        val d = guarded("entry") {
            try {
                dict.entry(entrySeq)?.let(::dictionaryData)
            } catch (e: RuntimeException) {
                failed = true
                throw e
            }
        }
        if (!failed) {
            if (s.entries.size >= MAX_CACHED) s.entries.clear()
            s.entries[entrySeq] = java.util.Optional.ofNullable(d)
        }
        return d
    }

    override fun lookup(literal: String): KanjiData? = guarded("kanji") { dict.kanji(literal)?.let(::kanjiData) }

    private fun <T> guarded(what: String, block: () -> T?): T? = try {
        block()
    } catch (e: RuntimeException) {
        runCatching { Log.w(ScreenLog.TAG, "review render data: $what lookup failed: ${e.javaClass.simpleName}") }
        null
    }

    companion object {
        private const val MAX_CACHED = 50_000

        /** First-sense glosses and the headword reading's pitch, as the server's queue and the note matcher use. */
        fun dictionaryData(d: DictEntryDetail): DictionaryData {
            val glosses = d.senses.optJSONObject(0)?.optJSONArray("gloss")?.let { gl ->
                (0 until gl.length()).mapNotNull { gl.optJSONObject(it)?.optString("text")?.takeIf(String::isNotEmpty) }
            }.orEmpty()
            val pitch = d.pitch.firstOrNull { it.reading == d.headwordReading }?.positions.orEmpty()
            return DictionaryData(d.headword, d.headwordReading, emptyList(), glosses, pitch)
        }

        fun kanjiData(k: KanjiDetail): KanjiData = KanjiData(k.literal, k.meanings, k.onReadings, k.kunReadings, k.strokeCount)
    }
}

/**
 * The example-word candidates of a kanji from the installed dictionary (C-25): every entry with an `entry_kanji` row for
 * the literal that is not retired. Runs through [InstalledDictionary.formRows]'s statement runner: column 1 carries the
 * headword, column 2 `reading|freq_rank` (empty rank = unranked). No dictionary installed: no words.
 */
class InstalledKanjiWords(private val dict: InstalledDictionary) : KanjiWordSource {
    override fun words(literal: String): List<DictWord> = try {
        dict.formRows(SQL, listOf(literal)) { true }.orEmpty().mapNotNull { r ->
            val reading = r.norm.substringBeforeLast('|')
            val rank = r.norm.substringAfterLast('|', "").toIntOrNull()
            DictWord(r.seq, r.form, reading, rank)
        }
    } catch (e: RuntimeException) {
        runCatching { Log.w(ScreenLog.TAG, "review kanji words: lookup failed: ${e.javaClass.simpleName}") }
        emptyList()
    }

    private companion object {
        const val SQL = "SELECT e.seq, e.headword, e.headword_reading || '|' || coalesce(e.freq_rank, '') FROM entry_kanji k " +
            "JOIN dict_entry e ON e.seq = k.seq WHERE k.literal = ? AND e.retired = 0"
    }
}
