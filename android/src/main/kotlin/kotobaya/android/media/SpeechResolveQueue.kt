package kotobaya.android.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The native speech resolver of SHELL.md 4.3 `resolveSpeech` (C-31): the local handler hands it every `(voice, text)`
 * pair that has no `tts_audio` row; it asks the server's `resolveSpeech` with the device token in the background, asks
 * again while the server says `pending`, and upserts `tts_audio`. The handler never waits for it.
 */
interface SpeechResolveQueue {
    /** Enqueues [pair]; the same pair twice is one request. [urgent]: the card on screen (asked first, 20 at a time). */
    fun request(voice: String, text: String, urgent: Boolean)

    /** Milliseconds left of the server's `failed` back-off for the pair (600 s), or null when it is not failed. */
    fun failedRemainingMs(voice: String, text: String): Long?

    object None : SpeechResolveQueue {
        override fun request(voice: String, text: String, urgent: Boolean) = Unit

        override fun failedRemainingMs(voice: String, text: String): Long? = null
    }
}

/** One blocking call of the server's `resolveSpeech`; null = it failed (offline, 5xx, 403). */
fun interface UrgentSpeechResolver {
    fun resolve(pairs: List<WantedAudio.Pair>, urgent: Boolean): List<SpeechAnswer>?
}

/**
 * [SpeechResolveQueue] over [resolver]. A pair the server reports `ready` is stored in [table] (and its blob wanted by
 * the prefetcher, reason `card`); `pending` is asked again after the server's `retry_after_ms` (at most [MAX_ROUNDS]
 * times); `failed` is remembered for the back-off time; a failed call keeps the pairs for the next [request] or [kick].
 */
class BackgroundSpeechResolveQueue(
    private val scope: CoroutineScope,
    private val table: TtsAudioTable,
    private val blobs: BlobStore,
    private val resolver: UrgentSpeechResolver,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : SpeechResolveQueue {
    private val lock = Any()
    private val pending = LinkedHashMap<WantedAudio.Pair, Entry>()
    private val failedUntil = HashMap<WantedAudio.Pair, Long>()
    private var job: Job? = null

    private class Entry(var urgent: Boolean, var rounds: Int = 0, var notBeforeMs: Long = 0L)

    override fun request(voice: String, text: String, urgent: Boolean) {
        val pair = WantedAudio.Pair(voice, text)
        synchronized(lock) {
            if (failedUntil[pair]?.let { it > nowMs() } == true) return
            val e = pending[pair]
            if (e == null) pending[pair] = Entry(urgent) else if (urgent) e.urgent = true
        }
        kick()
    }

    override fun failedRemainingMs(voice: String, text: String): Long? = synchronized(lock) {
        failedUntil[WantedAudio.Pair(voice, text)]?.let { it - nowMs() }?.takeIf { it > 0 }
    }

    fun pendingCount(): Int = synchronized(lock) { pending.size }

    /** Starts a drain unless one runs. */
    fun kick() {
        synchronized(lock) {
            if (job?.isActive == true || pending.isEmpty()) return
            job = scope.launch { drain() }
        }
    }

    /** Runs until nothing is pending or a call fails. Public so that a test can run it inline. */
    suspend fun drain() {
        while (true) {
            val (batch, urgent, waitMs) = nextBatch()
            if (batch.isEmpty()) {
                if (waitMs == null) return
                sleep(waitMs)
                continue
            }
            val answers = try {
                resolver.resolve(batch, urgent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (answers == null || answers.size != batch.size) return // offline or refused: kept for the next trigger
            val now = nowMs()
            synchronized(lock) {
                for ((pair, a) in batch.zip(answers)) land(pair, a, now)
            }
        }
    }

    private data class Batch(val pairs: List<WantedAudio.Pair>, val urgent: Boolean, val waitMs: Long?)

    private fun nextBatch(): Batch = synchronized(lock) {
        val now = nowMs()
        val due = pending.entries.filter { it.value.notBeforeMs <= now }
        if (due.isEmpty()) return Batch(emptyList(), false, pending.values.minOfOrNull { it.notBeforeMs - now })
        val urgent = due.filter { it.value.urgent }.take(URGENT_MAX)
        if (urgent.isNotEmpty()) return Batch(urgent.map { it.key }, true, null)
        Batch(due.take(WordAudioPrefetch.CHUNK).map { it.key }, false, null)
    }

    private fun land(pair: WantedAudio.Pair, a: SpeechAnswer, now: Long) {
        val entry = pending[pair] ?: return
        val hash = a.blobHash
        when {
            a.status == "ready" && hash != null && isBlobHash(hash) -> {
                table.upsert(pair.voice, pair.text, hash, a.durationMs ?: 0L, now)
                runCatching { blobs.want(hash, BlobReason.CARD, now) }
                pending.remove(pair)
            }
            a.status == "pending" && ++entry.rounds < MAX_ROUNDS -> {
                entry.urgent = false // asked once urgently; the server holds the job
                entry.notBeforeMs = now + (a.retryAfterMs ?: DEFAULT_RETRY_MS).coerceIn(500L, 60_000L)
            }
            a.status == "failed" -> {
                failedUntil[pair] = now + (a.retryAfterMs ?: FAILED_MS)
                pending.remove(pair)
            }
            else -> pending.remove(pair) // none, unknown, or pending for too long: the next card showing asks again
        }
    }

    companion object {
        const val URGENT_MAX = 20
        const val MAX_ROUNDS = 30
        const val DEFAULT_RETRY_MS = 1_000L
        const val FAILED_MS = 600_000L
    }
}
