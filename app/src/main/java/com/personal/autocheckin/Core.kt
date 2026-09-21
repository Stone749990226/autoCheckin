package com.personal.autocheckin

import java.time.ZonedDateTime

enum class PunchSlot(val key: String, val label: String, val requestCode: Int, val defaultHour: Int, val defaultMinute: Int) {
    CLOCK_IN("clock_in", "上班卡", 110, 8, 30),
    CLOCK_OUT("clock_out", "下班卡", 120, 18, 0);

    companion object {
        fun fromKey(key: String?): PunchSlot? = entries.firstOrNull { it.key == key }
    }
}

data class PunchSchedule(val slot: PunchSlot, val enabled: Boolean, val hour: Int, val minute: Int) {
    init { require(hour in 0..23 && minute in 0..59) }
}

object ScheduleTime {
    fun next(now: ZonedDateTime, hour: Int, minute: Int): ZonedDateTime {
        require(hour in 0..23 && minute in 0..59)
        val today = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    fun nextOccurrences(now: ZonedDateTime, schedules: List<PunchSchedule>): Map<PunchSlot, ZonedDateTime> =
        schedules.filter { it.enabled }.associate { it.slot to next(now, it.hour, it.minute) }

    // Two attempts take two minutes; leave cleanup time, including across midnight.
    fun hasConflict(schedules: List<PunchSchedule>): Boolean {
        val minutes = schedules.filter { it.enabled }.map { it.hour * 60 + it.minute }
        return minutes.indices.any { first ->
            (first + 1 until minutes.size).any { second ->
                val gap = kotlin.math.abs(minutes[first] - minutes[second])
                minOf(gap, 1440 - gap) < 3
            }
        }
    }
}

enum class RunOutcome { RUNNING, SUCCESS, UNCONFIRMED, ERROR, CANCELLED }

/** The only authority for attempt count and terminal state; callbacks cannot finish twice. */
class AttemptTracker {
    var attempt: Int = 1
        private set
    var outcome: RunOutcome = RunOutcome.RUNNING
        private set

    fun timeout(): Boolean {
        if (outcome != RunOutcome.RUNNING) return false
        if (attempt == 1) {
            attempt = 2
            return true
        }
        outcome = RunOutcome.UNCONFIRMED
        return false
    }

    fun finish(result: RunOutcome): Boolean {
        require(result != RunOutcome.RUNNING)
        if (outcome != RunOutcome.RUNNING) return false
        outcome = result
        return true
    }
}

object SuccessMatcher {
    fun match(text: String, keywords: List<String>, slot: PunchSlot? = null): String? {
        // Do not confuse a failure or explanatory sentence with a confirmation.
        val excluded = listOf("失败", "未打卡", "未成功", "不成功", "未能", "打卡成功率", "打卡成功后", "打卡成功时", "打卡成功提示", "打卡成功通知")
        return text.lineSequence().map { it.trim() }.firstOrNull { line ->
            line.isNotEmpty() && line.length <= 160 && excluded.none(line::contains) &&
                !(slot == PunchSlot.CLOCK_IN && line.contains("下班")) &&
                !(slot == PunchSlot.CLOCK_OUT && line.contains("上班")) &&
                keywords.any { it.isNotBlank() && line.contains(it.trim()) }
        }
    }
}

data class ClickConfig(
    val enabled: Boolean = false,
    val text: String = "",
    val description: String = "",
    val resourceId: String = "",
    val coordinateEnabled: Boolean = false,
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val rotation: Int = 0,
) {
    fun coordinatesValidFor(actualWidth: Int, actualHeight: Int, actualRotation: Int): Boolean =
        coordinateEnabled && width == actualWidth && height == actualHeight && rotation == actualRotation &&
            x in 0 until width && y in 0 until height
}
