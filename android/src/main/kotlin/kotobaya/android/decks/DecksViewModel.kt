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
    val filter: DeckFilter = DeckFilter.ALL,
    val sort: DeckSort = DeckSort.DECK_ORDER,
    /** Words per filter (one grouped query); empty while loading. */
    val counts: Map<DeckFilter, Int> = emptyMap(),
    /** Multi-select mode (long-press starts it); [selection] holds entry seqs. */
    val selecting: Boolean = false,
    val selection: Set<Long> = emptySet(),
    /** The word whose action sheet is open. */
    val actionWord: DeckWord? = null,
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
    /** AND-DECKCONTENT: download the selected deck's rejected contents again. */
    fun retryContent() {}
    /** A study session answered something (counts change). */
    fun sessionEnded() {}
    fun setFilter(filter: DeckFilter) {}
    fun setSort(sort: DeckSort) {}
    fun openActions(word: DeckWord) {}
    fun closeActions() {}
    /** [status] null = reset to automatic. */
    fun setStatus(word: DeckWord, status: String?) {}
    fun removeFromDeck(word: DeckWord) {}
    fun addToNotes(word: DeckWord) {}
    fun startSelecting(word: DeckWord) {}
    fun toggleSelected(word: DeckWord) {}
    fun selectAllVisible() {}
    fun endSelecting() {}
    fun setSelectionStatus(status: String?) {}
}

/**
 * The Decks tab (read-only): list, detail and a deck's study page. Counts follow the replica: every committed sync
 * write ([syncChanges], the sync status) and a manual refresh reload what is shown.
 */
class DecksViewModel(
    private val source: DeckSource,
    syncChanges: Flow<Any?>?,
    io: CoroutineDispatcher = Dispatchers.IO,
    /** Clears the deck's rejected archive and queues the content install (blocking; runs on [io]). */
    private val retryContent: (deckId: String) -> Unit = {},
    private val actions: DeckWordActions? = null,
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
                        val counts = source.statusCounts(id)
                        val words = source.words(id, s.limit, 0, s.filter, s.sort)
                        update {
                            if (it.selectedId == id && it.limit == s.limit && it.filter == s.filter && it.sort == s.sort) {
                                it.copy(words = words, counts = counts, loadFailed = false)
                            } else {
                                it
                            }
                        }
                    }
                }
            }
        }.also { loading = it }
    }

    override fun refresh() {
        reload()
    }

    override fun open(deckId: String) {
        update {
            it.copy(
                mode = DecksMode.DETAIL, selectedId = deckId, deck = null, words = null, limit = PAGE,
                filter = DeckFilter.ALL, sort = DeckSort.DECK_ORDER, counts = emptyMap(), selecting = false, selection = emptySet(), actionWord = null,
            )
        }
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

    override fun retryContent() {
        val id = current.selectedId ?: return
        launchIo {
            retryContent(id)
            reload()
        }
    }

    override fun sessionEnded() {
        reload()
    }

    override fun setFilter(filter: DeckFilter) {
        update { it.copy(filter = filter, words = null, limit = PAGE, selection = emptySet()) }
        reload()
    }

    override fun setSort(sort: DeckSort) {
        update { it.copy(sort = sort, words = null, limit = PAGE) }
        reload()
    }

    override fun openActions(word: DeckWord) = update { it.copy(actionWord = word) }

    override fun closeActions() = update { it.copy(actionWord = null) }

    /** Runs a write on IO, then reloads: the lists and counts follow the replica without a manual refresh. */
    private fun write(block: (DeckWordActions) -> Unit) {
        val a = actions ?: return
        launchIo {
            block(a)
            reload()
        }
    }

    override fun setStatus(word: DeckWord, status: String?) {
        val seq = word.entrySeq ?: return
        update { it.copy(actionWord = null) }
        write { it.setStatus(listOf(seq), status) }
    }

    override fun removeFromDeck(word: DeckWord) {
        val seq = word.entrySeq ?: return
        val deckId = current.selectedId ?: return
        update { it.copy(actionWord = null) }
        write { it.removeFromDeck(deckId, seq) }
    }

    override fun addToNotes(word: DeckWord) {
        update { it.copy(actionWord = null) }
        write { it.addToNotes(word.label, word.reading) }
    }

    override fun startSelecting(word: DeckWord) {
        val seq = word.entrySeq ?: return
        update { it.copy(selecting = true, selection = setOf(seq), actionWord = null) }
    }

    override fun toggleSelected(word: DeckWord) {
        val seq = word.entrySeq ?: return
        update { s ->
            val next = if (seq in s.selection) s.selection - seq else s.selection + seq
            s.copy(selection = next, selecting = next.isNotEmpty())
        }
    }

    override fun selectAllVisible() {
        val s = current
        val id = s.selectedId ?: return
        launchIo {
            val all = source.entrySeqs(id, s.filter).toSet()
            update { if (it.selectedId == id && it.filter == s.filter) it.copy(selection = all, selecting = all.isNotEmpty()) else it }
        }
    }

    override fun endSelecting() = update { it.copy(selecting = false, selection = emptySet()) }

    override fun setSelectionStatus(status: String?) {
        val seqs = current.selection
        if (seqs.isEmpty()) return
        update { it.copy(selecting = false, selection = emptySet()) }
        write { it.setStatus(seqs, status) }
    }

    companion object {
        const val PAGE: Int = 50

        /** Minimum time between two reloads caused by sync changes. */
        const val SYNC_RELOAD_MS: Long = 1_500
    }
}
