package kotobaya.tts

import kotobaya.cards.ReadingSetting
import kotobaya.contracts.render.WordAudio
import kotobaya.review.ReviewSettings
import kotobaya.server.Database
import kotobaya.server.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The word-audio sweep (ADR-047, C-29; SCHEMA.md 6.6 "Server" rule 2): 60 s after start and then every 600 s, walk
 * part (a) of the prefetch set `P(now)` (vocab notes with a live, unsuspended, not retired, non-new card due before
 * `now + audio.prefetch_days` days; by earliest such due, then note id) and enqueue a non-urgent `tts` job for every
 * pair that is not generated, not backing off and has no queued or running job, stopping when 300 `tts` jobs are
 * queued or running. It reads only; its one write is the enqueue (and the deletion of orphaned `tts_audio` rows the
 * lookup notices).
 */
class WordAudioSweep(
    private val db: Database,
    private val clock: () -> Instant = Instant::now,
    private val initialDelayMs: Long = 60_000,
    private val periodMs: Long = 600_000,
    private val pageSize: Int = 500,
) {
    private val log = LoggerFactory.getLogger(WordAudioSweep::class.java)

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        delay(initialDelayMs)
        while (isActive) {
            try {
                val n = runOnce()
                if (n > 0) log.info("word audio sweep enqueued {} tts jobs", n)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("word audio sweep failed", e)
            }
            delay(periodMs)
        }
    }

    /** One pass; returns the number of jobs it enqueued. */
    fun runOnce(): Int {
        val nowMs = clock().toEpochMilli()
        val (voice, days, readingEnabled) = db.txBlocking { dsl ->
            Triple(WordAudioStore.effectiveVoice(dsl), prefetchDays(dsl), ReadingSetting.load(dsl))
        }
        val windowEnd = Instant.ofEpochMilli(nowMs + days * 86_400_000L).atOffset(ZoneOffset.UTC)
        val seen = HashSet<SpeechPair>()
        var after: Pair<OffsetDateTime, String>? = null
        var enqueued = 0
        while (true) {
            val page = db.txBlocking { dsl -> notesPage(dsl, windowEnd, readingEnabled, after) }
            if (page.isEmpty()) return enqueued
            after = page.last().due to page.last().noteId
            val pairs = page.mapNotNull { n -> (n.audio as? WordAudio.Tts)?.let { SpeechPair(voice, it.text) } }
                .filter { seen.add(it) }
            val (added, full) = db.txBlocking { dsl -> enqueue(dsl, pairs) }
            enqueued += added
            if (full || page.size < pageSize) return enqueued
        }
    }

    /** Enqueues what [pairs] need; returns (jobs created, backlog reached its limit). */
    private fun enqueue(dsl: DSLContext, pairs: List<SpeechPair>): Pair<Int, Boolean> {
        if (WordAudioStore.backlog(dsl) >= WordAudioStore.BACKLOG_LIMIT) return 0 to true
        val generated = WordAudioStore.generated(dsl, pairs)
        val missing = pairs.filter { it !in generated }
        val state = WordAudioStore.jobState(dsl, missing)
        val wanted = missing.filter { it !in state.backoffLeftMs && it !in state.active }
        val before = WordAudioStore.backlog(dsl)
        val out = WordAudioStore.enqueue(dsl, wanted, urgent = false, limit = WordAudioStore.BACKLOG_LIMIT)
        val after = WordAudioStore.backlog(dsl)
        return (after - before).coerceAtLeast(0) to (EnqueueOutcome.HELD in out.values || after >= WordAudioStore.BACKLOG_LIMIT)
    }

    private class SweepNote(val noteId: String, val due: OffsetDateTime, val audio: WordAudio?)

    private fun notesPage(dsl: DSLContext, windowEnd: OffsetDateTime, readingEnabled: Boolean, after: Pair<OffsetDateTime, String>?): List<SweepNote> {
        val keyset = if (after == null) "" else "having (min(c.due_at), n.id) > (?::timestamptz, ?::uuid) "
        val args = mutableListOf<Any?>(windowEnd)
        if (after != null) {
            args += after.first
            args += after.second
        }
        args += pageSize
        return dsl.fetch(
            "select n.id::text, min(c.due_at), n.fields::text, e.headword, e.headword_reading " +
                "from app.note n join app.card c on c.note_id = n.id left join ref.dict_entry e on e.seq = n.entry_seq " +
                "where n.deleted_at is null and n.type = 'vocab' and c.deleted_at is null and c.merged_into_id is null " +
                "and not c.suspended and c.state <> 'new' and c.due_at < ?::timestamptz and ${ReadingSetting.notRetiredSql(readingEnabled)} " +
                "group by n.id, e.seq $keyset" +
                "order by min(c.due_at), n.id limit ?",
            *args.toTypedArray(),
        ).map { r ->
            val headword = r.get(3, String::class.java)
            val dict = headword?.let { WordAudioStore.dictionaryData(it, r.get(4, String::class.java)) }
            SweepNote(
                r.get(0, String::class.java),
                r.get(1, OffsetDateTime::class.java),
                WordAudioStore.resolve(null, "vocab", json.readTree(r.get(2, String::class.java)), dict),
            )
        }
    }

    private fun prefetchDays(dsl: DSLContext): Int {
        val text = dsl.fetchOptional("select value::text from app.settings where key = 'audio.prefetch_days'")
            .map { it.get(0, String::class.java) }.orElse(null) ?: return DEFAULT_PREFETCH_DAYS
        return ReviewSettings.intValue(json.readTree(text), 0, 365) ?: DEFAULT_PREFETCH_DAYS
    }

    companion object {
        const val DEFAULT_PREFETCH_DAYS = 7
    }
}
