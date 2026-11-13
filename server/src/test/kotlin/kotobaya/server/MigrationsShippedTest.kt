package kotobaya.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull

class MigrationsShippedTest {
    @Test
    fun everyContractMigrationIsOnTheClasspath() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "contracts/db").isDirectory) dir = dir.parentFile
        val db = File(requireNotNull(dir) { "contracts/db not found" }, "contracts/db")
        val migrations = db.listFiles { f -> Regex("""V\d+__.*\.sql""").matches(f.name) }.orEmpty()
        check(migrations.isNotEmpty()) { "no migrations found in $db" }
        for (m in migrations) {
            assertNotNull(
                javaClass.classLoader.getResource("db/migration/${m.name}"),
                "${m.name} missing from classpath db/migration",
            )
        }
    }
}
