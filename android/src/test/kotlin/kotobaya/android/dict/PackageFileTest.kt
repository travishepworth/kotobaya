package kotobaya.android.dict

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class PackageFileTest {
    private fun tmp(name: String) = File.createTempFile(name, ".db").also { it.delete(); it.deleteOnExit() }

    @Test
    fun deviceSchemaTableListEqualsDictSq() {
        val f = PackageFixtures.create(tmp("schema"), 1, "top60k", emptyList())
        val db = PackageFile.openReadOnly(f)
        try {
            val actual = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", null)
                .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            assertEquals(DictSchema.tables.map { it.name }.sorted(), actual.filterNot { it.startsWith("sqlite_") || it == "android_metadata" })
            for (t in DictSchema.tables) {
                val cols = db.rawQuery("PRAGMA table_info(${t.name})", null).use { c ->
                    buildList { while (c.moveToNext()) add(Triple(c.getString(1), c.getInt(5), c.getInt(0))) }
                }
                assertEquals(t.columns, cols.map { it.first }, t.name)
                // pk columns in PRIMARY KEY order
                assertEquals(t.pk, cols.filter { it.second > 0 }.sortedBy { it.second }.map { it.first }, t.name)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun emptyTableDigestAndExactEncoding() {
        val f = PackageFixtures.create(tmp("digest"), 1, "top60k", emptyList())
        val db = PackageFile.openReadOnly(f)
        try {
            val kanjiTable = DictSchema.tables.first { it.name == "entry_pitch" }
            assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", PackageFile.tableDigest(db, kanjiTable))
            // freq_list holds ('jpdb', 'jpdb frequency', 0): text, text, integer.
            val md = MessageDigest.getInstance("SHA-256")
            fun text(s: String) {
                val b = s.toByteArray()
                md.update(2); md.update(ByteBuffer.allocate(4).putInt(b.size).array()); md.update(b)
            }
            text("jpdb"); text("jpdb frequency")
            md.update(1); md.update(ByteBuffer.allocate(8).putLong(0).array())
            assertEquals(md.digest().toHex(), PackageFile.tableDigest(db, DictSchema.tables.first { it.name == "freq_list" }))
        } finally {
            db.close()
        }
    }
}
