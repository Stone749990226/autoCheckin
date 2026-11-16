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
            val detail = if (percent >= 0) "Battery: $percent%, not charging\nCharge soon so scheduled tasks can still run."
                else "The system reported low battery.\nCharge soon so scheduled tasks can still run."
            if (MailQueue.enqueue(context, "Battery low", detail)) settings.batteryReported = true
        }
    }
}
