package kotobaya.decks

import kotobaya.contracts.WordStatus
import kotobaya.contracts.cards.CardKinds
import kotobaya.contracts.decks.DeckProgress
import kotobaya.contracts.decks.DeckProgressCount
import kotobaya.contracts.decks.DeckWord
import kotobaya.contracts.fsrs.CardState

/** The one [DeckProgress] (ADR-041): "N of M words learned" for the server and the phone's local API. */
public object DefaultDeckProgress : DeckProgress {
    override fun counts(word: DeckWord): Boolean = word.status != WordStatus.BLACKLISTED && word.status != WordStatus.IGNORED

    override fun isLearned(word: DeckWord, readingEnabled: Boolean): Boolean {
        if (!counts(word)) return false
        if (word.status == WordStatus.KNOWN || word.status == WordStatus.NEVER_FORGET) return true
        val base = word.noteBaseType
        return word.cards.any { c ->
            val kind = c.kind
            val retired = base != null && kind != null && CardKinds.isRetired(base, kind, readingEnabled)
            !retired && c.state == CardState.REVIEW
        }
    }

    override fun count(words: List<DeckWord>, readingEnabled: Boolean): DeckProgressCount {
        val seen = HashSet<String>()
        var learned = 0
        var total = 0
        for (w in words) {
            if (!seen.add(w.key) || !counts(w)) continue
            total++
            if (isLearned(w, readingEnabled)) learned++
        }
        return DeckProgressCount(learned, total)
    }
}
