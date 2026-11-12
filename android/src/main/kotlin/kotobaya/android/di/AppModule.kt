package kotobaya.android.di

import kotobaya.android.db.DictionaryProvider
import kotobaya.android.db.EmptyDictionaryProvider
import kotobaya.android.db.openAppDatabase
import kotobaya.android.settings.SettingsStore
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    single { openAppDatabase(androidContext()) }
    single { SettingsStore(androidContext()) }
    single<DictionaryProvider> { EmptyDictionaryProvider() }
    single { FeatureRegistry(featureEntries) }
}
