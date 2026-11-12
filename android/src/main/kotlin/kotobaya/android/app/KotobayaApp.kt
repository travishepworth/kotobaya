package kotobaya.android.app

import android.app.Application
import kotobaya.android.di.appModule
import kotobaya.android.di.featureEntries
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class KotobayaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@KotobayaApp)
            modules(listOf(appModule) + featureEntries.mapNotNull { it.module })
        }
    }
}
