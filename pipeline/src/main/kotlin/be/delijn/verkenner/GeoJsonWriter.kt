package be.delijn.verkenner

import com.uber.h3core.util.LatLng
import java.time.DayOfWeek
import kotlin.math.pow
import kotlin.math.round
import kotlinx.serialization.json.*

fun buildHexFeature(result: HexResult, boundary: List<LatLng>): String {
    val closed = boundary + boundary.first()
    val feature = buildJsonObject {
        put("type", "Feature")
        put("geometry", buildJsonObject {
            put("type", "Polygon")
            put("coordinates", buildJsonArray {
                add(buildJsonArray {
                    closed.forEach { pt ->
                        add(buildJsonArray {
                            add(pt.lng.roundTo(6))
                            add(pt.lat.roundTo(6))
                        })
                    }
                })
            })
        })
        put("properties", buildJsonObject {
            put("h3index", result.h3Index)
            for (mode in FilterMode.entries) {
                for (scope in DayScope.entries) {
                    val r = result.byMode[mode]?.get(scope)
                    put("walking_minutes_${mode.key}_${scope.key}", r?.walkingMinutes?.roundTo(1)?.let(::JsonPrimitive) ?: JsonNull)
                    put("nearest_stop_${mode.key}_${scope.key}", r?.nearestStopName?.let(::JsonPrimitive) ?: JsonNull)
                }
            }
        })
    }
    return feature.toString()
}

fun buildStopFeature(
    stop: Stop,
    qualifiesByModeAndScope: Map<FilterMode, Map<DayScope, Boolean>>,
    departuresByDay: Map<DayOfWeek, List<*>>
): String {
    val feature = buildJsonObject {
        put("type", "Feature")
        put("geometry", buildJsonObject {
            put("type", "Point")
            put("coordinates", buildJsonArray {
                add(stop.lon.roundTo(6))
                add(stop.lat.roundTo(6))
            })
        })
        put("properties", buildJsonObject {
            put("stop_id", stop.id)
            put("stop_name", stop.name)
            for (mode in FilterMode.entries) {
                for (scope in DayScope.entries) {
                    put("qualifies_${mode.key}_${scope.key}", qualifiesByModeAndScope[mode]?.get(scope) ?: false)
                }
            }
            for (day in DayOfWeek.entries) put("departures_${day.name}", departuresByDay[day]?.size ?: 0)
        })
    }
    return feature.toString()
}

fun buildFeatureCollection(features: List<String>): String =
    """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

private fun Double.roundTo(decimals: Int): Double {
    val factor = 10.0.pow(decimals)
    return round(this * factor) / factor
}
