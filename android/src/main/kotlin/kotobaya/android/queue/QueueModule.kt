package kotobaya.android.queue

import kotobaya.android.di.FeatureEntry
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
            }, gate = get())
        }
    },
)
