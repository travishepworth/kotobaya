package kotobaya.android.dict

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * `GapFillQueue.request(kind, key, urgent)` of SHELL.md L-7 step 3 (C-31): a dictionary read that missed on the device
 * names the reference here and is answered at once; the fetch happens in the background.
 */
interface GapFillQueue {
    /** Enqueues one reference. The same reference twice is one fetch; `urgent` ones are fetched first. Never blocks. */
    fun request(kind: GapKind, key: String, urgent: Boolean)

    object None : GapFillQueue {
        override fun request(kind: GapKind, key: String, urgent: Boolean) = Unit
    }
}

/**
 * [GapFillQueue] that fetches in [scope] with [remote] (`getDictEntry` / `getKanji`), stores a hit in the sidecar
 * [cache], records a `404` as a negative result of the current server release, and calls [onLanded] after every
 * answer so that `shell.dict` follows (`gap_fill` counts changed). Offline or a failed fetch keeps the reference queued;
 * the next [request] or [kick] resumes. One drain at a time.
 */
class BackgroundGapFillQueue(
    private val scope: CoroutineScope,
    private val source: PackageSource,
    private val cache: DictGapCache,
    private val remote: DictGapRemote,
    private val onLanded: () -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) : GapFillQueue {
    private val lock = Any()
    private val pending = LinkedHashMap<GapRef, Boolean>()
    private var job: Job? = null

    override fun request(kind: GapKind, key: String, urgent: Boolean) {
        synchronized(lock) {
            val ref = GapRef(kind, key)
            val was = pending[ref]
            if (was == null || (urgent && !was)) pending[ref] = urgent
        }
        kick()
    }

    /** References waiting (tests, diagnostics). */
    fun pendingCount(): Int = synchronized(lock) { pending.size }

    /** Starts a drain unless one runs. */
    fun kick() {
        synchronized(lock) {
            if (job?.isActive == true || pending.isEmpty()) return
            job = scope.launch { drain() }
        }
    }

    /** Fetches until the queue is empty or a fetch fails. Public so that a test can run it inline. */
    suspend fun drain() {
        var released = false
        while (true) {
            val ref = next() ?: return
            try {
                if (!released && cache.serverReleaseId() == null) {
                    released = true
                    runCatching { remote.serverRelease() }.getOrNull()?.let(cache::recordServerRelease)
                }
                land(ref)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("dict gap queue: ${ref.kind.tag} ${ref.key}: $e")
                synchronized(lock) { pending.putIfAbsent(ref, false) } // offline: keep it for the next trigger
                return
            }
        }
    }

    private fun next(): GapRef? = synchronized(lock) {
        val ref = pending.entries.firstOrNull { it.value }?.key ?: pending.keys.firstOrNull() ?: return null
        pending.remove(ref)
        ref
    }

    private suspend fun land(ref: GapRef) {
        if (have(ref)) return
        val release = cache.serverReleaseId() ?: 0L
        val answer = when (ref.kind) {
            GapKind.ENTRY -> remote.entry(ref.key.toLongOrNull() ?: return)
            GapKind.KANJI -> remote.kanji(ref.key)
        }
        when (answer) {
            is RemoteAnswer.Found -> {
                val body = DictJson.stripOverlay(answer.body)
                when (ref.kind) { // an unreadable body is a failed fetch, not a hit
                    GapKind.ENTRY -> DictJson.entry(body)
                    GapKind.KANJI -> DictJson.kanji(body)
                }
                cache.put(listOf(GapRow(ref, release, body, nowMs())))
                cache.countLanded()
            }
            RemoteAnswer.NotFound -> cache.put(listOf(GapRow(ref, release, null, nowMs())))
        }
        onLanded()
    }

    private fun have(ref: GapRef): Boolean = when (ref.kind) {
        GapKind.ENTRY -> ref.key.toLongOrNull()?.let { source.packageEntry(it) != null || cache.entry(it) != null } ?: true
        GapKind.KANJI -> source.packageKanji(ref.key) != null || cache.kanji(ref.key) != null
    }
}
