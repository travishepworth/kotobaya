package kotobaya.android.sync.content

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.github.luben.zstd.ZstdOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotobaya.android.decks.DeckContent
import kotobaya.android.decks.DeckRepository
import kotobaya.android.queue.LocalQueueLoader
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.memoryDriver
import kotobaya.db.app.AppDatabase

/** AND-DECKCONTENT: deck-item and media-content archives (SNAPSHOT.md 7) install on the device. */
class ContentInstallerTest {
    private val driver: SqlDriver = memoryDriver()
    private val store = SqlDelightDeviceStore(driver)
    private val tmp: File = Files.createTempDirectory("content").toFile()
    private val blobs = HashMap<String, ByteArray>()
    private val fetched = ArrayList<String>()
    private val logged = ArrayList<String>()
    private val now = 1_800_000_000_000L

    private val fetcher = SnapshotFetcher { hash ->
        fetched += hash
        val bytes = blobs[hash] ?: return@SnapshotFetcher FetchResult.Transient("404")
        FetchResult.Ok(File(tmp, "$hash-${fetched.size}").apply { writeBytes(bytes) })
    }
    private val installer = ContentInstaller(store, driver, fetcher, { logged += it }, { now })

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    // ------------------------------------------------------------------ fixtures

    private val listDeck = "0193a1b2-0009-7000-8000-000000000004"
    private val mediaId = "0193a1b2-0007-7000-8000-000000000001"
    private val seg0 = "0193a1b2-0007-7000-8000-0000000005a0"
    private val sentA = "0193a1b2-0007-7000-8000-0000000005b0"
    private val sentB = "0193a1b2-0007-7000-8000-0000000005b1"

    private fun zstd(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZstdOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Publishes [text] as a blob; returns its hash. */
    private fun blob(text: String): String = zstd(text).let { b -> sha(b).also { blobs[it] = b } }

    private fun listItems(deckId: String, version: Int, seqs: List<Long>, type: String = "list"): String = buildString {
        append("{\"content_version\":$version,\"counts\":{\"item\":${seqs.size}},\"deck_id\":\"$deckId\",\"deck_type\":\"$type\",")
        append("\"format\":\"kotobaya-deck-items\",\"format_version\":1,\"record\":\"header\"}\n")
        seqs.forEachIndexed { i, s -> append("{\"entry_seq\":$s,\"occurrence_count\":0,\"position\":${1024 * (i + 1)},\"record\":\"item\"}\n") }
    }

    private fun mediaItems(version: Int): String =
        "{\"content_version\":$version,\"counts\":{\"item\":3},\"deck_id\":\"$mediaId\",\"deck_type\":\"media\"," +
            "\"format\":\"kotobaya-deck-items\",\"format_version\":1,\"record\":\"header\"}\n" +
            "{\"entry_seq\":1101220,\"occurrence_count\":2,\"position\":1,\"record\":\"item\"}\n" +
            "{\"entry_seq\":1358280,\"occurrence_count\":1,\"position\":2,\"record\":\"item\"}\n" +
            "{\"entry_seq\":2028920,\"occurrence_count\":1,\"position\":3,\"record\":\"item\"}\n"

    private fun tok(s: Int, e: Int, seq: Long?, cls: String = "content") =
        "{\"class\":\"$cls\",\"e\":$e,\"s\":$s,\"seq\":${seq ?: "null"},\"surface\":\"x\"}"

    /** One segment, two sentences: パン(1101220)×2, 食べる(1358280), は(2028920), one unmapped name. */
    private fun mediaContent(version: Int): String =
        "{\"content_version\":$version,\"counts\":{\"grammar_span\":1,\"occurrence\":3,\"segment\":1,\"sentence\":2}," +
            "\"format\":\"kotobaya-media-content\",\"format_version\":1,\"media_id\":\"$mediaId\",\"record\":\"header\"}\n" +
            "{\"id\":\"$seg0\",\"kind\":\"episode\",\"ordinal\":0,\"record\":\"segment\",\"sentence_count\":2,\"title\":\"第1話\"," +
            "\"token_count\":5,\"unmapped_token_count\":1}\n" +
            "{\"audio_blob_hash\":null,\"end_ms\":2000,\"id\":\"$sentA\",\"image_blob_hash\":null,\"location\":null,\"ordinal\":0," +
            "\"origin\":\"media\",\"record\":\"sentence\",\"segment_id\":\"$seg0\",\"start_ms\":1000,\"text\":\"パンを食べる。\"," +
            "\"tokens\":[${tok(0, 2, 1101220)},${tok(3, 6, 1358280)}]}\n" +
            "{\"audio_blob_hash\":null,\"end_ms\":null,\"id\":\"$sentB\",\"image_blob_hash\":null,\"location\":{\"page\":3},\"ordinal\":1," +
            "\"origin\":\"media\",\"record\":\"sentence\",\"segment_id\":\"$seg0\",\"start_ms\":null,\"text\":\"フリーレンはパン\"," +
            "\"tokens\":[${tok(0, 5, null, "name")},${tok(5, 6, 2028920, "particle")},${tok(6, 8, 1101220)}]}\n" +
            "{\"count\":2,\"entry_seq\":1101220,\"pos_class\":\"content\",\"record\":\"occurrence\",\"segment_id\":\"$seg0\"," +
            "\"sentence_ids\":[\"$sentA\",\"$sentB\"]}\n" +
            "{\"count\":1,\"entry_seq\":1358280,\"pos_class\":\"content\",\"record\":\"occurrence\",\"segment_id\":\"$seg0\"," +
            "\"sentence_ids\":[\"$sentA\"]}\n" +
            "{\"count\":1,\"entry_seq\":2028920,\"pos_class\":\"particle\",\"record\":\"occurrence\",\"segment_id\":\"$seg0\"," +
            "\"sentence_ids\":[\"$sentB\"]}\n" +
            "{\"end_offset\":6,\"grammar_point_id\":\"0193a1b2-0006-7000-8000-000000000001\",\"record\":\"grammar_span\"," +
            "\"rule_index\":0,\"sentence_id\":\"$sentB\",\"start_offset\":5}\n"

    private fun exec(sql: String) {
        driver.execute(null, sql, 0)
    }

    private fun long(sql: String): Long =
        driver.executeQuery(null, sql, { c -> c.next(); QueryResult.Value(c.getLong(0) ?: -1L) }, 0).value

    private fun strings(sql: String): List<String> = driver.executeQuery(null, sql, { c ->
        val out = ArrayList<String>()
        while (c.next().value) out += c.getString(0).orEmpty()
        QueryResult.Value(out)
    }, 0).value

    private fun deck(id: String, type: String, version: Int, hash: String?, deletedAt: Long? = null, name: String = "D") = exec(
        "INSERT INTO deck (id, name, type, content_version, content_blob_hash, created_at, updated_at, deleted_at) VALUES " +
            "('$id', '$name', '$type', $version, ${hash?.let { "'$it'" } ?: "NULL"}, 77, 0, ${deletedAt ?: "NULL"})",
    )

    private fun publishDeck(id: String, version: Int, hash: String) =
        exec("UPDATE deck SET content_version = $version, content_blob_hash = '$hash' WHERE id = '$id'")

    private fun media(version: Int, hash: String?) = exec(
        "INSERT INTO media (id, title, kind, source, content_version, content_blob_hash, created_at, updated_at) VALUES " +
            "('$mediaId', 'Fatal', 'anime', 'subtitles', $version, ${hash?.let { "'$it'" } ?: "NULL"}, 0, 0)",
    )

    private fun installed(table: String, id: String) = long("SELECT installed_content_version FROM $table WHERE id = '$id'")

    private fun items(deckId: String) =
        strings("SELECT item_key FROM deck_item WHERE deck_id = '$deckId' AND deleted_at IS NULL ORDER BY position")

    private fun learningSnapshot(): List<Long> = listOf(
        long("SELECT COUNT(*) FROM pending_op"),
        long("SELECT COUNT(*) FROM card"),
        long("SELECT COUNT(*) FROM note"),
        long("SELECT COUNT(*) FROM review_log"),
    )

    // ------------------------------------------------------------------ acceptance

    @Test
    fun deckItemsAndMediaContentInstallWithHeaderCountsAndRerunIsANoOp() {
        val listHash = blob(listItems(listDeck, 1, listOf(2028920, 1358280, 1101220)))
        deck(listDeck, "list", 1, listHash)
        val contentHash = blob(mediaContent(1))
        media(1, contentHash)
        val mediaDeckHash = blob(mediaItems(1))
        deck(mediaId, "media", 1, mediaDeckHash)
        val before = learningSnapshot()

        assertEquals(3, installer.pending().size)
        val s = installer.runAll()
        assertEquals(3, s.installed)
        assertEquals(0, s.rejected + s.retry)

        assertEquals(listOf("e:2028920", "e:1358280", "e:1101220"), items(listDeck))
        assertEquals(listOf("e:1101220", "e:1358280", "e:2028920"), items(mediaId))
        assertEquals(77L, long("SELECT MIN(added_at) FROM deck_item WHERE deck_id = '$listDeck'"))
        assertEquals(2L, long("SELECT occurrence_count FROM deck_item WHERE deck_id = '$mediaId' AND entry_seq = 1101220"))
        assertEquals(1L, installed("deck", listDeck))
        assertEquals(1L, installed("deck", mediaId))

        assertEquals(1L, long("SELECT COUNT(*) FROM media_segment WHERE media_id = '$mediaId'"))
        assertEquals(2L, long("SELECT COUNT(*) FROM sentence WHERE media_id = '$mediaId'"))
        assertEquals(3L, long("SELECT COUNT(*) FROM occurrence WHERE media_id = '$mediaId'"))
        assertEquals(1L, long("SELECT COUNT(*) FROM sentence_grammar_span WHERE media_id = '$mediaId' AND origin = 'detected'"))
        assertEquals(listOf("{\"page\":3}"), strings("SELECT location FROM sentence WHERE id = '$sentB'"))
        assertEquals(listOf("[\"$sentA\",\"$sentB\"]"), strings("SELECT sentence_ids FROM occurrence WHERE entry_seq = 1101220"))
        assertTrue(strings("SELECT tokens FROM sentence WHERE id = '$sentA'").single().startsWith("[{"))
        assertEquals(1L, installed("media", mediaId))
        assertEquals(before, learningSnapshot())

        // re-run: nothing pending, nothing fetched, rows unchanged
        val fetches = fetched.size
        assertEquals(emptyList(), installer.pending())
        assertEquals(0, installer.runAll().installed)
        assertEquals(fetches, fetched.size)
        assertEquals(3, items(listDeck).size)
    }

    @Test
    fun hashMismatchInstallsNothingAndLogs() {
        val good = listItems(listDeck, 1, listOf(1, 2))
        val claimed = "ab".repeat(32)
        blobs[claimed] = zstd(good) // bytes whose SHA-256 is not the claimed hash
        deck(listDeck, "list", 1, claimed)

        val r = installer.install(installer.pending().single())
        assertIs<InstallResult.Rejected>(r)
        assertEquals(emptyList(), items(listDeck))
        assertEquals(0L, installed("deck", listDeck))
        assertTrue(logged.any { "hash" in it && listDeck in it }, logged.toString())
        // rejected for this hash: not retried until a new version is announced
        assertEquals(emptyList(), installer.pending())
        assertEquals(DeckContent.FAILED, repo().deck(listDeck)?.content)
        // the user's Retry: pending (and DOWNLOADING) again
        installer.clearRejected(ContentKind.DECK_ITEMS, listDeck)
        assertEquals(listOf(listDeck), installer.pending().map { it.id })
        assertEquals(DeckContent.DOWNLOADING, repo().deck(listDeck)?.content)
    }

    @Test
    fun headerMismatchInstallsNothingAndLogs() {
        // archive for another deck id
        val h1 = blob(listItems("0193a1b2-0009-7000-8000-0000000000ff", 1, listOf(1, 2)))
        deck(listDeck, "list", 1, h1)
        assertIs<InstallResult.Rejected>(installer.install(installer.pending().single()))
        // archive says version 2, the row announced 1
        val other = "0193a1b2-0009-7000-8000-000000000005"
        deck(other, "list", 1, blob(listItems(other, 2, listOf(1, 2))))
        // archive says media deck, the row is a list deck
        val third = "0193a1b2-0009-7000-8000-000000000006"
        deck(third, "list", 1, blob(listItems(third, 1, listOf(1), type = "media").replace("\"occurrence_count\":0", "\"occurrence_count\":1").replace("\"position\":1024", "\"position\":1")))
        val s = installer.runAll()
        assertEquals(2, s.rejected)
        for (d in listOf(listDeck, other, third)) {
            assertEquals(emptyList(), items(d))
            assertEquals(0L, installed("deck", d))
        }
        assertEquals(3, logged.size, logged.toString())
    }

    @Test
    fun invalidArchiveInstallsNothing() {
        // counts say 3, only 2 items (truncated at a line boundary)
        val text = listItems(listDeck, 1, listOf(1, 2)).replace("\"item\":2", "\"item\":3")
        deck(listDeck, "list", 1, blob(text))
        assertIs<InstallResult.Rejected>(installer.install(installer.pending().single()))
        assertEquals(emptyList(), items(listDeck))
        assertEquals(0L, installed("deck", listDeck))
        assertEquals(1, logged.size)
    }

    @Test
    fun newerContentVersionReplacesOldItemsAtomically() {
        deck(listDeck, "list", 1, blob(listItems(listDeck, 1, listOf(10, 20, 30))))
        installer.runAll()
        assertEquals(listOf("e:10", "e:20", "e:30"), items(listDeck))

        // v2 is announced but broken: v1 rows stay
        val broken = blob(listItems(listDeck, 2, listOf(20, 40)).replace("\"item\":2", "\"item\":5"))
        publishDeck(listDeck, 2, broken)
        assertEquals(DeckContent.DOWNLOADING, repo().deck(listDeck)?.content)
        installer.runAll()
        assertEquals(listOf("e:10", "e:20", "e:30"), items(listDeck))
        assertEquals(1L, installed("deck", listDeck))

        // v3 replaces every row
        publishDeck(listDeck, 3, blob(listItems(listDeck, 3, listOf(20, 40))))
        assertEquals(1, installer.runAll().installed)
        assertEquals(listOf("e:20", "e:40"), items(listDeck))
        assertEquals(3L, installed("deck", listDeck))
        assertEquals(DeckContent.READY, repo().deck(listDeck)?.content)
    }

    @Test
    fun aRowThatMovedOnDuringTheDownloadIsNotInstalled() {
        val v1 = blob(listItems(listDeck, 1, listOf(1)))
        deck(listDeck, "list", 1, v1)
        val p = installer.pending().single()
        publishDeck(listDeck, 2, blob(listItems(listDeck, 2, listOf(2))))
        assertIs<InstallResult.Stale>(installer.install(p))
        assertEquals(emptyList(), items(listDeck))
        assertEquals(0L, installed("deck", listDeck))
        installer.runAll()
        assertEquals(listOf("e:2"), items(listDeck))
    }

    @Test
    fun tombstonedAndUserDecksAreSkippedAndTransientFailuresRetry() {
        deck(listDeck, "list", 1, blob(listItems(listDeck, 1, listOf(1))), deletedAt = 5)
        deck("0193a1b2-0009-7000-8000-000000000007", "user", 0, null)
        deck("0193a1b2-0009-7000-8000-000000000008", "list", 0, null) // not published yet
        assertEquals(emptyList(), installer.pending())

        val missing = "0193a1b2-0009-7000-8000-000000000009"
        deck(missing, "list", 1, "cd".repeat(32)) // blob not on the server (yet)
        val s = installer.runAll()
        assertEquals(1, s.retry)
        assertEquals(listOf(missing), installer.pending().map { it.id }) // still pending
        assertEquals(DeckContent.DOWNLOADING, repo().deck(missing)?.content)
        assertEquals(DeckContent.NOT_PUBLISHED, repo().deck("0193a1b2-0009-7000-8000-000000000008")?.content)
        assertEquals(DeckContent.READY, repo().deck("0193a1b2-0009-7000-8000-000000000007")?.content)
        assertEquals(null, repo().deck(listDeck)) // tombstoned: hidden
    }

    @Test
    fun mediaContentReplacesOnlyDetectedSpans() {
        val gp = "0193a1b2-0006-7000-8000-000000000002"
        exec(
            "INSERT INTO sentence_grammar_span (sentence_id, grammar_point_id, start_offset, end_offset, origin, created_at, hlc) " +
                "VALUES ('$sentA', '$gp', 0, 2, 'manual', 1, 'h')",
        )
        media(1, blob(mediaContent(1)))
        installer.runAll()
        exec("UPDATE media SET content_version = 2, content_blob_hash = '${blob(mediaContent(2))}' WHERE id = '$mediaId'")
        installer.runAll()
        assertEquals(2L, installed("media", mediaId))
        assertEquals(1L, long("SELECT COUNT(*) FROM sentence_grammar_span WHERE origin = 'detected'"))
        assertEquals(1L, long("SELECT COUNT(*) FROM sentence_grammar_span WHERE origin = 'manual'"))
        assertEquals(2L, long("SELECT COUNT(*) FROM sentence"))
    }

    /** The contract's worked examples (real producer bytes, `.jsonl.zst`) install with their header counts. */
    @Test
    fun contractExampleArchivesInstall() {
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "contracts/db/media/examples") }.first { it.isDirectory }
        fun example(name: String): String = File(dir, name).readBytes().let { b -> sha(b).also { blobs[it] = b } }
        deck(listDeck, "list", 2, example("list-deck.deck-items.v1.jsonl.zst"))
        media(1, example("new-media.content.v1.jsonl.zst"))
        deck(mediaId, "media", 1, example("new-media.deck-items.v1.jsonl.zst"))
        assertEquals(InstallRunSummary(installed = 3), installer.runAll())
        assertEquals(listOf("e:2028920", "e:1358280", "e:1101220"), items(listDeck))
        assertEquals(6, items(mediaId).size)
        assertEquals(2L, long("SELECT COUNT(*) FROM media_segment"))
        assertEquals(3L, long("SELECT COUNT(*) FROM sentence"))
        assertEquals(11L, long("SELECT COUNT(*) FROM occurrence"))
        assertEquals(1L, long("SELECT COUNT(*) FROM sentence_grammar_span"))
    }

    private fun repo() =DeckRepository(driver, LocalQueueLoader(AppDatabase(driver), { now }))
}
