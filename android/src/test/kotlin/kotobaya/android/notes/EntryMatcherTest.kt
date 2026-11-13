package kotobaya.android.notes

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotobaya.android.dict.DictStore
import kotobaya.android.dict.Fx
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.dict.PackageFixtures
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Matching on a fixture dictionary package, through the real [InstalledDictionary] (T302). */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class EntryMatcherTest {
    private val entries = listOf(
        Fx(1358280, "食べる", "たべる", listOf("to eat"), 100),
        Fx(1489780, "猫", "ねこ", listOf("cat"), 200),
        Fx(1600010, "ネコ", "ネコ", listOf("cat (colloquial)"), 900, common = false, kanjiForm = null),
        // three homophones read はし: 端 is rarer than 橋, 箸 is not common
        Fx(1489220, "橋", "はし", listOf("bridge"), 500),
        Fx(1489230, "端", "はし", listOf("edge"), 700),
        Fx(1489210, "箸", "はし", listOf("chopsticks"), 300, common = false),
        Fx(1000050, "古い", "ふるい", listOf("old"), 50, retired = true),
    )

    private fun installed(list: List<Fx> = entries): InstalledDictionary {
        val dir = File.createTempFile("match", "").also { it.delete(); it.mkdirs() }
        val store = DictStore(File(dir, "store"))
        PackageFixtures.create(File(dir, "p.db"), 1, "top60k", list)
        File(dir, "p.db").copyTo(store.installed)
        return InstalledDictionary(store)
    }

    private fun matcher(dict: InstalledDictionary = installed()) = EntryMatcher(InstalledCandidateSource(dict))

    @Test
    fun kanjiAndReadingHit() {
        val m = assertIs<MatchResult.Matched>(matcher().match("食べる", "たべる"))
        assertEquals(1358280L, m.entry.seq)
        assertEquals(MatchTier.WORD_AND_READING, m.tier)
        assertEquals(false, m.ambiguous)
        assertEquals(listOf("to eat"), m.entry.dictionaryData().glosses)
        // full-width / surrounding space is normalised; katakana reading folds to hiragana
        assertEquals(1358280L, (matcher().match(" 食べる ", "タベル") as MatchResult.Matched).entry.seq)
        // a reading the entry does not have still matches the word, one tier down
        assertEquals(MatchTier.WORD, (matcher().match("食べる", "くう") as MatchResult.Matched).tier)
    }

    @Test
    fun readingOnlyIsAmbiguousWithAStableTieBreak() {
        val dict = installed()
        val m = assertIs<MatchResult.Matched>(matcher(dict).match(null, "はし"))
        assertEquals(MatchTier.READING, m.tier)
        // common first (橋 500, 端 700), then rank, then seq; 箸 (not common) last
        assertEquals(1489220L, m.entry.seq)
        assertEquals(listOf(1489230L, 1489210L), m.alternatives)
        assertTrue(m.ambiguous)
        // same answer again, and from a package whose rows were inserted in another order
        assertEquals(m, matcher(dict).match(null, "はし"))
        assertEquals(m.entry.seq, (matcher(installed(entries.reversed())).match(null, "はし") as MatchResult.Matched).entry.seq)
        // the kana word written as the reading picks the same entry
        assertEquals(1489220L, (matcher(dict).match("はし", "はし") as MatchResult.Matched).entry.seq)
        // a kana headword wins for its own spelling: ネコ is the entry written ネコ, not 猫
        assertEquals(1600010L, (matcher(dict).match("ネコ", null) as MatchResult.Matched).entry.seq)
        assertEquals(1489780L, (matcher(dict).match("猫", "ねこ") as MatchResult.Matched).entry.seq)
    }

    @Test
    fun noMatch() {
        val m = matcher()
        assertEquals(MatchResult.NoMatch, m.match("ぴえん", "ぴえん"))
        // a kanji word that is not in the dictionary is not matched to a homophone by its reading
        assertEquals(MatchResult.NoMatch, m.match("嘴", "はし"))
        // retired entries are never matched
        assertEquals(MatchResult.NoMatch, m.match("古い", "ふるい"))
        assertEquals(MatchResult.NoMatch, m.match(null, null))
        // no dictionary installed
        val empty = InstalledDictionary(DictStore(File.createTempFile("none", "").also { it.delete(); it.mkdirs() }))
        assertEquals(MatchResult.NoMatch, EntryMatcher(InstalledCandidateSource(empty)).match("食べる", "たべる"))
    }
}
