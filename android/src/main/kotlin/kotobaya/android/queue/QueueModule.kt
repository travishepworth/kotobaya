package kotobaya.android.queue

import kotobaya.android.di.FeatureEntry
import kotobaya.sync.client.store.bookkeeping
import org.koin.dsl.module

/** Koin wiring of the queue loader (system clock; tests construct the loader directly). */
val queueFeature = FeatureEntry(
    id = "queue",
    module = module {
        // AND-PERF: one gate for every loader, so home, widget, notifications and review conflate their builds
        single { LoadGate() }
        factory {
            val koin = getKoin()
            LocalQueueLoader(get(), System::currentTimeMillis, awaitReady = {
                // readers wait for the startup replay (SYNC.md 10.5); the sync module may be absent in tests
                koin.getOrNull<kotobaya.android.sync.SyncBootstrap>()?.ready?.takeUnless { it.isCompleted }
                    ?.let { kotlinx.coroutines.runBlocking { it.await() } }
            }, gate = get(), materializer = object : EntryMaterializer {
                // resolved per call: the notes and sync modules may be absent (tests) and are wired after this one
                private fun notes() = koin.getOrNull<EntryMaterializer>()

                override fun materialize(entries: List<kotobaya.contracts.queue.PendingEntry>): Int = notes()?.materialize(entries) ?: 0

                override fun knows(entrySeq: Long): Boolean = notes()?.knows(entrySeq) ?: false

                override fun ready(): Boolean {
                    if (notes()?.ready() != true) return false
                    val pairing = koin.getOrNull<kotobaya.android.pairing.PairingRepository>() ?: return true
                    val paired = pairing.isPaired
                    if (!paired) return true
                    val coordinator = koin.getOrNull<kotobaya.android.sync.SyncCoordinator>() ?: return false
                    val store = koin.getOrNull<kotobaya.android.sync.SqlDelightDeviceStore>() ?: return false
                    val (lastSyncAt, rebuild) = store.read { it.bookkeeping.lastSyncAt to it.bookkeeping.rebuildPending }
                    return materializeAllowed(paired, coordinator.running.value, coordinator.consecutiveFailures, lastSyncAt, rebuild)
                }
            })
        }
    },
)
