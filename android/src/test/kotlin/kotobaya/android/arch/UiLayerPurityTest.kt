package kotobaya.android.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UI-FOUND-APP: screens are replaceable wholesale. Every file in a `ui` package holds composables that take
 * immutable state plus callbacks only: no Koin, no database, no repositories or stores, no dispatchers (all IO is
 * the ViewModels' job). Composables outside `ui` packages are only route glue (ViewModel lookup and wiring) in
 * `*Feature.kt` / `*Routes.kt` files, the nav shell, activities, the i18n helper, the demo previews and the Glance
 * widgets.
 */
class UiLayerPurityTest {
    private val root = listOf(File("src/main/kotlin"), File("android/src/main/kotlin")).first { it.isDirectory }

    private val sources: List<File> = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun isUi(f: File): Boolean = f.relativeTo(root).invariantSeparatorsPath.split('/').dropLast(1).contains("ui")

    /** Imports and identifiers a view file may not use. */
    private val forbidden = listOf(
        Regex("""^import org\.koin\."""),
        Regex("""^import app\.cash\.sqldelight\."""),
        Regex("""^import android\.database\."""),
        Regex("""^import kotobaya\.db\."""),
        // stores, tables and the minter (value types such as MintRefusal are fine)
        Regex("""^import kotobaya\.sync\.client\.(store|apply)\.|^import kotobaya\.sync\.client\.mint\.OpMinter$"""),
        Regex("""^import kotlinx\.coroutines\.Dispatchers"""),
        Regex(
            """^import kotobaya\.android\..*(Repository|Store|Backend|Source|Port|ApiClient|InstalledDictionary|DictInstaller|""" +
                """Registrar|Controller|Downloader|WordSearch|CardSearch|ReviewActions|ReviewSession|ViewModel)$""",
        ),
        // SharedPreferences-backed settings stores (ReviewPrefs is a plain value)
        Regex("""^import kotobaya\.android\..*\.(?!ReviewPrefs$)\w*Prefs$"""),
        Regex("""\bkoinInject\b|\bgetKoin\b|\bkoinViewModel\b|\bGlobalContext\b"""),
        Regex("""\bDispatchers\.IO\b"""),
    )

    @Test
    fun viewFilesReferenceNoKoinDatabaseOrRepositories() {
        val uiFiles = sources.filter(::isUi)
        assertTrue(uiFiles.size >= 15, "expected the screens under ui/ packages, found ${uiFiles.size} files")
        val problems = uiFiles.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                forbidden.firstOrNull { it.containsMatchIn(line.trim()) }?.let { "${f.relativeTo(root)}:${i + 1}: ${line.trim()}" }
            }
        }
        assertEquals(emptyList(), problems, problems.joinToString("\n"))
    }

    @Test
    fun composablesOutsideUiPackagesAreOnlyGlue() {
        val allowed = Regex("""(Feature|Routes|Activity)\.kt$|/nav/|/di/|/widget/|/i18n/|/demo/""")
        val offenders = sources.filter { !isUi(it) && "@Composable" in it.readText() }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { !allowed.containsMatchIn("/$it") }
        assertEquals(emptyList(), offenders, "move these composables into a ui package:\n" + offenders.joinToString("\n"))
    }
}
