package de.asdfghasdfgh.muditracks

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine
import android.os.CancellationSignal

/**
 * Foreground location service. The service is the reliable part of the app:
 *
 *  - foregroundServiceType=location keeps the process alive while the phone sleeps,
 *  - a short partial wake lock covers each GPS fix and HTTP request,
 *  - AlarmManager re-wakes the service every interval, so Doze cannot silence it,
 *  - fixes that cannot be delivered are persisted and retried on the next wakeup.
 */
class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cycleMutex = Mutex()
    private lateinit var configStore: ConfigStore
    private lateinit var queue: PendingQueue
    private lateinit var sender: HttpSender
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var locationManager: LocationManager
    private var loopJob: Job? = null
    private var lastLocation: Location? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        configStore = ConfigStore(this)
        queue = PendingQueue(File(filesDir, "pending.jsonl"))
        sender = HttpSender()
        alarmScheduler = AlarmScheduler(this)
        locationManager = getSystemService(LocationManager::class.java)
        StatusStore.init(this)
        createNotificationChannel()
        startForegroundCompat(buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        StatusStore.init(this)

        if (action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }

        val config = configStore.load()
        if (action == ACTION_START) {
            configStore.enabled = true
        }

        val enabled = configStore.enabled
        if (!enabled && action != ACTION_SEND_NOW) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!hasFineLocationPermission()) {
            StatusStore.log("Keine Standortberechtigung – Dienst angehalten.")
            StatusStore.update { it.copy(lastError = "Standortberechtigung fehlt") }
            if (enabled) {
                configStore.enabled = false
                alarmScheduler.cancel()
            }
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        if (config.validationError() != null) {
            StatusStore.log("Konfiguration unvollständig – Dienst angehalten.")
            StatusStore.update {
                it.copy(lastError = config.validationError() ?: "Konfiguration unvollständig")
            }
            if (enabled) {
                configStore.enabled = false
                alarmScheduler.cancel()
            }
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        when {
            action == ACTION_START -> {
                restartLoop(config)
            }
            !enabled -> {
                triggerCycle(config, oneShot = true)
            }
            else -> {
                ensureLoop(config)
                triggerCycle(config, oneShot = false)
            }
        }

        if (enabled) {
            alarmScheduler.schedule(config.intervalMinutes)
            updateNotification()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (configStore.enabled) {
            alarmScheduler.schedule(configStore.load().intervalMinutes)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        StatusStore.setRunning(false)
        loopJob?.cancel()
        scope.cancel()
        stopForegroundCompat()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun restartLoop(config: TrackConfig) {
        loopJob?.cancel()
        loopJob = null
        isRunning = true
        StatusStore.setRunning(true)
        loopJob = scope.launch {
            while (isActive) {
                runCycle(config)
                delay(config.intervalMinutes.coerceAtLeast(1) * 60_000L)
            }
        }
    }

    private fun ensureLoop(config: TrackConfig) {
        if (loopJob?.isActive == true) return
        restartLoop(config)
    }

    private fun triggerCycle(config: TrackConfig, oneShot: Boolean) {
        scope.launch {
            val acquired = cycleMutex.tryLock()
            if (!acquired) {
                if (oneShot) {
                    stopForegroundCompat()
                    stopSelf()
                }
                return@launch
            }
            try {
                runCycleLocked(config)
            } finally {
                cycleMutex.unlock()
                if (oneShot) {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
    }

    private suspend fun runCycle(config: TrackConfig) {
        cycleMutex.withLock {
            runCycleLocked(config)
        }
    }

    private suspend fun runCycleLocked(config: TrackConfig) {
        val wakeLock = acquireWakeLock()
        try {
            StatusStore.log("Suche Standort …")
            val location = locate()
            if (location == null) {
                StatusStore.update { it.copy(lastError = "Kein Standort-Fix") }
                StatusStore.log("Kein Standort-Fix – nächster Versuch beim nächsten Alarm.")
                return
            }

            val now = System.currentTimeMillis()
            val battery = batteryState()
            val payload = OwnTracksPayload.location(
                lat = location.latitude,
                lon = location.longitude,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else 250f,
                timestampMillis = if (location.time > 0) location.time else now,
                batteryPercent = battery.first,
                batteryStatus = battery.second,
                connection = connectionType(),
                tid = config.tid,
                topic = config.topic,
                altitudeMeters = if (location.hasAltitude()) location.altitude else null,
                velocityMetersPerSecond = if (location.hasSpeed()) location.speed else null,
                courseDegrees = if (location.hasBearing()) location.bearing else null,
            )

            StatusStore.update {
                it.copy(
                    lastFixAt = now,
                    lastFixLat = location.latitude,
                    lastFixLon = location.longitude,
                    lastFixAcc = if (location.hasAccuracy()) location.accuracy else null,
                    lastAttemptAt = now,
                )
            }
            updateNotification()

            val result = sender.send(config, payload)
            recordResult(result)
            if (result.success) {
                StatusStore.update { it.copy(lastSentAt = System.currentTimeMillis()) }
                StatusStore.log(
                    "Gesendet: %.5f, %.5f (%s)".format(
                        Locale.US,
                        location.latitude,
                        location.longitude,
                        result.message,
                    )
                )
            } else {
                queue.add(payload)
                StatusStore.update { it.copy(pending = queue.size()) }
                StatusStore.log("Nicht gesendet (${result.message}) – Punkt vorgemerkt.")
                if (!result.retryable) {
                    StatusStore.log("Fehler ist nicht wiederholbar; alter Rückstand bleibt erhalten.")
                }
            }

            drainBacklog(config)
            StatusStore.update { it.copy(pending = queue.size()) }
            updateNotification()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StatusStore.log("Zyklusfehler: ${e.javaClass.simpleName}: ${e.message}")
            StatusStore.update { it.copy(lastError = e.message ?: e.javaClass.simpleName) }
        } finally {
            releaseWakeLock(wakeLock)
        }
    }

    private fun drainBacklog(config: TrackConfig) {
        var sent = 0
        while (sent < MAX_BACKLOG_PER_CYCLE) {
            val payload = queue.peek() ?: return
            val result = sender.send(config, payload)
            recordResult(result)
            if (result.success) {
                queue.removeFirst()
                sent++
                StatusStore.update {
                    it.copy(lastSentAt = System.currentTimeMillis(), pending = queue.size())
                }
            } else {
                StatusStore.log("Rückstand bleibt liegen: ${result.message}")
                return
            }
        }
    }

    private fun recordResult(result: SendResult) {
        StatusStore.update {
            it.copy(
                lastCode = result.code,
                lastError = if (result.success) "" else result.message,
                lastBody = result.body,
                lastAttemptAt = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun locate(): Location? = coroutineScope {
        val cached = bestLastKnown()
        val gpsDeferred = async { currentLocation(LocationManager.GPS_PROVIDER, GPS_TIMEOUT_MS) }
        val network = currentLocation(LocationManager.NETWORK_PROVIDER, NETWORK_TIMEOUT_MS)

        if (network != null && network.hasAccuracy() && network.accuracy <= GOOD_NETWORK_ACCURACY_METERS) {
            gpsDeferred.cancel()
            return@coroutineScope betterOf(listOfNotNull(network, cached, lastLocation))
        }

        val gps = gpsDeferred.await()
        betterOf(listOfNotNull(gps, network, cached, lastLocation))
    }

    private fun betterOf(candidates: List<Location>): Location? {
        val now = System.currentTimeMillis()
        val freshEnough = candidates.filter { now - it.time <= MAX_FIX_AGE_MS }
        val chosen = freshEnough.minByOrNull { score(it) }
        if (chosen != null) lastLocation = chosen
        return chosen
    }

    private fun score(location: Location): Double {
        val ageSeconds = ((System.currentTimeMillis() - location.time).coerceAtLeast(0L)) / 1000.0
        val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 500.0
        return accuracy + ageSeconds * 0.5
    }

    private fun bestLastKnown(): Location? {
        val providers = try {
            locationManager.getProviders(true)
        } catch (_: Exception) {
            emptyList()
        }
        return providers.asSequence()
            .mapNotNull { provider ->
                try {
                    locationManager.getLastKnownLocation(provider)
                } catch (_: Exception) {
                    null
                }
            }
            .filter { System.currentTimeMillis() - it.time <= MAX_FIX_AGE_MS }
            .minByOrNull(::score)
    }

    private suspend fun currentLocation(provider: String, timeoutMs: Long): Location? {
        val enabled = try {
            locationManager.isProviderEnabled(provider)
        } catch (_: Exception) {
            false
        }
        if (!enabled) return null

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val cancellationSignal = CancellationSignal()
                continuation.invokeOnCancellation { cancellationSignal.cancel() }
                try {
                    locationManager.getCurrentLocation(
                        provider,
                        cancellationSignal,
                        mainExecutor,
                    ) { location ->
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    }
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? = try {
        val powerManager = getSystemService(PowerManager::class.java)
        powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Muditracks::location-cycle",
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    } catch (_: Exception) {
        null
    }

    private fun releaseWakeLock(wakeLock: PowerManager.WakeLock?) {
        try {
            if (wakeLock?.isHeld == true) wakeLock.release()
        } catch (_: Exception) {
            // Nothing useful to do here.
        }
    }

    private fun batteryState(): Pair<Int, Int> {
        return try {
            val batteryManager = getSystemService(BatteryManager::class.java)
            var percent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (percent !in 1..100) {
                val intent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) {
                    percent = (level * 100f / scale).roundToInt()
                }
            }
            val status = if (batteryManager.isCharging) 2 else 1
            percent.coerceIn(0, 100) to status
        } catch (_: Exception) {
            0 to 1
        }
    }

    private fun connectionType(): String {
        return try {
            val manager = getSystemService(ConnectivityManager::class.java)
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            when {
                capabilities == null -> "o"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "w"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "m"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "w"
                else -> "o"
            }
        } catch (_: Exception) {
            "o"
        }
    }

    private fun stopTracking() {
        configStore.enabled = false
        alarmScheduler.cancel()
        loopJob?.cancel()
        loopJob = null
        isRunning = false
        StatusStore.setRunning(false)
        StatusStore.log("Tracking gestoppt.")
        stopForegroundCompat()
        stopSelf()
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Standort-Tracking",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Sendet den Standort an Home Assistant"
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val status = StatusStore.state.value
        val detail = when {
            status.lastError.isNotBlank() -> "Fehler: ${status.lastError}"
            status.lastSentAt > 0 -> "Zuletzt ${time(status.lastSentAt)} · ${status.pending} in Warteschlange"
            else -> "Warte auf ersten Standort-Fix"
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val sendIntent = PendingIntent.getForegroundService(
            this,
            11,
            Intent(this, TrackingService::class.java).setAction(ACTION_SEND_NOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getForegroundService(
            this,
            12,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentTitle("Muditracks aktiv")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Jetzt senden", sendIntent)
            .addAction(0, "Stopp", stopIntent)
            .build()
    }

    private fun updateNotification() {
        if (!foregroundStarted) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        foregroundStarted = false
    }

    private fun time(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(millis))

    companion object {
        const val ACTION_START = "de.asdfghasdfgh.muditracks.action.START"
        const val ACTION_STOP = "de.asdfghasdfgh.muditracks.action.STOP"
        const val ACTION_TICK = "de.asdfghasdfgh.muditracks.action.TICK"
        const val ACTION_SEND_NOW = "de.asdfghasdfgh.muditracks.action.SEND_NOW"

        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1001
        private const val GPS_TIMEOUT_MS = 40_000L
        private const val NETWORK_TIMEOUT_MS = 12_000L
        private const val GOOD_NETWORK_ACCURACY_METERS = 150f
        private const val MAX_FIX_AGE_MS = 30 * 60_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 3 * 60_000L
        private const val MAX_BACKLOG_PER_CYCLE = 20

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) = deliver(context, ACTION_START)

        fun stop(context: Context) = deliver(context, ACTION_STOP)

        fun sendNow(context: Context) = deliver(context, ACTION_SEND_NOW)

        /**
         * The service is already a foreground service most of the time, so a normal
         * startService is enough and avoids Android 12's background-FGS restrictions.
         * Only a cold start needs startForegroundService.
         */
        fun deliver(context: Context, action: String) {
            val intent = Intent(context, TrackingService::class.java).setAction(action)
            if (isRunning) {
                context.startService(intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
