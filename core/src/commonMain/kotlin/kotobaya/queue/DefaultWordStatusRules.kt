package kotobaya.queue

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.WordStatus
import kotobaya.contracts.cards.CardKinds
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.queue.WordCardState
import kotobaya.contracts.queue.WordStatusRules

/** [WordStatusRules] exactly as its KDoc states. */
public object DefaultWordStatusRules : WordStatusRules {
    override fun autoStatus(noteLive: Boolean, cards: List<WordCardState>, readingEnabled: Boolean): WordStatus? {
        if (!noteLive) return null
        val started = cards
            .filterNot { c -> c.kind?.let { CardKinds.isRetired(NoteBaseType.VOCAB, it, readingEnabled) } ?: false }
            .filter { it.state != CardState.NEW }
        if (started.isEmpty()) return WordStatus.NEW
        val known = started.all {
            it.state == CardState.REVIEW && (it.intervalDays ?: 0) >= WordStatusRules.KNOWN_INTERVAL_DAYS
        }
        return if (known) WordStatus.KNOWN else WordStatus.LEARNING
    }

    override fun effective(override: WordStatus?, auto: WordStatus?): WordStatus = override ?: auto ?: WordStatus.NEW
}
