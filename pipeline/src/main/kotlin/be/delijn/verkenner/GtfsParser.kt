package be.delijn.verkenner

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import java.io.FileReader
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")

// Bounding box: Ghent area + ~3 km buffer to include stops near the city border
// 3 km ≈ 0.027° latitude, 0.043° longitude at 51°N
private const val LAT_MIN = 50.953
private const val LAT_MAX = 51.197
private const val LON_MIN = 3.577
private const val LON_MAX = 3.923

fun parseStops(gtfsDir: String): Map<String, Stop> {
    val format = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).build()
    return FileReader("$gtfsDir/stops.txt").use { reader ->
        CSVParser(reader, format).associate { row ->
            val id = row["stop_id"]
            val lat = row["stop_lat"].toDouble()
            val lon = row["stop_lon"].toDouble()
            id to Stop(id = id, name = row["stop_name"], lat = lat, lon = lon)
        }.filter { (_, stop) ->
            stop.lat in LAT_MIN..LAT_MAX && stop.lon in LON_MIN..LON_MAX
        }
    }
}

// Returns: service_id -> Set<LocalDate>
fun parseCalendarDates(gtfsDir: String): Map<String, Set<LocalDate>> {
    val format = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).build()
    val result = mutableMapOf<String, MutableSet<LocalDate>>()
    FileReader("$gtfsDir/calendar_dates.txt").use { reader ->
        CSVParser(reader, format).forEach { row ->
            if (row["exception_type"] == "1") {
                val date = LocalDate.parse(row["date"], DATE_FMT)
                result.getOrPut(row["service_id"]) { mutableSetOf() }.add(date)
            }
        }
    }
    return result
}

// Returns: service_id -> Set<trip_id>
fun parseTrips(gtfsDir: String): Map<String, Set<String>> {
    val format = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).build()
    val result = mutableMapOf<String, MutableSet<String>>()
    FileReader("$gtfsDir/trips.txt").use { reader ->
        CSVParser(reader, format).forEach { row ->
            result.getOrPut(row["service_id"]) { mutableSetOf() }.add(row["trip_id"])
        }
    }
    return result
}

// Returns: stop_id -> DayOfWeek -> List<LocalTime>
// Uses one representative date per day-of-week from a complete week in the data.
fun parseDeparturesPerStopPerDay(
    gtfsDir: String,
    ghentStopIds: Set<String>,
    serviceCalendar: Map<String, Set<LocalDate>>,
    serviceToTrips: Map<String, Set<String>>
): Map<String, Map<DayOfWeek, List<LocalTime>>> {

    val representativeWeek = findRepresentativeWeek(serviceCalendar)

    // date -> Set<trip_id> for the 7 representative dates
    val dateToTripIds: Map<LocalDate, Set<String>> = representativeWeek.values.associateWith { date ->
        serviceCalendar
            .filter { (_, dates) -> date in dates }
            .keys
            .flatMapTo(mutableSetOf()) { serviceId -> serviceToTrips[serviceId] ?: emptySet() }
    }

    // trip_id -> Set<DayOfWeek> (all days the trip is active in the representative week)
    val tripToDaysOfWeek: Map<String, Set<DayOfWeek>> = run {
        val tripToDates = mutableMapOf<String, MutableSet<LocalDate>>()
        for ((date, tripIds) in dateToTripIds) {
            for (tripId in tripIds) {
                tripToDates.getOrPut(tripId) { mutableSetOf() }.add(date)
            }
        }
        tripToDates.mapValues { (_, dates) -> dates.mapTo(mutableSetOf()) { it.dayOfWeek } }
    }

    val result = mutableMapOf<String, MutableMap<DayOfWeek, MutableList<LocalTime>>>()

    val format = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).build()
    FileReader("$gtfsDir/stop_times.txt").use { reader ->
        CSVParser(reader, format).forEach { row ->
            val stopId = row["stop_id"]
            if (stopId !in ghentStopIds) return@forEach
            val daysOfWeek = tripToDaysOfWeek[row["trip_id"]] ?: return@forEach
            val time = parseGtfsTime(row["departure_time"]) ?: return@forEach
            for (dayOfWeek in daysOfWeek) {
                result
                    .getOrPut(stopId) { mutableMapOf() }
                    .getOrPut(dayOfWeek) { mutableListOf() }
                    .add(time)
            }
        }
    }
    return result
}

// GTFS times can exceed 24:00:00 for overnight trips — discard them.
private fun parseGtfsTime(s: String): LocalTime? {
    val parts = s.split(":").map { it.toIntOrNull() ?: return null }
    if (parts.size != 3) return null
    if (parts[0] >= 24) return null  // overnight trip from previous day — discard
    return LocalTime.of(parts[0], parts[1], parts[2])
}

private fun findRepresentativeWeek(serviceCalendar: Map<String, Set<LocalDate>>): Map<DayOfWeek, LocalDate> {
    val allDates = serviceCalendar.values.flatten().toSortedSet()
    val midDate = allDates.toList()[allDates.size / 2]

    fun searchFrom(start: LocalDate, forward: Boolean): Map<DayOfWeek, LocalDate>? {
        var monday = start
        while (monday.dayOfWeek != DayOfWeek.MONDAY) monday = monday.minusDays(1)
        repeat(52) {
            val week = (0L..6L).map { monday.plusDays(it) }
            if (week.all { it in allDates }) {
                println("  Representative week: $monday .. ${monday.plusDays(6)}")
                return DayOfWeek.entries.associateWith { dow -> week[dow.value - 1] }
            }
            monday = if (forward) monday.plusWeeks(1) else monday.minusWeeks(1)
        }
        return null
    }

    return searchFrom(midDate, forward = true)
        ?: searchFrom(midDate, forward = false)
        ?: error("Could not find a complete representative week in GTFS data")
}
