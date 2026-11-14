package kotobaya.android.search

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotobaya.android.vm.StateViewModel
import kotobaya.android.word.NoteLoad
import kotobaya.android.word.WordLoad
import kotobaya.android.word.WordRepository
import kotobaya.contracts.search.SearchError

/** Everything the Search tab reads: dictionary words, my cards, and the word / note details. */
interface SearchPort {
    suspend fun words(q: String, after: String?): WordSearchOutcome

    suspend fun cards(q: String): CardSearchOutcome

    suspend fun word(seq: Long): WordLoad

    suspend fun note(noteId: String): NoteLoad
}

/** [SearchPort] over the app's [WordSearch], [CardSearchBackend] and [WordRepository]. */
class AppSearchPort(
    private val wordSearch: WordSearch,
    private val cardSearch: CardSearchBackend,
    private val details: WordRepository,
) : SearchPort {
    override suspend fun words(q: String, after: String?) = wordSearch.search(q, after)

    override suspend fun cards(q: String) = cardSearch.search(q)

    override suspend fun word(seq: Long) = details.load(seq)

    override suspend fun note(noteId: String) = details.loadNote(noteId)
}

enum class SearchMode { WORDS, CARDS }

/** The results area. */
sealed interface SearchResults {
    data object Idle : SearchResults
    data object Busy : SearchResults
    data class Words(val items: List<WordHit>, val next: String?) : SearchResults
    data class Cards(val hits: List<CardHit>, val shown: Int, val fromServer: Boolean) : SearchResults
    data class Invalid(val errors: List<SearchError>) : SearchResults
    data class Failed(val offline: Boolean) : SearchResults
}

/** A detail page opened from a result; its load is null while loading. */
sealed interface SearchDetail {
    data class Word(val seq: Long, val load: WordLoad? = null) : SearchDetail
    data class Note(val noteId: String, val load: NoteLoad? = null) : SearchDetail
}

data class SearchUiState(
    val mode: SearchMode = SearchMode.WORDS,
    val query: String = "",
    val results: SearchResults = SearchResults.Idle,
    val detail: SearchDetail? = null,
)

/** What the Search screen can ask for (no-ops by default, for previews). */
interface SearchIntents {
    fun setMode(mode: SearchMode) {}
    fun setQuery(query: String) {}
    fun search() {}
    /** Next page of word results. */
    fun moreWords() {}
    /** Shows the next [SearchViewModel.PAGE] card results. */
    fun moreCards() {}
    fun openWord(seq: Long) {}
    fun openNote(noteId: String) {}
    fun closeDetail() {}
}

/** The Search tab: words (dictionary) and my cards (query language), with word / note details inline. */
class SearchViewModel(
    private val port: SearchPort,
    io: CoroutineDispatcher = Dispatchers.IO,
) : StateViewModel<SearchUiState>(SearchUiState(), io), SearchIntents {
    /** A search or detail load that threw ends the busy state instead of spinning forever. */
    override fun onFailure(e: Throwable) = update {
        when {
            it.results == SearchResults.Busy -> it.copy(results = SearchResults.Failed(offline = false))
            else -> it.copy(detail = null)
        }
    }

    override fun setMode(mode: SearchMode) = update { if (it.mode == mode) it else it.copy(mode = mode, results = SearchResults.Idle) }

    override fun setQuery(query: String) = update { it.copy(query = query) }

    override fun search() = run(append = false)

    override fun moreWords() = run(append = true)

    override fun moreCards() = update { s ->
        val r = s.results as? SearchResults.Cards ?: return@update s
        s.copy(results = r.copy(shown = r.shown + PAGE))
    }

    private fun run(append: Boolean) {
        val s = current
        val q = s.query
        if (s.mode == SearchMode.WORDS) {
            val prev = s.results as? SearchResults.Words
            val after = if (append) prev?.next else null
            if (!append) update { it.copy(results = SearchResults.Busy) }
            launchIo {
                val results = when (val r = port.words(q, after)) {
                    is WordSearchOutcome.Page ->
                        SearchResults.Words((if (append) prev?.items.orEmpty() else emptyList()) + r.page.items, r.page.next)
                    is WordSearchOutcome.Failed -> SearchResults.Failed(r.offline)
                }
                update { it.copy(results = results) }
            }
        } else {
            update { it.copy(results = SearchResults.Busy) }
            launchIo {
                val results = when (val r = port.cards(q)) {
                    is CardSearchOutcome.Results -> SearchResults.Cards(r.hits, PAGE, r.fromServer)
                    is CardSearchOutcome.ParseError -> SearchResults.Invalid(r.errors)
                    is CardSearchOutcome.Failed -> SearchResults.Failed(r.offline)
                }
                update { it.copy(results = results) }
            }
        }
    }

    override fun openWord(seq: Long) {
        update { it.copy(detail = SearchDetail.Word(seq)) }
        launchIo {
            val load = port.word(seq)
            update { s -> if ((s.detail as? SearchDetail.Word)?.seq == seq) s.copy(detail = SearchDetail.Word(seq, load)) else s }
        }
    }

    override fun openNote(noteId: String) {
        update { it.copy(detail = SearchDetail.Note(noteId)) }
        launchIo {
            val load = port.note(noteId)
            update { s -> if ((s.detail as? SearchDetail.Note)?.noteId == noteId) s.copy(detail = SearchDetail.Note(noteId, load)) else s }
        }
    }

    override fun closeDetail() = update { it.copy(detail = null) }

    companion object {
        const val PAGE: Int = 50
    }
}
