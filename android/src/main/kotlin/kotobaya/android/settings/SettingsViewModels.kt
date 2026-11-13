package kotobaya.android.settings

import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotobaya.android.R
import kotobaya.android.vm.StateViewModel

/** Settings > Study: the three study settings as typed text, and the last save's result. */
data class StudySettingsUiState(
    val fields: Map<StudyKey, String> = StudyKey.entries.associateWith { "" },
    @StringRes val message: Int? = null,
)

interface StudySettingsIntents {
    fun setField(key: StudyKey, text: String) {}
    fun save() {}
}

/** Settings > Study over [StudySettingsModel] (replica reads, `settings.set` mints): all on IO. */
class StudySettingsViewModel(
    private val model: StudySettingsModel,
    io: CoroutineDispatcher = Dispatchers.IO,
) : StateViewModel<StudySettingsUiState>(StudySettingsUiState(), io), StudySettingsIntents {
    init {
        launchIo {
            val loaded = StudyKey.entries.associateWith { model.valueOf(it).toString() }
            update { it.copy(fields = loaded) }
        }
    }

    override fun setField(key: StudyKey, text: String) = update { it.copy(fields = it.fields + (key to text)) }

    override fun save() {
        val fields = current.fields
        launchIo {
            val res = save(fields)
            update { it.copy(message = res) }
        }
    }

    @StringRes
    private fun save(fields: Map<StudyKey, String>): Int {
        var out: Int = R.string.study_unchanged
        for (k in StudyKey.entries) {
            val n = fields[k].orEmpty().trim().toLongOrNull()
            when (if (n == null) StudyResult.OutOfRange else model.set(k, n)) {
                StudyResult.OutOfRange -> return R.string.study_invalid
                is StudyResult.Refused -> return R.string.study_refused
                StudyResult.Saved -> out = R.string.study_saved
                StudyResult.Unchanged -> Unit
            }
        }
        return out
    }
}

/** Settings > About. */
data class AboutUiState(
    val appVersion: String = "?",
    @StringRes val flavorRes: Int = R.string.about_flavor_own,
    /** The server's version, or null when unknown (unpaired, offline, or not loaded yet). */
    val serverVersion: String? = null,
)

/** The `ankidroid` flavor installs as com.ichi2.anki (build.gradle.kts); the fallback keeps our own id. */
fun flavorLabelRes(packageName: String): Int =
    if (packageName == "com.ichi2.anki") R.string.about_flavor_ankidroid else R.string.about_flavor_own

/** Settings > About: app version and flavor (given), the server's version from `/health` (on IO). */
class AboutViewModel(
    appVersion: String,
    packageName: String,
    serverVersion: suspend () -> String?,
    io: CoroutineDispatcher = Dispatchers.IO,
) : StateViewModel<AboutUiState>(AboutUiState(appVersion, flavorLabelRes(packageName)), io) {
    init {
        launchIo {
            val v = runCatching { serverVersion() }.getOrNull()
            update { it.copy(serverVersion = v) }
        }
    }
}
