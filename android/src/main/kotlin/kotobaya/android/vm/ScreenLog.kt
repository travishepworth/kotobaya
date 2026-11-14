package kotobaya.android.vm

import android.util.Log

/**
 * Tagged logcat lines for screen loads (AND-POSTSYNC): `adb logcat -s KotobayaLoad` shows every load that failed,
 * with the ViewModel that ran it and the stack. Never throws (plain JVM tests have no android.util.Log).
 */
object ScreenLog {
    const val TAG: String = "KotobayaLoad"

    /** A screen load in [where] failed with [e]. */
    fun loadFailed(where: String, e: Throwable) {
        runCatching { Log.e(TAG, "load failed in $where: $e", e) }
    }
}
