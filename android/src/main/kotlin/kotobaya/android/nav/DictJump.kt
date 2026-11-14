package kotobaya.android.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where a "look up in the dictionary" affordance wants to go. */
sealed interface DictTarget {
    /** The word detail of dictionary entry [seq]. */
    data class Word(val seq: Long) : DictTarget

    /** Words that use the kanji [literal] (the kanji tile's own behaviour). */
    data class Kanji(val literal: String) : DictTarget
}

/**
 * FEAT-DICT-APP: the one-shot request to open the offline dictionary's detail. Any screen calls [openWord] / [openKanji];
 * the app shell moves to the Search tab and the Search screen consumes it (a request set before that screen exists is
 * kept until it does).
 */
object DictJump {
    private val _pending = MutableStateFlow<DictTarget?>(null)
    val pending: StateFlow<DictTarget?> get() = _pending

    fun openWord(seq: Long) {
        _pending.value = DictTarget.Word(seq)
    }

    fun openKanji(literal: String) {
        _pending.value = DictTarget.Kanji(literal)
    }

    fun consume() {
        _pending.value = null
    }
}
