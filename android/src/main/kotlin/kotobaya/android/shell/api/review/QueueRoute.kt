package kotobaya.android.shell.api.review

import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.media.AudioPrefetchTrigger
import kotobaya.android.media.WordAudioLookup
import kotobaya.android.queue.EntryMaterializer
import kotobaya.android.queue.EntryPlan
import kotobaya.android.queue.EntryRef
import kotobaya.android.queue.KanjiSource
import kotobaya.android.queue.LoadGate
import kotobaya.android.queue.LoadedQueue
import kotobaya.android.queue.LocalQueueLoader
import kotobaya.android.queue.QueueSettings
import kotobaya.android.queue.materializeAllowed
import kotobaya.android.queue.studyDayOf
import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.get
import kotobaya.android.shell.api.identity.ShellIdentity
import kotobaya.android.shell.api.identity.timestamp
import kotobaya.android.review.CardRows
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SyncCoordinator
import kotobaya.contracts.queue.LoopState
import kotobaya.contracts.queue.PendingEntry
import kotobaya.contracts.queue.QueueItem
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.db.app.AppDatabase
import kotobaya.fsrs.FsrsScheduler
import kotobaya.queue.DefaultQueueBuilder
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.store.bookkeeping

/** The three queue sections of `kinds` (openapi `getReviewQueue`). */
internal val ALL_KINDS: Set<String> = setOf("new", "learning", "review")

private val LOWER_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/** The validated parameters of one `getReviewQueue` request. */
internal data class QueueRequest(val limit: Int, val deckId: String?, val kinds: Set<String>, val keepGoing: Boolean)

/**
 * Parameters exactly as the server reads them: `limit` 1..200 (default 20, else `out_of_range`), `deck_id` a lowercase
 * UUID (`invalid_value`), `kinds` comma-separated from `new|learning|review` (`invalid_value` per unknown part),
 * `keep_going` the literal `true` / `false` (`invalid_value`); every error in one `422`, unknown names first.
 */
internal fun queueRequest(call: LocalCall): QueueRequest {
    val q = call.query("limit", "deck_id", "kinds", "keep_going")
    val e = FieldErrors()
    val limit = q.int("limit", 1..200, 20, e)
    val deckId = q.first("deck_id")?.let { v ->
        if (LOWER_UUID.matches(v)) v else null.also { e.add("/deck_id", "invalid_value", "Must be a lowercase UUID.") }
    }
    val kinds = q.first("kinds")?.split(',')?.let { parts ->
        parts.filter { it !in ALL_KINDS }.forEach { e.add("/kinds", "invalid_value", "Unknown kind $it.") }
        parts.toSet()
    } ?: ALL_KINDS
    val keepGoing = q.bool("keep_going", false, e)
    e.throwIfAny()
    return QueueRequest(limit, deckId, kinds, keepGoing)
}

/**
 * `getReviewQueue` (SHELL.md 4.3): a stateless view over [LocalQueueLoader.load] — the core builder decides every queue
 * rule; this handler only shapes the answer like the server's `ReviewApi.queue`.
 *
 * - ADR-037: the loop ([LoadedQueue.state], core's `DefaultReviewLoop`) decides items, `loop` and the legacy `counts`;
 *   `new_per_session` is ignored. Learn-ahead is 0 (the loader's constant, ADR-033).
 * - C-27: drawn deck entries are materialized by the loader's rounds through the app's [EntryMaterializer]
 *   (`NoteCreator.materialize`, ops minted as this device, idempotent in one store transaction) when
 *   [materializeAllowed] and `kinds` includes `new` (the server's rule for a caller that may review); otherwise they
 *   stay in `toMaterialize`, counted in `counts.new` and `to_materialize`.
 */
internal fun getReviewQueue(call: LocalCall): ShellResponse {
    val r = queueRequest(call)
    val services = call.services
    val store = services.get<SqlDelightDeviceStore>()
    if (r.deckId != null) {
        val live = store.read { tx -> tx.learning.get(LearningTable.DECK, listOf(JsonString(r.deckId)))?.let(CardRows::live) == true }
        if (!live) throw ProblemException(Problems.notFound("No live deck ${r.deckId}."))
    }
    val db = services.get<AppDatabase>()
    val dict = services.get<InstalledDictionary>()
    val now = services.get<ShellIdentity>().nowMs()
    val coordinator = services.get<SyncCoordinator>()
    val materializer = ShellEntryMaterializer(services.get<EntryMaterializer>()) {
        "new" in r.kinds && store.read { it.bookkeeping.lastSyncAt to it.bookkeeping.rebuildPending }.let { (lastSyncAt, rebuild) ->
            materializeAllowed(true, coordinator.running.value, coordinator.consecutiveFailures, lastSyncAt, rebuild)
        }
    }
    // ADR-042: the process-wide cache when the app provides one (the loader runs on every answer), else per request
    val kanji = runCatching { services.get<KanjiSource>() }.getOrNull() ?: KanjiSource(dict)
    val headwords = HashMap<Long, String?>() // per-request cache
    val loader = LocalQueueLoader(
        db = db,
        // one instant for the whole request, as the server's `nowMs`
        clock = { now },
        headwordOf = { seq -> headwords.getOrPut(seq) { runCatching { dict.entry(seq)?.headword }.getOrNull() } },
        // a gate of its own: a build of another caller (native screens, another request) is never served for this one
        gate = LoadGate(),
        materializer = materializer,
        kanji = kanji,
    )
    val loaded = loader.load(deckFilter = r.deckId, keepGoing = r.keepGoing, keepGoingNewBatch = r.limit)
    // ADR-047 / SCHEMA.md 6.6 "Device": 30 s after a build that may have materialized, one prefetch pass (never a call here)
    if ("new" in r.kinds) runCatching { services.get<AudioPrefetchTrigger>().afterQueueLoad() }
    val settings = QueueSettings.from(db.localQueueQueries.schedulerSettings().executeAsList().associate { it.key to it.value_ })
    return ShellResponse.json(render(r, now, loaded, settings, store, dict, kanji, runCatching { services.get<WordAudioLookup>() }.getOrNull()))
}

private fun render(
    r: QueueRequest,
    now: Long,
    loaded: LoadedQueue,
    settings: QueueSettings,
    store: SqlDelightDeviceStore,
    dict: InstalledDictionary,
    kanji: KanjiSource,
    wordAudio: WordAudioLookup?,
): JsonObject {
    val queue = loaded.queue
    val input = loaded.input
    val state = loaded.state
    val legacy = state.legacy
    fun inKinds(i: QueueItem) = i.kind.name.lowercase() in r.kinds
    val wanted = queue.items.filter(::inKinds).take(r.limit)
    // C-26: all kinds -> the loop's own signal (never under keep going); a kinds filter -> one keep-going build, only for an empty normal page
    val moreAvailable = if (r.kinds == ALL_KINDS) {
        !r.keepGoing && legacy.moreAvailable
    } else {
        !r.keepGoing && wanted.isEmpty() &&
            DefaultQueueBuilder.build(input.copy(limits = input.limits.copy(keepGoing = true))).let { kg ->
                kg.items.any(::inKinds) || ("new" in r.kinds && (kg.toMaterialize.isNotEmpty() || kg.kanjiToMaterialize.isNotEmpty()))
            }
    }
    val day = studyDayOf(now, settings.timezone, settings.dayRolloverHour)
    val items = store.read { tx ->
        QueueViews(tx.learning, dict, FsrsScheduler, settings.leechThreshold, kanji, wordAudio)
            .items(wanted.map { it.cardId to if (it.kind == QueueItemKind.REVIEW) "scheduled" else "learning" }, now)
    }
    val out = LinkedHashMap<String, JsonValue>()
    out["items"] = JsonArray(items)
    // ADR-037: the deprecated `counts` keep their frozen meaning, from the loop's legacy block
    out["counts"] = JsonObject(linkedMapOf("new" to n(legacy.new), "learning" to n(legacy.learning), "review" to n(legacy.review)))
    out["study_day"] = JsonString(day.date.toString())
    out["day_ends_at"] = JsonString(timestamp(day.day.endMs))
    out["generated_at"] = JsonString(timestamp(now))
    out["more_available"] = JsonBool(moreAvailable)
    out["to_materialize"] = n(if ("new" in r.kinds) queue.toMaterialize.size + queue.kanjiToMaterialize.size else 0)
    out["loop"] = loopJson(state)
    // ADR-033: absent when no learning card falls due later today
    state.nextDueAt?.let { out["next_learning_due_at"] = JsonString(timestamp(it)) }
    return JsonObject(out)
}

/** `ReviewQueue.loop`, field for field as the server's `ReviewApi.loopJson`. */
private fun loopJson(s: LoopState): JsonObject = JsonObject(
    linkedMapOf(
        "phase" to JsonString(s.phase.wire),
        "keep_going" to JsonBool(s.keepGoing),
        "ready" to JsonObject(linkedMapOf("learning" to n(s.readyLearning), "review" to n(s.readyReviews), "total" to n(s.readyLearning + s.readyReviews))),
        "later_today" to JsonObject(linkedMapOf("count" to n(s.laterToday), "next_due_at" to (s.nextDueAt?.let { JsonString(timestamp(it)) } ?: JsonNull))),
        "backlog" to JsonObject(linkedMapOf("reviews" to n(s.backlog), "beyond_limit" to n(s.backlogBeyondLimit))),
        "reviews" to JsonObject(linkedMapOf("today" to n(s.reviewsToday), "limit" to (s.reviewsLimit?.let { n(it) } ?: JsonNull))),
        "new" to JsonObject(linkedMapOf("today" to n(s.newToday), "goal" to n(s.newGoal), "available" to JsonBool(s.newAvailable))),
        "keep_going_available" to JsonBool(s.keepGoingAvailable),
        "until_stop" to n(s.untilStop),
        "until_stop_parts" to JsonObject(
            linkedMapOf(
                "learning" to n(s.untilStopParts.learning),
                "later" to n(s.untilStopParts.later),
                "review" to n(s.untilStopParts.review),
                "new" to n(s.untilStopParts.new),
            ),
        ),
    ),
)

private fun n(v: Int): JsonNumber = JsonNumber(v.toDouble())

/**
 * The app's [EntryMaterializer] (production: `NoteCreator`), creating notes only while [allowed]. Unlike the native
 * loader's wiring (which hides pending entries while materializing is not allowed), the entries are still planned and
 * drawn, so a fetch that may not materialize reports them in `counts.new` and `to_materialize` (the server's answer to
 * a caller that may not write).
 */
internal class ShellEntryMaterializer(private val notes: EntryMaterializer, private val allowed: () -> Boolean) : EntryMaterializer {
    override fun ready(): Boolean = notes.ready()

    override fun plan(entries: List<EntryRef>, burySiblings: Boolean): Map<EntryRef, EntryPlan> = notes.plan(entries, burySiblings)

    override fun kanjiKinds(literals: Collection<String>): Map<String, List<String>> = notes.kanjiKinds(literals)

    override fun materialize(kanji: List<String>, entries: List<PendingEntry>): Set<String> =
        if (allowed()) notes.materialize(kanji, entries) else emptySet()
}
