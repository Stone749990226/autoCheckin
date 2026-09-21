package com.personal.autocheckin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager

class BatteryMail : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BATTERY_OKAY) {
            // Re-arm only at 25%, not at the system's lower battery-okay threshold.
            return
        }
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val percent = if (scale > 0 && level >= 0) level * 100 / scale else -1
        val settings = MailSettings(context)
        if (MailPolicy.rearmBattery(percent) && settings.batteryReported) settings.batteryReported = false
        if (!settings.enabled || !settings.lowBattery) return
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val systemLow = intent.action == Intent.ACTION_BATTERY_LOW && percent < 0 && !settings.batteryReported
        if (systemLow || MailPolicy.lowBatteryAlert(percent, plugged, settings.batteryReported)) {
            val detail = if (percent >= 0) "手机剩余电量 $percent%，未连接电源。请及时充电，避免上下班任务无法执行。" else "系统报告手机低电量，请及时充电。"
            if (MailQueue.enqueue(context, "手机低电量", detail)) settings.batteryReported = true
        }
    }
}
