package de.asdfghasdfgh.sokuseki

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RunStatus(
    val running: Boolean = false,
    val lastFixAt: Long = 0L,
    val lastFixLat: Double? = null,
    val lastFixLon: Double? = null,
    val lastFixAcc: Float? = null,
    val lastAttemptAt: Long = 0L,
    val lastSentAt: Long = 0L,
    val pending: Int = 0,
    val lastCode: Int = 0,
    val lastError: String = "",
    val lastBody: String = "",
    val logs: List<String> = emptyList(),
)

/**
 * Single source of truth for the UI and the notification. Values that matter after a
 * process restart are mirrored into SharedPreferences.
 */
object StatusStore {
    private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(RunStatus())

    val state: StateFlow<RunStatus> = _state.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _state.value = RunStatus(
            running = TrackingService.isRunning,
            lastFixAt = p.getLong(KEY_FIX_AT, 0),
            lastFixLat = p.getString(KEY_FIX_LAT, null)?.toDoubleOrNull(),
            lastFixLon = p.getString(KEY_FIX_LON, null)?.toDoubleOrNull(),
            lastFixAcc = p.getString(KEY_FIX_ACC, null)?.toFloatOrNull(),
            lastAttemptAt = p.getLong(KEY_ATTEMPT_AT, 0),
            lastSentAt = p.getLong(KEY_SENT_AT, 0),
            pending = p.getInt(KEY_PENDING, 0),
            lastCode = p.getInt(KEY_CODE, 0),
            lastError = p.getString(KEY_ERROR, "").orEmpty(),
            lastBody = p.getString(KEY_BODY, "").orEmpty(),
        )
    }

    @Synchronized
    fun update(transform: (RunStatus) -> RunStatus) {
        val next = transform(_state.value)
        _state.value = next
        prefs?.edit()
            ?.putLong(KEY_FIX_AT, next.lastFixAt)
            ?.putString(KEY_FIX_LAT, next.lastFixLat?.toString())
            ?.putString(KEY_FIX_LON, next.lastFixLon?.toString())
            ?.putString(KEY_FIX_ACC, next.lastFixAcc?.toString())
            ?.putLong(KEY_ATTEMPT_AT, next.lastAttemptAt)
            ?.putLong(KEY_SENT_AT, next.lastSentAt)
            ?.putInt(KEY_PENDING, next.pending)
            ?.putInt(KEY_CODE, next.lastCode)
            ?.putString(KEY_ERROR, next.lastError)
            ?.putString(KEY_BODY, next.lastBody)
            ?.apply()
    }

    fun setRunning(running: Boolean) {
        update { it.copy(running = running) }
    }

    fun log(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date())
        update { current ->
            current.copy(logs = (current.logs + "$stamp  $message").takeLast(MAX_LOG_LINES))
        }
    }

    private const val PREFS = "sokuseki_status"
    private const val KEY_FIX_AT = "fix_at"
    private const val KEY_FIX_LAT = "fix_lat"
    private const val KEY_FIX_LON = "fix_lon"
    private const val KEY_FIX_ACC = "fix_acc"
    private const val KEY_ATTEMPT_AT = "attempt_at"
    private const val KEY_SENT_AT = "sent_at"
    private const val KEY_PENDING = "pending"
    private const val KEY_CODE = "code"
    private const val KEY_ERROR = "error"
    private const val KEY_BODY = "body"
    private const val MAX_LOG_LINES = 30
}
