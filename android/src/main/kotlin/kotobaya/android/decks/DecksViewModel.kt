package kotobaya.android.decks

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotobaya.android.vm.StateViewModel

/** Which page of the Decks tab shows. */
enum class DecksMode { LIST, DETAIL, STUDY }

/** The Decks tab's state. Null lists are still loading. */
data class DecksUiState(
    val mode: DecksMode = DecksMode.LIST,
    val decks: List<DeckSummary>? = null,
    val selectedId: String? = null,
    val deck: DeckSummary? = null,
    val words: List<DeckWord>? = null,
    /** Words shown in the detail (grows by [DecksViewModel.PAGE] with "More"). */
    val limit: Int = DecksViewModel.PAGE,
    /** Bumped per "Study" visit, so each visit gets a fresh review session. */
    val studyRun: Int = 0,
)

/** What the Decks screen can ask for (no-ops by default, for previews). */
interface DecksIntents {
    fun refresh() {}
    fun open(deckId: String) {}
    fun back() {}
    fun study() {}
    fun endStudy() {}
    fun more() {}
    /** A study session answered something (counts change). */
    fun sessionEnded() {}
}

/**
 * The Decks tab (read-only): list, detail and a deck's study page. Counts follow the replica: every committed sync
 * write ([syncChanges], the sync status) and a manual refresh reload what is shown.
 */
class DecksViewModel(
    private val source: DeckSource,
    syncChanges: Flow<Any?>?,
    io: CoroutineDispatcher = Dispatchers.IO,
) : StateViewModel<DecksUiState>(DecksUiState(), io), DecksIntents {
    private var loading: Job? = null

    init {
        reload()
        syncChanges?.let { f ->
            viewModelScope.launch { f.distinctUntilChanged().drop(1).collect { reload() } }
        }
    }

    private fun reload() {
        val s = current
        loading?.cancel()
        loading = launchIo {
            when (s.mode) {
                DecksMode.LIST -> {
                    val decks = source.decks()
                    update { it.copy(decks = decks) }
                }
                DecksMode.DETAIL, DecksMode.STUDY -> {
                    val id = s.selectedId.orEmpty()
                    val deck = source.deck(id)
                    update { if (it.selectedId == id) it.copy(deck = deck) else it }
                    val words = source.words(id, s.limit, 0)
                    update { if (it.selectedId == id && it.limit == s.limit) it.copy(words = words) else it }
                }
            }
        }
    }

    override fun refresh() = reload()

    override fun open(deckId: String) {
        update { it.copy(mode = DecksMode.DETAIL, selectedId = deckId, deck = null, words = null, limit = PAGE) }
        reload()
    }

    override fun back() {
        update { it.copy(mode = DecksMode.LIST, decks = null) }
        reload()
    }

    override fun study() = update { it.copy(mode = DecksMode.STUDY, studyRun = it.studyRun + 1) }

    override fun endStudy() {
        update { it.copy(mode = DecksMode.DETAIL, deck = null, words = null, limit = PAGE) }
        reload()
    }

    override fun more() {
        update { it.copy(limit = it.limit + PAGE) }
        reload()
    }

    override fun sessionEnded() = reload()

    companion object {
        const val PAGE: Int = 50
    }
}
