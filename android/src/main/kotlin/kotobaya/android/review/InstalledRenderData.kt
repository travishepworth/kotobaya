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
