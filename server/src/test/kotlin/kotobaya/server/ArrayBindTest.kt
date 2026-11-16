package kotobaya.server

import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RC-SERVER root cause of the constant ~6 s `GET /review/queue` and `/stats/summary` (and the 30-80 s known-words): jOOQ
 * binds a Kotlin array as one varchar holding an array literal (`cast(? as varchar[])`). `x = any(?::uuid[])` is then an
 * I/O coercion Postgres never folds to a constant, and in a plan that filters per row (a nested loop's inner side, a seq
 * scan) it re-parses the whole array for every row: 45k deck_item rows x 1,100 note ids = 8 s on prod. The rule: a bound
 * array is matched with `any(array(select unnest(?::T[])))`, an uncorrelated sub-select Postgres evaluates once.
 */
class ArrayBindTest {
    private val db: Database = TestPg.migratedDb()

    @AfterTest
    fun closeDb() = db.close()

    @Test
    fun noServerQueryMatchesABoundArrayWithAnyDirectly() {
        val root = listOf(File("src/main/kotlin"), File("server/src/main/kotlin")).first { it.isDirectory }
        val bad = Regex("""\b(any|all)\s*\(\s*\?""", RegexOption.IGNORE_CASE)
        val hits = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().withIndex().filter { bad.containsMatchIn(it.value) }.map { "${f.path}:${it.index + 1}" }
        }.toList()
        assertEquals(emptyList(), hits, "use any(array(select unnest(?::T[]))) for a bound array (see ArrayBindTest KDoc)")
    }

    /** The rewrite selects exactly the rows the direct form did: empty, single, duplicate, unordered and absent ids. */
    @Test
    fun theOnceEvaluatedFormSelectsExactlyWhatTheDirectFormDid() {
        val ids = List(50) { UUID.nameUUIDFromBytes("n$it".toByteArray()).toString() }
        val table = "unnest(array[${ids.joinToString { "'$it'::uuid" }}]) with ordinality t(g, o)"
        val cases = listOf(
            emptyArray(),
            arrayOf(ids[0]),
            arrayOf(ids[3], ids[3], ids[7]),
            ids.subList(0, 20).reversed().toTypedArray(),
            arrayOf(UUID.randomUUID().toString()),
            ids.toTypedArray(),
        )
        db.txBlocking { dsl ->
            fun run(cond: String, arr: Any) = dsl.fetch("select g::text from $table where $cond order by o", arr).map { it.get(0, String::class.java) }
            for (arr in cases) {
                val direct = run("g = any(?::uuid[])", arr)
                assertEquals(ids.filter { it in arr }, direct, arr.joinToString())
                assertEquals(direct, run("g = any(array(select unnest(?::uuid[])))", arr), arr.joinToString())
            }
            val words = arrayOf("d", "b", "zz", "b")
            val wt = "unnest(array['a','b','c','d']) with ordinality t(w, o)"
            assertEquals(
                dsl.fetch("select w from $wt where w = any(?::text[]) order by o", words).map { it.get(0, String::class.java) },
                dsl.fetch("select w from $wt where w = any(array(select unnest(?::text[]))) order by o", words).map { it.get(0, String::class.java) },
            )
            val nums = arrayOf(3L, 9L, 9L, 42L)
            assertEquals(
                dsl.fetch("select n from generate_series(1, 10) n where n = any(?::bigint[]) order by n", nums).map { (it.get(0) as Number).toLong() },
                dsl.fetch("select n from generate_series(1, 10) n where n = any(array(select unnest(?::bigint[]))) order by n", nums).map { (it.get(0) as Number).toLong() },
            )
        }
    }

    /** A filter evaluated on every row (40k rows against 2,000 ids) still selects correctly (the source-scan guard is the real protection; no wall-clock assertion). */
    @Test
    fun aLargeArrayFilteredPerRowIsParsedOnce() {
        val ids = List(2000) { UUID.nameUUIDFromBytes("k$it".toByteArray()).toString() }.toTypedArray()
        db.txBlocking { dsl ->
            val n = dsl.fetchValue(
                "select count(*) from generate_series(1, 40000) g where md5(g::text)::uuid = any(array(select unnest(?::uuid[])))",
                ids,
            ) as Number
            assertEquals(0L, n.toLong())
        }
    }
}
