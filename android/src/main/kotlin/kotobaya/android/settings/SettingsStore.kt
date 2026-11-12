package kotobaya.android.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Device-local settings in SharedPreferences (no extra dependency; values are tiny strings).
 * T301b writes pairing results here.
 */
class SettingsStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("kotobaya-settings", Context.MODE_PRIVATE))

    var serverBaseUrl: String?
        get() = prefs.getString(K_SERVER, null)
        set(v) = put(K_SERVER, v)

    /** Device token slot. */
    var deviceToken: String?
        get() = prefs.getString(K_TOKEN, null)
        set(v) = put(K_TOKEN, v)

    /** English only for now. */
    var language: String
        get() = prefs.getString(K_LANG, "en") ?: "en"
        set(v) = put(K_LANG, v)

    /** "top60k" (referenced + top 60k) or "all". */
    var dictionaryScope: String
        get() = prefs.getString(K_SCOPE, "top60k") ?: "top60k"
        set(v) = put(K_SCOPE, v)

    /** Storage budget in megabytes. */
    var storageBudgetMb: Int
        get() = prefs.getInt(K_BUDGET, 2048)
        set(v) = prefs.edit().putInt(K_BUDGET, v).apply()

    private fun put(key: String, v: String?) {
        prefs.edit().apply { if (v == null) remove(key) else putString(key, v) }.apply()
    }

    private companion object {
        const val K_SERVER = "server_base_url"
        const val K_TOKEN = "device_token"
        const val K_LANG = "language"
        const val K_SCOPE = "dictionary_scope"
        const val K_BUDGET = "storage_budget_mb"
    }
}
