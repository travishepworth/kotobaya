package kotobaya.android.shell.parity

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotobaya.android.dict.DictEntryDetail
import kotobaya.android.dict.KanjiDetail
import kotobaya.android.dict.PackageInfo
import kotobaya.android.dict.PackageSource

/**
 * Step 1 of L-7 over JDBC for the plain-JVM parity harness (production: `InstalledDictionary`, which needs Android
 * `SQLiteDatabase`). Release, scope and presence work here; the detail reads build `org.json` values, which the plain
 * JVM unit-test classpath only has as Android stubs, so they are left to the ticket whose handler first needs them.
 */
class JdbcPackageSource(private val file: File) : PackageSource {
    init {
        Class.forName("org.sqlite.JDBC")
    }

    private fun <T> withConnection(block: (Connection) -> T): T? {
        if (!file.isFile) return null
        return DriverManager.getConnection("jdbc:sqlite:${file.path}").use(block)
    }

    override fun info(): PackageInfo? = withConnection { c ->
        val uv = c.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
        c.createStatement().use { s ->
            s.executeQuery("SELECT id, scope, version FROM dict_release").use { r ->
                if (!r.next()) null else PackageInfo(r.getLong(1), r.getString(2), r.getString(3), uv).takeIf { !r.next() }
            }
        }
    }

    override fun packageEntry(seq: Long): DictEntryDetail? =
        throw UnsupportedOperationException("JdbcPackageSource: entry details need org.json on the JVM classpath")

    override fun packageKanji(literal: String): KanjiDetail? =
        throw UnsupportedOperationException("JdbcPackageSource: kanji details need org.json on the JVM classpath")

    override fun presentEntries(seqs: Collection<Long>): Set<Long> =
        present(seqs.distinct().map { it.toString() }, "SELECT seq FROM dict_entry WHERE seq IN ") { it.toLong() }

    override fun presentKanji(literals: Collection<String>): Set<String> =
        present(literals.distinct(), "SELECT literal FROM kanji WHERE literal IN ") { it }

    private fun <K> present(keys: List<String>, select: String, parse: (String) -> K): Set<K> {
        if (keys.isEmpty()) return emptySet()
        return withConnection { c ->
            val out = HashSet<K>()
            for (chunk in keys.chunked(500)) {
                c.prepareStatement(select + chunk.joinToString(",", "(", ")") { "?" }).use { ps ->
                    chunk.forEachIndexed { i, k -> ps.setString(i + 1, k) }
                    ps.executeQuery().use { r -> while (r.next()) out += parse(r.getString(1)) }
                }
            }
            out
        } ?: emptySet()
    }
}
