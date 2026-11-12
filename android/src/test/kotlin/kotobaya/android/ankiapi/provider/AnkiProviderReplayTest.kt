package kotobaya.android.ankiapi.provider

import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 'S01 call-log replay': every fixture in src/test/resources/ankiapi/ is replayed through a
 * ContentResolver into [AnkiProvider] backed by a fresh [InMemoryAnkiStore]. See the README there
 * for the format.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AnkiProviderReplayTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val resolver: ContentResolver get() = app.contentResolver

    @Before
    fun grant() {
        shadowOf(app).grantPermissions(AnkiContract.READ_WRITE_PERMISSION)
        ProviderCallLog.clear()
    }

    @Test
    fun fixturesExist() {
        val names = fixtureFiles().map { it.name }
        assertTrue("add_content_api_basic.json" in names, "fixtures found: $names")
        assertTrue(names.size >= 3, "fixtures found: $names")
    }

    @Test
    fun replayAddContentApiBasic() = replay("add_content_api_basic.json")

    @Test
    fun replayAddContentApiCustomModel() = replay("add_content_api_custom_model.json")

    @Test
    fun replayUnknownAndErrors() = replay("unknown_and_errors.json")

    /** Catches fixtures added later (e.g. a real Takoboto log) without a named test. */
    @Test
    fun replayEveryFixture() {
        fixtureFiles().forEach { replay(it.name) }
    }

    private fun fixtureFiles(): List<File> {
        val dir = javaClass.classLoader?.getResource("ankiapi") ?: fail("ankiapi fixtures not on the test classpath")
        return File(dir.toURI()).listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
    }

    private fun replay(name: String) {
        val provider = Robolectric.setupContentProvider(AnkiProvider::class.java, AnkiContract.AUTHORITY)
        provider.attachStore(InMemoryAnkiStore(firstId = 1000, clockSeconds = { 1_700_000_000 }))
        val text = javaClass.classLoader!!.getResource("ankiapi/$name")!!.readText()
        val calls = JSONObject(text).getJSONArray("calls")
        val saved = mutableMapOf<String, String>()
        for (i in 0 until calls.length()) {
            val call = calls.getJSONObject(i)
            val where = "$name call #$i (${call.optString("comment", call.getString("method"))})"
            try {
                runCall(call, saved)
            } catch (e: AssertionError) {
                throw AssertionError("$where: ${e.message}", e)
            }
        }
    }

    private fun runCall(call: JSONObject, saved: MutableMap<String, String>) {
        fun sub(s: String): String = Regex("""\$\{(\w+)}""").replace(s) { m ->
            saved[m.groupValues[1]] ?: fail("unknown variable ${m.value}")
        }
        val uri = Uri.parse(sub(call.getString("uri")))
        val expect = call.getJSONObject("expect")
        val logBefore = ProviderCallLog.snapshot().size
        val outcome = runCatching {
            when (call.getString("method")) {
                "query" -> resolver.query(
                    uri,
                    call.optStrings("projection", ::sub),
                    call.optStringOrNull("selection")?.let(::sub),
                    call.optStrings("args", ::sub),
                    call.optStringOrNull("sort"),
                )
                "insert" -> resolver.insert(uri, call.getJSONObject("values").toValues(::sub))
                "bulkInsert" -> call.getJSONArray("valuesList").let { arr ->
                    resolver.bulkInsert(uri, Array(arr.length()) { arr.getJSONObject(it).toValues(::sub) })
                }
                "update" -> resolver.update(
                    uri,
                    call.getJSONObject("values").toValues(::sub),
                    call.optStringOrNull("selection")?.let(::sub),
                    call.optStrings("args", ::sub),
                )
                "delete" -> resolver.delete(uri, call.optStringOrNull("selection")?.let(::sub), call.optStrings("args", ::sub))
                "call" -> resolver.call(uri, call.getString("callMethod"), call.optStringOrNull("arg"), null)
                "getType" -> resolver.getType(uri)
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
            expect.has("rows") || expect.has("columns") || expect.has("rowCount") -> {
                val cursor = result as? android.database.Cursor ?: fail("query returned $result")
                cursor.use { c ->
                    if (expect.has("columns")) {
                        assertEquals(expect.getJSONArray("columns").strings(), c.columnNames.toList(), "columns")
                    }
                    val rows = buildList {
                        while (c.moveToNext()) add(List(c.columnCount) { c.getString(it) })
                    }
                    if (expect.has("rowCount")) assertEquals(expect.getInt("rowCount"), rows.size, "row count")
                    if (expect.has("rows")) {
                        val want = expect.getJSONArray("rows").let { arr ->
                            List(arr.length()) { r ->
                                val row = arr.getJSONArray(r)
                                List(row.length()) { cell -> if (row.isNull(cell)) null else sub(row.get(cell).toString()) }
                            }
                        }
                        assertEquals(want, rows, "rows")
                    }
                }
            }
            expect.has("uri") -> {
                val want = if (expect.isNull("uri")) null else sub(expect.getString("uri"))
                assertEquals(want, (result as Uri?)?.toString(), "insert uri")
                call.optStringOrNull("save")?.let { key ->
                    saved[key] = (result as Uri?)?.lastPathSegment ?: fail("nothing to save as $key")
                }
            }
            expect.has("count") -> assertEquals(expect.getInt("count"), result as Int, "count")
            expect.has("type") -> assertEquals(expect.optStringOrNull("type"), result as String?, "type")
            expect.has("bundle") -> assertNull(result, "call() bundle")
            else -> fail("expect has nothing to check: $expect")
        }
        if (call.optBoolean("logsUnknown")) {
            val added = ProviderCallLog.snapshot().drop(logBefore)
            assertTrue(added.any { ProviderCallLog.UNKNOWN_MARK in it }, "expected an unknown-call log line, got $added")
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)

    private fun JSONObject.optStrings(key: String, sub: (String) -> String): Array<String>? =
        if (!has(key) || isNull(key)) null else getJSONArray(key).strings().map(sub).toTypedArray()

    private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

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
