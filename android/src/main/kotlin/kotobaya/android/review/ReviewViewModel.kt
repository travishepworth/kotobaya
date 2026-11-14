package kotobaya.android.review

import android.media.MediaPlayer
import androidx.annotation.StringRes
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotobaya.android.R
import kotobaya.android.review.actions.ActionResult
import kotobaya.android.review.actions.CardActionState
import kotobaya.android.review.actions.EditableNote
import kotobaya.android.review.actions.ReviewActions
import kotobaya.android.review.actions.newActions
import kotobaya.android.vm.StateViewModel
import kotobaya.contracts.fsrs.Button
import kotobaya.contracts.render.CardSide
import kotobaya.sync.client.apply.LearningTables

/** What the review toolbar's overflow menu and its one-line result show. */
data class ReviewActionsUiState(
    /** The card's suspend / bury / flag / word-status state, read when the menu opens; null until then. */
    val card: CardActionState? = null,
    /** [card] is buried until a time still in the future (the menu offers Unbury). */
    val buried: Boolean = false,
    @StringRes val message: Int? = null,
    val undoable: Boolean = false,
    /** Cards whose front an edit left empty (the warning line). */
    val emptied: Int = 0,
    /** The note in the edit sheet, or null when the sheet is closed. */
    val editing: EditableNote? = null,
)

/** The review screen's state. */
data class ReviewUiState(
    /** False when the sync store is not wired (the "not ready" message). */
    val available: Boolean = true,
    val review: ReviewState = ReviewState.Loading,
    val meta: ReviewMeta = ReviewMeta(),
    val prefs: ReviewPrefs = ReviewPrefs(),
    /** The 'Sync now' button of the sync prompt is offered. */
    val canSync: Boolean = false,
    /** The last 'Play audio' found no cached blob (reset when the card or side changes). */
    val audioMissing: Boolean = false,
    val actions: ReviewActionsUiState = ReviewActionsUiState(),
) {
    /** Enough ops wait in the outbox to prompt a sync. */
    val syncPrompt: Boolean get() = meta.pendingOps >= ReviewSession.SYNC_PROMPT_AT
}

/** The toolbar actions that need no argument besides the card. */
enum class ReviewAction { BURY, SUSPEND, NEVER_FORGET, BLACKLIST }

/** What the review screen can ask for (no-ops by default, for previews). */
interface ReviewIntents {
    /** The file of a cached blob for the card WebView's media requests (called off the main thread). */
    fun mediaFile(sha256: String): File? = null
    fun flip() {}
    fun retry() {}
    fun answer(token: Long, button: Button) {}
    fun undo() {}
    fun toggleFurigana() {}
    fun syncNow() {}
    fun playAudio(sha256: String) {}
    fun openActions(cardId: String) {}
    fun perform(cardId: String, action: ReviewAction) {}
    fun flag(cardId: String, colour: Int) {}
    fun undoAction(cardId: String) {}
    fun edit(cardId: String) {}
    fun dismissEdit() {}
    fun saveEdit(cardId: String, edited: Map<String, String>) {}
}

/** Plays one cached blob at a time; false when the blob is not on the device (the no-audio state). */
internal class AudioPlayer(private val blobs: BlobResolver) {
    private var player: MediaPlayer? = null

    @Synchronized
    fun play(sha256: String): Boolean {
        val file = blobs.file(sha256)?.takeIf { it.isFile } ?: return false
        blobs.played(sha256)
        release()
        player = MediaPlayer().apply {
            setDataSource(file.path)
            setOnCompletionListener { release() }
            prepare()
            start()
        }
        return true
    }

    @Synchronized
    fun release() {
        player?.release()
        player = null
    }
}

private fun <L : LearningTables> ReviewBackend<L>.actionsOf(): ReviewActions<L> = newActions()

/**
 * The review screen (the Review tab, and a deck's "Study" page with [deckId]). A [ReviewSession] runs while the
 * screen is shown: [enter] starts one (after the startup gate), [leave] ends it like the old composition did
 * (audio released; [onSessionEnd] when something was answered), so coming back starts a fresh session.
 * [backend] null means the sync store is not wired yet.
 */
class ReviewViewModel(
    private val backend: ReviewBackend<*>?,
    private val deckId: String? = null,
    private val onSessionEnd: () -> Unit = {},
    io: CoroutineDispatcher = Dispatchers.IO,
) : StateViewModel<ReviewUiState>(ReviewUiState(available = backend != null, canSync = backend?.syncNow != null), io), ReviewIntents {
    private val audio = backend?.let { AudioPlayer(it.blobs) }
    private val actions: ReviewActions<*>? = backend?.actionsOf()
    private var session: ReviewSession<*>? = null
    private var sessionJob: Job? = null

    /** The file of a cached blob (the card WebView's media requests); null when not on the device. */
    override fun mediaFile(sha256: String): File? = backend?.blobs?.file(sha256)

    /** A failed load shows [ReviewState.Failed] (Retry); a failed intent on a shown card shows the inline error. */
    override fun onFailure(e: Throwable) {
        if (session == null || current.review == ReviewState.Loading) {
            sessionJob?.cancel()
            sessionJob = null
            session = null
            update { it.copy(review = ReviewState.Failed) }
        } else {
            setResult(R.string.review_error_action, false, 0)
        }
    }

    /** 'Retry' after [ReviewState.Failed]: starts a fresh session. */
    override fun retry() {
        leave()
        enter()
    }

    /** The screen is shown: starts a session unless one is running. */
    fun enter() {
        val b = backend ?: return
        if (sessionJob != null) return
        update { it.copy(review = ReviewState.Loading) }
        sessionJob = launchIo {
            b.awaitReady()
            val prefs = b.prefs()
            val s = b.newSession(deckId)
            session = s
            update { it.copy(prefs = prefs) }
            var shown: Pair<Long, CardSide>? = null
            launch {
                combine(s.state, s.meta) { st, m -> st to m }.collect { (st, m) ->
                    val showing = st as? ReviewState.Showing
                    val key = showing?.let { it.card.token to it.side }
                    val newCard = showing != null && showing.card.token != shown?.first
                    val changed = key != shown
                    shown = key
                    update { u ->
                        u.copy(
                            review = st,
                            meta = m,
                            audioMissing = if (changed) false else u.audioMissing,
                            actions = if (newCard) u.actions.copy(card = null, buried = false, editing = null) else u.actions,
                        )
                    }
                    // Autoplay the front's audio once per displayed card.
                    if (newCard && showing != null && prefs.autoplay) {
                        showing.card.front.audio.firstOrNull()?.let { runCatching { audio?.play(it) } }
                    }
                }
            }
            s.start()
        }
    }

    /** The screen went away: ends the session (a later [enter] starts a new one). */
    fun leave() {
        val s = session
        sessionJob?.cancel()
        sessionJob = null
        session = null
        audio?.release()
        if (s != null && s.meta.value.answered > 0) onSessionEnd()
    }

    override fun onCleared() {
        leave()
    }

    private fun withSession(block: (ReviewSession<*>) -> Unit) {
        val s = session ?: return
        launchIo { block(s) }
    }

    override fun flip() = withSession { it.flip() }

    override fun answer(token: Long, button: Button) = withSession { it.answer(token, button) }

    override fun undo() = withSession { it.undo() }

    override fun toggleFurigana() = withSession { it.toggleFurigana() }

    override fun syncNow() {
        backend?.syncNow?.invoke()
    }

    /** 'Play audio': plays [sha256] or shows the no-audio note. */
    override fun playAudio(sha256: String) {
        val a = audio ?: return
        launchIo {
            val played = runCatching { a.play(sha256) }.getOrDefault(false)
            update { it.copy(audioMissing = !played) }
        }
    }

    // --- toolbar actions (T304c) ---

    private fun readActionState(cardId: String) {
        val a = actions ?: return
        val b = backend ?: return
        val s = a.stateOf(cardId)
        val buried = s?.buriedUntil?.let { it > b.clock() } ?: false
        update { it.copy(actions = it.actions.copy(card = s, buried = buried)) }
    }

    /** The overflow menu opens: re-reads the card's state. */
    override fun openActions(cardId: String) {
        launchIo { readActionState(cardId) }
    }

    override fun perform(cardId: String, action: ReviewAction) {
        val a = actions ?: return
        perform(cardId, requeue = true) {
            when (action) {
                ReviewAction.BURY -> a.bury(cardId)
                ReviewAction.SUSPEND -> a.suspend(cardId)
                ReviewAction.NEVER_FORGET -> a.neverForget(cardId)
                ReviewAction.BLACKLIST -> a.blacklist(cardId)
            }
        }
    }

    override fun flag(cardId: String, colour: Int) {
        val a = actions ?: return
        perform(cardId, requeue = false) { a.flag(cardId, colour) }
    }

    private fun perform(cardId: String, requeue: Boolean, block: () -> ActionResult) {
        val s = session
        launchIo {
            when (val r = block()) {
                is ActionResult.Done -> {
                    if (requeue) {
                        // the card was buried/suspended: if the next card cannot be loaded, do not leave it on screen
                        try {
                            s?.start()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            update { it.copy(review = ReviewState.Failed) }
                            throw e
                        }
                    } else {
                        s?.refreshCurrent()
                    }
                    setResult(R.string.review_action_done, r.undoable, r.warnings.size)
                }
                is ActionResult.Refused -> setResult(R.string.review_action_refused, false, 0)
                ActionResult.NotApplicable -> setResult(R.string.review_action_not_applicable, false, 0)
            }
            readActionState(cardId)
        }
    }

    private fun setResult(@StringRes message: Int?, undoable: Boolean, emptied: Int) =
        update { it.copy(actions = it.actions.copy(message = message, undoable = undoable, emptied = emptied)) }

    /** 'Undo' of the last toolbar action. */
    override fun undoAction(cardId: String) {
        val a = actions ?: return
        val s = session
        launchIo {
            if (a.undo()) s?.start()
            update { it.copy(actions = it.actions.copy(undoable = false, message = null)) }
            readActionState(cardId)
        }
    }

    /** Opens the edit sheet for the card's note. */
    override fun edit(cardId: String) {
        val a = actions ?: return
        launchIo {
            val note = a.editable(cardId)
            update { it.copy(actions = it.actions.copy(editing = note)) }
        }
    }

    override fun dismissEdit() = update { it.copy(actions = it.actions.copy(editing = null)) }

    /** Saves the edit sheet ([edited]: field key to new HTML). */
    override fun saveEdit(cardId: String, edited: Map<String, String>) {
        val a = actions ?: return
        val s = session
        dismissEdit()
        launchIo {
            when (val r = a.saveEdit(cardId, edited)) {
                is ActionResult.Done -> {
                    s?.refreshCurrent()
                    setResult(R.string.review_edit_saved, r.undoable, r.warnings.size)
                }
                else -> update { it.copy(actions = it.actions.copy(message = R.string.review_action_refused, undoable = false)) }
            }
        }
    }
}
