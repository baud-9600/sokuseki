package de.asdfghasdfgh.muditracks

import android.content.Context

/**
 * Everything the tracker needs. The webhook URL is a secret: it is only ever read from
 * the app's private preferences or from the gitignored local.properties at build time.
 */
data class TrackConfig(
    val url: String = BuildConfig.DEFAULT_WEBHOOK_URL,
    val user: String = BuildConfig.DEFAULT_USER,
    val device: String = BuildConfig.DEFAULT_DEVICE,
    val tid: String = BuildConfig.DEFAULT_TID,
    val password: String = "",
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    val trustAllCerts: Boolean = false,
) {
    val topic: String
        get() = "owntracks/$user/$device"

    fun normalized(): TrackConfig = copy(
        url = url.trim(),
        user = user.trim(),
        device = device.trim(),
        tid = tid.trim(),
        password = password,
        intervalMinutes = intervalMinutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES),
    )

    fun validationError(): String? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "Die Webhook-URL muss mit http:// oder https:// beginnen."
        }
        if (user.isBlank()) return "Benutzername fehlt."
        if (device.isBlank()) return "Geräte-ID fehlt."
        if (intervalMinutes !in MIN_INTERVAL_MINUTES..MAX_INTERVAL_MINUTES) {
            return "Intervall muss zwischen $MIN_INTERVAL_MINUTES und $MAX_INTERVAL_MINUTES Minuten liegen."
        }
        return null
    }

    companion object {
        const val DEFAULT_INTERVAL_MINUTES = 5
        const val MIN_INTERVAL_MINUTES = 1
        const val MAX_INTERVAL_MINUTES = 120
    }
}

class ConfigStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): TrackConfig = TrackConfig(
        url = prefs.getString(KEY_URL, BuildConfig.DEFAULT_WEBHOOK_URL).orEmpty(),
        user = prefs.getString(KEY_USER, BuildConfig.DEFAULT_USER).orEmpty(),
        device = prefs.getString(KEY_DEVICE, BuildConfig.DEFAULT_DEVICE).orEmpty(),
        tid = prefs.getString(KEY_TID, BuildConfig.DEFAULT_TID).orEmpty(),
        password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
        intervalMinutes = prefs.getInt(KEY_INTERVAL, TrackConfig.DEFAULT_INTERVAL_MINUTES),
        trustAllCerts = prefs.getBoolean(KEY_TRUST_ALL, false),
    ).normalized()

    fun save(config: TrackConfig) {
        val normalized = config.normalized()
        prefs.edit()
            .putString(KEY_URL, normalized.url)
            .putString(KEY_USER, normalized.user)
            .putString(KEY_DEVICE, normalized.device)
            .putString(KEY_TID, normalized.tid)
            .putString(KEY_PASSWORD, normalized.password)
            .putInt(KEY_INTERVAL, normalized.intervalMinutes)
            .putBoolean(KEY_TRUST_ALL, normalized.trustAllCerts)
            .apply()
    }

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    companion object {
        const val PREFS_NAME = "muditracks"
        private const val KEY_URL = "url"
        private const val KEY_USER = "user"
        private const val KEY_DEVICE = "device"
        private const val KEY_TID = "tid"
        private const val KEY_PASSWORD = "password"
        private const val KEY_INTERVAL = "interval"
        private const val KEY_TRUST_ALL = "trust_all"
        private const val KEY_ENABLED = "enabled"
    }
}
