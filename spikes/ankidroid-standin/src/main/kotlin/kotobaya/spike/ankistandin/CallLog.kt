package kotobaya.spike.ankistandin

import android.content.ContentValues
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.time.Instant

/** Process-wide call log: every entry goes to logcat (tag [TAG]) and to the on-screen list. */
object CallLog {
    const val TAG = "AnkiStandin"

    private val lines = mutableListOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: (() -> Unit)? = null

    fun log(message: String) {
        val line = "${Instant.now()} $message"
        // Logcat truncates around 4 KB per line; chunk so long field payloads survive.
        line.chunked(3500).forEach { Log.i(TAG, it) }
        synchronized(lines) { lines.add(line) }
        mainHandler.post { listener?.invoke() }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() {
        synchronized(lines) { lines.clear() }
        mainHandler.post { listener?.invoke() }
    }

    fun setListener(l: (() -> Unit)?) {
        listener = l
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
        is String -> "\"" + value.replace(Contract.FIELD_SEPARATOR, "<US>").replace("\n", "\\n") + "\""
        else -> value.toString()
    }
}
