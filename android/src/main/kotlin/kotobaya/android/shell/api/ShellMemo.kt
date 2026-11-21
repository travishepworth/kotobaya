package kotobaya.android.shell.api

import kotobaya.android.queue.QueueReadCache
import kotobaya.sync.client.apply.LearningTable

/**
 * PERF-LF: a handler's answer kept until one of the learning [tables] it reads changes ([kotobaya.android.sync.ReplicaVersions],
 * through the app's [QueueReadCache]) or [key] (its parameters, and anything else it depends on: a time bucket, the
 * dictionary generation) differs. Without the cache (tests that do not provide one) [compute] runs every time. The
 * value must be immutable.
 */
internal fun <T> LocalCall.memoized(name: String, key: Any?, tables: Array<LearningTable>, compute: () -> T): T {
    val cache = runCatching { services.get<QueueReadCache>() }.getOrNull() ?: return compute()
    return cache.memo("shell|$name", cache.version(*tables), key, compute)
}
