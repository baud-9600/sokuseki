package de.asdfghasdfgh.sokuseki

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnTracksPayloadTest {

    @Test
    fun `builds the location payload Home Assistant expects`() {
        val payload = OwnTracksPayload.location(
            lat = 49.487123456789,
            lon = 8.466123456789,
            accuracyMeters = 23.4f,
            timestampMillis = 1_700_000_123_456L,
            batteryPercent = 87,
            batteryStatus = 1,
            connection = "w",
            tid = "kt",
            topic = "owntracks/mk/kompakt",
            altitudeMeters = 96.44,
            velocityMetersPerSecond = 1.26f,
            courseDegrees = 271.7f,
        )

        val json = JSONObject(payload)
        assertEquals("location", json.getString("_type"))
        assertEquals(49.487123, json.getDouble("lat"), 0.0000001)
        assertEquals(8.466123, json.getDouble("lon"), 0.0000001)
        assertEquals(23, json.getInt("acc"))
        assertEquals(1_700_000_123L, json.getLong("tst"))
        assertEquals(87, json.getInt("batt"))
        assertEquals("kt", json.getString("tid"))
        assertEquals("w", json.getString("conn"))
        assertEquals("owntracks/mk/kompakt", json.getString("topic"))
        assertEquals(96.4, json.getDouble("alt"), 0.0001)
        assertEquals(1.3, json.getDouble("vel"), 0.0001)
        assertEquals(272, json.getInt("cog"))
    }

    @Test
    fun `never emits zero accuracy because HA drops it`() {
        val json = JSONObject(
            OwnTracksPayload.location(
                lat = 1.0,
                lon = 2.0,
                accuracyMeters = 0f,
                timestampMillis = 1_700_000_000_000L,
                batteryPercent = 50,
                batteryStatus = 1,
                connection = "m",
                tid = "kt",
                topic = "owntracks/mk/kompakt",
            )
        )
        assertTrue(json.getInt("acc") >= 1)
    }
}
