package kotobaya.android.media.browse

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotobaya.android.vm.StateViewModel

/** Everything the Media tab reads, plus sentence playback. */
interface MediaBrowsePort {
    /** The media list from the replica (blocking). */
    fun list(): List<MediaRow>

    suspend fun coverage(mediaId: String): Loaded<CoverageView>

    suspend fun sentences(mediaId: String): Loaded<List<SentenceView>>

    /** Plays a sentence clip (downloads it first when not cached). */
    suspend fun play(hash: String)

    /** Stops playback and frees the player. */
    fun release()
}

/** [MediaBrowsePort] over the replica list, [MediaBrowseRepository] and a [SentencePlayer]. */
class AppMediaBrowsePort(
    private val source: MediaListSource,
    private val repo: MediaBrowseRepository,
    private val player: SentencePlayer,
) : MediaBrowsePort {
    override fun list() = source.list()

    override suspend fun coverage(mediaId: String) = repo.coverage(mediaId)

    override suspend fun sentences(mediaId: String) = repo.sentences(mediaId)

    override suspend fun play(hash: String) = player.play(hash)

    override fun release() = player.release()
}

data class MediaBrowseUiState(
    /** Null while loading. */
    val rows: List<MediaRow>? = null,
    /** The media whose detail shows, or null for the list. */
    val selected: MediaRow? = null,
    val coverage: Loaded<CoverageView>? = null,
    val sentences: Loaded<List<SentenceView>>? = null,
)

/** What the Media tab can ask for (no-ops by default, for previews). */
interface MediaBrowseIntents {
    fun open(row: MediaRow) {}
    fun back() {}
    fun play(hash: String) {}
}

/** The Media tab: the media list, and a media's coverage and i+1 sentences. */
class MediaBrowseViewModel(
    private val port: MediaBrowsePort,
    io: CoroutineDispatcher = Dispatchers.IO,
) : StateViewModel<MediaBrowseUiState>(MediaBrowseUiState(), io), MediaBrowseIntents {
    private var detailJob: Job? = null

    init {
        launchIo {
            val rows = port.list()
            update { it.copy(rows = rows) }
        }
    }

    override fun open(row: MediaRow) {
        update { it.copy(selected = row, coverage = null, sentences = null) }
        detailJob?.cancel()
        detailJob = launchIo {
            val coverage = port.coverage(row.id)
            update { it.copy(coverage = coverage) }
            val sentences = port.sentences(row.id)
            update { it.copy(sentences = sentences) }
        }
    }

    override fun back() {
        detailJob?.cancel()
        port.release()
        update { it.copy(selected = null) }
    }

    override fun play(hash: String) {
        launchIo { port.play(hash) }
    }

    override fun onCleared() = port.release()
}
