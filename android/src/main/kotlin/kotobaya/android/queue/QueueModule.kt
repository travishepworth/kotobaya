package kotobaya.android.queue

import kotobaya.android.di.FeatureEntry
import org.koin.dsl.module

/** Koin wiring of the queue loader (system clock; tests construct the loader directly). */
val queueFeature = FeatureEntry(
    id = "queue",
    module = module { factory { LocalQueueLoader(get(), System::currentTimeMillis) } },
)
