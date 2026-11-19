package kotobaya.android.media

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.nio.file.Files
import kotobaya.android.db.createAppDatabase
import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.db.app.AppDatabase
import kotobaya.sync.client.apply.DeviceOpApplier
import kotobaya.sync.client.apply.InMemoryLearningTables
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.InMemoryDeviceStore
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.UuidV7Source
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** C-29 / AND-29: the prefetch set picks the right pairs, the pass stores only `ready` answers, the migration adds the table. */
class WordAudioPrefetchTest {
    private val now = 1_760_000_000_000L
    private val day = 86_400_000L
    private val ids = UuidV7Source(Random(9))
    private val store = InMemoryDeviceStore(InMemoryLearningTables.seeded()) { it.copy() }
    private val minter = OpMinter(store, DeviceOpApplier<InMemoryLearningTables>(), Clock { now }, ids)
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { createAppDatabase(it) }
    private val dir: File = Files.createTempDirectory("blobs").toFile()
    private val blobs = BlobStore(driver, dir)
    private val table = TtsAudioTable(driver)

    init {
        store.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, "01931f3e-0000-7000-8000-0000000000a1") }
    }

    private fun obj(vararg p: Pair<String, JsonValue>) = JsonObject(linkedMapOf(*p))

    private fun hash(c: Char) = c.toString().repeat(64)

    /** A vocab note with [fields] and one `meaning` card; returns the card id. */
    private fun card(vararg fields: Pair<String, String>): String {
        val noteId = ids.newOpId(now)
        assertIs<MintResult.Minted>(
            minter.mint(
                OpType.NOTE_CREATED,
                obj(
                    "id" to JsonString(noteId),
                    "note_type_id" to JsonString(Reserved.NOTE_TYPE_VOCAB),
                    "fields" to obj(*fields.map { (k, v) -> k to JsonString(v) }.toTypedArray()),
                ),
            ),
        )
        val cardId = ids.newOpId(now)
        assertIs<MintResult.Minted>(
            minter.mint(OpType.CARD_CREATED, obj("id" to JsonString(cardId), "note_id" to JsonString(noteId), "kind" to JsonString("meaning"))),
        )
        return cardId
    }

    private fun due(cardId: String, at: Long) = store.transaction { tx ->
        val row = checkNotNull(tx.learning.get(LearningTable.CARD, listOf(JsonString(cardId))))
        tx.learning.put(LearningTable.CARD, row + mapOf("state" to JsonString("review"), "due_at" to JsonNumber(at.toDouble())))
    }

    private fun set(newCards: List<String>) = WordAudioSet(store, entry = { null }, newWork = { NewWork(newCards, emptyList()) })

    @Test
    fun prefetchSetHasNewCardsFirstThenDueWindowOnly() {
        val fresh = card("reading" to "あたらしい")
        val soon = card("reading" to "たべる")
        val sooner = card("reading" to "のむ")
        val own = card("audio" to "[sound:${hash('a')}]", "reading" to "いる")
        val far = card("reading" to "みる")
        val suspended = card("reading" to "くる")
        due(soon, now + 3 * day)
        due(sooner, now + day)
        due(own, now + 2 * day)
        due(far, now + 30 * day)
        due(suspended, now + day)
        assertIs<MintResult.Minted>(minter.mint(OpType.CARD_SUSPENDED, obj("id" to JsonString(suspended))))

        val plan = set(listOf(fresh)).compute(now)
        assertEquals(
            listOf(
                WantedAudio.Pair("3", "あたらしい"), // part (b) first
                WantedAudio.Pair("3", "のむ"), // then (a) by due time
                WantedAudio.NoteBlob(hash('a')),
                WantedAudio.Pair("3", "たべる"),
            ),
            plan.items,
        )
        assertEquals(1, plan.newCount)
    }

    @Test
    fun passAsksOnlyUnknownOrStalePairsAndKeepsOnlyReadyAnswers() {
        val fresh = card("reading" to "あたらしい")
        val soon = card("reading" to "たべる")
        val stale = card("reading" to "のむ")
        val own = card("audio" to "[sound:${hash('a')}]")
        due(soon, now + day)
        due(stale, now + 2 * day)
        due(own, now + 3 * day)
        table.upsert("3", "たべる", hash('c'), 500, now - day) // known and fresh: not asked again
        table.upsert("3", "のむ", hash('d'), 500, now - 8 * day) // older than 7 days: asked again

        val asked = mutableListOf<List<String>>()
        val resolver = SpeechResolver { pairs ->
            asked += pairs.map { it.text }
            pairs.map { p -> if (p.text == "あたらしい") SpeechAnswer("ready", hash('e'), 640) else SpeechAnswer("pending", null, null) }
        }
        val summary = WordAudioPrefetch(set(listOf(fresh)), table, blobs, resolver) { now }.run()

        assertEquals(listOf(listOf("あたらしい", "のむ")), asked)
        assertEquals(2, summary.asked)
        assertEquals(1, summary.ready)
        assertEquals(TtsAudioRow(hash('e'), 640, now), table.get("3", "あたらしい"))
        assertEquals(hash('d'), table.get("3", "のむ")?.blobHash) // pending keeps the old row
        assertNull(table.get("3", "ぜんぜん"))
        // the ready blob and the note's own blob are wanted for the downloader
        val wanted = blobs.entries().filter { it.state == BlobState.WANTED }.map { it.hash }.toSet()
        assertEquals(setOf(hash('e'), hash('a')), wanted)
    }

    @Test
    fun failedCallStopsThePassWithoutWritingRows() {
        card("reading" to "あたらしい").let { due(it, now + day) }
        val summary = WordAudioPrefetch(set(emptyList()), table, blobs, { null }) { now }.run()
        assertTrue(summary.failed)
        assertNull(table.get("3", "あたらしい"))
    }

    @Test
    fun lookupReportsHashOnlyWhenBytesCanBeHad() {
        table.upsert("3", "たべる", hash('c'), 500, now)
        var online = false
        val lookup = DeviceWordAudioLookup(table, blobs) { online }
        assertNull(lookup.blobHash("3", "のむ")) // no row
        assertNull(lookup.blobHash("3", "たべる")) // row, not cached, offline
        online = true
        assertEquals(hash('c'), lookup.blobHash("3", "たべる")) // online: the intercept downloads it
        online = false
        blobs.want(hash('c'), BlobReason.CARD, now)
        blobs.markCached(hash('c'), 10, "audio/wav", now)
        assertEquals(hash('c'), lookup.blobHash("3", "たべる")) // cached: playable offline
    }

    @Test
    fun migrationFromVersion1AddsTheTable() {
        val d = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(d)
        d.execute(null, "DROP TABLE tts_audio", 0)
        assertEquals(2L, AppDatabase.Schema.version)
        AppDatabase.Schema.migrate(d, 1, 2)
        TtsAudioTable(d).upsert("3", "たべる", hash('c'), 1, 2)
        val n = d.executeQuery(null, "SELECT count(*) FROM tts_audio", { c -> QueryResult.Value(if (c.next().value) c.getLong(0) else null) }, 0).value
        assertEquals(1L, n)
    }
}
