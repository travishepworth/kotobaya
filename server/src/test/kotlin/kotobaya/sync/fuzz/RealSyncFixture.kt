package kotobaya.sync.fuzz

import kotobaya.server.Uuid7
import kotobaya.sync.SyncConfig
import kotobaya.sync.SyncFixture
import kotobaya.sync.merge.CoreCardCache
import kotobaya.sync.merge.DuplicateMerge
import kotlin.test.assertEquals

/**
 * [SyncFixture] with the production hooks of T202c: the real merge policy ([DuplicateMerge]) and the
 * core card cache ([CoreCardCache]). [serverCardIds] feeds the ids of cards the merge creates on a
 * survivor (scenario logs show the ids the server "happened" to pick; otherwise UUIDv7).
 */
abstract class RealSyncFixture : SyncFixture() {
    protected val serverCardIds = ArrayDeque<String>()
    protected val coreCache = CoreCardCache()
    protected open val backupsDisabled = false

    override fun syncConfig() = SyncConfig(
        clock = { syncNow.get() },
        cardCache = coreCache,
        merge = DuplicateMerge { serverCardIds.removeFirstOrNull() ?: Uuid7.generate().toString() },
        backupsDisabled = backupsDisabled,
    )

    protected val server: SimServer by lazy { SimServer(sync, syncNow) { principal(it) } }

    /** Asserts the server and every device replica hold the same SYNC.md 11 projection. */
    protected fun assertConverged(label: String, vararg devices: SimDevice) {
        val sp = ServerProjection.of(db)
        for (d in devices) {
            val dp = d.r.projection()
            assertEquals(emptyList(), ServerProjection.diff(sp, dp), "$label: device ${d.id.takeLast(2)} differs from the server")
        }
    }

    companion object {
        const val C = "01931f3e-0000-7000-8000-0000000000c3"
    }
}
