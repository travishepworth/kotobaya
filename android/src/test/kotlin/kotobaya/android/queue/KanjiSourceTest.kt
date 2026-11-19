package kotobaya.android.queue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.dict.InstalledDictionary
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The dictionary reads of a build are batched and cached (misses too) until the dictionary generation changes. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class KanjiSourceTest {
    private class Fake : KanjiDictionary {
        override var generation: Long = 1
        var formCalls = 0
        var factCalls = 0

        override fun formJson(seqs: Collection<Long>): Map<Long, InstalledDictionary.FormJson> {
            formCalls++
            // even seqs exist: a form with two elements of the same text and a uk sense that applies to every form
            return seqs.filter { it % 2 == 0L }.associateWith {
                InstalledDictionary.FormJson(
                    """[{"text":"寿司","tags":["ateji"]},{"text":"寿司","tags":["rK"]}]""",
                    """[{"applies_to_kanji":["*"],"misc":["uk"]}]""",
                )
            }
        }

        override fun kanjiFacts(literals: Collection<String>): Map<String, Pair<Int?, Int?>> {
            factCalls++
            return literals.associateWith { 8 to null }
        }
    }

    @Test
    fun aBuildOver10kNotesIsOneBatchedPassAndTheNextBuildReadsNothing() {
        val dict = Fake()
        val source = KanjiSource(dict)
        val seqs = (1L..10_000L).toList()
        val t0 = System.nanoTime()
        source.prefetch(seqs, listOf("寿", "司"))
        println("KanjiSource first build over 10k entries: ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertEquals(1, dict.formCalls)
        assertEquals(1, dict.factCalls)
        seqs.forEach { source.form(it, "寿司") }
        listOf("寿", "司").forEach(source::facts)
        assertEquals(1, dict.formCalls, "hits and misses are cached")
        assertEquals(1, dict.factCalls)
        // union of the tags of every element with that text; uk of the first sense that applies; a miss has neither
        assertEquals(setOf("ateji", "rK"), source.form(2, "寿司").formTags)
        assertTrue(source.form(2, "寿司").usuallyKana)
        assertEquals(emptySet(), source.form(3, "寿司").formTags)
        assertEquals(8, source.facts("寿").grade)
        dict.generation = 2
        source.form(2, "寿司")
        assertEquals(2, dict.formCalls, "a new dictionary generation drops the cache")
    }
}
