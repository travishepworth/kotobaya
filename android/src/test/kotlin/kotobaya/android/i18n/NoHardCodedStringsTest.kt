package kotobaya.android.i18n

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Compose sources must take user-visible text from strings.xml via T(), never a literal. */
class NoHardCodedStringsTest {
    private val literalArg = Regex(
        """\b(Text|text|label|title|contentDescription|placeholder|supportingText)\s*[(=]\s*"[^"$]*[A-Za-z]""",
    )

    @Test
    fun noUserVisibleLiteralsInMainSources() {
        val offenders = File("src/main/kotlin").walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { f ->
                f.readLines().withIndex()
                    .filter { literalArg.containsMatchIn(it.value) }
                    .map { "${f.path}:${it.index + 1}: ${it.value.trim()}" }
            }.toList()
        assertTrue(offenders.isEmpty(), "Hard-coded strings:\n" + offenders.joinToString("\n"))
    }
}
