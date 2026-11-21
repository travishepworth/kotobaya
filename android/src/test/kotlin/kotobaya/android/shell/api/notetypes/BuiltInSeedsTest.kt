package kotobaya.android.shell.api.notetypes

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * LF-A8 review follow-up: the built-in note types `updateNoteType` / `deleteNoteType` check against (the replica's
 * seeded rows, core `BuiltIns`) are the server's `OpRules.BUILTIN_SEEDS` — same ids, same seeded field keys and
 * template kinds — so the device refuses exactly what the server refuses (`409 builtin_immutable`).
 */
class BuiltInSeedsTest {
    /** `BUILTIN_SEEDS` parsed from the server's source (the android tests cannot load the server's classes). */
    private fun serverSeeds(): Map<String, Pair<Set<String>, Set<String>>> {
        val src = File("../server/src/main/kotlin/kotobaya/sync/OpRules.kt").readText()
        val block = src.substringAfter("val BUILTIN_SEEDS").substringBefore("\n        )\n")
        val entry = Regex("\"([0-9a-f-]{36})\" to \\(\\s*setOf\\(([^)]*)\\) to\\s*setOf\\(([^)]*)\\)", RegexOption.MULTILINE)
        fun strings(s: String) = Regex("\"([^\"]+)\"").findAll(s).map { it.groupValues[1] }.toSet()
        return entry.findAll(block).associate { m -> m.groupValues[1] to (strings(m.groupValues[2]) to strings(m.groupValues[3])) }
    }

    @Test
    fun seededKeysAndKindsEqualTheServers() {
        val server = serverSeeds()
        assertTrue(server.size == 6, "parsed ${server.size} server seeds")
        assertEquals(server, SEEDS)
    }
}
