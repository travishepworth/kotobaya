package kotobaya.android.demo

import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotobaya.android.decks.DeckSource
import kotobaya.android.decks.DeckSummary
import kotobaya.android.decks.DeckWord
import kotobaya.android.grammar.GrammarCapturePort
import kotobaya.android.grammar.GrammarPointView
import kotobaya.android.grammar.RuleTestView
import kotobaya.android.media.browse.CoverageView
import kotobaya.android.media.browse.Loaded
import kotobaya.android.media.browse.MediaBrowsePort
import kotobaya.android.media.browse.MediaRow
import kotobaya.android.media.browse.SentenceView
import kotobaya.android.media.browse.parseCoverage
import kotobaya.android.media.browse.parseSentences
import kotobaya.android.net.ApiError
import kotobaya.android.pairing.DevicesPort
import kotobaya.android.pairing.PairResult
import kotobaya.android.pairing.PairingTarget
import kotobaya.android.queue.QueueCounts
import kotobaya.android.review.DictionaryLookup
import kotobaya.android.review.QueueSource
import kotobaya.android.review.ReviewBackend
import kotobaya.android.review.ReviewPrefs
import kotobaya.android.search.CardHit
import kotobaya.android.search.CardSearchOutcome
import kotobaya.android.search.SearchPort
import kotobaya.android.search.WordHit
import kotobaya.android.search.WordPage
import kotobaya.android.search.WordSearchOutcome
import kotobaya.android.struggle.ConfusableView
import kotobaya.android.struggle.DrillItem
import kotobaya.android.struggle.HotKanjiView
import kotobaya.android.struggle.HotWordView
import kotobaya.android.struggle.LeechView
import kotobaya.android.struggle.SentenceItem
import kotobaya.android.struggle.StruggleSource
import kotobaya.android.struggle.parseConfusables
import kotobaya.android.struggle.parseHotKanji
import kotobaya.android.struggle.parseHotWords
import kotobaya.android.struggle.parseSentenceItems
import kotobaya.android.sync.SyncFlowPort
import kotobaya.android.sync.SyncStatusView
import kotobaya.android.word.CardView
import kotobaya.android.word.NoteLoad
import kotobaya.android.word.NoteView
import kotobaya.android.word.SenseView
import kotobaya.android.word.WordDetail
import kotobaya.android.word.WordLoad
import kotobaya.contracts.Uuid
import kotobaya.contracts.queue.QueueItem
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.ReviewQueue
import kotobaya.contracts.render.DictionaryData
import kotobaya.sync.client.apply.DeviceOpApplier
import kotobaya.sync.client.apply.InMemoryLearningTables
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.protocol.SyncPhase
import kotobaya.sync.client.protocol.SyncStatus
import kotobaya.sync.client.rejected.RejectedActionResult
import kotobaya.sync.client.rejected.RejectedEntry
import kotobaya.sync.client.rejected.RejectedOpsService
import kotobaya.sync.client.remint.RemintOutcome
import kotobaya.sync.client.remint.RemintPreview
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.InMemoryDeviceStore
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.UuidV7Source
import org.json.JSONArray
import org.json.JSONObject

/*
 * UI-FOUND-APP: every screen's port over the demo dataset. Reads come from [DemoData]; learning writes (review
 * answers, toolbar actions, rejected-op actions) go to an in-memory device store seeded from it ([DemoStore]), so
 * demo mode never touches the real database or the server.
 */

private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

private fun items(list: List<JSONObject>): JSONObject = JSONObject().put("items", JSONArray(list))

/** What a demo note is called: its expression / sentence / kanji field, else its dictionary headword. */
internal fun DemoData.titleOf(note: JSONObject): String {
    val f = note.optJSONObject("fields")
    val own = listOf("expression", "sentence", "pattern", "literal", "word_a", "front").firstNotNullOfOrNull { f?.str(it) }
    return own ?: note.str("kanji_literal")
        ?: note.optLong("entry_seq").takeIf { it > 0 }?.let { entry(it)?.str("headword") }
        ?: note.optString("id")
}

private fun JSONObject.firstGlosses(): List<String> =
    optJSONArray("senses").objects().firstOrNull()?.optJSONArray("gloss").objects().map { it.optString("text") }

private fun loaded(json: JSONObject?): Loaded.Data<JSONObject>? = json?.let { Loaded.Data(it, DemoData.NOW_MS, fromCache = false) }

/** The in-memory device store of demo mode, seeded with the scenario's note types, notes, cards and decks. */
class DemoStore(val data: DemoData) {
    val store = InMemoryDeviceStore(InMemoryLearningTables.seeded()) { it.copy() }
    private val clock = Clock { DemoData.NOW_MS }
    private val ids = UuidV7Source(Random(DEMO_SEED))
    val minter = OpMinter(store, DeviceOpApplier<InMemoryLearningTables>(), clock, ids)
    val rejected = RejectedOpsService(store, minter, clock, ids)

    init {
        store.transaction { tx ->
            tx.syncState.put(SyncStateKeys.DEVICE_ID, data.devices.firstOrNull()?.optString("id") ?: DEMO_DEVICE)
            val t = tx.learning
            data.noteTypes.filter { !it.optBoolean("builtin") }.forEach { t.put(LearningTable.NOTE_TYPE, it.toRow()) }
            for (note in data.notes) {
                t.put(LearningTable.NOTE, note.toRow("cards"))
                note.optJSONArray("cards").objects().forEach { t.put(LearningTable.CARD, it.toRow()) }
            }
            data.decks.forEach { t.put(LearningTable.DECK, it.toRow()) }
        }
    }

    /** Cards due by the end of the demo study day, oldest first, then new cards. */
    fun queue(deckId: String?): ReviewQueue {
        val inDeck: Set<Long>? = deckId?.let { id -> data.deckItems(id).map { it.optLong("entry_seq") }.toSet() }
        val notes = data.notes.associateBy { it.optString("id") }
        val cards = store.learning().all(LearningTable.CARD).mapNotNull { c ->
            val id = (c["id"] as? kotobaya.contracts.sync.JsonString)?.value ?: return@mapNotNull null
            val note = notes[(c["note_id"] as? kotobaya.contracts.sync.JsonString)?.value] ?: return@mapNotNull null
            if (inDeck != null && note.optLong("entry_seq") !in inDeck) return@mapNotNull null
            if ((c["suspended"] as? kotobaya.contracts.sync.JsonBool)?.value == true) return@mapNotNull null
            val state = (c["state"] as? kotobaya.contracts.sync.JsonString)?.value ?: "new"
            val due = (c["due_at"] as? kotobaya.contracts.sync.JsonNumber)?.value?.toLong()
            when {
                state == "new" -> Triple(id, QueueItemKind.NEW, Long.MAX_VALUE)
                due != null && due < DemoData.DAY_END_MS ->
                    Triple(id, if (state == "review") QueueItemKind.REVIEW else QueueItemKind.LEARNING, due)
                else -> null
            }
        }.sortedBy { it.third }
        return ReviewQueue(cards.map { QueueItem(it.first, it.second, deckId) }, emptyList(), null)
    }

    fun dictionary(seq: Long): DictionaryData? = data.entry(seq)?.let { e ->
        DictionaryData(
            expression = e.optString("headword"),
            reading = e.optString("headword_reading"),
            furigana = emptyList(),
            glosses = e.firstGlosses(),
            pitchDrops = e.optJSONArray("pitch").objects().firstOrNull()?.optJSONArray("positions")
                ?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty(),
        )
    }

    /** The review screen's backend over this store (answers stay in memory). */
    fun reviewBackend(): ReviewBackend<InMemoryLearningTables> = ReviewBackend(
        store = store,
        minter = minter,
        queue = object : QueueSource {
            override fun load(newShownThisSession: Int) = queue(null)

            override fun forDeck(deckId: String) = QueueSource { queue(deckId) }
        },
        prefs = { ReviewPrefs() },
        dictionary = DictionaryLookup(::dictionary),
        clock = { DemoData.NOW_MS },
        nextStudyDayStart = { DemoData.DAY_END_MS },
    )

    private companion object {
        const val DEMO_DEVICE = "0193a000-0003-7000-8000-000000000003"
        const val DEMO_SEED = 17
    }
}

class DemoSearchPort(private val data: DemoData) : SearchPort {
    private fun matches(q: String, vararg texts: String?) = texts.any { it != null && it.contains(q, ignoreCase = true) }

    override suspend fun words(q: String, after: String?): WordSearchOutcome {
        if (q.isBlank()) return WordSearchOutcome.Page(WordPage(emptyList(), null))
        val all = data.entries.filter { e -> matches(q.trim(), e.str("headword"), e.str("headword_reading"), *e.firstGlosses().toTypedArray()) }
        val from = after?.toIntOrNull() ?: 0
        val page = all.drop(from).take(PAGE).map { e ->
            WordHit(e.optLong("seq"), e.optString("headword"), e.optString("headword_reading"), e.firstGlosses().joinToString("; "), e.str("status") ?: "new")
        }
        return WordSearchOutcome.Page(WordPage(page, (from + PAGE).takeIf { it < all.size }?.toString()))
    }

    override suspend fun cards(q: String): CardSearchOutcome {
        val hits = data.notes.filter { q.isBlank() || matches(q.trim(), data.titleOf(it)) }.flatMap { note ->
            note.optJSONArray("cards").objects().map { c ->
                CardHit(
                    c.optString("id"), note.optString("id"), c.optString("kind"), c.optString("state"), c.optBoolean("suspended"),
                    note.optLong("entry_seq").takeIf { it > 0 }, data.titleOf(note),
                )
            }
        }
        return CardSearchOutcome.Results(hits)
    }

    private fun noteView(note: JSONObject) = NoteView(
        note.optString("id"),
        data.titleOf(note),
        note.optJSONArray("cards").objects().map { CardView(it.optString("id"), it.optString("kind"), it.optString("state"), it.optBoolean("suspended")) },
    )

    override suspend fun word(seq: Long): WordLoad {
        val e = data.entry(seq) ?: return WordLoad.NotFound
        return WordLoad.Found(
            WordDetail(
                seq = seq,
                headword = e.optString("headword"),
                reading = e.optString("headword_reading"),
                kanjiForms = e.optJSONArray("kanji_forms").objects().map { it.optString("text") },
                readings = e.optJSONArray("readings").objects().map { it.optString("text") },
                senses = e.optJSONArray("senses").objects().map { s ->
                    SenseView(s.optJSONArray("pos").strings(), s.optJSONArray("gloss").objects().map { it.optString("text") })
                },
                kanji = e.optJSONArray("kanji").strings(),
                status = e.str("status") ?: "new",
                retired = e.optBoolean("retired"),
                notes = data.notes.filter { it.optLong("entry_seq") == seq }.map(::noteView),
            ),
        )
    }

    override suspend fun note(noteId: String): NoteLoad {
        val n = data.notes.firstOrNull { it.optString("id") == noteId } ?: return NoteLoad.Unavailable
        return NoteLoad.Found(noteView(n), n.optString("mnemonic"), n.optJSONArray("tags").strings())
    }

    private companion object {
        const val PAGE = 50
    }
}

class DemoDeckSource(private val data: DemoData) : DeckSource {
    private fun summary(d: JSONObject): DeckSummary {
        val seqs = data.deckItems(d.optString("id")).map { it.optLong("entry_seq") }.toSet()
        val cards = data.notes.filter { it.optLong("entry_seq") in seqs }.flatMap { it.optJSONArray("cards").objects() }
        val due = cards.count { c ->
            c.optString("state") == "review" && (c.str("due_at")?.let(::instantMs) ?: Long.MAX_VALUE) < DemoData.DAY_END_MS
        }
        return DeckSummary(
            id = d.optString("id"),
            name = d.optString("name"),
            type = d.optString("type"),
            enabled = d.optBoolean("enabled", true),
            query = d.str("query"),
            counts = QueueCounts(
                due = due,
                new = cards.count { it.optString("state") == "new" },
                learning = cards.count { it.optString("state") in setOf("learning", "relearning") },
            ),
            totalCards = d.optInt("item_count"),
        )
    }

    override fun decks(): List<DeckSummary> = data.decks.map(::summary)

    override fun deck(id: String): DeckSummary? = data.decks.firstOrNull { it.optString("id") == id }?.let(::summary)

    override fun words(deckId: String, limit: Int, offset: Int): List<DeckWord> =
        data.deckItems(deckId).drop(offset).take(limit).map { item ->
            val note = data.notes.firstOrNull { it.optLong("entry_seq") == item.optLong("entry_seq") && item.has("entry_seq") }
            DeckWord(item.optString("item_key"), note?.let { data.titleOf(it) } ?: item.optString("item_key"))
        }
}

class DemoMediaBrowsePort(private val data: DemoData) : MediaBrowsePort {
    override fun list(): List<MediaRow> = data.media.map { MediaRow(it.optString("id"), it.optString("title"), it.optString("kind"), it.optString("parse_status")) }

    override suspend fun coverage(mediaId: String): Loaded<CoverageView> =
        loaded(data.coverage(mediaId))?.let { Loaded.Data(parseCoverage(it.value), it.updatedAtMs, it.fromCache) } ?: Loaded.NeedsConnection

    override suspend fun sentences(mediaId: String): Loaded<List<SentenceView>> =
        Loaded.Data(parseSentences(items(data.sentences.filter { it.optString("media_id") == mediaId })), DemoData.NOW_MS, fromCache = false)

    override suspend fun play(hash: String) = Unit

    override fun release() = Unit
}

class DemoStruggleSource(private val data: DemoData) : StruggleSource {
    private fun <T> now(value: T): Loaded<T> = Loaded.Data(value, DemoData.NOW_MS, fromCache = false)

    override suspend fun hotWords(): Loaded<List<HotWordView>> = now(parseHotWords(items(data.hotWords)))

    override suspend fun hotKanji(): Loaded<List<HotKanjiView>> = now(parseHotKanji(items(data.hotKanji)))

    override suspend fun leeches(cursor: String?): Loaded<Pair<List<LeechView>, String?>> = now(
        data.notes.flatMap { n -> n.optJSONArray("cards").objects().map { n to it } }
            .filter { (_, c) -> c.optBoolean("leech") || c.optInt("lapses") >= LEECH_LAPSES }
            .map { (n, c) -> LeechView(c.optString("id"), n.optString("id"), data.titleOf(n), c.optInt("lapses"), c.optBoolean("suspended")) } to null,
    )

    override suspend fun confusables(): Loaded<List<ConfusableView>> = now(parseConfusables(items(data.confusables)))

    override suspend fun wordConfusables(entrySeq: Long): Loaded<List<ConfusableView>> {
        val noteIds = data.notes.filter { it.optLong("entry_seq") == entrySeq }.map { it.optString("id") }.toSet()
        return now(parseConfusables(items(data.confusables)).filter { it.noteAId in noteIds || it.noteBId in noteIds })
    }

    override suspend fun sentences(entrySeq: Long): Loaded<List<SentenceItem>> = now(
        parseSentenceItems(
            items(data.sentences.filter { s -> s.optJSONArray("tokens").objects().any { it.optLong("seq") == entrySeq } }),
        ),
    )

    override suspend fun drill(source: String): Loaded<List<DrillItem>> = now(
        parseHotWords(items(data.hotWords)).mapNotNull { w ->
            val card = data.notes.firstOrNull { it.optString("id") == w.noteId }?.optJSONArray("cards").objects().firstOrNull()
                ?: return@mapNotNull null
            DrillItem(card.optString("id"), w.headword, listOf(w.reading, w.glosses.take(3).joinToString("; ")).filter { it.isNotEmpty() }.joinToString("\n"))
        },
    )

    override suspend fun setConfusable(c: ConfusableView, status: String) = Result.success(Unit)

    override suspend fun saveMnemonic(noteId: String, mnemonic: String) = Result.success(Unit)

    override suspend fun makeSentenceCard(s: SentenceItem) = Result.success(Unit)

    override suspend fun answerDrill(cardId: String, rating: Int) = Result.success(Unit)

    private companion object {
        const val LEECH_LAPSES = 8
    }
}

/** A paired, idle, recently synced device; the rejected-ops actions run on the demo store. */
class DemoSyncFlowPort(private val demo: DemoStore) : SyncFlowPort {
    override val status: StateFlow<SyncStatusView?> = MutableStateFlow(
        SyncStatusView(
            status = SyncStatus(SyncPhase.Idle, 0, 0, 0, DemoData.NOW_MS - 5 * 60_000L, null, false, false),
            paired = true,
            staleOverTwoDays = false,
            upgradeRequired = null,
            consecutiveFailures = 0,
        ),
    )

    override fun reload(): Pair<List<RejectedEntry>, List<Uuid>> = demo.rejected.list() to emptyList()

    override suspend fun syncNow() = Unit

    override fun syncInBackground() = Unit

    override fun retry(opId: Uuid): RejectedActionResult = demo.rejected.retry(listOf(opId))

    override fun discard(opId: Uuid): RejectedActionResult = demo.rejected.discard(listOf(opId))

    override fun discardAll(): RejectedActionResult = demo.rejected.discardAll()

    override fun previewRemint(): RemintPreview = demo.rejected.previewRemint()

    override fun remint(preview: RemintPreview): RemintOutcome = demo.rejected.remint(preview)
}

class DemoDevicesPort(private val data: DemoData) : DevicesPort {
    override fun isPaired() = true

    override fun deviceId(): String? = data.devices.firstOrNull()?.optString("id")

    override fun serverBaseUrl() = "https://kotobaya.example"

    override suspend fun pair(target: PairingTarget): PairResult = PairResult.Failed(ApiError.Offline)

    override fun unpair() = Unit
}

/** The capture panel offline: searches find nothing, writes say "needs a connection". */
object DemoGrammarCapturePort : GrammarCapturePort {
    override suspend fun search(q: String): List<GrammarPointView> = emptyList()

    override suspend fun createPoint(id: String, title: String, pattern: String, rule: String) = throw ApiError.Offline

    override suspend fun testRule(pointId: String, rule: String): RuleTestView = throw ApiError.Offline

    override suspend fun putSpan(pointId: String, sentenceId: String, start: Int, end: Int, mediaId: String?) = throw ApiError.Offline

    override suspend fun appendRule(point: GrammarPointView, rule: String) = throw ApiError.Offline
}
