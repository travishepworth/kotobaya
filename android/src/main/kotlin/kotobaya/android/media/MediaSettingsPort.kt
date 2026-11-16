package kotobaya.android.media

import kotobaya.android.dict.DictStore

/** What Settings > Offline media reads and does (blocking; run on IO). */
interface MediaSettingsPort {
    fun breakdown(): StorageBreakdown

    fun clearCache()

    var prefetchDays: Int
    var budgetMb: Int
    var allowMetered: Boolean
}

/** [MediaSettingsPort] over the blob cache, the dictionary's size and [MediaPrefs]. */
class AppMediaSettingsPort(private val store: BlobStore, private val dict: DictStore, private val prefs: MediaPrefs) : MediaSettingsPort {
    override fun breakdown() = store.breakdown(dict.installedBytes())

    override fun clearCache() = store.clearCache()

    override var prefetchDays: Int by prefs::prefetchDays
    override var budgetMb: Int by prefs::budgetMb
    override var allowMetered: Boolean by prefs::allowMetered
}
