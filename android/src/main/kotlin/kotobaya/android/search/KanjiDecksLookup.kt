package kotobaya.android.search

import kotobaya.android.review.BackExtrasBuilder
import kotobaya.android.review.BackExtrasIndexSource
import kotobaya.android.review.KanjiWordSource

/**
 * AND-KANJI-DECKS: the decks "containing" a kanji for the Search screen's kanji query, from the same per-build
 * [kotobaya.android.review.BackExtrasIndex] the review back uses (read once per call; one in-memory pass over the
 * kanji's dictionary words, no per-word queries).
 */
fun interface KanjiDecksLookup {
    /** (deck name, words with the kanji in it) in priority order. */
    fun lookup(literal: String): List<Pair<String, Int>>

    companion object {
        fun over(index: BackExtrasIndexSource, words: KanjiWordSource): KanjiDecksLookup = KanjiDecksLookup { literal ->
            BackExtrasBuilder.kanjiDecksOf(literal, words, index.load()).map { it.name to it.count }
        }
    }
}
