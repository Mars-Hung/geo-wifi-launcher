package tw.geowifi.launcher.core.schedule

import java.time.DayOfWeek

object ScheduleCodec {
    fun encodeDays(days: Set<DayOfWeek>): String {
        return days.sortedBy { it.value }.joinToString(",") { it.name }
    }

    fun decodeDays(raw: String): Set<DayOfWeek> {
        if (raw.isBlank()) return emptySet()
        return raw.split(',').filter { it.isNotBlank() }.map { DayOfWeek.valueOf(it.trim()) }.toSet()
    }

    fun encodeRanges(ranges: List<TimeRange>): String {
        return ranges.joinToString(",") { "${it.startMinute}-${it.endMinute}" }
    }

    fun decodeRanges(raw: String): List<TimeRange> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',').filter { it.isNotBlank() }.map { token ->
            val parts = token.split('-')
            require(parts.size == 2) { token }
            TimeRange(parts[0].toInt(), parts[1].toInt())
        }
    }
}
