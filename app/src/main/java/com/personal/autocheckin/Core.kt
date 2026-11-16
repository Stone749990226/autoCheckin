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

/** Fixed retry cadence: four rounds of 150 seconds, one round every three minutes. */
object RetryPlan {
    const val ROUNDS = 4
    /** Round 1 and 2 run back to back; DingTalk is cleared and relaunched for rounds 3 and 4. */
    const val ROUNDS_PER_BATCH = 2
    const val WINDOW_MS = 150_000L
    const val PERIOD_MS = 180_000L
    /** Four rounds end 11.5 minutes after the trigger; keep the other slot further out than that. */
    const val SLOT_GAP_MINUTES = 15
}

/**
 * English wording for outgoing mail only: subjects and bodies never contain the Chinese task
 * wording, while the in-app interface and logs stay Chinese for troubleshooting.
 */
object MailText {
    const val APP = "Helper"

    fun task(slot: PunchSlot): String = when (slot) {
        PunchSlot.CLOCK_IN -> "Morning task"
        PunchSlot.CLOCK_OUT -> "Evening task"
    }

    /** Same as [task], for call sites that only kept the Chinese slot label. */
    fun task(label: String): String = if (label.contains("上班")) "Morning task" else "Evening task"

    fun result(outcome: RunOutcome): String = when (outcome) {
        RunOutcome.SUCCESS -> "completed"
        RunOutcome.UNCONFIRMED -> "needs review"
        RunOutcome.ERROR -> "error"
        RunOutcome.CANCELLED -> "cancelled"
        else -> "finished"
    }

    fun headline(outcome: RunOutcome): String = result(outcome).replaceFirstChar { it.uppercase() }

    /** Mail-only translation; the Chinese detail is still what the local log records. */
    fun reason(detail: String): String = when {
        detail.startsWith("缺少条件：") -> "Missing requirement: " +
            detail.removePrefix("缺少条件：").split("、").joinToString(", ") { requirement(it) }
        detail.startsWith("后台唤醒失败：") -> "Wake-up failed: " + detail.substringAfter("：")
        detail.startsWith("启动异常：") -> "Startup failed: " + detail.substringAfter("：")
        detail.startsWith("启动钉钉失败：") -> "Could not open DingTalk: " + detail.substringAfter("：")
        detail.startsWith("执行点击时") -> "Accessibility service disconnected before the configured click"
        detail == "找不到钉钉启动入口" -> "DingTalk launch entry not found"
        detail == "无障碍服务未连接" -> "Accessibility service not connected"
        detail == "检测到安全锁屏，无法自动解锁" -> "Secure lock screen detected, cannot unlock automatically"
        detail == "系统拒绝解除无密码锁屏" -> "System refused to dismiss the swipe lock"
        detail == "解除锁屏被取消" -> "Keyguard dismissal was cancelled"
        detail == "设备管理员权限被撤销" -> "Device admin permission was revoked"
        detail.startsWith("无障碍服务被系统中断") -> "Accessibility service was interrupted by the system"
        detail.startsWith("无障碍服务已断开") -> "Accessibility service disconnected"
        detail.startsWith("无障碍服务已销毁") -> "Accessibility service was destroyed"
        else -> "See the in-app log for details"
    }

    private fun requirement(name: String): String = when {
        name.startsWith("精确闹钟") -> "exact alarm"
        name.startsWith("通知") -> "notifications"
        name.startsWith("电池优化豁免") -> "battery optimization exemption"
        name.startsWith("无障碍") -> "accessibility service"
        name.startsWith("设备管理员") -> "device admin"
        name.startsWith("未安装钉钉") -> "DingTalk not installed"
        name.startsWith("请关闭密码") -> "secure lock screen must be removed"
        else -> name
    }
}

object ScheduleTime {
    fun next(now: ZonedDateTime, hour: Int, minute: Int): ZonedDateTime {
        require(hour in 0..23 && minute in 0..59)
        val today = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    fun nextOccurrences(now: ZonedDateTime, schedules: List<PunchSchedule>): Map<PunchSlot, ZonedDateTime> =
        schedules.filter { it.enabled }.associate { it.slot to next(now, it.hour, it.minute) }

    // Four rounds take 11.5 minutes; require the other slot to start after that, otherwise its
    // alarm would arrive while this run is still alive and be discarded as a duplicate.
    fun hasConflict(schedules: List<PunchSchedule>): Boolean {
        val minutes = schedules.filter { it.enabled }.map { it.hour * 60 + it.minute }
        return minutes.indices.any { first ->
            (first + 1 until minutes.size).any { second ->
                val gap = kotlin.math.abs(minutes[first] - minutes[second])
                minOf(gap, 1440 - gap) < RetryPlan.SLOT_GAP_MINUTES
            }
        }
    }
}

enum class RunOutcome { RUNNING, SUCCESS, UNCONFIRMED, ERROR, CANCELLED }

/** The only authority for round count and terminal state; callbacks cannot finish twice. */
class AttemptTracker {
    var round: Int = 1
        private set
    var outcome: RunOutcome = RunOutcome.RUNNING
        private set

    /** Records one failed round. Returns true when another round should start. */
    fun roundFailed(): Boolean {
        if (outcome != RunOutcome.RUNNING) return false
        if (round >= RetryPlan.ROUNDS) {
            outcome = RunOutcome.UNCONFIRMED
            return false
        }
        round++
        return true
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
