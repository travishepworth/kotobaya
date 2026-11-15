package kotobaya.android.review

import android.util.Log
import kotobaya.android.dict.DictEntryDetail
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.dict.KanjiDetail
import kotobaya.android.vm.ScreenLog
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.KanjiData

/**
 * The review renderer's dictionary and kanji data from the installed on-device dictionary (AND-CARDHTML).
 * Notes made from dictionary entries (jpdb imports, auto-kanji) keep their fields empty and render from this data,
 * as on the server and web; without it their cards are blank. A read failure degrades to "unavailable" (null) for
 * that card instead of failing the whole session.
 */
class InstalledRenderData(private val dict: InstalledDictionary) : DictionaryLookup, KanjiLookup {
    override fun lookup(entrySeq: Long): DictionaryData? = guarded("entry") { dict.entry(entrySeq)?.let(::dictionaryData) }

    override fun lookup(literal: String): KanjiData? = guarded("kanji") { dict.kanji(literal)?.let(::kanjiData) }

    private fun <T> guarded(what: String, block: () -> T?): T? = try {
        block()
    } catch (e: RuntimeException) {
        runCatching { Log.w(ScreenLog.TAG, "review render data: $what lookup failed: ${e.javaClass.simpleName}") }
        null
    }

    companion object {
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
