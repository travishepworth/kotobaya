package kotobaya.android.app

import android.app.Application
import kotobaya.android.ankiapi.provider.AnkiStore
import kotobaya.android.ankiapi.provider.AnkiStoreHolder
import kotobaya.android.ankiapi.store.DeferredAnkiStore
import kotobaya.android.di.appModule
import kotobaya.android.di.featureEntries
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

open class KotobayaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val koin = startKoin {
            androidContext(this@KotobayaApp)
            modules(listOf(appModule) + featureEntries.mapNotNull { it.module })
        }.koin
        // T305c: eagerly, so a Takoboto call during a cold start finds the store (resolved on first use)
        AnkiStoreHolder.set(DeferredAnkiStore { koin.get<AnkiStore>() })
    }
}
