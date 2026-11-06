package nethical.digipaws

import nethical.digipaws.utils.DaySchedule
import nethical.digipaws.utils.DaySchedule.Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class DayScheduleTest {

    private val weekdays = listOf(
        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY
    )

    // 2026-11-02 is a Monday.
    private fun at(dayOfMonth: Int, hour: Int, minute: Int): Calendar =
        Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.NOVEMBER, dayOfMonth, hour, minute)
        }

    private val morning = Range(7 * 60 + 30, 11 * 60 + 30, weekdays)
    private val afternoon = Range(12 * 60 + 30, 16 * 60 + 30, weekdays)

    @Test
    fun splitWorkdayBlocksInsideBothWindows() {
        assertEquals(60, DaySchedule.minutesLeft(morning, at(2, 10, 30)))      // Mon 10:30
        assertEquals(30, DaySchedule.minutesLeft(afternoon, at(6, 16, 0)))     // Fri 16:00
    }

    @Test
    fun lunchBreakAndEdgesAreFree() {
        assertNull(DaySchedule.minutesLeft(morning, at(2, 11, 30)))            // end is exclusive
        assertNull(DaySchedule.minutesLeft(afternoon, at(2, 12, 0)))           // lunch
        assertEquals(240, DaySchedule.minutesLeft(morning, at(2, 7, 30)))      // start is inclusive
    }

    @Test
    fun weekendIsFree() {
        assertNull(DaySchedule.minutesLeft(morning, at(7, 10, 0)))             // Sat
        assertNull(DaySchedule.minutesLeft(afternoon, at(8, 13, 0)))           // Sun
    }

    @Test
    fun differentHoursOnDifferentDays() {
        val fridayShort = Range(8 * 60, 12 * 60, listOf(Calendar.FRIDAY))
        assertEquals(60, DaySchedule.minutesLeft(fridayShort, at(6, 11, 0)))   // Fri
        assertNull(DaySchedule.minutesLeft(fridayShort, at(5, 11, 0)))         // Thu
    }

    @Test
    fun entriesWithoutDaysApplyEveryDay() {
        val legacy = Range(9 * 60, 10 * 60, null)
        assertEquals(30, DaySchedule.minutesLeft(legacy, at(8, 9, 30)))        // Sun
        assertEquals(30, DaySchedule.minutesLeft(Range(9 * 60, 10 * 60, emptyList()), at(8, 9, 30)))
    }

    @Test
    fun overnightRangeBelongsToTheDayItStarts() {
        val fridayNight = Range(22 * 60, 2 * 60, listOf(Calendar.FRIDAY))
        assertEquals(240, DaySchedule.minutesLeft(fridayNight, at(6, 22, 0)))  // Fri 22:00, ends Sat 02:00
        assertEquals(60, DaySchedule.minutesLeft(fridayNight, at(7, 1, 0)))    // Sat 01:00
        assertNull(DaySchedule.minutesLeft(fridayNight, at(6, 1, 0)))          // Fri 01:00 = Thursday's night
        assertNull(DaySchedule.minutesLeft(fridayNight, at(7, 22, 30)))        // Sat 22:30
    }

    @Test
    fun sundayNightRunsIntoMonday() {
        val sundayNight = Range(23 * 60, 1 * 60, listOf(Calendar.SUNDAY))
        assertEquals(30, DaySchedule.minutesLeft(sundayNight, at(9, 0, 30)))   // Mon 00:30
    }

    @Test
    fun emptyRangeNeverMatches() {
        assertNull(DaySchedule.minutesLeft(Range(600, 600, null), at(2, 10, 0)))
    }

    @Test
    fun currentEndIsTheEndOfTheRunningOccurrence() {
        val now = at(2, 10, 30).apply { set(Calendar.SECOND, 42) }             // Mon 10:30:42
        assertEquals(at(2, 11, 30).timeInMillis, DaySchedule.currentEnd(morning, now))
        assertNull(DaySchedule.currentEnd(morning, at(2, 12, 0)))
        val fridayNight = Range(22 * 60, 2 * 60, listOf(Calendar.FRIDAY))
        assertEquals(at(7, 2, 0).timeInMillis, DaySchedule.currentEnd(fridayNight, at(6, 23, 0)))
    }

    @Test
    fun endingEarlySkipsOnlyTheRunningOccurrence() {
        // "End now" on Monday at 10:30 stores the end of that occurrence.
        val endedUntil = DaySchedule.currentEnd(morning, at(2, 10, 30))
        val ended = morning.copy(endedUntil = endedUntil)
        assertNull(DaySchedule.minutesLeft(ended, at(2, 10, 31)))
        assertEquals(true, DaySchedule.isEndedEarly(ended, at(2, 11, 29)))
        // The afternoon entry is separate and still blocks.
        assertEquals(60, DaySchedule.minutesLeft(afternoon, at(2, 15, 30)))
        // Tuesday morning blocks again, and the "ended" mark is gone.
        assertEquals(240, DaySchedule.minutesLeft(ended, at(3, 7, 30)))
        assertEquals(false, DaySchedule.isEndedEarly(ended, at(3, 7, 30)))
    }

    @Test
    fun labels() {
        Locale.setDefault(Locale.ENGLISH)
        assertEquals("Every day", DaySchedule.label(null, "Every day"))
        assertEquals("Every day", DaySchedule.label(DaySchedule.WEEK, "Every day"))
        assertEquals("Mon–Fri", DaySchedule.label(weekdays, "Every day"))
        assertEquals(
            "Mon, Tue, Fri",
            DaySchedule.label(listOf(Calendar.FRIDAY, Calendar.MONDAY, Calendar.TUESDAY), "Every day")
        )
        assertEquals("Sat, Sun", DaySchedule.label(listOf(Calendar.SATURDAY, Calendar.SUNDAY), "Every day"))
    }
}
