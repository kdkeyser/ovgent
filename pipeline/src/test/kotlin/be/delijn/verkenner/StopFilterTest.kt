package be.delijn.verkenner

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.DayOfWeek
import java.time.LocalTime

class StopFilterTest {

    @Test
    fun `qualifies when departures cover the full window with gaps at most 30 minutes`() {
        val departures = (0..27).map { i -> LocalTime.of(7, 0).plusMinutes(i * 30L) }
        assertTrue(meetsFrequencyRule(departures))
    }

    @Test
    fun `fails when a single gap exceeds 30 minutes`() {
        val departures = listOf("07:00", "07:25", "08:05", "08:30")
            .map { LocalTime.parse(it) }
        assertFalse(meetsFrequencyRule(departures))
    }

    @Test
    fun `fails when fewer than 2 departures in window`() {
        val departures = listOf("12:00").map { LocalTime.parse(it) }
        assertFalse(meetsFrequencyRule(departures))
    }

    @Test
    fun `ignores departures outside the 07 to 21 window`() {
        val departures: List<LocalTime> = listOf(LocalTime.of(6, 50)) +
            (0..27).map { i -> LocalTime.of(7, 0).plusMinutes(i * 30L) }
        assertTrue(meetsFrequencyRule(departures))
    }

    @Test
    fun `fails when only two departures near window start leave a huge end gap`() {
        val departures = listOf("07:00", "07:15", "21:30")
            .map { LocalTime.parse(it) }
        assertFalse(meetsFrequencyRule(departures))
    }

    @Test
    fun `fails when a large gap exists between window start and first departure`() {
        val departures = listOf("08:00", "08:15", "20:30")
            .map { LocalTime.parse(it) }
        assertFalse(meetsFrequencyRule(departures))
    }

    @Test
    fun `gap exactly 30 minutes fails due to large gap elsewhere`() {
        val departures = listOf("07:00", "07:30", "08:00", "21:00")
            .map { LocalTime.parse(it) }
        assertFalse(meetsFrequencyRule(departures))
    }

    @Test
    fun `gap of 31 minutes fails`() {
        val departures = listOf("07:00", "07:31", "08:02")
            .map { LocalTime.parse(it) }
        assertFalse(meetsFrequencyRule(departures))
    }

    // hasHourlyService

    @Test
    fun `hasHourlyService passes when every hour 07 to 20 has a departure`() {
        val departures = (7..20).map { h -> LocalTime.of(h, 0) }
        assertTrue(hasHourlyService(departures))
    }

    @Test
    fun `hasHourlyService fails when one hour in window is missing`() {
        val departures = (7..20).filter { it != 12 }.map { h -> LocalTime.of(h, 0) }
        assertFalse(hasHourlyService(departures))
    }

    // buildQualifyingStops — FULLWEEK scope

    @Test
    fun `weekday-only stop does not qualify for any mode with FULLWEEK scope`() {
        val frequent = (0..27).map { i -> LocalTime.of(7, 0).plusMinutes(i * 30L) }
        val byDay = DayOfWeek.entries
            .filter { it.value <= 5 }
            .associateWith { frequent }
        val result = buildQualifyingStops(mapOf("stop1" to byDay), DayScope.FULLWEEK)
        FilterMode.entries.forEach { mode ->
            assertFalse(result[mode]!!.contains("stop1"), "${mode.key} should not qualify")
        }
    }

    @Test
    fun `stop with 1 departure on all 7 days qualifies for MIN1_DAY only with FULLWEEK scope`() {
        val one = listOf(LocalTime.of(12, 0))
        val byDay = DayOfWeek.entries.associateWith { one }
        val result = buildQualifyingStops(mapOf("stop1" to byDay), DayScope.FULLWEEK)
        assertTrue(result[FilterMode.MIN1_DAY]!!.contains("stop1"))
        assertFalse(result[FilterMode.MIN10_DAY]!!.contains("stop1"))
        assertFalse(result[FilterMode.HOURLY]!!.contains("stop1"))
        assertFalse(result[FilterMode.HALF_HOURLY]!!.contains("stop1"))
    }

    @Test
    fun `stop with 10 departures on all days qualifies for MIN10_DAY, 9 on one day does not`() {
        val ten = (0..9).map { i -> LocalTime.of(7, 0).plusMinutes(i * 60L) }
        val nine = ten.take(9)
        val allTen = DayOfWeek.entries.associateWith { ten }
        val oneNine = DayOfWeek.entries.associateWith { day -> if (day == DayOfWeek.SUNDAY) nine else ten }
        val r1 = buildQualifyingStops(mapOf("stop1" to allTen), DayScope.FULLWEEK)
        assertTrue(r1[FilterMode.MIN10_DAY]!!.contains("stop1"))
        val r2 = buildQualifyingStops(mapOf("stop2" to oneNine), DayScope.FULLWEEK)
        assertFalse(r2[FilterMode.MIN10_DAY]!!.contains("stop2"))
    }

    // buildQualifyingStops — WEEKDAY scope

    @Test
    fun `weekday-only stop qualifies for all modes with WEEKDAY scope given sufficient service`() {
        val frequent = (0..27).map { i -> LocalTime.of(7, 0).plusMinutes(i * 30L) }
        val byDay = DayOfWeek.entries
            .filter { it.value <= 5 }
            .associateWith { frequent }
        val result = buildQualifyingStops(mapOf("stop1" to byDay), DayScope.WEEKDAY)
        FilterMode.entries.forEach { mode ->
            assertTrue(result[mode]!!.contains("stop1"), "${mode.key} should qualify for WEEKDAY scope")
        }
    }

    @Test
    fun `stop with weekend-only service does not qualify for WEEKDAY scope`() {
        val one = listOf(LocalTime.of(12, 0))
        val byDay = mapOf(DayOfWeek.SATURDAY to one, DayOfWeek.SUNDAY to one)
        val result = buildQualifyingStops(mapOf("stop1" to byDay), DayScope.WEEKDAY)
        FilterMode.entries.forEach { mode ->
            assertFalse(result[mode]!!.contains("stop1"), "${mode.key} should not qualify — weekday days have no departures")
        }
    }

    @Test
    fun `stop with 9 departures on one weekday does not qualify for MIN10_DAY with WEEKDAY scope`() {
        val ten = (0..9).map { i -> LocalTime.of(7, 0).plusMinutes(i * 60L) }
        val nine = ten.take(9)
        val byDay = DayOfWeek.entries
            .filter { it.value <= 5 }
            .associateWith { day -> if (day == DayOfWeek.WEDNESDAY) nine else ten }
        val result = buildQualifyingStops(mapOf("stop1" to byDay), DayScope.WEEKDAY)
        assertFalse(result[FilterMode.MIN10_DAY]!!.contains("stop1"))
    }
}
