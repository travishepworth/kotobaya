package kotobaya.android.media

import kotobaya.android.dict.DictEntryDetail
import kotobaya.android.review.CardRows
import kotobaya.contracts.cards.CardKinds
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.render.WordAudio
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.cards.BuiltInNoteTypes
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.store.DeviceStore

/** One item of the prefetch set `P(now)` (SCHEMA.md 6.6): a note's own audio blob, or a (voice, text) pair. */
sealed interface WantedAudio {
    data class NoteBlob(val hash: String) : WantedAudio

    data class Pair(val voice: String, val text: String) : WantedAudio
}

/** The new cards a normal queue build at `now` shows: the cards of `queue.items` (new) and the entries it would materialize. */
data class NewWork(val cardIds: List<String>, val entrySeqs: List<Long>)

/** `P(now)`; the first [newCount] items are part (b), today's new cards. */
data class WordAudioPlan(val items: List<WantedAudio>, val newCount: Int)

/**
 * The prefetch set `P(now)` of SCHEMA.md 6.6, computed from the replica and the installed dictionary:
 * (b) today's new cards (every NEW item, then every entry to materialize, in queue order) first, then (a) the vocab
 * notes with a live, unsuspended, non-retired, non-new card due before `now + audio.prefetch_days`, ordered by their
 * smallest such `due_at`, then note id. Each pair or blob once, first position wins. A note with no speakable text is
 * skipped (the resolver says none).
 */
class WordAudioSet<L : LearningTables>(
    private val store: DeviceStore<L>,
    private val entry: (Long) -> DictEntryDetail?,
    private val newWork: (nowMs: Long) -> NewWork,
) {
    fun compute(nowMs: Long): WordAudioPlan {
        val new = newWork(nowMs)
        return store.transaction { tx ->
            val t = tx.learning
            val voice = WordAudios.voice(t)
            val out = LinkedHashSet<WantedAudio>()
            val types = HashMap<String, NoteTypeInfo?>()
            fun add(a: WordAudio?) {
                when (a) {
                    is WordAudio.Note -> out += WantedAudio.NoteBlob(a.blobHash)
                    is WordAudio.Tts -> out += WantedAudio.Pair(voice, a.text)
                    null -> Unit
                }
            }
            fun noteAudio(note: kotobaya.sync.client.apply.Row): WordAudio? {
                val typeId = CardRows.str(note["note_type_id"])
                val info = typeId?.let { id -> types.getOrPut(id) { CardRows.get(t, LearningTable.NOTE_TYPE, id)?.let(CardRows::noteTypeInfo) } }
                    ?: BuiltInNoteTypes.ALL.firstOrNull { it.info.baseType.wire == CardRows.str(note["type"]) }?.info
                    ?: return null
                val seq = (note["entry_seq"] as? JsonNumber)?.value?.toLong()
                val dictionary = seq?.let { s -> runCatching { entry(s) }.getOrNull() }?.let(WordAudios::dictionary)
                return WordAudios.resolve(info, CardRows.fieldsOf(note), dictionary)
            }

            // (b) today's new cards, then the entries to materialize (fields empty: the dictionary reading)
            for (cardId in new.cardIds) {
                val card = CardRows.get(t, LearningTable.CARD, cardId) ?: continue
                val note = CardRows.str(card["note_id"])?.let { CardRows.get(t, LearningTable.NOTE, it) } ?: continue
                if (CardRows.live(note)) add(noteAudio(note))
            }
            val vocab = BuiltInNoteTypes.VOCAB.info
            for (seq in new.entrySeqs) {
                val e = runCatching { entry(seq) }.getOrNull() ?: continue
                add(WordAudios.resolve(vocab, emptyMap(), WordAudios.dictionary(e)))
            }

            val newCount = out.size

            // (a) cards due inside the window
            val horizon = nowMs + prefetchDays(t) * DAY_MS
            val readingEnabled = CardKinds.readingEnabled(t.get(LearningTable.SETTINGS, listOf(JsonString(CardKinds.SETTING_READING_ENABLED)))?.get("value"))
            val firstDue = HashMap<String, Long>()
            for (c in t.all(LearningTable.CARD)) {
                if (!CardRows.live(c) || CardRows.refused(c) || (c["suspended"] as? JsonBool)?.value == true) continue
                if (CardRows.str(c["state"]) == "new") continue
                // retired: a vocab reading card while cards.reading_enabled is off (ADR-035); only vocab notes are used below
                if (!readingEnabled && CardRows.str(c["kind"]) == CardKinds.READING) continue
                val due = (c["due_at"] as? JsonNumber)?.value?.toLong() ?: continue
                if (due >= horizon) continue
                val noteId = CardRows.str(c["note_id"]) ?: continue
                firstDue.merge(noteId, due, ::minOf)
            }
            val ordered = firstDue.entries.sortedWith(compareBy({ it.value }, { it.key }))
            for ((noteId, _) in ordered) {
                val note = CardRows.get(t, LearningTable.NOTE, noteId) ?: continue
                if (!CardRows.live(note) || CardRows.str(note["type"]) != "vocab") continue
                add(noteAudio(note))
            }
            WordAudioPlan(out.toList(), newCount)
        }
    }

    private fun prefetchDays(t: LearningTables): Long {
        val v = (t.get(LearningTable.SETTINGS, listOf(JsonString(SETTING_PREFETCH_DAYS)))?.get("value") as? JsonNumber)?.value
        return if (v != null && v == Math.floor(v) && v in 0.0..365.0) v.toLong() else DEFAULT_PREFETCH_DAYS
    }

    companion object {
        const val SETTING_PREFETCH_DAYS = "audio.prefetch_days"
        const val DEFAULT_PREFETCH_DAYS = 7L
        private const val DAY_MS = 86_400_000L
    }
}

/** One answer of `resolveSpeech` (SpeechResolution), reduced to what the device keeps. */
data class SpeechAnswer(val status: String, val blobHash: String?, val durationMs: Long?, val retryAfterMs: Long? = null)

/** `POST /tts/resolve` for up to 200 `(voice, text)` pairs, non-urgent; null = the call failed (offline, 5xx, 403). */
fun interface SpeechResolver {
    fun resolve(pairs: List<WantedAudio.Pair>): List<SpeechAnswer>?
}

data class WordAudioPassSummary(val asked: Int, val ready: Int, val noteBlobs: Int, val stoppedEarly: Boolean, val failed: Boolean)

/**
 * The resolve half of the device prefetch pass (SCHEMA.md 6.6 "Device" rules 2-4): turns `P(now)` into `blob_cache`
 * rows (`wanted`, reason `card`) and `tts_audio` rows. The bytes are downloaded afterwards by the existing
 * [AudioPrefetcher], fed by [TtsAudioNeeds]. One pass at a time.
 */
class WordAudioPrefetch<L : LearningTables>(
    private val set: WordAudioSet<L>,
    private val table: TtsAudioTable,
    private val blobs: BlobStore,
    private val resolver: SpeechResolver,
    private val nowMs: () -> Long,
) {
    private val lock = Any()

    fun run(): WordAudioPassSummary = synchronized(lock) {
        val now = nowMs()
        val plan = set.compute(now)
        val wanted = plan.items
        val known = blobs.entries().mapTo(HashSet()) { it.hash }
        var noteBlobs = 0
        for (w in wanted) if (w is WantedAudio.NoteBlob && w.hash !in known) {
            blobs.want(w.hash, BlobReason.CARD, now)
            noteBlobs++
        }
        // pairs with no local row, or one older than 7 days, in P order (today's new cards come first)
        val todo = wanted.filterIsInstance<WantedAudio.Pair>().filter { p ->
            val row = table.get(p.voice, p.text)
            row == null || row.checkedAt < now - RECHECK_MS
        }
        val partB = plan.items.take(plan.newCount).toSet()
        val newCount = todo.count { it in partB } // part (b) leads P, so these are the first pairs of todo
        var asked = 0
        var ready = 0
        for ((i, chunk) in todo.chunked(CHUNK).withIndex()) {
            val answers = resolver.resolve(chunk)
            if (answers == null || answers.size != chunk.size) return WordAudioPassSummary(asked, ready, noteBlobs, stoppedEarly = false, failed = true)
            asked += chunk.size
            var chunkReady = 0
            for ((p, a) in chunk.zip(answers)) {
                val hash = a.blobHash
                if (a.status == "ready" && hash != null && isBlobHash(hash)) {
                    table.upsert(p.voice, p.text, hash, a.durationMs ?: 0L, now)
                    if (hash !in known) blobs.want(hash, BlobReason.CARD, now)
                    chunkReady++
                }
            }
            ready += chunkReady
            // stop after a chunk with fewer than half ready (the server is still generating) - never before every
            // pair of part (b) has been asked once in this pass
            val partBDone = (i + 1) * CHUNK >= newCount
            if (partBDone && chunkReady * 2 < chunk.size) return WordAudioPassSummary(asked, ready, noteBlobs, stoppedEarly = true, failed = false)
        }
        WordAudioPassSummary(asked, ready, noteBlobs, stoppedEarly = false, failed = false)
    }

    companion object {
        const val CHUNK = 200
        const val RECHECK_MS = 7L * 86_400_000L
    }
}

/**
 * The tts and note blobs of `P(now)` that the device knows (a local `tts_audio` row, or the note's own hash), for the
 * existing [AudioPrefetcher]: it protects them from eviction and downloads them. Their due time is fabricated from the
 * position in `P` (`now - size + index`) so the planner keeps `P` order (today's new cards first) and every one is
 * inside any prefetch window.
 */
class TtsAudioNeeds<L : LearningTables>(
    private val set: WordAudioSet<L>,
    private val table: TtsAudioTable,
) : AudioNeedsSource {
    override fun needs(nowMs: Long, windowDays: Int): List<AudioNeed> {
        val wanted = set.compute(nowMs).items
        val hashes = wanted.mapNotNull { w ->
            when (w) {
                is WantedAudio.NoteBlob -> w.hash
                is WantedAudio.Pair -> table.get(w.voice, w.text)?.blobHash
            }
        }.distinct()
        return hashes.mapIndexed { i, h -> AudioNeed(h, nowMs - (hashes.size - i)) }
    }
}
