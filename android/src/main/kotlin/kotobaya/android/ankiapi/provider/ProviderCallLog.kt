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

    /**
     * Whether string data (note fields, names, selection args, returned cells) is written verbatim.
     * True only in debuggable builds (set by [AnkiProvider.onCreate]); release builds log the shape
     * of each value (length and field count) so the log carries no note content.
     */
    @Volatile
    var revealValues: Boolean = false

    /**
     * Data values: in debug builds strings appear with the unit separator and newlines made
     * visible; in release builds as `<str len=N fields=K>`. Keys and numbers are always shown.
     */
    fun show(value: Any?): String = when (value) {
        null -> "null"
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { show(it) }
        is ContentValues -> value.keySet().sorted().joinToString(prefix = "{", postfix = "}") { "$it=${show(value.get(it))}" }
        is Bundle -> value.keySet().sorted().joinToString(prefix = "{", postfix = "}") {
            @Suppress("DEPRECATION")
            "$it=${show(value.get(it))}"
        }
        is String -> if (revealValues) visible(value) else shape(value)
        else -> value.toString()
    }

    /** Column names and other identifiers: always verbatim. */
    fun showNames(names: Array<out String>?): String =
        names?.joinToString(prefix = "[", postfix = "]") { visible(it) } ?: "null"

    /** SQL-ish selections: structure always verbatim, quoted literals redacted in release builds. */
    fun showSelection(selection: String?): String = when {
        selection == null -> "null"
        revealValues -> visible(selection)
        else -> visible(selection.replace(Regex("""'(?:[^']|'')*'|"(?:[^"]|"")*"""")) { shape(it.value.drop(1).dropLast(1)) })
    }

    private fun visible(s: String): String =
        "\"" + s.replace(AnkiContract.FIELD_SEPARATOR, "<US>").replace("\n", "\\n") + "\""

    private fun shape(s: String): String =
        "<str len=${s.length} fields=${s.count { it == AnkiContract.FIELD_SEPARATOR[0] } + 1}>"

    private const val LOGCAT_CHUNK = 3500
}
