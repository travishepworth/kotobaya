package kotobaya.android.struggle

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotobaya.android.R
import kotobaya.android.media.browse.Loaded
import kotobaya.android.vm.StateViewModel

/** The pages of the struggle screen. */
sealed interface StrugglePage {
    data object Home : StrugglePage
    data class Word(val word: HotWordView) : StrugglePage
    data object Leeches : StrugglePage
    data object Confusables : StrugglePage
    data class Drill(val source: String) : StrugglePage
}

/** The Home page: hot words and kanji (null while loading). */
data class StruggleHomeState(
    val words: Loaded<List<HotWordView>>? = null,
    val kanji: Loaded<List<HotKanjiView>>? = null,
)

/** A hot word's page. */
data class StruggleWordState(
    val sentences: Loaded<List<SentenceItem>>? = null,
    val partners: Loaded<List<ConfusableView>>? = null,
    val mnemonic: String = "",
    @StringRes val message: Int? = null,
)

data class StruggleLeechesState(
    val rows: List<LeechView> = emptyList(),
    val cursor: String? = null,
    /** The last page's load (null while the first loads). */
    val load: Loaded<*>? = null,
)

data class StruggleConfusablesState(
    val pairs: Loaded<List<ConfusableView>>? = null,
    @StringRes val message: Int? = null,
)

data class StruggleDrillState(
    val items: Loaded<List<DrillItem>>? = null,
    val index: Int = 0,
    val revealed: Boolean = false,
    val failed: Boolean = false,
)

/** The struggle screen's state: the page shown and each page's data (reset whenever the page is opened). */
data class StruggleUiState(
    val page: StrugglePage = StrugglePage.Home,
    val home: StruggleHomeState = StruggleHomeState(),
    val word: StruggleWordState = StruggleWordState(),
    val leeches: StruggleLeechesState = StruggleLeechesState(),
    val confusables: StruggleConfusablesState = StruggleConfusablesState(),
    val drill: StruggleDrillState = StruggleDrillState(),
)

/** What the struggle screen can ask for (no-ops by default, for previews). */
interface StruggleIntents {
    fun open(page: StrugglePage) {}
    fun home() {}
    fun setMnemonic(text: String) {}
    fun saveMnemonic() {}
    fun makeSentenceCard(sentence: SentenceItem) {}
    fun moreLeeches() {}
    fun setConfusable(pair: ConfusableView, status: String) {}
    fun reveal() {}
    fun answerDrill(rating: Int) {}
}

/**
 * The struggle screen (hot words, leeches, confusables, drills; online with a last-good cache). [deepLinks] is the
 * hot-word widget's pending entry ([StruggleDeepLink]); it opens that word's page.
 */
class StruggleViewModel(
    private val source: StruggleSource,
    deepLinks: StateFlow<Long?>? = null,
    private val consumeDeepLink: () -> Unit = {},
    io: CoroutineDispatcher = Dispatchers.IO,
) : StateViewModel<StruggleUiState>(StruggleUiState(), io), StruggleIntents {
    private var pageJob: Job? = null

    init {
        open(StrugglePage.Home)
        deepLinks?.let { links ->
            viewModelScope.launch {
                links.collect { seq ->
                    if (seq == null) return@collect
                    consumeDeepLink()
                    launchIo {
                        val hot = (source.hotWords() as? Loaded.Data)?.value?.firstOrNull { it.entrySeq == seq }
                        if (hot != null) open(StrugglePage.Word(hot))
                    }
                }
            }
        }
    }

    override fun home() = open(StrugglePage.Home)

    override fun open(page: StrugglePage) {
        pageJob?.cancel()
        update { it.copy(page = page) }
        pageJob = when (page) {
            StrugglePage.Home -> {
                update { it.copy(home = StruggleHomeState()) }
                launchIo {
                    val words = source.hotWords()
                    update { it.copy(home = it.home.copy(words = words)) }
                    val kanji = source.hotKanji()
                    update { it.copy(home = it.home.copy(kanji = kanji)) }
                }
            }
            is StrugglePage.Word -> {
                update { it.copy(word = StruggleWordState(mnemonic = page.word.mnemonic)) }
                val seq = page.word.entrySeq
                if (seq == null) null else launchIo {
                    val sentences = source.sentences(seq)
                    update { it.copy(word = it.word.copy(sentences = sentences)) }
                    val partners = source.wordConfusables(seq)
                    update { it.copy(word = it.word.copy(partners = partners)) }
                }
            }
            StrugglePage.Leeches -> {
                update { it.copy(leeches = StruggleLeechesState()) }
                launchIo { loadLeeches(more = false) }
            }
            StrugglePage.Confusables -> {
                update { it.copy(confusables = StruggleConfusablesState()) }
                launchIo {
                    val pairs = source.confusables()
                    update { it.copy(confusables = it.confusables.copy(pairs = pairs)) }
                }
            }
            is StrugglePage.Drill -> {
                update { it.copy(drill = StruggleDrillState()) }
                launchIo {
                    val items = source.drill(page.source)
                    update { it.copy(drill = it.drill.copy(items = items)) }
                }
            }
        }
    }

    private suspend fun loadLeeches(more: Boolean) {
        val r = source.leeches(if (more) current.leeches.cursor else null)
        update { s ->
            val l = s.leeches
            if (r is Loaded.Data) {
                val merged = if (more) l.rows + r.value.first else r.value.first
                s.copy(leeches = l.copy(load = r, rows = merged.associateBy { it.cardId }.values.toList(), cursor = r.value.second))
            } else {
                s.copy(leeches = l.copy(load = r))
            }
        }
    }

    override fun moreLeeches() {
        launchIo { loadLeeches(more = true) }
    }

    /** A write that threw (the sources return Results; this is the storage layer failing) shows the write-failed line. */
    override fun onFailure(e: Throwable) = update {
        it.copy(
            word = it.word.copy(message = R.string.struggle_write_failed),
            confusables = it.confusables.copy(message = R.string.struggle_write_failed),
        )
    }

    override fun setMnemonic(text: String) = update { it.copy(word = it.word.copy(mnemonic = text)) }

    private fun reportWord(r: Result<Unit>, @StringRes ok: Int) =
        update { it.copy(word = it.word.copy(message = if (r.isSuccess) ok else R.string.struggle_write_failed)) }

    override fun saveMnemonic() {
        val page = current.page as? StrugglePage.Word ?: return
        val text = current.word.mnemonic
        launchIo { reportWord(source.saveMnemonic(page.word.noteId, text), R.string.struggle_mnemonic_saved) }
    }

    override fun makeSentenceCard(sentence: SentenceItem) {
        launchIo { reportWord(source.makeSentenceCard(sentence), R.string.struggle_card_created) }
    }

    override fun setConfusable(pair: ConfusableView, status: String) {
        launchIo {
            val r = source.setConfusable(pair, status)
            val message = if (r.isSuccess) R.string.struggle_pair_updated else R.string.struggle_write_failed
            update { it.copy(confusables = it.confusables.copy(message = message)) }
            if (r.isSuccess) {
                val pairs = source.confusables()
                update { it.copy(confusables = it.confusables.copy(pairs = pairs)) }
            }
        }
    }

    override fun reveal() = update { it.copy(drill = it.drill.copy(revealed = true)) }

    override fun answerDrill(rating: Int) {
        val d = current.drill
        val item = (d.items as? Loaded.Data)?.value?.getOrNull(d.index) ?: return
        launchIo {
            val failed = source.answerDrill(item.cardId, rating).isFailure
            update { it.copy(drill = it.drill.copy(failed = failed, revealed = false, index = it.drill.index + 1)) }
        }
    }
}
