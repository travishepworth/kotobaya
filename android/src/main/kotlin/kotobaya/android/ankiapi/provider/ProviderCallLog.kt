package kotobaya.android.ankiapi.provider

import android.content.ContentValues
import android.os.Bundle
import android.util.Log
import java.time.Instant

/**
 * Bounded, process-wide log of every provider call (kept in production on purpose: the provider
 * contract was written from memory, so the human sends this log to replace the seeded fixtures).
 * Lines go to logcat under [TAG] and to a ring buffer of [CAPACITY] entries for a debug screen
 * ([snapshot]). Entries for calls the provider did not understand start with [UNKNOWN_MARK].
 */
object ProviderCallLog {
    const val TAG: String = "KotobayaAnkiApi"
    const val CAPACITY: Int = 500
    const val MAX_LINE_CHARS: Int = 4000
    const val UNKNOWN_MARK: String = "!!"

    private val lines = ArrayDeque<String>()

    fun log(message: String) {
        val line = "${Instant.now()} $message".let {
            if (it.length > MAX_LINE_CHARS) it.take(MAX_LINE_CHARS) + "…" else it
        }
        // Logcat truncates around 4 KB per line; chunk so long field payloads survive.
        runCatching { line.chunked(LOGCAT_CHUNK).forEach { Log.i(TAG, it) } }
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > CAPACITY) lines.removeFirst()
        }
    }

    fun unknown(message: String) {
        log("$UNKNOWN_MARK $message")
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() {
        synchronized(lines) { lines.clear() }
    }

    /** Makes the unit separator and newlines visible so field boundaries show in the log. */
    fun show(value: Any?): String = when (value) {
        null -> "null"
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { show(it) }
        is ContentValues -> value.keySet().sorted().joinToString(prefix = "{", postfix = "}") { "$it=${show(value.get(it))}" }
        is Bundle -> value.keySet().sorted().joinToString(prefix = "{", postfix = "}") {
            @Suppress("DEPRECATION")
            "$it=${show(value.get(it))}"
        }
        is String -> "\"" + value.replace(AnkiContract.FIELD_SEPARATOR, "<US>").replace("\n", "\\n") + "\""
        else -> value.toString()
    }

    private const val LOGCAT_CHUNK = 3500
}
