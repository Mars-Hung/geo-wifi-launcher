package tw.geowifi.launcher.ui

import android.content.Context
import java.time.DayOfWeek
import tw.geowifi.launcher.R
import tw.geowifi.launcher.core.rule.BluetoothAtom
import tw.geowifi.launcher.core.rule.ChargeType
import tw.geowifi.launcher.core.rule.ChargingAtom
import tw.geowifi.launcher.core.rule.Clause
import tw.geowifi.launcher.core.rule.RegionAtom
import tw.geowifi.launcher.core.rule.WifiAtom
import tw.geowifi.launcher.core.schedule.WeekSchedule
import tw.geowifi.launcher.core.rule.LocationTransition

fun formatDwell(seconds: Int, immediate: String): String {
    if (seconds <= 0) return immediate
    val minutes = seconds / 60
    val remain = seconds % 60
    return if (remain == 0) "$minutes:00" else "${seconds}s"
}

fun summarizeClauses(context: Context, clauses: List<Clause>): String {
    val and = context.getString(R.string.summary_and)
    val or = context.getString(R.string.summary_or)
    return clauses.joinToString(and) { clause ->
        val text = clause.options.joinToString(or) { atom ->
            when (atom) {
                is RegionAtom -> if (atom.transition == LocationTransition.EXIT) {
                    context.getString(R.string.summary_exit, atom.radiusMeters.toInt())
                } else {
                    context.getString(
                        R.string.summary_enter,
                        atom.radiusMeters.toInt(),
                        formatDwell((atom.dwellMillis / 1000).toInt(), context.getString(R.string.editor_dwell_immediate)),
                    )
                }
                is WifiAtom -> context.getString(
                    R.string.summary_wifi,
                    atom.ssid,
                    atom.minRssiDbm,
                    formatDwell((atom.dwellMillis / 1000).toInt(), context.getString(R.string.editor_dwell_immediate)),
                )
                is ChargingAtom -> if (atom.types.isEmpty()) {
                    context.getString(R.string.summary_charging_any)
                } else {
                    context.getString(R.string.summary_charging, atom.types.joinToString("、") { chargeLabel(context, it) })
                }
                is BluetoothAtom -> context.getString(
                    R.string.summary_bt,
                    atom.deviceName.ifBlank { atom.address },
                )
            }
        }
        if (clause.options.size > 1) "（$text）" else text
    }
}

fun summarizeSchedule(context: Context, schedule: WeekSchedule?): String {
    if (schedule == null) return context.getString(R.string.summary_any_time)
    val labels = context.resources.getStringArray(R.array.weekday_labels)
    val days = if (schedule.days.size == 7 || schedule.days.isEmpty()) {
        context.getString(R.string.summary_every_day)
    } else {
        groupDays(schedule.days).joinToString(context.getString(R.string.summary_or).trim()) { (start, end) ->
            val a = labels[start.ordinal]
            val b = labels[end.ordinal]
            if (start == end) a else a + context.getString(R.string.summary_through) + b
        }
    }
    if (schedule.ranges.isEmpty() || schedule.ranges.any { it.fullDay }) {
        return "$days ${context.getString(R.string.summary_all_day)}"
    }
    val ranges = schedule.ranges.joinToString("、") { range ->
        val text = "${clock(range.startMinute)}–${clock(range.endMinute)}"
        if (range.crossesMidnight) text + context.getString(R.string.summary_cross) else text
    }
    return "$days $ranges"
}

fun chargeLabel(context: Context, type: ChargeType): String {
    return when (type) {
        ChargeType.USB -> context.getString(R.string.editor_charge_usb)
        ChargeType.AC -> context.getString(R.string.editor_charge_ac)
        ChargeType.WIRELESS -> context.getString(R.string.editor_charge_wireless)
    }
}

fun clock(minuteOfDay: Int): String {
    val hour = minuteOfDay / 60
    val minute = minuteOfDay % 60
    return "%02d:%02d".format(hour, minute)
}

private fun groupDays(days: Set<DayOfWeek>): List<Pair<DayOfWeek, DayOfWeek>> {
    val ordered = DayOfWeek.entries.filter { it in days }
    if (ordered.isEmpty()) return emptyList()
    val groups = ArrayList<Pair<DayOfWeek, DayOfWeek>>()
    var start = ordered.first()
    var previous = ordered.first()
    for (day in ordered.drop(1)) {
        if (previous.plus(1) == day) {
            previous = day
        } else {
            groups += start to previous
            start = day
            previous = day
        }
    }
    groups += start to previous
    return groups
}
