package kotobaya.android.review

import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.KanjiData

/** Dictionary data for a vocab entry (T302 on-device dictionary), or null when unavailable. */
fun interface DictionaryLookup {
    fun lookup(entrySeq: Long): DictionaryData?
}

/** Kanji data for a kanji note's `kanji_literal` (the on-device dictionary's `kanji` table), or null when unavailable. */
fun interface KanjiLookup {
    fun lookup(literal: String): KanjiData?
}
