package kotobaya.android.search

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /** Which of [seqs] the user has a note for (and in which decks); empty when that is unknown (replica not ready). */
    suspend fun myCards(seqs: Collection<Long>): Map<Long, MyCardInfo> = emptyMap()
}

/** [SearchPort] over the app's [WordSearch], [CardSearchBackend] and [WordRepository]. */
class AppSearchPort(
    private val wordSearch: WordSearch,
    private val cardSearch: CardSearchBackend,
    private val details: WordRepository,
    private val mine: MyCardsLookup? = null,
) : SearchPort {
    override suspend fun words(q: String, after: String?) = wordSearch.search(q, after)

    override suspend fun cards(q: String) = cardSearch.search(q)

    override suspend fun word(seq: Long) = details.load(seq)

    override suspend fun note(noteId: String) = details.loadNote(noteId)

    override suspend fun myCards(seqs: Collection<Long>): Map<Long, MyCardInfo> =
        mine?.let { m -> withContext(Dispatchers.IO) { runCatching { m.lookup(seqs) }.getOrDefault(emptyMap()) } } ?: emptyMap()
}

/** A dictionary result; [mine] is non-null when the user has a note for the entry ("In my cards"). */
data class WordRow(val hit: WordHit, val mine: MyCardInfo? = null)

/**
 * True when [query] uses the card-query syntax (a `key:value` or comparison term such as `is:due`, `lapses>3`): such a
 * query is a card search, not a dictionary lookup.
 */
fun isCardQuery(query: String): Boolean = CARD_QUERY_TERM.containsMatchIn(query)

private val CARD_QUERY_TERM = Regex("(^|[\\s(\\-])[A-Za-z_]+(:|[<>=])")

/** The results area. */
sealed interface SearchResults {
    data object Idle : SearchResults
    data object Busy : SearchResults
    /** One merged list: dictionary entries (dictionary order, flagged when in my cards), then notes with no listed entry. */
    data class Merged(val words: List<WordRow>, val next: String?, val notes: List<CardHit>) : SearchResults
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
    val query: String = "",
    val results: SearchResults = SearchResults.Idle,
    val detail: SearchDetail? = null,
)

/** What the Search screen can ask for (no-ops by default, for previews). */
interface SearchIntents {
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

/**
 * The Search tab: one query, one list (ADR-026). A plain query lists dictionary entries, each flagged "In my cards" when
 * the user has a note for it, then the user's notes that match but are not among those entries. A query in the card-query
 * syntax lists cards only. Word / note details open inline.
 */
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
        if (q.isBlank()) return update { it.copy(results = SearchResults.Idle) }
        if (isCardQuery(q)) {
            update { it.copy(results = SearchResults.Busy) }
            launchIo {
                val results = when (val r = port.cards(q)) {
                    is CardSearchOutcome.Results -> SearchResults.Cards(r.hits, PAGE, r.fromServer)
                    is CardSearchOutcome.ParseError -> SearchResults.Invalid(r.errors)
                    is CardSearchOutcome.Failed -> SearchResults.Failed(r.offline)
                }
                update { it.copy(results = results) }
            }
            return
        }
        val prev = s.results as? SearchResults.Merged
        if (append && prev == null) return
        val after = if (append) prev?.next else null
        if (!append) update { it.copy(results = SearchResults.Busy) }
        launchIo {
            val wordsOutcome = port.words(q, after)
            val page = (wordsOutcome as? WordSearchOutcome.Page)?.page
            val mine = port.myCards(page?.items?.map { it.seq }.orEmpty())
            val rows = page?.items.orEmpty().map { WordRow(it, mine[it.seq]) }
            val results = if (append && prev != null) {
                if (page == null) prev else prev.copy(words = prev.words + rows, next = page.next)
            } else {
                // Notes come from the replica / server card search; when the dictionary search failed they still show.
                val cards = (port.cards(q) as? CardSearchOutcome.Results)?.hits.orEmpty()
                val listed = rows.mapTo(HashSet()) { it.hit.seq }
                val notes = cards.filter { it.entrySeq == null || it.entrySeq !in listed }.distinctBy { it.noteId }.take(PAGE)
                if (page == null && notes.isEmpty()) {
                    SearchResults.Failed((wordsOutcome as WordSearchOutcome.Failed).offline)
                } else {
                    SearchResults.Merged(rows, page?.next, notes)
                }
            }
            update { it.copy(results = results) }
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
