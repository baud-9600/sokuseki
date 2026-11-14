package de.asdfghasdfgh.sokuseki

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TrackingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        StatusStore.init(context)
        val configStore = ConfigStore(context)
        if (!configStore.enabled) return

        val config = configStore.load()
        try {
            TrackingService.deliver(context, TrackingService.ACTION_TICK)
        } catch (e: Exception) {
            StatusStore.log(
                context.getString(R.string.log_alarm_failed, e.javaClass.simpleName)
            )
            AlarmScheduler(context).schedule(config.intervalMinutes)
        }
    }

    companion object {
        const val ACTION_TICK = "de.asdfghasdfgh.sokuseki.action.TICK"
    }
}
