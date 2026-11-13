package kotobaya.android.app

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import kotobaya.android.ankiapi.provider.AnkiStore
import kotobaya.android.ankiapi.provider.AnkiStoreHolder
import kotobaya.android.ankiapi.store.DeferredAnkiStore
import kotobaya.android.demo.DemoMode
import kotobaya.android.di.appModule
import kotobaya.android.di.featureEntries
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

open class KotobayaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // BUG-8: debug builds log every disk read or write on the main thread (SQLite included)
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        // UI-FOUND-APP: the debug demo-data switch; its module, loaded last, overrides the screens' ports
        val demo = if (debuggable && DemoMode.enabled(this)) listOf(DemoMode.module(this, DemoMode.scenario(this))) else emptyList()
        if (debuggable) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyLog().build())
        }
        val koin = startKoin {
            androidContext(this@KotobayaApp)
            modules(listOf(appModule) + featureEntries.mapNotNull { it.module } + demo)
        }.koin
        // T305c: eagerly, so a Takoboto call during a cold start finds the store (resolved on first use)
        AnkiStoreHolder.set(DeferredAnkiStore { koin.get<AnkiStore>() })
    }
}
