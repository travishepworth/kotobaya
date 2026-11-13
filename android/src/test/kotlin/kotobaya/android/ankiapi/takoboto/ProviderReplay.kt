package kotobaya.android.ankiapi.takoboto

import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotobaya.android.ankiapi.provider.ProviderCallLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Replays a provider call fixture (format: src/test/resources/ankiapi/README.md, plus an optional
 * top-level `vars` object of preset `${name}` values) through [resolver]. Returns the saved variables.
 */
class ProviderReplay(private val resolver: ContentResolver) {
    fun replay(resource: String): Map<String, String> {
        val text = javaClass.classLoader!!.getResource(resource)?.readText() ?: fail("fixture $resource not on the classpath")
        val root = JSONObject(text)
        val saved = mutableMapOf<String, String>()
        root.optJSONObject("vars")?.let { v -> v.keys().forEach { saved[it] = v.getString(it) } }
        val calls = root.getJSONArray("calls")
        for (i in 0 until calls.length()) {
            val call = calls.getJSONObject(i)
            val where = "$resource call #$i (${call.optString("comment", call.getString("method"))})"
            try {
                run(call, saved)
            } catch (e: AssertionError) {
                throw AssertionError("$where: ${e.message}", e)
            }
        }
        return saved
    }

    private fun run(call: JSONObject, saved: MutableMap<String, String>) {
        fun sub(s: String): String = Regex("""\$\{(\w+)}""").replace(s) { m -> saved[m.groupValues[1]] ?: fail("unknown variable ${m.value}") }
        val uri = Uri.parse(sub(call.getString("uri")))
        val expect = call.getJSONObject("expect")
        val logBefore = ProviderCallLog.snapshot().size
        val outcome = runCatching {
            when (call.getString("method")) {
                "query" -> resolver.query(uri, call.strings("projection", ::sub), call.str("selection")?.let(::sub), call.strings("args", ::sub), call.str("sort"))
                "insert" -> resolver.insert(uri, call.getJSONObject("values").toValues(::sub))
                "bulkInsert" -> call.getJSONArray("valuesList").let { arr -> resolver.bulkInsert(uri, Array(arr.length()) { arr.getJSONObject(it).toValues(::sub) }) }
                "update" -> resolver.update(uri, call.getJSONObject("values").toValues(::sub), call.str("selection")?.let(::sub), call.strings("args", ::sub))
                "delete" -> resolver.delete(uri, call.str("selection")?.let(::sub), call.strings("args", ::sub))
                "getType" -> resolver.getType(uri)
                "call" -> resolver.call(uri, call.getString("callMethod"), call.str("arg"), null)
                else -> fail("unknown method ${call.getString("method")}")
            }
        }
        if (expect.has("throws")) {
            val e = outcome.exceptionOrNull() ?: fail("expected ${expect.getString("throws")}, got ${outcome.getOrNull()}")
            assertEquals(expect.getString("throws"), e::class.java.simpleName, e.toString())
            return
        }
        val result = outcome.getOrThrow()
        when {
            expect.has("rows") || expect.has("rowCount") || expect.has("columns") -> (result as? Cursor ?: fail("query returned $result")).use { c ->
                if (expect.has("columns")) assertEquals(expect.getJSONArray("columns").list(), c.columnNames.toList(), "columns")
                val rows = buildList { while (c.moveToNext()) add(List(c.columnCount) { c.getString(it) }) }
                if (expect.has("rowCount")) assertEquals(expect.getInt("rowCount"), rows.size, "row count: $rows")
                if (expect.has("rows")) {
                    val arr = expect.getJSONArray("rows")
                    val want = List(arr.length()) { r ->
                        val row = arr.getJSONArray(r)
                        List(row.length()) { if (row.isNull(it)) null else sub(row.get(it).toString()) }
                    }
                    assertEquals(want, rows, "rows")
                }
            }
            expect.has("uri") -> {
                val want = if (expect.isNull("uri")) null else sub(expect.getString("uri"))
                assertEquals(want, (result as Uri?)?.toString(), "insert uri")
                call.str("save")?.let { saved[it] = (result as Uri?)?.lastPathSegment ?: fail("nothing to save as $it") }
            }
            expect.has("count") -> assertEquals(expect.getInt("count"), result as Int, "count")
            expect.has("type") -> assertEquals(expect.str("type"), result as String?, "type")
            expect.has("bundle") -> assertNull(result, "call() bundle")
            else -> fail("expect has nothing to check: $expect")
        }
        if (call.optBoolean("logsUnknown")) {
            val added = ProviderCallLog.snapshot().drop(logBefore)
            assertTrue(added.any { ProviderCallLog.UNKNOWN_MARK in it }, "expected an unknown-call log line, got $added")
        }
    }

    private fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)

    private fun JSONObject.strings(key: String, sub: (String) -> String): Array<String>? =
        if (!has(key) || isNull(key)) null else getJSONArray(key).list().map(sub).toTypedArray()

    private fun JSONArray.list(): List<String> = List(length()) { getString(it) }

    private fun JSONObject.toValues(sub: (String) -> String): ContentValues = ContentValues().also { cv ->
        keys().forEach { k ->
            when (val v = get(k)) {
                JSONObject.NULL -> cv.putNull(k)
                is String -> cv.put(k, sub(v))
                is Int -> cv.put(k, v.toLong())
                is Long -> cv.put(k, v)
                is Boolean -> cv.put(k, v)
                is Number -> cv.put(k, v.toDouble())
                else -> fail("unsupported value $k=$v")
            }
        }
    }
}
