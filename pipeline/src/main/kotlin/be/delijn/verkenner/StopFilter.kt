package be.delijn.verkenner

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.temporal.ChronoUnit

private val WINDOW_START: LocalTime = LocalTime.of(7, 0)
private val WINDOW_END: LocalTime = LocalTime.of(21, 0)
private const val MAX_GAP_MINUTES = 30L

fun meetsFrequencyRule(departures: List<LocalTime>): Boolean {
    val inWindow = departures
        .filter { it >= WINDOW_START && it < WINDOW_END }
        .sorted()
    if (inWindow.isEmpty()) return false
    if (ChronoUnit.MINUTES.between(WINDOW_START, inWindow.first()) > MAX_GAP_MINUTES) return false
    for (i in 0 until inWindow.size - 1) {
        if (ChronoUnit.MINUTES.between(inWindow[i], inWindow[i + 1]) > MAX_GAP_MINUTES) return false
    }
    if (ChronoUnit.MINUTES.between(inWindow.last(), WINDOW_END) > MAX_GAP_MINUTES) return false
    return true
}

// A stop qualifies for a day if every hour in [WINDOW_START, WINDOW_END) has at least one departure.
fun hasHourlyService(departures: List<LocalTime>): Boolean {
    val hoursWithService = departures
        .filter { it >= WINDOW_START && it < WINDOW_END }
        .mapTo(mutableSetOf()) { it.hour }
    return (WINDOW_START.hour until WINDOW_END.hour).all { it in hoursWithService }
}

fun buildQualifyingStops(
    stopDepartures: Map<String, Map<DayOfWeek, List<LocalTime>>>,
    dayScope: DayScope
): Map<FilterMode, Set<String>> {
    val daysToCheck: List<DayOfWeek> = when (dayScope) {
        DayScope.WEEKDAY -> listOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
        )
        DayScope.FULLWEEK -> DayOfWeek.entries
    }

    fun meetsRule(departures: List<LocalTime>, mode: FilterMode): Boolean = when (mode) {
        FilterMode.MIN1_DAY -> departures.isNotEmpty()
        FilterMode.MIN10_DAY -> departures.size >= 10
        FilterMode.HOURLY -> hasHourlyService(departures)
        FilterMode.HALF_HOURLY -> meetsFrequencyRule(departures)
    }

    return FilterMode.entries.associateWith { mode ->
        stopDepartures.filter { (_, byDay) ->
            daysToCheck.all { day -> meetsRule(byDay[day].orEmpty(), mode) }
        }.keys
    }
}
