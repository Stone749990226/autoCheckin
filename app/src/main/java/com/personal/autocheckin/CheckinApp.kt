package com.personal.autocheckin

import android.app.Application
import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

class CheckinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val settings = SettingsStore(this)
        AlarmScheduler.cancelLegacy(this)
        settings.activeRun?.let {
            RunLog.write(this, it, "INTERRUPTED", "${settings.activeRunLabel}：上次运行因进程终止而中断，结果未确认；不补执行")
            MailQueue.enqueue(this, "${MailText.task(settings.activeRunLabel)}: interrupted",
                "Run: $it\nTask: ${MailText.task(settings.activeRunLabel)}\nResult: interrupted\n" +
                    "Note: the previous run ended with the process; the outcome is unconfirmed.", "interrupted-$it")
            settings.activeRun = null
        }
        CheckinService.createChannel(this)
        // Battery broadcasts are event driven. This does not poll or keep the CPU awake.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(BatteryMail(), IntentFilter(Intent.ACTION_BATTERY_CHANGED), RECEIVER_NOT_EXPORTED)
        else registerReceiver(BatteryMail(), IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        try { if (MailQueue.read(this).isNotEmpty()) MailQueue.schedule(this) } catch (_: Exception) {
            RunLog.write(this, null, "MAIL_ERROR", "无法读取待发队列，请从邮件设置检查")
        }
    }
}

class CheckinAdminReceiver : DeviceAdminReceiver() {
    override fun onDisabled(context: Context, intent: Intent) {
        RunLog.write(context, null, "PERMISSION", "设备管理员已撤销，自动锁屏不可用")
        CheckinService.current?.fail("设备管理员权限被撤销")
    }
}
