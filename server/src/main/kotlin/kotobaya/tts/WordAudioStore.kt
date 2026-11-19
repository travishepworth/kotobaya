package kotobaya.tts

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.FuriganaSegment
import kotobaya.contracts.render.WordAudio
import kotobaya.contracts.render.WordAudioInput
import kotobaya.jobs.JobRepo
import kotobaya.render.DefaultWordAudioResolver
import kotobaya.server.Database
import kotobaya.server.json
import kotlinx.coroutines.CancellationException
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.util.UUID

/** The identity of one piece of TTS audio (SCHEMA.md 6.6 "pair"): exact strings, no folding. */
data class SpeechPair(val voice: String, val text: String) {
    val dedupeKey: String get() = ttsDedupeKey(voice, text)
}

/** A generated pair: its `app.tts_audio` row whose blob row exists. */
data class GeneratedAudio(val blobHash: String, val durationMs: Int)

/** What [WordAudioStore.enqueue] did for one pair. */
enum class EnqueueOutcome {
    /** A `tts` job for the pair is queued or running after the call (created now or already there). */
    ACTIVE,

    /** Not enqueued: the backlog was at its limit. */
    HELD,
}

/**
 * Word audio on the server (ADR-047, C-29; SCHEMA.md 6.6 "Server"): the core resolver's inputs, the `app.tts_audio`
 * lookup, the back-off test and the bounded enqueue of `tts` jobs. A system table and the job queue only: no op, no
 * learning data (CLAUDE.md rule 8 untouched).
 */
object WordAudioStore {
    /** At most this many `tts` jobs queued or running after a non-urgent enqueue (sweep, `resolveSpeech`). */
    const val BACKLOG_LIMIT = 300

    /** At most this many after an urgent enqueue: the 300 plus a reserve of 40 for the cards on screen. */
    const val URGENT_LIMIT = 340

    /** A failed or cancelled pair is not retried for this long (SCHEMA.md 6.6 "backing off"). */
    const val BACKOFF_MS = 600_000L

    /** Serializes counting and enqueueing `tts` jobs, so concurrent callers cannot pass the limits together. */
    private const val TTS_ENQUEUE_LOCK = 7033047L

    /** Vocab note type info for hosts that only know the base type (the resolver reads nothing else). */
    private val VOCAB_INFO = NoteTypeInfo(NoteBaseType.VOCAB, listOf("expression", "reading", "meaning"), emptyList(), "")

    /**
     * The renderer's dictionary data of an entry: headword, its reading over the whole headword as one furigana segment
     * (the same mapping as `QueueItem.text` and the web), [glosses] and [pitchDrops] as given.
     */
    fun dictionaryData(headword: String, reading: String, glosses: List<String> = emptyList(), pitchDrops: List<Int> = emptyList()): DictionaryData =
        DictionaryData(
            expression = headword,
            reading = reading,
            furigana = listOf(FuriganaSegment(headword, reading.takeUnless { it == headword })),
            glosses = glosses,
            pitchDrops = pitchDrops,
        )

    /** `effectiveVoice` of the stored `tts.voice`: its string value when it is a JSON string, else null → `"3"`. */
    fun effectiveVoice(dsl: DSLContext): String {
        val stored = dsl.fetchOptional(
            "select value #>> '{}' from app.settings where key = 'tts.voice' and jsonb_typeof(value) = 'string'",
        ).map { it.get(0, String::class.java) }.orElse(null)
        return DefaultWordAudioResolver.effectiveVoice(stored)
    }

    /** The core resolver over a note's [fields] (JSON object), its type and dictionary data. */
    fun resolve(noteType: NoteTypeInfo?, baseType: String?, fields: JsonNode?, dictionary: DictionaryData?): WordAudio? {
        val info = noteType ?: if (baseType == NoteBaseType.VOCAB.wire) VOCAB_INFO else return null
        val map = LinkedHashMap<String, String>()
        fields?.fields()?.forEach { (k, v) -> if (v.isTextual) map[k] = v.asText() }
        return runCatching { DefaultWordAudioResolver.resolve(WordAudioInput(info, map, dictionary)) }.getOrNull()
    }

    /** `QueueItem.word_audio` JSON (null node for none). */
    fun wordAudioJson(audio: WordAudio?, voice: String, generated: Map<SpeechPair, GeneratedAudio>): JsonNode = when (audio) {
        null -> json.nullNode()
        is WordAudio.Note -> json.createObjectNode().put("source", "note").put("blob_hash", audio.blobHash).putNull("text").putNull("voice")
        is WordAudio.Tts -> {
            val o: ObjectNode = json.createObjectNode().put("source", "tts")
            val g = generated[SpeechPair(voice, audio.text)]
            if (g == null) o.putNull("blob_hash") else o.put("blob_hash", g.blobHash)
            o.put("text", audio.text).put("voice", voice)
        }
    }

    /**
     * The generated pairs among [pairs]. A row whose blob row is gone is deleted here and the pair counts as not
     * generated (SCHEMA.md 6.6 "generated").
     */
    fun generated(dsl: DSLContext, pairs: Collection<SpeechPair>): Map<SpeechPair, GeneratedAudio> {
        if (pairs.isEmpty()) return emptyMap()
        val distinct = pairs.toSet().toList()
        val out = HashMap<SpeechPair, GeneratedAudio>()
        val orphans = ArrayList<SpeechPair>()
        dsl.fetch(
            "select t.voice, t.text, t.blob_hash, t.duration_ms, (b.hash is not null) from app.tts_audio t " +
                "join unnest(?::text[], ?::text[]) as q(voice, text) on q.voice = t.voice and q.text = t.text " +
                "left join app.blob b on b.hash = t.blob_hash",
            distinct.map { it.voice }.toTypedArray(), distinct.map { it.text }.toTypedArray(),
        ).forEach { r ->
            val p = SpeechPair(r.get(0, String::class.java), r.get(1, String::class.java))
            if (r.get(4, Boolean::class.java)) {
                out[p] = GeneratedAudio(r.get(2, String::class.java), r.get(3, Int::class.java))
            } else {
                orphans += p
            }
        }
        if (orphans.isNotEmpty()) {
            dsl.execute(
                "delete from app.tts_audio t using unnest(?::text[], ?::text[]) as q(voice, text) " +
                    "where q.voice = t.voice and q.text = t.text and not exists (select 1 from app.blob b where b.hash = t.blob_hash)",
                orphans.map { it.voice }.toTypedArray(), orphans.map { it.text }.toTypedArray(),
            )
        }
        return out
    }

    /** Which of [hashes] have an `app.blob` row. */
    fun blobsPresent(dsl: DSLContext, hashes: Collection<String>): Set<String> {
        if (hashes.isEmpty()) return emptySet()
        return dsl.fetch("select hash from app.blob where hash = any(array(select unnest(?::text[])))", hashes.toSet().toTypedArray())
            .map { it.get(0, String::class.java) }.toSet()
    }

    /** Job state of the pairs: time left of the back-off (ms, > 0) for backing-off pairs, and the pairs with an active job. */
    class JobState(val backoffLeftMs: Map<SpeechPair, Long>, val active: Set<SpeechPair>)

    fun jobState(dsl: DSLContext, pairs: Collection<SpeechPair>): JobState {
        if (pairs.isEmpty()) return JobState(emptyMap(), emptySet())
        val byKey = pairs.toSet().associateBy { it.dedupeKey }
        val keys = byKey.keys.toTypedArray()
        val backoff = HashMap<SpeechPair, Long>()
        dsl.fetch(
            "select distinct on (dedupe_key) dedupe_key, status, " +
                "(extract(epoch from (finished_at - (now() - make_interval(secs => ?)))) * 1000)::bigint " +
                "from app.job where kind = 'tts' and dedupe_key = any(array(select unnest(?::text[]))) order by dedupe_key, created_at desc, id desc",
            BACKOFF_MS / 1000.0, keys,
        ).forEach { r ->
            val status = r.get(1, String::class.java)
            val left = (r.get(2) as Number?)?.toLong()
            if ((status == "failed" || status == "cancelled") && left != null && left > 0) {
                backoff[checkNotNull(byKey[r.get(0, String::class.java)])] = left
            }
        }
        val active = dsl.fetch(
            "select dedupe_key from app.job where kind = 'tts' and status in ('queued', 'running') and dedupe_key = any(array(select unnest(?::text[])))", keys,
        ).mapNotNull { byKey[it.get(0, String::class.java)] }.toSet()
        return JobState(backoff, active)
    }

    /** `tts` jobs queued or running now. */
    fun backlog(dsl: DSLContext): Int =
        (dsl.fetchValue("select count(*) from app.job where kind = 'tts' and status in ('queued', 'running')") as Number).toInt()

    /**
     * Enqueue a `tts` job (payload `{text, voice}`, the usual dedupe key) for each of [pairs] in order, while fewer than
     * [limit] `tts` jobs are queued or running. A pair that already has a queued or running job is [EnqueueOutcome.ACTIVE]
     * and costs nothing; with [urgent] its queued job's `run_after` is lowered to `now − 1 day` (only below the limit).
     * New urgent jobs get that `run_after` too (SCHEMA.md 6.6 "Urgent"). The caller filters generated and backing-off
     * pairs out first. Call inside a transaction; it takes a transaction-scoped lock so the limit holds across callers.
     */
    fun enqueue(dsl: DSLContext, pairs: List<SpeechPair>, urgent: Boolean, limit: Int, createdByTokenId: UUID? = null): Map<SpeechPair, EnqueueOutcome> {
        if (pairs.isEmpty()) return emptyMap()
        dsl.execute("select pg_advisory_xact_lock(?)", TTS_ENQUEUE_LOCK)
        val out = LinkedHashMap<SpeechPair, EnqueueOutcome>()
        var count = backlog(dsl)
        val active = jobState(dsl, pairs).active
        val runAfter = if (urgent) {
            dsl.fetchValue("select now() - interval '1 day'").let { (it as OffsetDateTime).toInstant() }
        } else {
            null
        }
        for (p in pairs) {
            if (p in out) continue
            if (p in active) {
                if (urgent && count < limit) {
                    dsl.execute(
                        "update app.job set run_after = least(run_after, now() - interval '1 day') " +
                            "where kind = 'tts' and status = 'queued' and dedupe_key = ?",
                        p.dedupeKey,
                    )
                }
                out[p] = EnqueueOutcome.ACTIVE
                continue
            }
            if (count >= limit) {
                out[p] = EnqueueOutcome.HELD
                continue
            }
            val payload = json.createObjectNode().put("text", p.text).put("voice", p.voice)
            val r = JobRepo.enqueue(dsl, "tts", payload, dedupeKey = p.dedupeKey, createdByTokenId = createdByTokenId, runAfter = runAfter)
            if (r.created) count++
            out[p] = EnqueueOutcome.ACTIVE
        }
        return out
    }

    /** Most urgent enqueues per served page (SCHEMA.md 6.6 "Server" rule 1). */
    const val SERVED_URGENT = 20

    /**
     * Rule 1 of SCHEMA.md 6.6 "Server": for the first [SERVED_URGENT] served items whose `word_audio` is a `tts` pair
     * that is not generated and not backing off, an urgent `tts` job. [items] are `QueueItem` JSON. Call in its own
     * transaction; the caller never fails a request because of it.
     */
    fun enqueueServed(dsl: DSLContext, items: Iterable<JsonNode>) {
        val missing = items.mapNotNull { item ->
            val a = item["word_audio"]?.takeIf { it.isObject } ?: return@mapNotNull null
            if (a["source"]?.asText() != "tts" || a["blob_hash"]?.isNull != true) return@mapNotNull null
            SpeechPair(a["voice"].asText(), a["text"].asText())
        }.distinct()
        if (missing.isEmpty()) return
        val state = jobState(dsl, missing)
        val wanted = missing.filter { it !in state.backoffLeftMs }.take(SERVED_URGENT)
        enqueue(dsl, wanted, urgent = true, limit = URGENT_LIMIT)
    }
}

private val servedLog = LoggerFactory.getLogger("kotobaya.tts.served")

/**
 * [WordAudioStore.enqueueServed] in its own transaction after the page was built; a failure is logged and never fails
 * the request (SCHEMA.md 6.6 "Server" rule 1).
 */
suspend fun enqueueServedAudio(db: Database, items: Iterable<JsonNode>) {
    try {
        db.tx { WordAudioStore.enqueueServed(it, items) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        servedLog.warn("word audio enqueue failed", e)
    }
}
