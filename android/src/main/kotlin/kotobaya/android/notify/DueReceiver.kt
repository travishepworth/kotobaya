package kotobaya.android.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.koin.core.context.GlobalContext

/** The alarm, boot, clock and time-zone changes all end in the same thing: recompute and re-plan. */
class DueReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val koin = GlobalContext.getOrNull() ?: return
        when (intent.action) {
            ACTION_ALARM -> koin.get<DueScheduler>().recordFired(System.currentTimeMillis())
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> Unit
            else -> return
        }
        koin.get<DueTrigger>().fire()
    }

    companion object {
        const val ACTION_ALARM = "kotobaya.android.notify.DUE_ALARM"
    }
}
