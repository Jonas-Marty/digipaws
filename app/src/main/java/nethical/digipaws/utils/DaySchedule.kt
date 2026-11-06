package nethical.digipaws.utils

import java.text.DateFormatSymbols
import java.util.Calendar

/**
 * Weekday support for timed actions (Auto-Focus hours and App Blocker cheat hours).
 *
 * Days are [Calendar.DAY_OF_WEEK] values. A null or empty list means every day, which is
 * what entries saved before weekdays existed deserialize to.
 */
object DaySchedule {

    /** Display order, Monday first. */
    val WEEK = listOf(
        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
        Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
    )

    /**
     * @property endedUntil wall-clock millis until which the range was ended early ("End now");
     * null when it never was. Only the occurrence running at that moment is skipped.
     */
    data class Range(
        val startMinutes: Int,
        val endMinutes: Int,
        val days: List<Int>?,
        val endedUntil: Long? = null
    )

    fun isEndedEarly(range: Range, now: Calendar): Boolean =
        range.endedUntil != null && now.timeInMillis < range.endedUntil

    fun appliesOn(days: List<Int>?, dayOfWeek: Int): Boolean =
        days.isNullOrEmpty() || dayOfWeek in days

    private fun previousDay(dayOfWeek: Int): Int =
        if (dayOfWeek == Calendar.SUNDAY) Calendar.SATURDAY else dayOfWeek - 1

    /**
     * Minutes until [range] ends if [now] falls inside it, else null.
     *
     * A range that crosses midnight belongs to the day it starts on: Friday 22:00-02:00 also
     * covers Saturday 00:00-02:00, but not Friday 00:00-02:00.
     */
    fun minutesLeft(range: Range, now: Calendar): Int? {
        if (isEndedEarly(range, now)) return null
        val current = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val day = now.get(Calendar.DAY_OF_WEEK)
        val (start, end, days) = range

        if (start < end) {
            return if (current in start until end && appliesOn(days, day)) end - current else null
        }
        if (start > end) {
            if (current >= start && appliesOn(days, day)) return end + 24 * 60 - current
            if (current < end && appliesOn(days, previousDay(day))) return end - current
        }
        return null
    }

    /** Wall-clock millis at which the occurrence running at [now] ends, or null if none runs. */
    fun currentEnd(range: Range, now: Calendar): Long? {
        val minutesLeft = minutesLeft(range, now) ?: return null
        return (now.clone() as Calendar).apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, minutesLeft)
        }.timeInMillis
    }

    /** "Every day", "Mon–Fri", or "Mon, Tue, Fri" in the device language. */
    fun label(days: List<Int>?, everyDay: String): String {
        if (days.isNullOrEmpty() || days.toSet().containsAll(WEEK)) return everyDay
        val selected = WEEK.filter { it in days }
        val names = DateFormatSymbols.getInstance().shortWeekdays
        val firstIndex = WEEK.indexOf(selected.first())
        val contiguous = selected.size >= 3 &&
            selected == WEEK.subList(firstIndex, firstIndex + selected.size)
        return if (contiguous) {
            "${names[selected.first()]}–${names[selected.last()]}"
        } else {
            selected.joinToString(", ") { names[it] }
        }
    }

    fun shortName(dayOfWeek: Int): String = DateFormatSymbols.getInstance().shortWeekdays[dayOfWeek]
}
