package be.delijn.verkenner

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import com.uber.h3core.util.LatLng
import kotlinx.serialization.json.*
import java.time.LocalTime
import java.time.DayOfWeek

class GeoJsonWriterTest {

    @Test
    fun `hex feature has correct structure`() {
        val result = HexResult(
            h3Index = "89be8d58aaffff",
            byMode = mapOf(
                FilterMode.HOURLY to mapOf(
                    DayScope.WEEKDAY to RoutingResult(7.5, "Gent-Sint-Pieters"),
                    DayScope.FULLWEEK to RoutingResult(12.3, "Gent-Dampoort")
                ),
                FilterMode.HALF_HOURLY to mapOf(
                    DayScope.WEEKDAY to null,
                    DayScope.FULLWEEK to null
                )
            )
        )
        val boundary = listOf(LatLng(51.05, 3.72), LatLng(51.06, 3.73), LatLng(51.05, 3.74))
        val json = buildHexFeature(result, boundary)
        val obj = Json.parseToJsonElement(json).jsonObject

        assertEquals("Feature", obj["type"]?.jsonPrimitive?.content)
        val props = obj["properties"]!!.jsonObject
        assertFalse(props.containsKey("h3index"), "h3index should not be emitted")
        assertEquals(7.5, props["walking_minutes_hourly_weekday"]?.jsonPrimitive?.double)
        assertEquals(12.3, props["walking_minutes_hourly_fullweek"]?.jsonPrimitive?.double)
        assertEquals("Gent-Sint-Pieters", props["nearest_stop_hourly_weekday"]?.jsonPrimitive?.content)
        assertEquals("Gent-Dampoort", props["nearest_stop_hourly_fullweek"]?.jsonPrimitive?.content)
        assertTrue(props["walking_minutes_halfhour_weekday"] is JsonNull)
        val ring = obj["geometry"]!!.jsonObject["coordinates"]!!.jsonArray[0].jsonArray
        assertEquals(ring.first(), ring.last(), "Ring must be closed (first == last)")
    }

    @Test
    fun `null routing result serializes as JSON null`() {
        val result = HexResult(
            h3Index = "89be8d58aaffff",
            byMode = mapOf(
                FilterMode.HOURLY to mapOf(DayScope.WEEKDAY to null, DayScope.FULLWEEK to null)
            )
        )
        val boundary = listOf(LatLng(51.05, 3.72))
        val json = buildHexFeature(result, boundary)
        val obj = Json.parseToJsonElement(json).jsonObject
        assertTrue(obj["properties"]!!.jsonObject["walking_minutes_hourly_weekday"] is JsonNull)
    }

    @Test
    fun `feature collection wraps features correctly`() {
        val fc = buildFeatureCollection(listOf("""{"type":"Feature"}"""))
        val obj = Json.parseToJsonElement(fc).jsonObject
        assertEquals("FeatureCollection", obj["type"]?.jsonPrimitive?.content)
        assertEquals(1, obj["features"]?.jsonArray?.size)
    }

    @Test
    fun `stop feature has correct qualifies properties`() {
        val stop = Stop("s1", "Test Stop", 51.05, 3.72)
        val qualifies = mapOf(
            FilterMode.HOURLY to mapOf(DayScope.WEEKDAY to true, DayScope.FULLWEEK to false),
            FilterMode.MIN1_DAY to mapOf(DayScope.WEEKDAY to false, DayScope.FULLWEEK to false)
        )
        val departures = mapOf(DayOfWeek.MONDAY to listOf(LocalTime.of(8, 0)))
        val json = buildStopFeature(stop, qualifies, departures)
        val obj = Json.parseToJsonElement(json).jsonObject
        val props = obj["properties"]!!.jsonObject
        assertEquals("s1", props["stop_id"]?.jsonPrimitive?.content)
        assertEquals(true, props["qualifies_hourly_weekday"]?.jsonPrimitive?.boolean)
        assertEquals(false, props["qualifies_hourly_fullweek"]?.jsonPrimitive?.boolean)
        assertEquals(1, props["departures_MONDAY"]?.jsonPrimitive?.int)
        assertEquals(0, props["departures_SUNDAY"]?.jsonPrimitive?.int)
    }
}
