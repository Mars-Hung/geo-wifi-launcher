package tw.geowifi.launcher.core.schedule

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

data class TimeRange(
    val startMinute: Int,
    val endMinute: Int,
) {
    init {
        require(startMinute in 0..1439) { "startMinute" }
        require(endMinute in 0..1439) { "endMinute" }
    }

    val crossesMidnight: Boolean get() = endMinute < startMinute
    val fullDay: Boolean get() = startMinute == endMinute
}

data class WeekSchedule(
    val days: Set<DayOfWeek>,
    val ranges: List<TimeRange>,
)

object TimeWindows {
    fun isActiveAt(schedule: WeekSchedule?, local: LocalDateTime): Boolean {
        if (schedule == null) return true
        if (schedule.days.isEmpty() && schedule.ranges.isEmpty()) return true
        val days = effectiveDays(schedule)
        val minute = local.hour * 60 + local.minute
        val today = local.dayOfWeek
        val yesterday = today.minus(1)
        if (schedule.ranges.isEmpty()) return today in days
        return schedule.ranges.any { range ->
            when {
                range.fullDay -> today in days
                range.startMinute < range.endMinute ->
                    today in days && minute >= range.startMinute && minute < range.endMinute
                else ->
                    (today in days && minute >= range.startMinute) ||
                        (yesterday in days && minute < range.endMinute)
            }
        }
    }

    fun nextBoundary(schedule: WeekSchedule?, now: LocalDateTime): LocalDateTime? {
        if (schedule == null || (schedule.days.isEmpty() && schedule.ranges.isEmpty())) return null
        val merged = merge(concreteIntervals(schedule, now.toLocalDate().minusDays(1), now.toLocalDate().plusDays(14)))
        return merged
            .flatMap { listOf(it.start, it.end) }
            .filter { it.isAfter(now) }
            .minOrNull()
    }

    private fun effectiveDays(schedule: WeekSchedule): Set<DayOfWeek> {
        return if (schedule.days.isEmpty()) DayOfWeek.entries.toSet() else schedule.days
    }

    private data class Interval(val start: LocalDateTime, val end: LocalDateTime)

    private fun concreteIntervals(schedule: WeekSchedule, from: LocalDate, to: LocalDate): List<Interval> {
        val days = effectiveDays(schedule)
        val ranges = if (schedule.ranges.isEmpty()) listOf(TimeRange(0, 0)) else schedule.ranges
        val out = ArrayList<Interval>()
        var date = from
        while (!date.isAfter(to)) {
            if (date.dayOfWeek in days) {
                for (range in ranges) {
                    if (range.fullDay) {
                        out += Interval(date.atStartOfDay(), date.plusDays(1).atStartOfDay())
                    } else if (range.startMinute < range.endMinute) {
                        out += Interval(
                            date.atStartOfDay().plusMinutes(range.startMinute.toLong()),
                            date.atStartOfDay().plusMinutes(range.endMinute.toLong()),
                        )
                    } else {
                        out += Interval(
                            date.atStartOfDay().plusMinutes(range.startMinute.toLong()),
                            date.plusDays(1).atStartOfDay().plusMinutes(range.endMinute.toLong()),
                        )
                    }
                }
            }
            date = date.plusDays(1)
        }
        return out
    }

    private fun merge(intervals: List<Interval>): List<Interval> {
        val sorted = intervals.sortedBy { it.start }
        val out = ArrayList<Interval>()
        for (interval in sorted) {
            val last = out.lastOrNull()
            if (last == null || interval.start.isAfter(last.end)) {
                out += interval
            } else if (interval.end.isAfter(last.end)) {
                out[out.lastIndex] = Interval(last.start, interval.end)
            }
        }
        return out
    }
}
