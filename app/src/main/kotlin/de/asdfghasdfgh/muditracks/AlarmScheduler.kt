package de.asdfghasdfgh.muditracks

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Watchdog independent of the foreground service. It wakes the CPU in Doze so a
 * killed service is restarted and a new fix is attempted even when the phone sleeps.
 */
class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun schedule(intervalMinutes: Int) {
        val intent = Intent(context, TrackingAlarmReceiver::class.java)
            .setAction(TrackingAlarmReceiver.ACTION_TICK)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAt = System.currentTimeMillis() +
            intervalMinutes.coerceAtLeast(1) * 60_000L

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !alarmManager.canScheduleExactAlarms()
            ) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    fun cancel() {
        val intent = Intent(context, TrackingAlarmReceiver::class.java)
            .setAction(TrackingAlarmReceiver.ACTION_TICK)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pendingIntent)
    }

    companion object {
        private const val REQUEST_CODE = 4711
    }
}
