package kotobaya.fsrs

import java.io.File
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * [FsrsVectors] (commonTest, so js and android run the golden vectors too) must be exactly the
 * encoding of `fixtures/fsrs/vectors.json`. Floats are compared as the JSON's own tokens (Python
 * `repr`, which the generator copied verbatim), so no precision is lost in the copy.
 */
class FsrsVectorsDriftTest {
    @Test
    fun generatedVectorsMatchFixtureJson() {
        val json = locate("fixtures/fsrs/vectors.json").readText()
        val expected = encode(json)
        if (expected != FsrsVectors.sequences) {
            val first = expected.indices.firstOrNull { expected[it] != FsrsVectors.sequences.getOrNull(it) }
            fail(
                "FsrsVectors.kt is stale (first differing sequence: $first of ${expected.size}); " +
                    "regenerate it from vectors.json with the encoding in its KDoc",
            )
        }
        assertEquals(240, expected.size)
    }

    private fun encode(json: String): List<String> {
        val sequences = mutableListOf<String>()
        var reviews = mutableListOf<String>()
        var fields = mutableMapOf<String, String>()
        for (m in TOKEN.findAll(json.substring(json.indexOf("\"sequences\"")))) {
            val (key, value) = m.destructured
            when (key) {
                "id" -> if (reviews.isNotEmpty()) {
                    sequences += reviews.joinToString(";")
                    reviews = mutableListOf()
                }
                "kind" -> Unit
                else -> {
                    fields[key] = value
                    if (key == "interval_seconds") { // last key of a review in the generator's order
                        reviews += encodeReview(fields)
                        fields = mutableMapOf()
                    }
                }
            }
        }
        if (reviews.isNotEmpty()) sequences += reviews.joinToString(";")
        return sequences
    }

    private fun encodeReview(f: Map<String, String>): String {
        val state = when (val s = f.getValue("state").trim('"')) {
            "Learning" -> "L"
            "Review" -> "R"
            "Relearning" -> "X"
            else -> error("state $s")
        }
        val step = f.getValue("step").let { if (it == "null") "-" else it }
        return listOf(
            f.getValue("rating"),
            ms(f.getValue("review_at")),
            state,
            step,
            f.getValue("stability"),
            f.getValue("difficulty"),
            ms(f.getValue("due")),
        ).joinToString(",")
    }

    private fun ms(quoted: String): String = OffsetDateTime.parse(quoted.trim('"')).toInstant().toEpochMilli().toString()

    private fun locate(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, relative)
            if (f.isFile) return f
            dir = dir.parentFile
        }
        error("$relative not found above ${System.getProperty("user.dir")}")
    }

    private companion object {
        val TOKEN = Regex("\"(\\w+)\":\\s*(null|\"[^\"]*\"|-?[0-9][0-9.eE+-]*)")
    }
}
