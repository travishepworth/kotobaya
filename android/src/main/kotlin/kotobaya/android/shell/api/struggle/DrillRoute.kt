package kotobaya.android.shell.api.struggle

import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.media.WordAudioLookup
import kotobaya.android.review.CardRows
import kotobaya.android.shell.api.Fallthrough
import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.actions.enumValue
import kotobaya.android.shell.api.actions.long
import kotobaya.android.shell.api.actions.string
import kotobaya.android.shell.api.actions.unknownKeys
import kotobaya.android.shell.api.actions.uuidValue
import kotobaya.android.shell.api.browse.cardsMatching
import kotobaya.android.shell.api.get
import kotobaya.android.shell.api.identity.ShellIdentity
import kotobaya.android.shell.api.identity.obj
import kotobaya.android.shell.api.review.AnswerViews
import kotobaya.android.shell.api.review.QueueViews
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.drill.DrillCandidate
import kotobaya.drill.DrillSelector
import kotobaya.drill.NotePair
import kotobaya.drill.ScoredNote
import kotobaya.fsrs.FsrsScheduler
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.store.UuidV7Source

private val DRILL_KEYS = setOf("source", "entry_seq", "query", "card_ids", "mode", "limit")
private val SOURCES = setOf("hot_words", "leeches", "confusables", "query", "cards")

/** Session ids only: a UUIDv7 the client uses for bookkeeping. Never stored, never an op id. */
private val sessionIds = UuidV7Source(SecureRandom().asKotlinRandom())

private fun isNull(v: kotobaya.contracts.sync.JsonValue?): Boolean = v == null || v == JsonNull

/**
 * `createDrillSession` (SHELL.md 4.3): an ephemeral list of cards. Nothing is stored and no op is minted: the store is
 * only ever read ([SqlDelightDeviceStore.read] rejects a write). Struggle sources are picked by the core
 * [DrillSelector] (seeded by the session id) over the replica; the items are the `QueueItem` view of the queue
 * ([QueueViews]) with `review_type` = the session's mode and a null `preview`. Answers go through `answerReview` with
 * `mode = drill | cram`, which never changes a schedule.
 */
internal fun createDrillSession(call: LocalCall): ShellResponse {
    val body = call.bodyObject()
    val e = FieldErrors()
    e.unknownKeys(body, DRILL_KEYS)
    val source = e.enumValue(body, "source", SOURCES, required = true)
    val mode = e.enumValue(body, "mode", setOf("drill", "cram")) ?: "drill"
    val limit = body.members["limit"]?.let { e.long(it, "/limit", 1, 500)?.toInt() } ?: 20
    val entrySeq = body.members["entry_seq"]?.let { e.long(it, "/entry_seq", 1) }
    val query = e.string(body, "query", 1, 2000)
    val cardIds = body.members["card_ids"]?.let { n ->
        val items = (n as? JsonArray)?.items
        if (items == null || items.size < 1 || items.size > 500) {
            e.add("/card_ids", "invalid_value", "An array of 1 to 500 ids.")
            null
        } else {
            items.mapIndexedNotNull { i, x -> e.uuidValue(x, "/card_ids/$i") }.also {
                if (it.toSet().size != it.size) e.add("/card_ids", "invalid_value", "Ids must be unique.")
            }
        }
    }
    if (source == "query" && query == null && "query" !in body.members) e.add("/query", "required", "query is required for source query.")
    if (source == "cards" && cardIds == null && "card_ids" !in body.members) e.add("/card_ids", "required", "card_ids is required for source cards.")
    e.throwIfAny()

    val services = call.services
    val now = services.get<ShellIdentity>().nowMs()
    val dict = services.get<InstalledDictionary>()
    val store = services.get<SqlDelightDeviceStore>()
    val sessionId = sessionIds.newOpId(now)
    val seed = sessionId.fold(0L) { h, ch -> h * 31 + ch.code }

    // the query source is the card search language evaluated on the device (C-31: never forwarded)
    val queryIds: List<String>? = if (source == "query") {
        services.cardsMatching(checkNotNull(query), now) ?: return Fallthrough
    } else {
        null
    }

    val items = store.read { tx ->
        val t = tx.learning
        val threshold = AnswerViews.leechThreshold(t)
        val sources = DrillInputs(t)
        val ids = when (source) {
            "cards" -> sources.live(checkNotNull(cardIds)).take(limit)
            "leeches" -> DrillSelector.leeches(sources.leechCandidates(threshold), threshold, limit, seed)
            "hot_words" -> {
                val hot = HotSources(t, now).scoredNotes().map { ScoredNote(it.noteId, it.score) }
                DrillSelector.hotWords(hot, sources.candidatesOf(hot.map { it.noteId }), limit, seed)
            }
            "confusables" -> {
                val only = entrySeq?.let { sources.notesOfEntry(it) }
                val pairs = sources.confirmedPairs().filter { p -> only == null || p.noteA in only || p.noteB in only }
                val cards = sources.candidatesOf(pairs.flatMap { listOf(it.noteA, it.noteB) }.toSet())
                DrillSelector.confusables(pairs, cards, only?.singleOrNull(), limit, seed)
            }
            // `-lapses` order, ties by id
            else -> sources.live(checkNotNull(queryIds)).sortedWith(compareByDescending<String> { sources.lapses(it) }.thenBy { it }).take(limit)
        }
        QueueViews(t, dict, FsrsScheduler, threshold, wordAudio = runCatching { services.get<WordAudioLookup>() }.getOrNull()).items(ids.map { it to mode }, now)
    }
    return ShellResponse.json(obj("id" to JsonString(sessionId), "mode" to JsonString(mode), "items" to JsonArray(items)))
}

/** The inputs of the core [DrillSelector] over the replica. Read-only. */
private class DrillInputs(private val t: LearningTables) {
    private val notes = HashMap<String, Row?>()

    private fun note(id: String): Row? = notes.getOrPut(id) { CardRows.get(t, LearningTable.NOTE, id) }

    private fun noteLive(card: Row): Boolean = CardRows.str(card["note_id"])?.let { note(it) }?.let { isNull(it["deleted_at"]) } == true

    fun lapses(cardId: String): Double = (CardRows.get(t, LearningTable.CARD, cardId)?.get("lapses") as? JsonNumber)?.value ?: 0.0

    /** [ids] that are live cards of live notes, in the given order. */
    fun live(ids: List<String>): List<String> = ids.filter { id ->
        CardRows.get(t, LearningTable.CARD, id)?.let { CardRows.live(it) && noteLive(it) } == true
    }

    private fun candidate(c: Row) = DrillCandidate(
        CardRows.str(c["id"]).orEmpty(),
        CardRows.str(c["note_id"]).orEmpty(),
        (c["lapses"] as? JsonNumber)?.value?.toInt() ?: 0,
    )

    /** Live cards of live notes with `lapses >= threshold`. */
    fun leechCandidates(threshold: Int): List<DrillCandidate> =
        t.all(LearningTable.CARD).filter { CardRows.live(it) && ((it["lapses"] as? JsonNumber)?.value ?: 0.0) >= threshold && noteLive(it) }.map(::candidate)

    /** Live cards of the live notes in [noteIds]. */
    fun candidatesOf(noteIds: Collection<String>): List<DrillCandidate> =
        noteIds.distinct().flatMap { id -> t.where(LearningTable.CARD, "note_id", JsonString(id)) }
            .filter { CardRows.live(it) && noteLive(it) }.map(::candidate)

    /** Confirmed pairs of live notes, one per pair even when confirmed for several reasons. */
    fun confirmedPairs(): List<NotePair> =
        t.all(LearningTable.CONFUSABLE).filter { CardRows.str(it["status"]) == "confirmed" }
            .mapNotNull { r ->
                val a = CardRows.str(r["note_a_id"]) ?: return@mapNotNull null
                val b = CardRows.str(r["note_b_id"]) ?: return@mapNotNull null
                if (note(a)?.let { isNull(it["deleted_at"]) } == true && note(b)?.let { isNull(it["deleted_at"]) } == true) NotePair(a, b) else null
            }.distinct()

    /** Ids of the live notes of dictionary entry [seq]. */
    fun notesOfEntry(seq: Long): List<String> =
        t.where(LearningTable.NOTE, "entry_seq", JsonNumber(seq.toDouble())).filter { isNull(it["deleted_at"]) }.mapNotNull { CardRows.str(it["id"]) }
}
