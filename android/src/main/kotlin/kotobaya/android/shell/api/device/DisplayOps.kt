package kotobaya.android.shell.api.device

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.Looper
import java.lang.ref.WeakReference
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.LocalRoute
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.get
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonObject

/** The display preferences of this device (`ShellDisplay`): device-local, not synced, no op (SHELL.md 6.3). */
interface DisplayOps {
    /** `lock_portrait`; false until set. */
    fun lockPortrait(): Boolean

    /** Stores [value] and applies it to the host activity at once. */
    fun setLockPortrait(value: Boolean)
}

/** The activity whose orientation follows `lock_portrait`; the shell activity attaches itself while it lives. */
object ActivityOrientation {
    private var current: WeakReference<Activity>? = null

    /** Applies [lockPortrait] to [activity] now; later [apply] calls reach it. Call on the UI thread. */
    fun attach(activity: Activity, lockPortrait: Boolean) {
        current = WeakReference(activity)
        set(activity, lockPortrait)
    }

    fun detach(activity: Activity) {
        if (current?.get() === activity) current = null
    }

    /** Runs at once on the UI thread, else posted to it (the bridge answers through the UI thread too). */
    fun apply(lockPortrait: Boolean) {
        val a = current?.get() ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) set(a, lockPortrait) else a.runOnUiThread { set(a, lockPortrait) }
    }

    private fun set(a: Activity, lockPortrait: Boolean) {
        a.requestedOrientation =
            if (lockPortrait) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}

/** [DisplayOps] over SharedPreferences (survives restart and unpair; not part of the replica). */
class CoreDisplayOps(
    private val prefs: SharedPreferences,
    private val apply: (Boolean) -> Unit = ActivityOrientation::apply,
) : DisplayOps {
    override fun lockPortrait(): Boolean = prefs.getBoolean(KEY, false)

    override fun setLockPortrait(value: Boolean) {
        prefs.edit().putBoolean(KEY, value).apply()
        apply(value)
    }

    companion object {
        private const val KEY = "lock_portrait"
        private const val FILE = "kotobaya-display"

        fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        /** The stored value, for the activity at create. */
        fun storedLockPortrait(context: Context): Boolean = prefs(context).getBoolean(KEY, false)
    }
}

internal val displayRoutes: List<LocalRoute> = listOf(
    LocalRoute("getDisplayPrefs", "GET", "/shell/v1/display") { call -> getDisplayPrefs(call) },
    LocalRoute("updateDisplayPrefs", "PATCH", "/shell/v1/display") { call -> updateDisplayPrefs(call) },
)

private fun displayOps(call: LocalCall): DisplayOps =
    runCatching { call.services.get<DisplayOps>() }
        .getOrElse { CoreDisplayOps(CoreDisplayOps.prefs(call.services.get<Context>())) }

private fun view(ops: DisplayOps) = ShellResponse.json(JsonObject(linkedMapOf("lock_portrait" to JsonBool(ops.lockPortrait()))))

private fun getDisplayPrefs(call: LocalCall): ShellResponse {
    call.query()
    return view(displayOps(call))
}

/** `ShellDisplayPatch`: only `lock_portrait` (boolean), at least one property; else `422` and nothing changes. */
private fun updateDisplayPrefs(call: LocalCall): ShellResponse {
    call.query()
    val body = call.bodyObject()
    body.members.keys.firstOrNull { it != "lock_portrait" }?.let {
        throw ProblemException(Problems.validationFailed("/$it", "unknown_field", "Unknown property $it."))
    }
    if (body.members.isEmpty()) throw ProblemException(Problems.validationFailed("", "required", "Send at least one property."))
    val v = body.members.getValue("lock_portrait") as? JsonBool
        ?: throw ProblemException(Problems.validationFailed("/lock_portrait", "invalid_type", "Must be true or false."))
    val ops = displayOps(call)
    ops.setLockPortrait(v.value)
    return view(ops)
}
