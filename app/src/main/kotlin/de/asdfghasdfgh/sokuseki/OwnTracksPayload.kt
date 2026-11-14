package de.asdfghasdfgh.sokuseki

import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Builds the exact JSON shape the OwnTracks Android app posts in HTTP mode.
 *
 * Home Assistant ignores location messages without a usable `acc` value, so a
 * fallback accuracy is used when the platform does not report one.
 */
object OwnTracksPayload {

    fun location(
        lat: Double,
        lon: Double,
        accuracyMeters: Float,
        timestampMillis: Long,
        batteryPercent: Int,
        batteryStatus: Int,
        connection: String,
        tid: String,
        topic: String,
        altitudeMeters: Double? = null,
        velocityMetersPerSecond: Float? = null,
        courseDegrees: Float? = null,
        trigger: String = "u",
    ): String {
        val acc = accuracyMeters.roundToInt().coerceAtLeast(MIN_ACCURACY_METERS)
        return JSONObject().apply {
            put("_type", "location")
            put("lat", round6(lat))
            put("lon", round6(lon))
            put("acc", acc)
            put("tst", timestampMillis / 1000L)
            put("batt", batteryPercent.coerceIn(0, 100))
            put("bs", batteryStatus)
            put("tid", tid)
            put("conn", connection)
            put("t", trigger)
            put("m", 0)
            put("topic", topic)
            if (altitudeMeters != null && !altitudeMeters.isNaN()) {
                put("alt", (altitudeMeters * 10.0).roundToLong() / 10.0)
            }
            if (velocityMetersPerSecond != null && velocityMetersPerSecond >= 0f) {
                put("vel", (velocityMetersPerSecond * 10f).roundToInt() / 10.0)
            }
            if (courseDegrees != null && courseDegrees >= 0f) {
                put("cog", courseDegrees.roundToInt() % 360)
            }
        }.toString()
    }

    private fun round6(value: Double): Double = (value * 1_000_000.0).roundToLong() / 1_000_000.0

    /** Home Assistant discards `acc == 0`, so never send zero. */
    const val MIN_ACCURACY_METERS = 1
}
