package de.asdfghasdfgh.muditracks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        StatusStore.init(context)
        val configStore = ConfigStore(context)
        if (!configStore.enabled) return

        val config = configStore.load()
        AlarmScheduler(context).schedule(config.intervalMinutes)
        try {
            TrackingService.deliver(context, TrackingService.ACTION_START)
        } catch (e: Exception) {
            StatusStore.log("Neustart: Dienst konnte nicht starten: ${e.javaClass.simpleName}")
        }
    }
}
