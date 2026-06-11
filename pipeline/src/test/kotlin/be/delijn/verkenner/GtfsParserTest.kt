package be.delijn.verkenner

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

class GtfsParserTest {

    @Test
    fun `parseWeekArg returns Monday of given ISO week`() {
        // 2026-W23 starts on Monday June 1, 2026
        assertEquals(LocalDate.of(2026, 6, 1), parseWeekArg("2026-W23"))
    }

    @Test
    fun `parseWeekArg handles year-boundary week`() {
        // 2026-W01 starts on Monday December 29, 2025
        assertEquals(LocalDate.of(2025, 12, 29), parseWeekArg("2026-W01"))
    }

    @Test
    fun `parseWeekArg throws on wrong format`() {
        assertThrows<IllegalArgumentException> { parseWeekArg("2026-07") }
        assertThrows<IllegalArgumentException> { parseWeekArg("2026-W7") }
        assertThrows<IllegalArgumentException> { parseWeekArg("26-W07") }
    }
}
