package kotobaya.android.nav

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotobaya.android.ui.theme.ThemeMode

/**
 * The Settings › Appearance choice (DESIGN §2.5): System (default), Light or Dark, kept in its own
 * SharedPreferences file under [ThemeMode.storageKey]. Device-local on purpose: it is not learning data, so it never
 * syncs. Both activities read it before their first frame, so there is no theme flash.
 */
object Appearance {
    private const val FILE = "kotobaya-appearance"
    private const val KEY = "theme"

    private val _mode = MutableStateFlow<ThemeMode?>(null)

    /** The current choice; read [load] once before collecting. */
    val mode: StateFlow<ThemeMode?> get() = _mode

    /** Loads the stored choice (a tiny prefs file: the first read happens before the first frame). */
    fun load(context: Context): ThemeMode {
        _mode.value?.let { return it }
        val m = ThemeMode.fromStorageKey(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null))
        _mode.value = m
        return m
    }

    fun set(context: Context, mode: ThemeMode) {
        _mode.value = mode
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, mode.storageKey).apply()
    }
}
