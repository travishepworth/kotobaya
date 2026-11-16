package kotobaya.android.shell.api.mining

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotobaya.android.dict.DictGapCache
import kotobaya.android.dict.DictLookup
import kotobaya.android.dict.DictStore
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.net.MetaLimits
import kotobaya.android.notes.mergeEmitter
import kotobaya.android.notify.DueTrigger
import kotobaya.android.shell.api.LocalApi
import kotobaya.android.shell.api.LocalRoutes
import kotobaya.android.shell.api.LocalWrites
import kotobaya.android.shell.api.OfflineUpstream
import kotobaya.android.shell.api.ShellGate
import kotobaya.android.shell.api.ShellRequest
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.ShellServices
import kotobaya.android.shell.api.Upstream
import kotobaya.android.shell.api.identity.DictReleaseRef
import kotobaya.android.shell.api.identity.ShellIdentity
import kotobaya.android.shell.parity.ParityDictionary
import kotobaya.android.shell.parity.ParityJson
import kotobaya.android.shell.parity.ParityTemp
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SqlDevice
import kotobaya.android.sync.TestServer
import kotobaya.android.sync.assertSameRows
import kotobaya.android.sync.projection
import kotobaya.android.sync.serverReplica
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpType
import kotobaya.db.app.AppDatabase
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.protocol.CycleResult
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SHELL-L5 acceptance `SameWordTwiceOfflineTest` — the SYNC.md 8.1 worked example through the shell: two phones add
 * the same dictionary entry with `createNote` while offline (neither sees the other's note, so neither is a local
 * duplicate) and each reviews its card. After both sync, the server's merge (`note.merged`, `card.merged`) leaves one
 * live note — the one that reached the server first — with one card per kind holding both reviews, and both phones and
 * the server have the same rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class SameWordTwiceOfflineTest {
    private val t0 = 1_795_168_800_000L
    private val entry = 1000220L
    private val vocab = "00000000-0000-7000-8000-000000000101"

    /** A phone: the production sync stack over [TestServer] and the shell's local API over its replica. */
    private class Phone(id: String, server: TestServer, clockMs: Long, seed: Int) {
        val device = SqlDevice(id, server, clockMs, seed, dbDir = ParityTemp.next("phone"))
        private val store: SqlDelightDeviceStore get() = device.store
        private val dictStore: DictStore = ParityDictionary.fixture.install(ParityTemp.next("phone-dict"))
        private val cache = DictGapCache(store, device.driver).also { it.recordServerRelease(ParityDictionary.fixture.releaseId) }
        private val installed = InstalledDictionary(dictStore, null, cache)
        private val services = mapOf<KClass<*>, Any>(
            SqlDelightDeviceStore::class to store,
            LocalWrites::class to LocalWrites(store, device.stack.minter) {},
            DictStore::class to dictStore,
            DictGapCache::class to cache,
            InstalledDictionary::class to installed,
            DictLookup::class to DictLookup(installed, cache, null),
            Upstream::class to OfflineUpstream,
            AppDatabase::class to AppDatabase(device.driver),
            DueTrigger::class to DueTrigger(background = { it.run() }) { emptyList() },
            ShellIdentity::class to object : ShellIdentity {
                override fun deviceName(): String = "phone"
                override fun tokenId(): String? = null
                override fun serverVersion(): String? = null
                override fun coreVersion(): String = "test"
                override fun dictRelease(): DictReleaseRef? = null
                override fun limits(): MetaLimits = MetaLimits.DEFAULTS
                override fun nowMs(): Long = device.clock.now
            },
        )
        private var n = 0
        val api = LocalApi(
            routes = LocalRoutes.all,
            gate = object : ShellGate {
                override fun isPaired(): Boolean = true
                override fun isRebuildPending(): Boolean = false
                override suspend fun awaitReady() = Unit
            },
            services = object : ShellServices {
                override fun <T : Any> get(type: KClass<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return (services[type] ?: error("no service ${type.simpleName}")) as T
                }
            },
            newRequestId = { "r${++n}" },
        )

        fun post(path: String, body: String): ShellResponse =
            runBlocking { api.handle(ShellRequest("POST", path, headers = mapOf("content-type" to "application/json"), body = body)) }

        fun tables(): LearningTables = device.tables()
    }

    private fun str(v: Any?): String = (v as JsonString).value

    private fun liveCard(t: LearningTables, noteId: String): Row =
        t.where(LearningTable.CARD, "note_id", JsonString(noteId)).single { (it["deleted_at"] ?: JsonNull) == JsonNull }

    @Test
    fun twoPhonesAddTheSameEntryOfflineAndConvergeOnOneSurvivor() {
        val server = TestServer(t0)
        server.emitAfter = mergeEmitter(server)
        val a = Phone("01931f3e-0000-7000-8000-0000000000a7", server, t0, seed = 1)
        val b = Phone("01931f3e-0000-7000-8000-0000000000b7", server, t0 + 5_000, seed = 2)
        a.device.transport.offline = true
        b.device.transport.offline = true

        val na = a.post("/api/v1/notes", """{"note_type_id":"$vocab","entry_seq":$entry,"tags":["phone-a"]}""")
        val nb = b.post("/api/v1/notes", """{"note_type_id":"$vocab","entry_seq":$entry,"source_sentence":"パンを食べる"}""")
        assertEquals(201, na.status)
        assertEquals(201, nb.status, "offline, B cannot see A's note: not a duplicate")
        val noteA = str(ParityJson.obj(ParityJson.parse(na.body!!)).members["id"])
        val noteB = str(ParityJson.obj(ParityJson.parse(nb.body!!)).members["id"])
        val cardA = str(liveCard(a.tables(), noteA)["id"])
        val cardB = str(liveCard(b.tables(), noteB)["id"])

        // each phone reviews its own card offline
        a.device.clock.now += 60_000
        b.device.clock.now += 60_000
        val reviewA = "0193b000-0003-7000-8000-0000000000a1"
        val reviewB = "0193b000-0003-7000-8000-0000000000b1"
        assertEquals(200, a.post("/api/v1/review/answer", """{"id":"$reviewA","card_id":"$cardA","rating":3,"duration_ms":4000,"utc_offset_min":0,"button":"good"}""").status)
        assertEquals(200, b.post("/api/v1/review/answer", """{"id":"$reviewB","card_id":"$cardB","rating":1,"duration_ms":4000,"utc_offset_min":0,"button":"again"}""").status)

        a.device.transport.offline = false
        b.device.transport.offline = false
        server.nowMs = t0 + 120_000
        assertIs<CycleResult.Completed>(a.device.sync())
        assertIs<CycleResult.Completed>(b.device.sync()) // B's note.created collides: merged into A's
        assertIs<CycleResult.Completed>(a.device.sync())
        assertIs<CycleResult.Completed>(b.device.sync())

        val merges = server.loggedOps().filter { it.opType == OpType.NOTE_MERGED || it.opType == OpType.CARD_MERGED }.map { it.opType }
        assertEquals(listOf(OpType.NOTE_MERGED, OpType.CARD_MERGED), merges)

        val reference = projection(serverReplica(server))
        assertSameRows(reference, projection(a.tables()), "phone A")
        assertSameRows(reference, projection(b.tables()), "phone B")

        for ((name, t) in listOf("A" to a.tables(), "B" to b.tables())) {
            val live = t.where(LearningTable.NOTE, "entry_seq", JsonNumber(entry.toDouble()))
                .filter { (it["deleted_at"] ?: JsonNull) == JsonNull && (it["merged_into_id"] ?: JsonNull) == JsonNull }
            assertEquals(listOf(noteA), live.map { str(it["id"]) }, "$name: one survivor, the note that reached the server first")
            assertEquals(JsonString(noteA), t.get(LearningTable.NOTE, listOf(JsonString(noteB)))!!["merged_into_id"], name)
            val survivorNote = t.get(LearningTable.NOTE, listOf(JsonString(noteA)))!!
            assertEquals(JsonString("パンを食べる"), survivorNote["source_sentence"], "$name: the loser's values forwarded (8.2)")
            assertEquals(JsonString(cardA), t.get(LearningTable.CARD, listOf(JsonString(cardB)))!!["merged_into_id"], name)
            // both reviews are kept and the survivor card's state counts both (replayed, SYNC.md 8.3)
            val reviews = t.all(LearningTable.REVIEW_LOG).map { str(it["id"]) }.toSet()
            assertEquals(setOf(reviewA, reviewB), reviews, name)
            assertEquals(JsonNumber(2.0), liveCard(t, noteA)["reps"], "$name: the survivor card replays both reviews")
        }

        // after the merge B's createNote for the entry is a duplicate of the survivor
        val again = b.post("/api/v1/notes", """{"note_type_id":"$vocab","entry_seq":$entry}""")
        assertEquals(409, again.status)
        assertEquals(JsonString(noteA), ParityJson.obj(ParityJson.parse(again.body!!)).members["existing_id"])
    }
}
