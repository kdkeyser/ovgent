package be.delijn.verkenner

enum class FilterMode(val key: String) {
    MIN1_DAY("min1"),
    MIN10_DAY("min10"),
    HOURLY("hourly"),
    HALF_HOURLY("halfhour")
}

enum class DayScope(val key: String) {
    WEEKDAY("weekday"),
    FULLWEEK("fullweek")
}

data class Stop(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double
)

data class RoutingResult(
    val walkingMinutes: Double,
    val nearestStopName: String
)

data class HexResult(
    val h3Index: String,
    val byMode: Map<FilterMode, Map<DayScope, RoutingResult?>>
)
