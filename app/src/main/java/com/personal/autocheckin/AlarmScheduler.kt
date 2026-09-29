package com.personal.autocheckin

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.ZonedDateTime

object AlarmScheduler {
    const val FULL_TEST_DELAY_MS = 5_000L
    private const val TEST_REQUEST_CODE_OFFSET = 1_000
    private const val TEST_ACTION = "com.personal.autocheckin.RUN_TEST"

    private fun pending(context: Context, slot: PunchSlot, at: Long = 0) = PendingIntent.getBroadcast(
        context, slot.requestCode, Intent(context, AlarmReceiver::class.java)
            .setAction("com.personal.autocheckin.RUN.${slot.key}")
            .putExtra("slot", slot.key).putExtra("scheduledAt", at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun testPending(context: Context, slot: PunchSlot) = PendingIntent.getBroadcast(
        context, slot.requestCode + TEST_REQUEST_CODE_OFFSET,
        Intent(context, AlarmReceiver::class.java).setAction(TEST_ACTION).putExtra("slot", slot.key),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun scheduleFullTest(context: Context, slot: PunchSlot): Boolean = try {
        val at = System.currentTimeMillis() + FULL_TEST_DELAY_MS
        val open = PendingIntent.getActivity(context, 102 + slot.requestCode,
            Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Use the same user-visible alarm-clock path as production. Honor delays an
        // ordinary exact-while-idle alarm until the user manually turns the screen on.
        context.getSystemService(AlarmManager::class.java)
            .setAlarmClock(AlarmManager.AlarmClockInfo(at, open), testPending(context, slot))
        RunLog.write(context, null, "TEST_SCHEDULED", "${slot.label}：5 秒后开始亮屏、启动钉钉、等待结果并锁屏")
        true
    } catch (e: SecurityException) {
        RunLog.write(context, null, "START_ERROR", "${slot.label}完整流程测试安排失败：${e.javaClass.simpleName}")
        false
    }

    fun cancelLegacy(context: Context) {
        val old = PendingIntent.getBroadcast(context, 100,
            Intent(context, AlarmReceiver::class.java).setAction("com.personal.autocheckin.RUN"),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(old)
        old.cancel()
    }

    fun cancel(context: Context, slot: PunchSlot) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, slot))
        SettingsStore(context).setScheduledAt(slot, 0)
    }

    fun cancel(context: Context) {
        cancelLegacy(context)
        PunchSlot.entries.forEach { cancel(context, it) }
    }

    fun schedule(context: Context): Boolean {
        val settings = SettingsStore(context)
        cancelLegacy(context)
        if (!settings.enabled || !Permissions.exact(context) || ScheduleTime.hasConflict(settings.schedules)) {
            cancel(context)
            return false
        }
        // Evaluate both; a failure must not short-circuit updating the other alarm.
        val results = PunchSlot.entries.map { schedule(context, it) }
        return results.all { it } && settings.schedules.any { it.enabled }
    }

    fun schedule(context: Context, slot: PunchSlot): Boolean {
        val settings = SettingsStore(context)
        val config = settings.schedule(slot)
        if (!settings.enabled || !config.enabled || !Permissions.exact(context)) {
            cancel(context, slot)
            return !config.enabled
        }
        val at = ScheduleTime.nextOccurrences(ZonedDateTime.now(), listOf(config)).getValue(slot).toInstant().toEpochMilli()
        return try {
            val showTask = PendingIntent.getActivity(context, 101, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // ColorOS batches setExactAndAllowWhileIdle. Each alarm clock stays independently exact.
            context.getSystemService(AlarmManager::class.java)
                .setAlarmClock(AlarmManager.AlarmClockInfo(at, showTask), pending(context, slot, at))
            settings.setScheduledAt(slot, at)
            true
        } catch (e: SecurityException) {
            cancel(context, slot)
            RunLog.write(context, null, "SCHEDULE_ERROR", "${slot.label}：${e.javaClass.simpleName}")
            MailQueue.enqueue(context, "${slot.label}调度失败", "精确闹钟调度失败，请检查权限。")
            false
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val slot = PunchSlot.fromKey(intent.getStringExtra("slot")) ?: return
        if (intent.action == "com.personal.autocheckin.RUN_TEST") {
            RunLog.write(context, null, "TEST_ALARM", "${slot.label}：5 秒倒计时结束，开始完整亮屏流程")
            try {
                context.startForegroundService(Intent(context, CheckinService::class.java)
                    .putExtra("source", "manual_delayed").putExtra("slot", slot.key))
            } catch (e: Exception) {
                RunLog.write(context, null, "START_ERROR", "${slot.label}完整流程测试启动失败：${e.javaClass.simpleName}")
            }
            return
        }
        val settings = SettingsStore(context)
        val at = intent.getLongExtra("scheduledAt", 0)
        if (!settings.enabled || !settings.schedule(slot).enabled || at == 0L || at != settings.scheduledAt(slot)) return
        // Advance only this slot. Morning completion must not consume the evening alarm.
        AlarmScheduler.schedule(context, slot)
        val delay = System.currentTimeMillis() - at
        RunLog.write(context, null, "ALARM", "${slot.label}：计划时间=$at；触发偏差=${delay}ms")
        if (delay !in 0..300_000L) {
            RunLog.write(context, null, "MISSED", "${slot.label}：闹钟偏差 ${delay / 1000} 秒，超过 5 分钟窗口，不补执行")
            MailQueue.enqueue(context, "${slot.label}任务错过", "闹钟偏差 ${delay / 1000} 秒，超过执行窗口，未补执行，请人工核对。")
            return
        }
        try {
            context.startForegroundService(Intent(context, CheckinService::class.java)
                .putExtra("source", "alarm").putExtra("slot", slot.key))
        } catch (e: Exception) {
            RunLog.write(context, null, "START_ERROR", "${slot.label}：前台服务启动失败：${e.javaClass.simpleName}")
            MailQueue.enqueue(context, "${slot.label}无法启动", "前台服务启动失败：${e.javaClass.simpleName}，请检查后台运行设置。")
        }
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (SettingsStore(context).enabled) {
            val ok = AlarmScheduler.schedule(context)
            RunLog.write(context, null, "RESCHEDULE", "${intent.action}，上下班任务重新调度${if (ok) "成功" else "失败：检查权限和时段配置"}")
        }
    }
}
