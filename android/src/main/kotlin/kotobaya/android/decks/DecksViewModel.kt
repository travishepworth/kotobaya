package kotobaya.android.decks

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotobaya.android.queue.QueueCounts
import kotobaya.android.sync.SyncStatusView
import kotobaya.android.vm.StateViewModel

/** Which page of the Decks tab shows. */
enum class DecksMode { LIST, DETAIL, STUDY }

/** The Decks tab's state. Null lists are still loading. */
data class DecksUiState(
    val mode: DecksMode = DecksMode.LIST,
    val decks: List<DeckSummary>? = null,
    /** Counts of the whole review queue, loaded with [decks] (AND-QUEUE: what Home's hero shows); null while loading. */
    val queue: QueueCounts? = null,
    val selectedId: String? = null,
    val deck: DeckSummary? = null,
    val words: List<DeckWord>? = null,
    /** Words shown in the detail (grows by [DecksViewModel.PAGE] with "More"). */
    val limit: Int = DecksViewModel.PAGE,
    /** Bumped per "Study" visit, so each visit gets a fresh review session. */
    val studyRun: Int = 0,
    /** The last load threw (logged as `KotobayaLoad`); the next sync change or refresh tries again. */
    val loadFailed: Boolean = false,
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

    /** AND-PERF: cancelling a reload does not stop its blocking queries; the next one waits for them (no overlap). */
    private val reloadLock = Mutex()

    init {
        reload()
        syncChanges?.let { f ->
            // AND-POSTSYNC: a pull publishes a status per committed chunk (and a progress update per chunk); each
            // used to start a full reload (one queue build per deck) and cancel the one running, which kept running
            // anyway. Progress alone does not change what is shown; the rest is conflated, one reload at a time,
            // at most one per SYNC_RELOAD_MS.
            viewModelScope.launch {
                f.map { if (it is SyncStatusView) it.copy(progress = null) else it }
                    .distinctUntilChanged().drop(1).conflate()
                    .collect {
                        reload().join()
                        delay(SYNC_RELOAD_MS)
                    }
            }
        }
    }

    override fun onFailure(e: Throwable) = update { it.copy(loadFailed = true) }

    private fun reload(): Job {
        val s = current
        loading?.cancel()
        return launchIo {
            reloadLock.withLock {
                ensureActive()
                when (s.mode) {
                    DecksMode.LIST -> {
                        val o = source.overview()
                        update { it.copy(decks = o.decks, queue = o.queue, loadFailed = false) }
                    }
                    DecksMode.DETAIL, DecksMode.STUDY -> {
                        val id = s.selectedId.orEmpty()
                        val deck = source.deck(id)
                        update { if (it.selectedId == id) it.copy(deck = deck) else it }
                        val words = source.words(id, s.limit, 0)
                        update { if (it.selectedId == id && it.limit == s.limit) it.copy(words = words, loadFailed = false) else it }
                    }
                }
            }
        }.also { loading = it }
    }

    override fun refresh() {
        reload()
    }

    override fun open(deckId: String) {
        update { it.copy(mode = DecksMode.DETAIL, selectedId = deckId, deck = null, words = null, limit = PAGE) }
        reload()
    }

    override fun back() {
        update { it.copy(mode = DecksMode.LIST, decks = null, queue = null) }
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

    override fun sessionEnded() {
        reload()
    }

    companion object {
        const val PAGE: Int = 50

        /** Minimum time between two reloads caused by sync changes. */
        const val SYNC_RELOAD_MS: Long = 1_500
    }
}
